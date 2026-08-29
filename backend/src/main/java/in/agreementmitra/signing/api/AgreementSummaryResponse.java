package in.agreementmitra.signing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A row in the caller's "My Agreements" list (the resume view). A lightweight summary -- terms
 * only, no party PII -- plus the <b>derived</b> {@link AgreementDisplayStatus} and an {@code
 * editable} flag the SPA uses to decide whether to offer "Edit" (editable) or "View/Download"
 * (signed). The status is projected on read from the signing side, never stored on the agreement.
 *
 * <p>{@code trackingNumber} is the display-only reference {@code AM-<LAST6>-<DDMMYY>} (same as
 * {@link AgreementResponse}); the raw {@code id} stays the canonical identifier used by the
 * claim/edit/read routes.
 */
public record AgreementSummaryResponse(
    UUID id,
    String trackingNumber,
    String propertyAddress,
    BigDecimal monthlyRent,
    LocalDate startDate,
    LocalDate endDate,
    int durationMonths,
    Instant createdAt,
    AgreementDisplayStatus status,
    boolean editable) {}
