package in.agreementmitra.signing.api;

import in.agreementmitra.signing.SignatureStatus;
import java.util.Optional;

/**
 * The <b>derived</b> display status shown in the "My Agreements" list. The agreement aggregate
 * stays status-less (the signing FSM is the single source of truth); this is projected on read from
 * the most-recent signing request's {@link SignatureStatus} via the {@code SigningRequestQuery}
 * seam, so it can never drift from the FSM.
 *
 * <p>Mapping (D3): no signing request -> {@link #DRAFT} (still editable); a non-terminal request
 * ({@code PDF_GENERATED}/{@code STAMPED}/{@code SIGN_REQUESTED}) -> {@link #IN_PROGRESS} (frozen);
 * {@code SIGNED} -> {@link #SIGNED}; {@code EXPIRED} -> {@link #EXPIRED}; {@code FAILED}/{@code
 * STAMP_FAILED} -> {@link #ACTION_NEEDED}.
 */
public enum AgreementDisplayStatus {
  DRAFT,
  IN_PROGRESS,
  SIGNED,
  EXPIRED,
  ACTION_NEEDED;

  /** Project the (optional) most-recent signing status to a display status. */
  public static AgreementDisplayStatus from(Optional<SignatureStatus> signatureStatus) {
    return signatureStatus.map(AgreementDisplayStatus::from).orElse(DRAFT);
  }

  private static AgreementDisplayStatus from(SignatureStatus status) {
    return switch (status) {
      case PDF_GENERATED, STAMPED, SIGN_REQUESTED, DRAFT -> IN_PROGRESS;
      case SIGNED -> SIGNED;
      case EXPIRED -> EXPIRED;
      case FAILED, STAMP_FAILED -> ACTION_NEEDED;
    };
  }

  /**
   * An agreement is editable only while it has no signing request (i.e. still a {@link #DRAFT});
   * once signing has started the terms are frozen (the same freeze the draft-upload path enforces).
   */
  public boolean editable() {
    return this == DRAFT;
  }
}
