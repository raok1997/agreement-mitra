package in.agreementmitra.signing.recovery;

/**
 * What actually happened to a recovery request, in our vocabulary.
 *
 * <p>Deliberately more granular than the response, which is identical in every case (design D1).
 * The distinction exists for the audit trail and for operators; it must never reach the caller, or
 * the endpoint becomes the enumeration oracle the whole design avoids.
 */
enum RecoveryOutcome {

  /** A link was dispatched to at least one party. */
  SENT,

  /** No agreement matched, or it was unpaid, or it already has an owner. Indistinguishable here. */
  NOT_ELIGIBLE,

  /** The agreement was eligible, but no party had a contact on an enabled channel. */
  NO_CONTACT,

  /** The request exceeded the rate limit and was not acted on. */
  THROTTLED,

  /** No enabled channel or no public base URL, so no link could be sent. */
  NOT_CONFIGURED
}
