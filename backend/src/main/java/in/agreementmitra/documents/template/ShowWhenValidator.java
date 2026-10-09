package in.agreementmitra.documents.template;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates, as part of resolution, that every {@code showWhen} in a composed template parses under
 * the closed grammar, references only declared field keys, and compares an {@code enum} field only
 * against one of its declared options. Data-independent: it inspects the template's structure,
 * never any user value. A parse failure, an undeclared-field reference or an unknown option literal
 * raises {@link ResolutionException}, naming the clause and the offending identifier -- so
 * resolution is reject-or-nothing on a bad condition.
 *
 * <p>The option check matters because a literal that matches no option is not an error at render
 * time: the clause simply never shows. For an option-gated covenant (sub-letting, dispute mode)
 * that is a deed silently missing a term.
 */
final class ShowWhenValidator {

  private ShowWhenValidator() {}

  static void validate(TemplateDefinition template) {
    Map<String, Field> fields = new LinkedHashMap<>();
    for (Field field : template.fields()) {
      fields.put(field.key(), field);
    }
    for (Clause clause : template.clauses()) {
      if (clause instanceof Clause.Inline inline && inline.showWhen() != null) {
        validateCondition(inline.id(), inline.showWhen(), fields);
      }
    }
  }

  private static void validateCondition(
      String clauseId, String showWhen, Map<String, Field> fields) {
    ShowWhenExpr expr;
    try {
      expr = ShowWhenParser.parse(showWhen);
    } catch (ShowWhenException e) {
      throw new ResolutionException(
          "clause '" + clauseId + "' has an invalid showWhen: " + e.getMessage());
    }
    checkFieldRefs(clauseId, expr, fields);
  }

  private static void checkFieldRefs(
      String clauseId, ShowWhenExpr expr, Map<String, Field> fields) {
    switch (expr) {
      case ShowWhenExpr.Or o -> {
        checkFieldRefs(clauseId, o.left(), fields);
        checkFieldRefs(clauseId, o.right(), fields);
      }
      case ShowWhenExpr.And a -> {
        checkFieldRefs(clauseId, a.left(), fields);
        checkFieldRefs(clauseId, a.right(), fields);
      }
      case ShowWhenExpr.Not not -> checkFieldRefs(clauseId, not.expr(), fields);
      case ShowWhenExpr.Compare c -> {
        checkFieldRefs(clauseId, c.left(), fields);
        checkFieldRefs(clauseId, c.right(), fields);
        checkOptionLiteral(clauseId, c.left(), c.right(), fields);
        checkOptionLiteral(clauseId, c.right(), c.left(), fields);
      }
      case ShowWhenExpr.FieldRef ref -> {
        if (!fields.containsKey(ref.key())) {
          throw new ResolutionException(
              "clause '" + clauseId + "' showWhen references undeclared field '" + ref.key() + "'");
        }
      }
      case ShowWhenExpr.Num ignored -> {}
      case ShowWhenExpr.Str ignored -> {}
      case ShowWhenExpr.Bool ignored -> {}
    }
  }

  private static void checkOptionLiteral(
      String clauseId, ShowWhenExpr side, ShowWhenExpr other, Map<String, Field> fields) {
    if (side instanceof ShowWhenExpr.FieldRef ref
        && other instanceof ShowWhenExpr.Str literal
        && fields.get(ref.key()).type() == FieldType.ENUM
        && fields.get(ref.key()).options() != null
        && !fields.get(ref.key()).options().contains(literal.value())) {
      throw new ResolutionException(
          "clause '"
              + clauseId
              + "' showWhen compares enum field '"
              + ref.key()
              + "' with '"
              + literal.value()
              + "', which is not one of its options");
    }
  }
}
