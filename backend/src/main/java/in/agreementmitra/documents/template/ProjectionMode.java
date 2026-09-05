package in.agreementmitra.documents.template;

/**
 * The two document-projection validation tiers (design D5).
 *
 * <ul>
 *   <li>{@link #PREVIEW} -- validate present values, tolerate missing ones (they become
 *       placeholders); {@code required} is <b>not</b> enforced, so a half-filled draft still
 *       renders.
 *   <li>{@link #GENERATE} -- validate fully; every {@code required} field must be present (or carry
 *       a default) and valid, so no incomplete document is ever committed as a draft.
 * </ul>
 */
enum ProjectionMode {
  PREVIEW,
  GENERATE
}
