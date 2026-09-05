package in.agreementmitra.signing.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * The non-file half of a staff e-stamp upload: which agreement, and the SHCIL certificate fields
 * the staff member transcribed from the certificate they purchased.
 *
 * <p>Bound from the multipart form's plain parts and bean-validated, so a request missing a
 * mandatory field is refused with a 400 carrying a <b>field-level error list</b> before the handler
 * body runs - therefore before any blob is written or any state changes. The error entries carry
 * field names and constraint messages only; per the app-wide contract no rejected value is ever
 * echoed back.
 *
 * <p>Mandatory: certificate number, issue date, duty amount, jurisdiction. Optional: description of
 * document, purchased by. The sizes are storage bounds; the certificate-number pattern is a
 * deliberately permissive character-set guard (SHCIL formats vary by state) that still guarantees
 * the value is safe to draw into a PDF and can never smuggle a control character into a log line.
 */
public record StampIntakeRequest(
    @NotBlank @Size(max = 32) String agreementReference,
    // Surrounding whitespace is tolerated (a staff-typed value routinely carries it) and stripped
    // by normalisation downstream; the character set inside is what this guards.
    @NotBlank
        @Size(min = 6, max = 72)
        @Pattern(regexp = "\\s*[A-Za-z0-9][A-Za-z0-9 /-]*[A-Za-z0-9]\\s*")
        String certificateNumber,
    @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issueDate,
    @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal dutyAmount,
    @NotBlank @Size(max = 64) String jurisdiction,
    @Size(max = 256) String descriptionOfDocument,
    @Size(max = 128) String purchasedBy,
    // Opt-in, and absent means false: starting an eSign spends a billable transaction and puts an
    // invitation in front of both parties, so it must never happen because a field was omitted.
    //
    // Boxed on purpose. This binds from a multipart form through the record's canonical
    // constructor, and a PRIMITIVE boolean has no value to bind when the field is simply not sent -
    // which fails the whole request with a 400. Every client that predates this field sends exactly
    // that, so a primitive here would have broken uploads that have nothing to do with signing.
    Boolean initiateSigning) {

  /** Absent, blank, or false all mean the same thing: do not start signing. */
  public boolean signingRequested() {
    return Boolean.TRUE.equals(initiateSigning);
  }

  /**
   * PII: {@code purchasedBy} is a party name and {@code descriptionOfDocument} describes the
   * property, and the certificate number evidences duty payment. None of them may reach a log, so
   * the default record rendering is replaced with one that carries nothing.
   */
  @Override
  public String toString() {
    return "StampIntakeRequest{}";
  }
}
