package in.agreementmitra.signing.stamp;

import java.util.UUID;

/**
 * Deterministic synthetic stamp-serial generator. The serial is derived purely from the agreement
 * id, so it is reproducible across runs and stable for a given agreement, and distinct agreements
 * get distinct serials (a 10-digit space over the mixed 128 bits — effectively collision-free).
 * Never uses {@code Math.random} or the wall clock. The {@code BW } prefix is the Karnataka
 * non-judicial series; the long numeric tail makes it obviously synthetic — it must never be
 * mistaken for a real procured stamp.
 */
final class StampSerials {

  private static final long SERIAL_MODULUS = 10_000_000_000L; // 10 digits

  private StampSerials() {}

  static String forAgreement(UUID agreementId) {
    long mixed = agreementId.getMostSignificantBits() ^ agreementId.getLeastSignificantBits();
    long digits = Math.floorMod(mixed, SERIAL_MODULUS);
    return String.format("BW %010d", digits);
  }
}
