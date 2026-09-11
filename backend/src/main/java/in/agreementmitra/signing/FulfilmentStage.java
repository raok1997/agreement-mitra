package in.agreementmitra.signing;

import java.util.Optional;

/**
 * Where an agreement stands in the fulfilment pipeline, at the resolution a customer needs:
 * "awaiting the e-stamp" and "out for signature" are different answers to "where is my agreement?",
 * and {@link in.agreementmitra.signing.api.AgreementDisplayStatus} deliberately collapses them for
 * the list. This is a read-only projection of the most-recent signing request's {@link
 * SignatureStatus} and nothing else -- no reason, no certificate detail, no staff identity.
 *
 * <p>Wire contract: a client compares against these names as a set, never by ordinal. The enum
 * order here is narrative, not a state machine; {@code STAMP_FAILED} sits last and is <b>not</b>
 * "past" anything.
 */
public enum FulfilmentStage {
  NOT_STARTED,
  AWAITING_STAMP,
  STAMPED,
  OUT_FOR_SIGNATURE,
  SIGNED,
  EXPIRED,
  FAILED,
  STAMP_FAILED;

  /** Project the (optional) most-recent signing status to a fulfilment stage. */
  public static FulfilmentStage from(Optional<SignatureStatus> signatureStatus) {
    return signatureStatus.map(FulfilmentStage::from).orElse(NOT_STARTED);
  }

  private static FulfilmentStage from(SignatureStatus status) {
    return switch (status) {
      // DRAFT is declared but reserved (not on the active path); a request that somehow carries it
      // has not started fulfilment.
      case DRAFT -> NOT_STARTED;
      case PDF_GENERATED -> AWAITING_STAMP;
      case STAMPED -> STAMPED;
      case SIGN_REQUESTED -> OUT_FOR_SIGNATURE;
      case SIGNED -> SIGNED;
      case EXPIRED -> EXPIRED;
      case FAILED -> FAILED;
      case STAMP_FAILED -> STAMP_FAILED;
    };
  }

  /** Whether the pipeline can move no further from here without staff starting over. */
  public boolean terminal() {
    return switch (this) {
      case SIGNED, EXPIRED, FAILED, STAMP_FAILED -> true;
      case NOT_STARTED, AWAITING_STAMP, STAMPED, OUT_FOR_SIGNATURE -> false;
    };
  }
}
