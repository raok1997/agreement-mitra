package in.agreementmitra.documents.template;

import java.util.List;

/**
 * A single structural operation a {@link LayerPatch} applies over the composed-so-far template.
 * Operations edit the <b>structured definition</b> (fields / clauses / sections) -- never raw
 * markup -- so the markup/data boundary holds. Every op except the {@code Add*} ops targets an
 * existing element by its stable key / id / title; a target that does not exist fails resolution.
 *
 * <p>A field's {@code type} is deliberately <b>not</b> overridable ({@link OverrideField} carries
 * no type): changing a field's type would silently invalidate slots and defaults, so a type change
 * is a new field, not an override.
 */
sealed interface Op
    permits Op.AddField,
        Op.OverrideField,
        Op.RemoveField,
        Op.AddClause,
        Op.ReplaceClause,
        Op.RemoveClause,
        Op.AddSection,
        Op.ReplaceSection,
        Op.RemoveSection,
        Op.ReorderSections,
        Op.ReorderEntries {

  /** Append a new typed field to the data contract. */
  record AddField(Field field) implements Op {}

  /**
   * Override metadata of an existing field, selected by {@code key}. Each override component is
   * <b>null when absent</b> (leave the current value untouched); {@code defaultPresent}
   * distinguishes "set the default to this value" (possibly {@code null}) from "do not touch the
   * default". Neither {@code key} nor {@code type} is overridable.
   */
  record OverrideField(
      String key,
      Boolean required,
      boolean defaultPresent,
      Object defaultValue,
      List<String> options,
      FieldValidation validation,
      String group,
      String label)
      implements Op {

    public OverrideField {
      options = options == null ? null : List.copyOf(options);
    }
  }

  /**
   * Remove the field with {@code key}. Fields carry no type override, so a type change is modelled
   * as a remove + add; a removal that orphans a surviving clause slot or section entry fails
   * resolution.
   */
  record RemoveField(String key) implements Op {}

  /** Insert a clause, immediately after the inline clause with id {@code after}, or at the end. */
  record AddClause(String after, Clause clause) implements Op {}

  /** Replace the inline clause with id {@code id} in place. */
  record ReplaceClause(String id, Clause clause) implements Op {}

  /** Remove the inline clause with id {@code id}. */
  record RemoveClause(String id) implements Op {}

  /** Insert a section, immediately after the section titled {@code after}, or at the end. */
  record AddSection(String after, Section section) implements Op {}

  /** Replace the section titled {@code title} in place. */
  record ReplaceSection(String title, Section section) implements Op {}

  /** Remove the section titled {@code title}. */
  record RemoveSection(String title) implements Op {}

  /** Reorder all sections; {@code order} must be a permutation of the current section titles. */
  record ReorderSections(List<String> order) implements Op {

    public ReorderSections {
      order = List.copyOf(order);
    }
  }

  /**
   * Reorder the entries of the section titled {@code title}; {@code order} must be a permutation of
   * that section's current entries.
   */
  record ReorderEntries(String title, List<String> order) implements Op {

    public ReorderEntries {
      order = List.copyOf(order);
    }
  }
}
