package in.agreementmitra.signing.agreement;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The agreement's <b>one</b> externally-visible reference: {@code AM} + eight body characters + one
 * check character, e.g. {@code AM7K3QPW9Z4}. Assigned at agreement creation, persisted, immutable,
 * and unique at the database level.
 *
 * <p>There is deliberately only one. The customer is given this number, staff type <em>this same
 * number</em> to attach the purchased e-stamp, and the document's provenance line renders it. Two
 * references for one agreement is the transcription hazard, not the cure.
 *
 * <p>Two properties make it safe to carry through a manual loop - read off an order, taken to the
 * SHCIL portal, typed back hours later:
 *
 * <ul>
 *   <li><b>Unambiguous alphabet.</b> 31 characters with {@code 0/O}, {@code 1/I/L} removed, so the
 *       glyph pairs people confuse when reading a code aloud simply cannot occur.
 *   <li><b>Check character.</b> A position-weighted sum modulo 31 (a prime, and the weights are
 *       consecutive) catches every single-character substitution and every transposition of two
 *       characters. That turns the dangerous failure - silently stamping the WRONG agreement with a
 *       certificate that cost real money - into a safe one: the input is simply rejected.
 * </ul>
 *
 * <p>The body is drawn from {@link SecureRandom}, never derived from the agreement id, so a
 * reference cannot be computed from a known id nor used to enumerate neighbours. Possessing a valid
 * reference authorises nothing by itself - stamp intake still demands the STAFF role.
 *
 * <p>The <b>retired</b> {@code AM-<LAST6>-<DDMMYY>} form (derived at render time, not
 * collision-free) is recognised by {@link #isLegacyDerivedFormat(String)} purely so an old value
 * pasted into a lookup is refused deliberately rather than by accident.
 */
final class TrackingReference {

  /** 31 characters: digits 2-9 and A-Z without I, L, O. Prime cardinality (the check modulus). */
  private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

  private static final int MODULUS = ALPHABET.length(); // 31

  static final String PREFIX = "AM";

  private static final int BODY_LENGTH = 8;

  /** Total characters: prefix + body + one check character. */
  static final int LENGTH = PREFIX.length() + BODY_LENGTH + 1;

  /** The retired derived form - never accepted as a lookup key. */
  private static final Pattern LEGACY_DERIVED = Pattern.compile("AM-[0-9A-F]{6}-[0-9]{6}");

  private static final SecureRandom RANDOM = new SecureRandom();

  private TrackingReference() {}

  /**
   * A fresh, unguessable reference. ~40 bits of entropy; the DB unique constraint is the arbiter.
   */
  static String generate() {
    StringBuilder body = new StringBuilder(BODY_LENGTH);
    for (int i = 0; i < BODY_LENGTH; i++) {
      body.append(ALPHABET.charAt(RANDOM.nextInt(MODULUS)));
    }
    return PREFIX + body + checkCharacter(body.toString());
  }

  /**
   * Canonical form of a typed value: trimmed and uppercased, so {@code " am7k3qpw9z4 "} and {@code
   * "AM7K3QPW9Z4"} are the same reference. Null in, null out.
   */
  static String normalize(String raw) {
    return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
  }

  /**
   * True if {@code candidate} (already {@link #normalize(String) normalized}) is a well-formed
   * reference whose check character agrees with its body. A single mistyped character fails here
   * rather than resolving to a different agreement.
   */
  static boolean isValid(String candidate) {
    if (candidate == null || candidate.length() != LENGTH || !candidate.startsWith(PREFIX)) {
      return false;
    }
    String payload = candidate.substring(PREFIX.length());
    for (int i = 0; i < payload.length(); i++) {
      if (ALPHABET.indexOf(payload.charAt(i)) < 0) {
        return false;
      }
    }
    String body = payload.substring(0, BODY_LENGTH);
    return payload.charAt(BODY_LENGTH) == checkCharacter(body);
  }

  /**
   * True if {@code candidate} is a retired {@code AM-<LAST6>-<DDMMYY>} derived reference. Such a
   * value was never collision-free and is never a lookup key; recognising it keeps the refusal
   * deliberate rather than incidental.
   */
  static boolean isLegacyDerivedFormat(String candidate) {
    return candidate != null && LEGACY_DERIVED.matcher(candidate).matches();
  }

  /**
   * Position-weighted checksum over the body. Weights are {@code i + 2} (consecutive, so a
   * transposition changes the sum) and the modulus is prime (so a substitution always changes it).
   */
  private static char checkCharacter(String body) {
    int accumulator = 0;
    for (int i = 0; i < body.length(); i++) {
      accumulator += ALPHABET.indexOf(body.charAt(i)) * (i + 2);
    }
    return ALPHABET.charAt(accumulator % MODULUS);
  }
}
