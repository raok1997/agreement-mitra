package in.agreementmitra.signing.agreement;

import static in.agreementmitra.signing.agreement.StampQuoteFixtures.challan;
import static in.agreementmitra.signing.agreement.StampQuoteFixtures.paperUnplannable;
import static in.agreementmitra.signing.agreement.StampQuoteFixtures.quoted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.agreementmitra.ConflictException;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.documents.api.TemplateDetail;
import in.agreementmitra.rules.DutyOutcome;
import in.agreementmitra.rules.RuleRef;
import in.agreementmitra.rules.StampDutyCalculator;
import in.agreementmitra.signing.PaymentState;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The jurisdiction precondition, now derived from the stamp duty rules. Every case is a way the
 * gate could let an agreement we cannot stamp -- or cannot lawfully charge for -- reach a step that
 * commits us to something real. Real {@link StampQuoting} over a mocked calculator and catalog.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JurisdictionEligibilityTest {

  @Mock private AgreementRepository repository;
  @Mock private TemplateCatalogApi templateCatalog;
  @Mock private StampDutyCalculator calculator;
  @Mock private StampQuoteRecordRepository frozenQuotes;

  @SuppressWarnings("unchecked")
  private JurisdictionEligibility gate() {
    ObjectProvider<Clock> clock = mock(ObjectProvider.class);
    when(clock.getIfAvailable(any())).thenReturn(Clock.systemUTC());
    StampQuoting quoting = new StampQuoting(repository, templateCatalog, calculator, clock);
    return new JurisdictionEligibility(repository, quoting, frozenQuotes);
  }

  private Agreement agreementPinnedTo(UUID templateId) {
    Agreement agreement =
        Agreement.create(
            "12 MG Road",
            new BigDecimal("25000.00"),
            new BigDecimal("50000.00"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 1));
    if (templateId != null) {
      agreement.selectTemplate(templateId);
    }
    when(repository.findById(agreement.getId())).thenReturn(Optional.of(agreement));
    return agreement;
  }

  private Agreement tgAgreement() {
    UUID templateId = UUID.randomUUID();
    Agreement agreement = agreementPinnedTo(templateId);
    when(templateCatalog.find(templateId.toString()))
        .thenReturn(
            Optional.of(
                new TemplateDetail(
                    templateId.toString(),
                    "Residential Rental",
                    "blurb",
                    new TemplateDetail.Dimensions("TG", "residential", "en"),
                    1)));
    when(calculator.chargeableStates()).thenReturn(Set.of("TG"));
    return agreement;
  }

  private void calculatorQuotes(DutyOutcome outcome, boolean chargeable) {
    when(calculator.quote(any())).thenReturn(outcome);
    when(calculator.isChargeable(any(RuleRef.class))).thenReturn(chargeable);
  }

  private static void assertRefused(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((ConflictException) e).kind())
        .isEqualTo(ConflictException.Kind.JURISDICTION_UNSUPPORTED);
  }

  @Test
  @DisplayName("a quoted, chargeable, plannable agreement is permitted")
  void quotableChargeablePasses() {
    Agreement agreement = tgAgreement();
    calculatorQuotes(quoted(84000, paperUnplannable(), challan(84000)), true);

    assertThatCode(() -> gate().require(agreement.getId())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("an unreviewed rule is refused unless unreviewed rules are allowed")
  void unreviewedRuleIsRefused() {
    Agreement agreement = tgAgreement();
    calculatorQuotes(quoted(84000, challan(84000)), false);

    assertRefused(() -> gate().require(agreement.getId()));
  }

  @Test
  @DisplayName("Unsupported and NeedsAdjudication are both refused")
  void nonQuotedOutcomesAreRefused() {
    Agreement agreement = tgAgreement();
    calculatorQuotes(new DutyOutcome.Unsupported("term beyond slabs"), true);
    assertRefused(() -> gate().require(agreement.getId()));

    calculatorQuotes(
        new DutyOutcome.NeedsAdjudication("variable rent", StampQuoteFixtures.TG_RULE), true);
    assertRefused(() -> gate().require(agreement.getId()));
  }

  @Test
  @DisplayName("a quote no stamp medium can plan is refused")
  void unplannableIsRefused() {
    Agreement agreement = tgAgreement();
    calculatorQuotes(quoted(84000, paperUnplannable()), true);

    assertRefused(() -> gate().require(agreement.getId()));
  }

  @Test
  @DisplayName("an agreement with NO pinned template fails closed, with no default fallback")
  void nullTemplateFailsClosed() {
    Agreement agreement = agreementPinnedTo(null);

    assertThatThrownBy(() -> gate().require(agreement.getId()))
        .isInstanceOf(ConflictException.class)
        .satisfies(e -> assertThat(((ConflictException) e).rejectedJurisdiction()).isNull());
  }

  @Test
  @DisplayName("an unresolvable pinned template is a JURISDICTION refusal, not a 404")
  void unresolvableTemplateIsAJurisdictionRefusal() {
    Agreement agreement = agreementPinnedTo(UUID.randomUUID());
    when(templateCatalog.find(anyString())).thenReturn(Optional.empty());

    assertRefused(() -> gate().require(agreement.getId()));
  }

  @Test
  @DisplayName("a missing agreement passes through to the caller's own 404")
  void missingAgreementPassesThrough() {
    UUID unknown = UUID.randomUUID();
    when(repository.findById(unknown)).thenReturn(Optional.empty());

    assertThatCode(() -> gate().require(unknown)).doesNotThrowAnyException();
    assertThatCode(() -> gate().requireForFulfilment(unknown)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a PAID agreement with a frozen quote passes fulfilment even if its rule changed")
  void paidFrozenQuotePassesFulfilment() {
    Agreement agreement = tgAgreement();
    agreement.recordPayment(
        new in.agreementmitra.signing.PaymentConfirmation(
            agreement.getId(),
            new BigDecimal("1239.00"),
            "INR",
            "pay_ref",
            java.time.Instant.now()),
        null);
    when(frozenQuotes.findTopByAgreementIdOrderByCreatedAtDesc(agreement.getId()))
        .thenReturn(Optional.of(mock(StampQuoteRecord.class)));
    calculatorQuotes(new DutyOutcome.Unsupported("rule since removed"), false);

    assertThat(agreement.paymentState()).isEqualTo(PaymentState.PAID);
    assertThatCode(() -> gate().requireForFulfilment(agreement.getId())).doesNotThrowAnyException();
    assertRefused(() -> gate().require(agreement.getId()));
  }

  @Test
  @DisplayName("a WAIVED agreement is judged afresh at fulfilment, frozen quote or not")
  void waivedIsJudgedAfresh() {
    Agreement agreement = tgAgreement();
    agreement.waivePayment(UUID.randomUUID(), java.time.Instant.now());
    calculatorQuotes(new DutyOutcome.Unsupported("no rule"), true);

    assertRefused(() -> gate().requireForFulfilment(agreement.getId()));
  }

  @Test
  @DisplayName("the refusal lists the chargeable states as the eligible ones")
  void refusalListsChargeableStates() {
    Agreement agreement = tgAgreement();
    calculatorQuotes(quoted(84000, challan(84000)), false);

    assertThatThrownBy(() -> gate().require(agreement.getId()))
        .isInstanceOf(ConflictException.class)
        .satisfies(
            e -> {
              ConflictException ex = (ConflictException) e;
              assertThat(ex.rejectedJurisdiction()).isEqualTo("TG");
              assertThat(ex.eligibleJurisdictions()).containsExactly("TG");
            });
    assertThat(gate().eligible()).containsExactly("TG");
  }
}
