package in.agreementmitra.signing.agreement;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Server-managed stamp DATA carried by the {@link Agreement} aggregate - descriptive only, not a
 * status (the stamp LIFECYCLE lives on the signing-request FSM; CR-2's status-less Agreement is
 * preserved). All-nullable: an embedded value with every column null means "no stamp attached yet".
 *
 * <p>Since the manual-estamp-upload CR this records a <b>real</b> SHCIL e-stamp certificate that
 * staff purchased out-of-band and uploaded: its certificate number (the single-use token evidencing
 * duty payment), issue date, duty amount, and jurisdiction, plus the object-storage keys of the
 * uploaded scan and the composited instrument. {@code dutyPaid} is genuinely {@code true} for an
 * attached stamp. The optional {@code documentDescription} / {@code purchasedBy} are recorded
 * verbatim from the certificate.
 *
 * <p>A record value object (an immutable JPA {@code @Embeddable}). Neither the stamped-PDF bytes
 * nor the scan bytes are stored here - they live in MinIO/S3 and only the keys are persisted. Held
 * internally and exposed only through {@link AgreementService}; deliberately NOT on the public
 * {@code AgreementResponse} (mirrors {@code draftPdfKey}).
 *
 * <p><b>PII:</b> {@code purchasedBy} is a party name and {@code documentDescription} describes the
 * property, so unlike the previous synthetic value this record is NOT safe to log verbatim. {@link
 * #toString()} is overridden to emit the storage keys and a redacted certificate number only.
 */
@Embeddable
public record StampInfo(
    @Column(name = "stamp_certificate_number") String certificateNumber,
    @Column(name = "stamped_pdf_key") String stampedPdfKey,
    @Column(name = "stamp_scan_key") String scanKey,
    @Column(name = "stamp_duty_amount") BigDecimal dutyAmount,
    @Column(name = "stamp_jurisdiction") String jurisdiction,
    @Column(name = "stamp_certificate_issue_date") LocalDate certificateIssueDate,
    @Column(name = "stamp_document_description") String documentDescription,
    @Column(name = "stamp_purchased_by") String purchasedBy,
    @Column(name = "stamp_duty_paid") Boolean dutyPaid,
    @Column(name = "stamp_attached_at") Instant attachedAt) {

  /**
   * Redacted rendering: the certificate number appears as its last four characters only, and the
   * certificate's party name / property description never appear at all. The default record {@code
   * toString} would print both verbatim, so this override is a PII control, not cosmetics.
   */
  @Override
  public String toString() {
    return "StampInfo{certificateNumber="
        + redactedCertificateNumber()
        + ", stampedPdfKey="
        + stampedPdfKey
        + ", scanKey="
        + scanKey
        + ", jurisdiction="
        + jurisdiction
        + ", dutyPaid="
        + dutyPaid
        + "}";
  }

  /** Last four characters of the certificate number, or {@code null} when none is attached. */
  public String redactedCertificateNumber() {
    if (certificateNumber == null) {
      return null;
    }
    int from = Math.max(0, certificateNumber.length() - 4);
    return "***" + certificateNumber.substring(from);
  }
}
