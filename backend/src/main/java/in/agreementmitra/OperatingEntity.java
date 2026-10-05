package in.agreementmitra;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The legal entity that operates AgreementMitra, bound from {@code operator.*}
 * (operating-entity-disclosure D3). The LLPIN and GSTIN come from deployment env and default to
 * blank, which means "not yet issued".
 *
 * <p><b>The legal name is a constant, not a property.</b> The terms of service name the same party
 * in plain text, so no deployment may rename it: a bindable {@code operator.legal-name} would let
 * relaxed binding take {@code OPERATOR_LEGAL_NAME} from the environment. Having no such property,
 * an env var of that name is simply ignored.
 *
 * <p><b>Fails closed on the value.</b> A non-blank LLPIN or GSTIN that fails its format stops the
 * application from starting. Errors name the property and never echo the value.
 *
 * <p><b>The GSTIN is reachable only as an {@link Optional}.</b> Deliberately not a record, so no
 * component accessor hands out a raw string: there is no default to print, and every caller must
 * handle the not-yet-issued case. A future tax invoice or receipt reads it from here and nowhere
 * else.
 */
@ConfigurationProperties(prefix = "operator")
public final class OperatingEntity {

  /** Kept equal to {@code OPERATOR_LEGAL_NAME} in frontend {@code operatorFacts.ts} by its test. */
  public static final String LEGAL_NAME = "KAVISAT TEK LABS LLP";

  /** Kept equal to {@code LLPIN_PATTERN} in frontend {@code operatorFacts.ts} by its test. */
  static final String LLPIN_REGEX = "^[A-Z]{3}-\\d{4}$";

  private static final Pattern LLPIN = Pattern.compile(LLPIN_REGEX);

  private static final Pattern GSTIN =
      Pattern.compile("^\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

  /** PAN character 4 (GSTIN index 5): the holder type. {@code F} is a firm or LLP. */
  private static final int HOLDER_TYPE_INDEX = 5;

  private static final String CHECK_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

  private final String llpin;
  private final String gstin;

  public OperatingEntity(String llpin, String gstin) {
    this.llpin = strip(llpin);
    this.gstin = strip(gstin);
    if (!this.llpin.isEmpty() && !LLPIN.matcher(this.llpin).matches()) {
      throw new IllegalStateException("operator.llpin is not a valid LLPIN (AAA-0000)");
    }
    if (!this.gstin.isEmpty() && !validFirmGstin(this.gstin)) {
      throw new IllegalStateException(
          "operator.gstin is not a valid firm GSTIN (structure, holder type F, check character)");
    }
  }

  public String legalName() {
    return LEGAL_NAME;
  }

  public Optional<String> llpin() {
    return llpin.isEmpty() ? Optional.empty() : Optional.of(llpin);
  }

  public Optional<String> gstin() {
    return gstin.isEmpty() ? Optional.empty() : Optional.of(gstin);
  }

  private static String strip(String value) {
    return value == null ? "" : value.strip();
  }

  private static boolean validFirmGstin(String value) {
    return GSTIN.matcher(value).matches()
        && value.charAt(HOLDER_TYPE_INDEX) == 'F'
        && value.charAt(14) == checkCharacter(value);
  }

  /** The GSTIN mod-36 check character over its first fourteen characters, factors 1, 2, 1, 2... */
  static char checkCharacter(String gstin) {
    int sum = 0;
    for (int i = 0; i < 14; i++) {
      int product = CHECK_ALPHABET.indexOf(gstin.charAt(i)) * (i % 2 == 0 ? 1 : 2);
      sum += product / 36 + product % 36;
    }
    return CHECK_ALPHABET.charAt((36 - sum % 36) % 36);
  }

  @Override
  public String toString() {
    return "OperatingEntity[legalName=" + LEGAL_NAME + "]";
  }
}
