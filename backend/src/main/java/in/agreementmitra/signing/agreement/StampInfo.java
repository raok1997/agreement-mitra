package in.agreementmitra.signing.agreement;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;

/**
 * Server-managed stamp DATA carried by the {@link Agreement} aggregate — descriptive only, not a
 * status (the stamp LIFECYCLE lives on the signing-request FSM; CR-2's status-less Agreement is
 * preserved). All-nullable: an embedded value with every column null means "no stamp procured yet".
 *
 * <p>A record value object (an immutable JPA {@code @Embeddable}). The {@code stampedPdfKey} is the
 * object-storage key of the composited stamped PDF — the bytes live in MinIO/S3, never here. Held
 * internally and exposed only through {@link AgreementService}; deliberately NOT on the public
 * {@code AgreementResponse} (mirrors {@code draftPdfKey}). Fields are non-PII (a synthetic serial,
 * a storage key, a denomination, a jurisdiction code), so {@code toString} is safe to log.
 */
@Embeddable
public record StampInfo(
    @Column(name = "stamp_serial") String serial,
    @Column(name = "stamped_pdf_key") String stampedPdfKey,
    @Column(name = "stamp_denomination") Integer denomination,
    @Column(name = "stamp_jurisdiction") String jurisdiction,
    @Column(name = "stamp_duty_paid") Boolean dutyPaid,
    @Column(name = "stamp_procured_at") Instant procuredAt) {}
