package in.agreementmitra.documents.template;

import java.util.List;

/**
 * A clause is either a {@link Ref} to a clause-library id (recorded opaquely, never resolved by
 * this capability) or an {@link Inline} clause of plain text with typed {@code {{slots}}}.
 *
 * <p>This is where the markup/data boundary lives in the format: inline {@code text} is plain text,
 * never HTML and never an expression. The model only <i>records</i> a clause's slots (as declared
 * field references) -- it never fills, escapes, or evaluates them.
 */
sealed interface Clause permits Clause.Ref, Clause.Inline {

  /** A reference to a clause-library entry; {@code ref} is an opaque id, not resolved here. */
  record Ref(String ref) implements Clause {}

  /**
   * An inline clause. {@code text} is plain text that may contain {@code {{slot}}} placeholders;
   * {@code slots} are those placeholders parsed out (deduplicated, first-appearance order), each of
   * which must name a declared field. {@code showWhen} (nullable) is carried <b>verbatim</b> and is
   * never parsed, validated, or evaluated by this capability.
   */
  record Inline(String id, String text, String showWhen, List<String> slots) implements Clause {

    public Inline {
      slots = List.copyOf(slots);
    }
  }
}
