package in.agreementmitra.signing;

/**
 * Why an agreement closed. Recorded alongside the closure time so a closed agreement always says
 * what happened to it.
 *
 * <p><b>Abandoned stays distinguishable from completed in every read and report.</b> One produced a
 * signed agreement delivered to both parties; the others produced nothing. Collapsing them into a
 * single "closed" fact would turn closure into an eraser rather than a filter, and would hide
 * genuine failures somebody should have looked at.
 */
public enum ClosureReason {

  /** Signing completed and the signed agreement reached every party. The happy path. */
  COMPLETED,

  /** A party rejected, so the signing request reached the terminal {@code FAILED}. */
  ABANDONED_SIGNING_FAILED,

  /** The signing window ran out, so the signing request reached the terminal {@code EXPIRED}. */
  ABANDONED_SIGNING_EXPIRED,

  /** The purchased certificate could not be composited, so the request reached STAMP_FAILED. */
  ABANDONED_STAMP_FAILED;

  /** True for every reason other than {@link #COMPLETED}: closed, but nothing was produced. */
  public boolean abandoned() {
    return this != COMPLETED;
  }
}
