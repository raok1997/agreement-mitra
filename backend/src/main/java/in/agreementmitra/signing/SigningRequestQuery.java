package in.agreementmitra.signing;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only seam over the signing-request aggregate, exposed at the module root (like {@link
 * BlobStore} / {@link EsignProvider}) so a sibling sub-package can ask "has signing started for
 * this agreement?" without depending on the {@code signingrequest} package internals. The draft
 * upload flow uses it to finalize (freeze) a draft once a signing request exists.
 */
public interface SigningRequestQuery {

  /** True if any signing request has been created for the given agreement. */
  boolean existsForAgreement(UUID agreementId);

  /**
   * The current signing {@link SignatureStatus} for the agreement's most-recent signing request, or
   * empty when none exists. Backs the agreement-list's <em>derived</em> status (the agreement stays
   * status-less). Only a plain enum crosses the module boundary here -- never a signing-request
   * aggregate.
   */
  Optional<SignatureStatus> currentStatusForAgreement(UUID agreementId);

  /**
   * The fulfilment-facing view of the signing request behind a provider transaction id, or empty
   * for a transaction we do not hold. Read by the delivery path, which is entered from the shared
   * completion path (webhook and reconciliation alike) and therefore only ever knows the provider's
   * id at that point.
   */
  Optional<SigningCompletionView> completionViewFor(String providerDocumentId);

  /**
   * The same view for an agreement's most-recent signing request, or empty when it has none. Backs
   * the retry sweep and the staff diagnostic view, both of which work from an agreement id.
   */
  Optional<SigningCompletionView> completionViewForAgreement(UUID agreementId);

  /**
   * The object-storage key of the agreement's signed PDF, or empty when signing has not completed
   * or the artifact is not stored yet. Backs the party-facing download; only the key crosses, never
   * the bytes and never any signer data.
   */
  Optional<String> signedPdfKeyForAgreement(UUID agreementId);
}
