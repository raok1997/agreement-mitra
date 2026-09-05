package in.agreementmitra.signing.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One recipient's delivery record as staff see it: enough to act on a failure, and nothing more.
 *
 * <p>The recipient address is <b>redacted</b> (local part masked). Staff need to know <em>which
 * party</em> is stuck and <em>why</em>; reading a party's mailbox out of a support screen is not
 * part of that, and the document itself is never exposed here at all.
 *
 * @param deliveryId the record's id - the handle for a deliberate re-send
 * @param signerId which party this row delivers to
 * @param artifact which artifact (always the signed agreement; the audit trail is never delivered)
 * @param recipient the redacted recipient address, or {@code ***} when none was resolvable
 * @param status {@code PENDING} / {@code IN_PROGRESS} / {@code SENT} / {@code FAILED} / {@code
 *     UNRESOLVABLE}
 * @param needsAttention whether a human should be looking at this row
 * @param attempts how many attempts have been made
 * @param lastError a short fixed failure token, or null
 * @param notificationOnly true when the document was too large to attach and the party was pointed
 *     at the in-app copy instead
 * @param sentAt when the provider accepted the message - <b>not</b> proof it arrived
 * @param resendCount how many deliberate staff re-sends have been requested
 * @param resentAt when the last deliberate re-send was requested
 */
public record DeliveryStatusResponse(
    UUID deliveryId,
    UUID signerId,
    String artifact,
    String recipient,
    String status,
    boolean needsAttention,
    int attempts,
    String lastError,
    boolean notificationOnly,
    Instant sentAt,
    int resendCount,
    Instant resentAt) {}
