package in.agreementmitra.signing.staffalert;

/** Delivery state of one staff alert. The values are also the CHECK constraint in V27. */
enum StaffAlertStatus {

  /** Waiting for its next attempt. A claimed row stays here; its next-attempt time is the lease. */
  PENDING,

  /** The channel accepted the message. Terminal. */
  SENT,

  /** Refused permanently, out of attempts, or stale. Terminal: nothing re-sends it. */
  FAILED
}
