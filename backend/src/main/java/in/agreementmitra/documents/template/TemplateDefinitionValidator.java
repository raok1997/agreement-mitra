package in.agreementmitra.documents.template;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Semantic validation: the cross-reference rules a JSON Schema cannot express. Runs on a bound
 * model <i>after</i> structural validation has passed. Reject-or-nothing -- the first violation
 * raises {@link TemplateDefinitionException} naming only a structural location (field {@code key},
 * clause {@code id}, section title/entry), never a data value.
 *
 * <p>Deliberately does <b>not</b> parse, check, or evaluate {@code showWhen}, and does <b>not</b>
 * resolve {@code ref} clauses -- both are out of scope for this capability.
 */
final class TemplateDefinitionValidator {

  void validate(TemplateDefinition definition) {
    Set<String> fieldKeys = new LinkedHashSet<>();
    for (Field field : definition.fields()) {
      if (!fieldKeys.add(field.key())) {
        throw new TemplateDefinitionException(
            "field key '" + field.key() + "' is declared more than once");
      }
      validateOptions(field);
      validateDefault(field);
    }

    Set<String> clauseIds = new LinkedHashSet<>();
    for (Clause clause : definition.clauses()) {
      // A ref clause is opaque and un-addressable here; only inline clauses have an id + slots.
      if (clause instanceof Clause.Inline inline) {
        if (!clauseIds.add(inline.id())) {
          throw new TemplateDefinitionException(
              "clause id '" + inline.id() + "' is declared more than once");
        }
        for (String slot : inline.slots()) {
          if (!fieldKeys.contains(slot)) {
            throw new TemplateDefinitionException(
                "clause '" + inline.id() + "' references undeclared slot '" + slot + "'");
          }
        }
      }
    }

    Set<String> addressable = new HashSet<>(fieldKeys);
    addressable.addAll(clauseIds);
    for (Section section : definition.sections()) {
      for (String entry : section.entries()) {
        if (!addressable.contains(entry)) {
          throw new TemplateDefinitionException(
              "section '"
                  + section.title()
                  + "' has entry '"
                  + entry
                  + "' that resolves to no declared field or clause");
        }
      }
    }

    // The document header's execution-line slots (if any) must name declared fields, like clauses.
    DocumentMeta document = definition.meta().document();
    if (document != null && document.executionLine() != null) {
      for (String slot : TemplateNodeBinder.parseSlots(document.executionLine())) {
        if (!fieldKeys.contains(slot)) {
          throw new TemplateDefinitionException(
              "document executionLine references undeclared slot '" + slot + "'");
        }
      }
    }
  }

  private void validateOptions(Field field) {
    boolean hasOptions = field.options() != null && !field.options().isEmpty();
    if (field.type() == FieldType.ENUM && !hasOptions) {
      throw new TemplateDefinitionException("enum field '" + field.key() + "' declares no options");
    }
    if (field.type() != FieldType.ENUM && field.options() != null) {
      throw new TemplateDefinitionException(
          "non-enum field '" + field.key() + "' declares options");
    }
  }

  private void validateDefault(Field field) {
    Object value = field.defaultValue();
    if (value == null) {
      return;
    }
    boolean consistent =
        switch (field.type()) {
          case BOOL -> value instanceof Boolean;
          case INT -> value instanceof Long || value instanceof Integer;
          case MONEY -> value instanceof Number;
          case DATE -> value instanceof String s && isIsoDate(s);
          case TEXT, LONGTEXT -> value instanceof String;
          case ENUM ->
              value instanceof String s && field.options() != null && field.options().contains(s);
        };
    if (!consistent) {
      throw new TemplateDefinitionException(
          "field '" + field.key() + "' has a default inconsistent with its type");
    }
  }

  private static boolean isIsoDate(String value) {
    try {
      LocalDate.parse(value);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }
}
