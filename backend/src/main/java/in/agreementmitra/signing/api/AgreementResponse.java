package in.agreementmitra.signing.api;

import in.agreementmitra.signing.agreement.Role;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response view of a persisted agreement. Carries the tenancy dates and the server-derived {@code
 * durationMonths} (the whole-month term shown to the user), plus each party's structured fields and
 * stored full {@code name}. {@code createdAt} serializes as ISO-8601 and {@code startDate}/{@code
 * endDate} as ISO dates (Boot's default {@code JavaTimeModule}).
 *
 * <p>{@code trackingNumber} is the display-only agreement reference {@code AM-<LAST6>-<DDMMYY>}
 * (the same value the rendered document shows in its provenance line), derived server-side from the
 * id + start date -- nothing is persisted for it. The raw {@code id} stays the canonical
 * identifier; the tracking number is a readable veneer and is not collision-free, so it is never a
 * key.
 *
 * <p>{@code captureData}/{@code activeSections} are the persisted full capture state (M5): the flat
 * working-set field map and the added optional-section titles, returned (owner-scoped) so the
 * capture form can restore the optional sections and dynamic values when an agreement is reopened
 * for edit. Both are {@code null} for an agreement with no stored capture state (a legacy row / an
 * API client that sent only the fixed fields).
 */
public record AgreementResponse(
    UUID id,
    String trackingNumber,
    String propertyAddress,
    BigDecimal monthlyRent,
    BigDecimal securityDeposit,
    LocalDate startDate,
    LocalDate endDate,
    int durationMonths,
    Instant createdAt,
    List<SignerResponse> signers,
    Map<String, String> captureData,
    List<String> activeSections) {

  /**
   * Backward-compatible constructor without the M5 capture state: keeps callers/tests that build a
   * response without a capture blob compiling. Both capture fields default to {@code null} (the
   * client restores only the fixed fields + parties, as before).
   */
  public AgreementResponse(
      UUID id,
      String trackingNumber,
      String propertyAddress,
      BigDecimal monthlyRent,
      BigDecimal securityDeposit,
      LocalDate startDate,
      LocalDate endDate,
      int durationMonths,
      Instant createdAt,
      List<SignerResponse> signers) {
    this(
        id,
        trackingNumber,
        propertyAddress,
        monthlyRent,
        securityDeposit,
        startDate,
        endDate,
        durationMonths,
        createdAt,
        signers,
        null,
        null);
  }

  public record SignerResponse(
      UUID id,
      String name,
      String firstName,
      String lastName,
      String fatherName,
      String currentAddress,
      String email,
      String mobile,
      Role role) {}
}
