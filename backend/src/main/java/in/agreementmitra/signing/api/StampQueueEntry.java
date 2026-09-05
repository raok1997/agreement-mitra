package in.agreementmitra.signing.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One row of the staff stamp-intake queue: an order that has been finalised and paid for but whose
 * e-stamp has not yet been bought and uploaded.
 *
 * <p>It carries the {@code trackingReference} so the console can upload <b>against that entry</b>
 * without an operator re-typing anything - which removes the transcription step entirely rather
 * than relying on the reference's check character to catch a slip.
 *
 * <p><b>STAFF-only, and it carries personal data.</b> Buying the certificate means naming the first
 * party, the second party, and the state on the vendor's form, so the row carries the pinned
 * template's name + state and every {@link StampPartyEntry party} with their name and father's
 * name. This deliberately reverses the row's original non-PII shape (see {@code
 * staff-queue-fulfilment-context}); it is reachable only through {@code /api/staff/**}, which the
 * filter chain gates on the STAFF role before any handler runs.
 *
 * <p>Still excluded, deliberately: contact details, monthly rent, security deposit, and the full
 * street address - only the property <em>city</em>. A fulfilment queue is one of the most
 * widely-read screens an operations team has; it should carry the least data that still lets
 * someone do the job.
 *
 * <p>{@code templateName} / {@code templateState} are {@code null} together when the pinned
 * template cannot be resolved (unpinned, superseded, or archived). The row still appears: an
 * unresolvable template is a reason to show less, never a reason to hide outstanding work.
 *
 * <p>{@code paymentState} is {@code null} until a payment module exists (design D8) - the field is
 * present so the console does not need reshaping when the payment gate lands.
 */
public record StampQueueEntry(
    UUID agreementId,
    String trackingReference,
    String templateName,
    String templateState,
    List<StampPartyEntry> parties,
    String propertyCity,
    LocalDate agreementStartDate,
    Instant awaitingSince,
    long waitingSeconds,
    String paymentState) {

  /** Defensive copy: the party list is personal data and the row must stay immutable. */
  public StampQueueEntry {
    parties = parties == null ? List.of() : List.copyOf(parties);
  }

  /**
   * One party on the row: which side of the agreement they are on, their full name as it will
   * appear on the instrument, and their father's name - the three things the vendor's purchase form
   * asks for.
   *
   * <p>{@code role} is the plain {@code OWNER} / {@code TENANT} string; the console labels them
   * first and second party. {@link #toString()} emits the role only, so a record's generated one
   * cannot leak names into a log line.
   */
  public record StampPartyEntry(String role, String name, String fatherName) {

    /** Role only -- never a name. Module-wide DEBUG must not leak party PII. */
    @Override
    public String toString() {
      return "StampPartyEntry{role=" + role + "}";
    }
  }

  /** Identifiers and a count -- never a party name. */
  @Override
  public String toString() {
    return "StampQueueEntry{agreementId="
        + agreementId
        + ", trackingReference="
        + trackingReference
        + ", parties="
        + parties.size()
        + "}";
  }
}
