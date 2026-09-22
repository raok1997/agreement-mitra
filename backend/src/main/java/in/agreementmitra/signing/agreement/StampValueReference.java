package in.agreementmitra.signing.agreement;

import in.agreementmitra.rules.DutyOutcome;
import in.agreementmitra.signing.PaymentState;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The least stamp value a certificate attached at intake must carry (state-stamp-duty-quoting,
 * design D8).
 *
 * <p>For an agreement <b>paid</b> with a frozen stamp quote it is the stamp value the customer
 * chose and paid for -- including an acknowledged below-duty choice, which intake honours rather
 * than overrides. Otherwise (a staff waiver, or an order placed before stamp quoting existed) it is
 * the legal duty recomputed now. Empty only when neither exists, which the jurisdiction gate has
 * already refused.
 *
 * <p>Java-{@code public} so the signing-request package can call it; still Modulith-internal.
 */
@Component
public class StampValueReference {

  private final AgreementRepository agreements;
  private final StampQuoteRecordRepository frozenQuotes;
  private final StampQuoting quoting;

  StampValueReference(
      AgreementRepository agreements,
      StampQuoteRecordRepository frozenQuotes,
      StampQuoting quoting) {
    this.agreements = agreements;
    this.frozenQuotes = frozenQuotes;
    this.quoting = quoting;
  }

  /** The reference in paise, and whether it came from a frozen quote. */
  public record Reference(long minorUnits, boolean frozen) {}

  public java.util.Optional<Reference> forAgreement(UUID agreementId) {
    Agreement agreement = agreements.findById(agreementId).orElse(null);
    if (agreement == null) {
      return java.util.Optional.empty();
    }
    if (agreement.paymentState() == PaymentState.PAID) {
      java.util.Optional<StampQuoteRecord> frozen =
          frozenQuotes.findTopByAgreementIdOrderByCreatedAtDesc(agreementId);
      if (frozen.isPresent()) {
        return java.util.Optional.of(new Reference(frozen.get().stampValueMinorUnits(), true));
      }
    }
    OptionalLong duty =
        quoting
            .evaluate(agreement)
            .quote()
            .map(DutyOutcome.Quoted::amountPaise)
            .map(OptionalLong::of)
            .orElse(OptionalLong.empty());
    return duty.isPresent()
        ? java.util.Optional.of(new Reference(duty.getAsLong(), false))
        : java.util.Optional.empty();
  }
}
