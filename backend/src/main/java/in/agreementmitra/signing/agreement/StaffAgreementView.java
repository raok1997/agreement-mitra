package in.agreementmitra.signing.agreement;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The projection of an agreement that the staff stamp-intake flow is allowed to see: everything
 * needed to <b>purchase the e-stamp certificate</b> for it, and nothing else.
 *
 * <p>Purchasing on the vendor portal requires naming the first party, the second party, and the
 * state whose stamp paper is being bought. So this view carries the agreement's tracking reference,
 * the pinned template's <b>name and state</b>, and every {@link StaffPartyView party} with their
 * name and father's name - plus the property <em>city</em>, the agreement date, and enough to
 * confirm the right instrument before a purchased certificate is spent on it.
 *
 * <p><b>This deliberately reverses an earlier decision.</b> The view was originally specified as
 * non-PII on the reasoning that "staff need to disambiguate, not to read the customer's agreement".
 * That was right about disambiguation and wrong about fulfilment: it was written before the queue
 * became the operator's workbench, and naming the parties on a stamp certificate <em>is</em> the
 * work. The reversal is scoped, not general - see the {@code staff-queue-fulfilment-context}
 * change.
 *
 * <p><b>What stays out, and stays out deliberately:</b> party contact details (email / mobile), the
 * monthly rent, the security deposit, and the full street address. Widening this projection once is
 * not licence to widen it again; adding a field here is a spec decision, not a convenience.
 *
 * <p>The {@code templateName} / {@code templateState} pair is {@code null} together when the pinned
 * template cannot be resolved - unpinned, or since superseded or archived in the catalog. That is a
 * reason to show less on the row, never a reason to drop outstanding work off the queue.
 *
 * <p>{@link #toString()} is overridden: a record's generated one prints every component, which here
 * would mean party names in any log line that interpolates a view.
 */
public record StaffAgreementView(
    UUID agreementId,
    String trackingReference,
    String propertyCity,
    LocalDate agreementStartDate,
    String templateName,
    String templateState,
    List<StaffPartyView> parties) {

  /** Defensive copy: the party list is personal data and the view must stay immutable. */
  public StaffAgreementView {
    parties = parties == null ? List.of() : List.copyOf(parties);
  }

  /** Identifiers and a count -- never a party name. Module-wide DEBUG must not leak party PII. */
  @Override
  public String toString() {
    return "StaffAgreementView{agreementId="
        + agreementId
        + ", trackingReference="
        + trackingReference
        + ", parties="
        + parties.size()
        + "}";
  }
}
