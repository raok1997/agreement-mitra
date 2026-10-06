package in.agreementmitra.signing.agreement;

/** Why an unpaid draft was deleted - the {@code reason} of its {@link AgreementDeletion} record. */
enum DeletionReason {
  /** Its owner deleted it through {@code DELETE /api/agreements/{id}}. */
  OWNER_DELETE,
  /** The daily retention run deleted it after 90 days without an edit ({@link DraftRetention}). */
  RETENTION_PURGE
}
