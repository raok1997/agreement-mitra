package in.agreementmitra.signing.stamp;

import in.agreementmitra.StampFailedException;
import java.util.UUID;

/**
 * Vendor-neutral e-stamp procurement seam, parallel to {@code EsignProvider}. Procuring a stamp
 * means obtaining a stamp serial and compositing the stamp onto the agreement's draft to produce
 * the legally-stampable instrument. All provider specifics (template, serial source, and — for a
 * real adapter — the duty-payment API) live behind this interface, so a real SHCIL / state-portal
 * adapter can replace the v1 synthetic implementation with no caller change.
 */
public interface StampProvider {

  /**
   * Procure a stamp for {@code agreementId} and composite it onto {@code draftPdf}.
   *
   * @param agreementId the internal agreement id (the deterministic serial is derived from it)
   * @param draftPdf the untrusted, user-uploaded draft PDF bytes
   * @return the stamp serial, denomination, jurisdiction, duty-paid flag, and the composited
   *     stamped PDF bytes
   * @throws StampFailedException if the draft cannot be parsed or composited (fail closed)
   */
  StampResult procure(UUID agreementId, byte[] draftPdf);
}
