package in.agreementmitra.documents.template;

import in.agreementmitra.documents.api.FormField;
import in.agreementmitra.documents.api.FormSchema;
import in.agreementmitra.documents.api.FormSection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Projects a resolved {@link EffectiveTemplate} into an immutable {@link FormSchema} -- the
 * exploration's <b>layer (2)</b>. A pure, deterministic, <b>data-independent</b> function of the
 * effective template's fields + sections: it reads no user data, takes no clock/IO/random input,
 * and evaluates <b>no</b> {@code showWhen}. Given the same effective template it always produces an
 * equal {@code FormSchema}, which is what makes the schema safely cacheable per {@code (state,
 * type, layer-version-set)} -- the same key that pins the effective template.
 *
 * <p>Co-located with the definition records ({@link Field} / {@link Section} / {@link
 * EffectiveTemplate}) so it can read them without any record visibility being widened; it returns
 * only the {@code public} {@link FormSchema} tree, so no internal type crosses the module boundary.
 */
final class FormProjector {

  /** Project one effective template to its form schema. Pure; never null. */
  FormSchema project(EffectiveTemplate effective) {
    TemplateDefinition definition = effective.template();

    // Index fields by key so section entries can be split: a field-key entry is a form input; a
    // clause-id entry is document body content and is skipped (it has no key in this map).
    Map<String, Field> fieldsByKey = new LinkedHashMap<>();
    for (Field field : definition.fields()) {
      fieldsByKey.put(field.key(), field);
    }

    List<FormSection> sections = new ArrayList<>();
    for (Section section : definition.sections()) {
      List<FormField> fields = new ArrayList<>();
      // A SIGNATURES section's entries are the signer NAME field keys the compiler uses to derive
      // signature zones + eSign anchors; those names are captured by the party cards, so the block
      // is
      // document-only and contributes NO form inputs. Leaving fields empty routes a mandatory
      // signatures block to the document-only omission below (and an optional one to a zero-field
      // opt-in toggle), never surfacing owner/tenant name a second time.
      if (section.render() != RenderKind.SIGNATURES) {
        for (String entry : section.entries()) {
          Field field = fieldsByKey.get(entry);
          if (field == null) {
            continue; // clause-id entry -- document content, not a form input
          }
          fields.add(projectField(field));
        }
      }
      if (fields.isEmpty() && !section.optional()) {
        // A field-less MANDATORY section is a document-only section (e.g. the always-rendered "Now
        // This Agreement Witnesseth" clause list, or a mandatory signature block) -- document
        // structure, not a capture step -- so it is omitted from the FormSchema (it still renders
        // in the document via the compiler). A field-less OPTIONAL section is RETAINED: it is an
        // opt-in add-on the user toggles from the Add-optional catalog with no fields to fill (e.g.
        // an optional "In Witness Whereof" signature block), so the catalog must surface it.
        continue;
      }
      // optional + renderKind are projected verbatim from the effective template's Section: M0
      // already applied the declared defaults (optional = false, render = KEYVALUE), so the
      // projector neither derives nor re-defaults. renderKind crosses as the raw lowercase token
      // (an opaque string) so the public DTO does not couple to the internal RenderKind enum.
      sections.add(
          new FormSection(
              section.title(),
              fields,
              section.optional(),
              section.render().name().toLowerCase(Locale.ROOT)));
    }

    Meta meta = definition.meta();
    return new FormSchema(
        new FormSchema.Dimensions(effective.dimensions().state(), effective.dimensions().type()),
        meta.id(),
        meta.version(),
        effective.contentHash(),
        sections);
  }

  private static FormField projectField(Field field) {
    return new FormField(
        field.key(),
        field.label(),
        widgetFor(field.type()),
        typeToken(field.type()),
        field.required(),
        field.defaultValue(),
        field.type() == FieldType.ENUM ? projectOptions(field.options()) : null,
        field.group(),
        projectValidation(field.validation()),
        // showWhen sits on clauses, not fields, in this model -- the field-level slot is reserved
        // and carried through unevaluated. There is no field condition to carry today.
        null);
  }

  /**
   * Project an enum field's raw option values to {@code {value, label}} pairs, the label derived by
   * the shared {@link OptionLabels} humaniser -- the same label the compiler renders in the
   * document body, so the list box and the document read identically.
   */
  private static List<FormField.Option> projectOptions(List<String> values) {
    if (values == null) {
      return null;
    }
    List<FormField.Option> options = new ArrayList<>(values.size());
    for (String value : values) {
      options.add(new FormField.Option(value, OptionLabels.humanize(value)));
    }
    return options;
  }

  private static FormField.Validation projectValidation(FieldValidation validation) {
    if (validation == null) {
      return null;
    }
    return new FormField.Validation(
        validation.min(),
        validation.max(),
        validation.minLength(),
        validation.maxLength(),
        validation.pattern());
  }

  /**
   * The closed {@link FieldType} -&gt; widget mapping (design D4). Exhaustive over the enum so
   * adding a new {@code FieldType} without a widget is a compile error, not a silent gap.
   */
  private static String widgetFor(FieldType type) {
    return switch (type) {
      case TEXT -> "text";
      case LONGTEXT -> "textarea";
      case INT -> "number";
      case MONEY -> "money";
      case DATE -> "date";
      case BOOL -> "checkbox";
      case ENUM -> "select";
    };
  }

  /** The raw field-type token ({@code text | longtext | int | money | date | bool | enum}). */
  private static String typeToken(FieldType type) {
    return type.name().toLowerCase(Locale.ROOT);
  }
}
