// Client-side validation of an agreement tracking reference.
//
// This exists for ONE reason: to catch typos before they reach the server. The recovery endpoint
// answers identically whatever you send it, so a mistyped reference produces a cheerful "check your
// email" and an inbox that stays empty. Checking the reference's own check character here turns
// that dead end into an immediate correction, and it does so WITHOUT asking the server anything -
// so it leaks nothing about which references exist.
//
// Mirrors the server's TrackingReference: prefix "AM", eight body characters from a 31-character
// alphabet with the confusable glyphs removed, then one check character computed as a
// position-weighted sum modulo 31.

/** 31 characters: digits 2-9 and A-Z without I, L, O. Prime cardinality (the check modulus). */
const ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
const MODULUS = ALPHABET.length;
const PREFIX = "AM";
const BODY_LENGTH = 8;
const LENGTH = PREFIX.length + BODY_LENGTH + 1;

/** Upper-cases and strips spacing/punctuation people add when reading a code aloud. */
export function normalizeReference(raw: string): string {
  return (raw ?? "").toUpperCase().replace(/[^0-9A-Z]/g, "");
}

/**
 * Position-weighted checksum over the body, mirroring the server exactly.
 *
 * Weights are `i + 2` (consecutive, so a transposition changes the sum) and the modulus is prime
 * (so a substitution always changes it). These two constants must match `TrackingReference` on the
 * server character for character - a port that drifts would reject references the server issued,
 * which is worse than not validating at all.
 */
function checkCharacter(body: string): string {
  let sum = 0;
  for (let i = 0; i < body.length; i++) {
    const value = ALPHABET.indexOf(body.charAt(i));
    if (value < 0) return "";
    sum += value * (i + 2);
  }
  return ALPHABET.charAt(sum % MODULUS);
}

/**
 * Whether this could be a real reference: right shape, alphabet, and check character.
 *
 * True does NOT mean the agreement exists - only that it is worth sending. Nothing here can, or
 * should, be able to tell the difference.
 */
export function isWellFormedReference(candidate: string): boolean {
  const value = normalizeReference(candidate);
  if (value.length !== LENGTH || !value.startsWith(PREFIX)) return false;
  const payload = value.slice(PREFIX.length);
  const body = payload.slice(0, BODY_LENGTH);
  for (const ch of body) {
    if (!ALPHABET.includes(ch)) return false;
  }
  return payload.charAt(BODY_LENGTH) === checkCharacter(body);
}
