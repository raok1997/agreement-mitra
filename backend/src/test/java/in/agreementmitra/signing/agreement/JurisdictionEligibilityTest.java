package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import in.agreementmitra.ConflictException;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.documents.api.TemplateDetail;
import java.math.BigDecimal;
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

/**
 * The jurisdiction precondition itself. Every case here is a way the gate could let an agreement we
 * cannot stamp reach a step that commits us to something real.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JurisdictionEligibilityTest {

  @Mock private AgreementRepository repository;
  @Mock private TemplateCatalogApi templateCatalog;

  private JurisdictionEligibility gate(Set<String> eligible) {
    return new JurisdictionEligibility(
        new JurisdictionProperties(eligible), repository, templateCatalog);
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

  private void catalogReturns(UUID templateId, String state) {
    when(templateCatalog.find(templateId.toString()))
        .thenReturn(
            Optional.of(
                new TemplateDetail(
                    templateId.toString(),
                    "Residential Rental",
                    "blurb",
                    new TemplateDetail.Dimensions(state, "residential", "en"),
                    1)));
  }

  @Test
  @DisplayName("an eligible jurisdiction is permitted")
  void eligiblePasses() {
    UUID templateId = UUID.randomUUID();
    Agreement agreement = agreementPinnedTo(templateId);
    catalogReturns(templateId, "TG");

    assertThatCode(() -> gate(Set.of("TG")).require(agreement.getId())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("the national jurisdiction is refused")
  void nationalIsRefused() {
    UUID templateId = UUID.randomUUID();
    Agreement agreement = agreementPinnedTo(templateId);
    catalogReturns(templateId, "IN");

    assertThatThrownBy(() -> gate(Set.of("TG")).require(agreement.getId()))
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((ConflictException) e).kind())
        .isEqualTo(ConflictException.Kind.JURISDICTION_UNSUPPORTED);
  }

  @Test
  @DisplayName("an agreement with NO pinned template fails closed, with no default fallback")
  void nullTemplateFailsClosed() {
    // The accepted breaking change: state/type are documented optional at create, so this path
    // used to reach paid fulfilment. An agreement whose jurisdiction we cannot name is one whose
    // duty we cannot compute, so "unknown" is not "fine".
    Agreement agreement = agreementPinnedTo(null);

    assertThatThrownBy(() -> gate(Set.of("TG")).require(agreement.getId()))
        .isInstanceOf(ConflictException.class)
        .satisfies(
            e -> {
              ConflictException ex = (ConflictException) e;
              assertThat(ex.kind()).isEqualTo(ConflictException.Kind.JURISDICTION_UNSUPPORTED);
              assertThat(ex.rejectedJurisdiction()).isNull();
            });
  }

  @Test
  @DisplayName("an unresolvable pinned template is a JURISDICTION refusal, not a 404")
  void unresolvableTemplateIsAJurisdictionRefusal() {
    // Resolved through find(), not detail(): detail() throws for an unknown OR non-published id,
    // which would surface a misleading 404 even for a template whose state was eligible.
    UUID templateId = UUID.randomUUID();
    Agreement agreement = agreementPinnedTo(templateId);
    when(templateCatalog.find(anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> gate(Set.of("TG")).require(agreement.getId()))
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((ConflictException) e).kind())
        .isEqualTo(ConflictException.Kind.JURISDICTION_UNSUPPORTED);
  }

  @Test
  @DisplayName("an empty allowlist refuses every jurisdiction, cleanly")
  void emptyAllowlistRefusesEverything() {
    UUID templateId = UUID.randomUUID();
    Agreement agreement = agreementPinnedTo(templateId);
    catalogReturns(templateId, "TG");

    assertThatThrownBy(() -> gate(Set.of()).require(agreement.getId()))
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((ConflictException) e).kind())
        .isEqualTo(ConflictException.Kind.JURISDICTION_UNSUPPORTED);
  }

  @Test
  @DisplayName("adding the national dimension to the allowlist does not admit it")
  void nationalCannotBeConfiguredIn() {
    UUID templateId = UUID.randomUUID();
    Agreement agreement = agreementPinnedTo(templateId);
    catalogReturns(templateId, "IN");

    assertThatThrownBy(() -> gate(Set.of("IN", "TG")).require(agreement.getId()))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  @DisplayName("a further state is admitted by configuration alone")
  void configAloneAdmitsAFurtherState() {
    UUID templateId = UUID.randomUUID();
    Agreement agreement = agreementPinnedTo(templateId);
    catalogReturns(templateId, "KA");

    assertThatThrownBy(() -> gate(Set.of("TG")).require(agreement.getId()))
        .isInstanceOf(ConflictException.class);
    assertThatCode(() -> gate(Set.of("TG", "KA")).require(agreement.getId()))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a template state differing only by case still matches")
  void caseInsensitiveMatch() {
    UUID templateId = UUID.randomUUID();
    Agreement agreement = agreementPinnedTo(templateId);
    catalogReturns(templateId, "tg");

    assertThatCode(() -> gate(Set.of("TG")).require(agreement.getId())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("an unknown agreement is permitted through: resolving it is the caller's 404")
  void unknownAgreementIsNotOurProblem() {
    UUID unknown = UUID.randomUUID();
    when(repository.findById(unknown)).thenReturn(Optional.empty());

    assertThatCode(() -> gate(Set.of("TG")).require(unknown)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("the refusal names the rejected jurisdiction and the eligible ones, and no PII")
  void refusalCarriesActionableServerDerivedContext() {
    UUID templateId = UUID.randomUUID();
    Agreement agreement = agreementPinnedTo(templateId);
    catalogReturns(templateId, "IN");

    assertThatThrownBy(() -> gate(Set.of("TG")).require(agreement.getId()))
        .isInstanceOf(ConflictException.class)
        .satisfies(
            e -> {
              ConflictException ex = (ConflictException) e;
              assertThat(ex.rejectedJurisdiction()).isEqualTo("IN");
              assertThat(ex.eligibleJurisdictions()).containsExactly("TG");
              // Server-derived only: nothing here came from a request body.
              assertThat(ex.partyLabels()).isEmpty();
            });
  }
}
