package in.agreementmitra.signing.api;

import java.time.Instant;
import java.util.UUID;

/**
 * An agreement's terminal fulfilment state as a read view: is there work outstanding, and if not,
 * when did it finish and why.
 *
 * <p>{@code reason} is carried separately from {@code state} on purpose. Collapsing "closed as
 * completed" and "closed as abandoned" into a single closed flag would erase the only difference
 * that matters - one produced a signed agreement delivered to both parties, the other produced
 * nothing - and would turn closure from a filter into an eraser.
 *
 * <p>Carries no party PII, no address, and no document key.
 *
 * @param agreementId the agreement
 * @param state {@code OPEN} or {@code CLOSED}
 * @param reason why it closed, or {@code null} while open
 * @param closedAt when it closed, or {@code null} while open
 */
public record ClosureStateResponse(
    UUID agreementId, String state, String reason, Instant closedAt) {}
