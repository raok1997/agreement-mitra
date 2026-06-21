package in.agreementmitra.signing;

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
}
