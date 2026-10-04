package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.AgreementIds;
import in.agreementmitra.signing.PaymentConfirmation;
import in.agreementmitra.signing.SignRequest;
import in.agreementmitra.signing.SignatureStatus;
import in.agreementmitra.signing.SigningCompletionView;
import in.agreementmitra.signing.api.StampQueueEntry;
import in.agreementmitra.signing.payment.PaymentConfirmedEvent;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The nine named types render an agreement id only in redacted form (agreement-id-debug-logging
 * 4.4, design D8).
 */
class AgreementIdToStringRedactionTest {

  private static final UUID ID = UUID.randomUUID();
  private static final String PREFIX = AgreementIds.redact(ID);

  private static void assertRedacted(Object value) {
    assertThat(value.toString()).contains(PREFIX).doesNotContain(ID.toString());
  }

  @Test
  void agreement() {
    Agreement agreement =
        Agreement.create(
            "12 MG Road, Bengaluru",
            new BigDecimal("25000.00"),
            BigDecimal.ZERO,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 1));
    assertThat(agreement.toString())
        .contains(AgreementIds.redact(agreement.getId()))
        .doesNotContain(agreement.getId().toString());
  }

  @Test
  void signingCompletionView() {
    assertRedacted(
        new SigningCompletionView(
            UUID.randomUUID(), ID, SignatureStatus.SIGNED, "signed/x.pdf", List.of()));
  }

  @Test
  void staffAgreementView() {
    assertRedacted(
        new StaffAgreementView(ID, "AM-1234", "Bengaluru", null, "Lease", "KA", List.of()));
  }

  @Test
  void stampQueueEntry() {
    assertRedacted(
        new StampQueueEntry(
            ID,
            "AM-1234",
            "Lease",
            "KA",
            List.of(),
            "Bengaluru",
            null,
            Instant.now(),
            0L,
            null,
            null,
            null));
  }

  @Test
  void stampInfoRedactsBothIdBearingKeys() {
    StampInfo info =
        new StampInfo(
            "IN-KA123456789",
            "stamped/" + ID + ".pdf",
            "estamp-scans/" + ID,
            BigDecimal.TEN,
            "KA",
            null,
            null,
            null,
            true,
            Instant.now());
    assertThat(info.toString())
        .contains("stamped/" + PREFIX + ".pdf")
        .contains("estamp-scans/" + PREFIX)
        .doesNotContain(ID.toString());
  }

  @Test
  void paymentConfirmationAlsoCutsTheReference() {
    PaymentConfirmation confirmation =
        new PaymentConfirmation(ID, BigDecimal.TEN, "INR", "pay_RZP8b2j0XYZ", Instant.now());
    assertRedacted(confirmation);
    assertThat(confirmation.toString()).doesNotContain("pay_RZP8b2j0XYZ").contains("****0XYZ");
  }

  @Test
  void paymentConfirmedEvent() {
    assertRedacted(new PaymentConfirmedEvent(ID));
  }

  @Test
  void providerOrderRedactsTheReceiptAndTheOrderId() throws Exception {
    Class<?> type = Class.forName("in.agreementmitra.signing.payment.RazorpayClient$ProviderOrder");
    Constructor<?> ctor = type.getDeclaredConstructors()[0];
    ctor.setAccessible(true);
    Object order = ctor.newInstance("order_RZP9f3k1", "created", 49_900L, "INR", ID.toString());
    assertRedacted(order);
    assertThat(order.toString()).doesNotContain("order_RZP9f3k1");
  }

  @Test
  void signRequestCountsInviteesAndShowsNoEmail() {
    SignRequest request =
        new SignRequest(
            ID.toString(),
            new byte[] {1, 2, 3},
            List.of(new SignRequest.Invitee("Asha", "asha@example.com", null, false)));
    assertRedacted(request);
    assertThat(request.toString()).doesNotContain("asha@example.com").doesNotContain("Asha");
  }
}
