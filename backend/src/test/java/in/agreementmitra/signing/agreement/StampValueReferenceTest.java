package in.agreementmitra.signing.agreement;

import static in.agreementmitra.signing.agreement.StampQuoteFixtures.challan;
import static in.agreementmitra.signing.agreement.StampQuoteFixtures.quoted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.documents.api.TemplateDetail;
import in.agreementmitra.rules.RuleRef;
import in.agreementmitra.rules.StampDutyCalculator;
import in.agreementmitra.signing.PaymentConfirmation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Which stamp value a certificate must cover at intake (design D8), plus the frozen quote's own
 * invariant that a below-duty choice always carries its acknowledgement.
 */
class StampValueReferenceTest {

  private final AgreementRepository agreements = mock(AgreementRepository.class);
  private final StampQuoteRecordRepository frozenQuotes = mock(StampQuoteRecordRepository.class);
  private final TemplateCatalogApi catalog = mock(TemplateCatalogApi.class);
  private final StampDutyCalculator calculator = mock(StampDutyCalculator.class);

  @SuppressWarnings("unchecked")
  private StampValueReference reference() {
    ObjectProvider<Clock> clock = mock(ObjectProvider.class);
    when(clock.getIfAvailable(any())).thenReturn(Clock.systemUTC());
    return new StampValueReference(
        agreements, frozenQuotes, new StampQuoting(agreements, catalog, calculator, clock));
  }

  private Agreement tgAgreement() {
    Agreement agreement =
        Agreement.create(
            "12 MG Road",
            new BigDecimal("10000.00"),
            BigDecimal.ZERO,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 1));
    UUID templateId = UUID.randomUUID();
    agreement.selectTemplate(templateId);
    when(agreements.findById(agreement.getId())).thenReturn(Optional.of(agreement));
    when(catalog.find(templateId.toString()))
        .thenReturn(
            Optional.of(
                new TemplateDetail(
                    templateId.toString(),
                    "Rental",
                    "b",
                    new TemplateDetail.Dimensions("TG", "residential", "en"),
                    1)));
    when(calculator.quote(any())).thenReturn(quoted(44_000L, challan(44_000L)));
    when(calculator.isChargeable(any(RuleRef.class))).thenReturn(true);
    return agreement;
  }

  private static StampQuoteRecord frozenAt(UUID agreementId, long stampValue, long duty) {
    StampQuoteRecord.Snapshot snapshot =
        new StampQuoteRecord.Snapshot(
            duty,
            stampValue,
            "stamp-paper",
            "TG-lease-residential",
            "a".repeat(64),
            false,
            "b".repeat(64),
            LocalDate.of(2026, 1, 1),
            true,
            List.of());
    return StampQuoteRecord.freeze(
        UUID.randomUUID(),
        agreementId,
        snapshot,
        snapshot.belowDuty()
            ? new StampQuoteRecord.Acknowledgement("under-stamp-v1", null, Instant.now())
            : null,
        Instant.now());
  }

  @Test
  void aPaidAgreementWithAFrozenQuoteUsesTheChosenStampValue() {
    Agreement agreement = tgAgreement();
    agreement.recordPayment(
        new PaymentConfirmation(
            agreement.getId(), new BigDecimal("499.00"), "INR", "pay", Instant.now()),
        null);
    when(frozenQuotes.findTopByAgreementIdOrderByCreatedAtDesc(agreement.getId()))
        .thenReturn(Optional.of(frozenAt(agreement.getId(), 10_000L, 44_000L)));

    assertThat(reference().forAgreement(agreement.getId()))
        .contains(new StampValueReference.Reference(10_000L, true));
  }

  @Test
  void aWaivedAgreementFallsBackToTheRecomputedLegalDuty() {
    Agreement agreement = tgAgreement();
    agreement.waivePayment(UUID.randomUUID(), Instant.now());

    assertThat(reference().forAgreement(agreement.getId()))
        .contains(new StampValueReference.Reference(44_000L, false));
  }

  @Test
  void anUnknownAgreementHasNoReference() {
    UUID unknown = UUID.randomUUID();
    when(agreements.findById(unknown)).thenReturn(Optional.empty());

    assertThat(reference().forAgreement(unknown)).isEmpty();
  }

  @Test
  void aBelowDutyFreezeRequiresAnAcknowledgementAndOnlyThen() {
    StampQuoteRecord.Snapshot below =
        new StampQuoteRecord.Snapshot(
            44_000L,
            10_000L,
            "stamp-paper",
            "r",
            "a".repeat(64),
            false,
            "b".repeat(64),
            LocalDate.of(2026, 1, 1),
            true,
            List.of());
    StampQuoteRecord.Snapshot atDuty =
        new StampQuoteRecord.Snapshot(
            44_000L,
            44_000L,
            "challan",
            "r",
            "a".repeat(64),
            false,
            "b".repeat(64),
            LocalDate.of(2026, 1, 1),
            true,
            List.of());
    StampQuoteRecord.Acknowledgement ack =
        new StampQuoteRecord.Acknowledgement("under-stamp-v1", null, Instant.now());

    assertThatThrownBy(
            () ->
                StampQuoteRecord.freeze(
                    UUID.randomUUID(), UUID.randomUUID(), below, null, Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                StampQuoteRecord.freeze(
                    UUID.randomUUID(), UUID.randomUUID(), atDuty, ack, Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            StampQuoteRecord.freeze(UUID.randomUUID(), UUID.randomUUID(), below, ack, Instant.now())
                .belowDuty())
        .isTrue();
    assertThat(
            StampQuoteRecord.freeze(
                    UUID.randomUUID(), UUID.randomUUID(), atDuty, null, Instant.now())
                .toString())
        .doesNotContain("MG Road");
  }
}
