package in.agreementmitra.signing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A row in the caller's "My Agreements" list (the resume view). A lightweight summary -- the terms
 * plus <b>party names only</b> (never Aadhaar, VID, father's name, mobile, email or address) -- and
 * the <b>derived</b> {@link AgreementDisplayStatus} and an {@code editable} flag the SPA uses to
 * decide whether to offer "Edit" (editable) or "View/Download" (signed). The status is projected on
 * read from the signing side, never stored on the agreement.
 *
 * <p>{@code ownerNames} and {@code tenantNames} hold every party of that role in entry order.
 * {@code lastEditedAt} is when the content was last changed through the drafting surface.
 *
 * <p>{@code trackingNumber} carries the agreement's single tracking reference (same as {@link
 * AgreementResponse}); the raw {@code id} stays the canonical identifier used by the
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
    Instant lastEditedAt,
    List<String> ownerNames,
    List<String> tenantNames,
    AgreementDisplayStatus status,
    boolean editable) {}
