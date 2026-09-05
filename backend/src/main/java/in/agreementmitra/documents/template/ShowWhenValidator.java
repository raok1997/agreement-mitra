package in.agreementmitra.documents.template;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Validates, as part of resolution, that every {@code showWhen} in a composed template parses under
 * the closed grammar and references only declared field keys. Data-independent: it inspects the
 * template's structure, never any user value. A parse failure or an undeclared-field reference
 * raises {@link ResolutionException}, naming the clause and (for an unknown field) the identifier
 * -- so resolution is reject-or-nothing on a bad condition.
 */
final class ShowWhenValidator {

  private ShowWhenValidator() {}

  static void validate(TemplateDefinition template) {
    Set<String> fieldKeys = new LinkedHashSet<>();
    for (Field field : template.fields()) {
      fieldKeys.add(field.key());
    }
    for (Clause clause : template.clauses()) {
      if (clause instanceof Clause.Inline inline && inline.showWhen() != null) {
        validateCondition(inline.id(), inline.showWhen(), fieldKeys);
      }
    }
  }

  private static void validateCondition(String clauseId, String showWhen, Set<String> fieldKeys) {
    ShowWhenExpr expr;
    try {
      expr = ShowWhenParser.parse(showWhen);
    } catch (ShowWhenException e) {
      throw new ResolutionException(
          "clause '" + clauseId + "' has an invalid showWhen: " + e.getMessage());
    }
    checkFieldRefs(clauseId, expr, fieldKeys);
  }

  private static void checkFieldRefs(String clauseId, ShowWhenExpr expr, Set<String> fieldKeys) {
    switch (expr) {
      case ShowWhenExpr.Or o -> {
        checkFieldRefs(clauseId, o.left(), fieldKeys);
        checkFieldRefs(clauseId, o.right(), fieldKeys);
      }
      case ShowWhenExpr.And a -> {
        checkFieldRefs(clauseId, a.left(), fieldKeys);
        checkFieldRefs(clauseId, a.right(), fieldKeys);
      }
      case ShowWhenExpr.Not not -> checkFieldRefs(clauseId, not.expr(), fieldKeys);
      case ShowWhenExpr.Compare c -> {
        checkFieldRefs(clauseId, c.left(), fieldKeys);
        checkFieldRefs(clauseId, c.right(), fieldKeys);
      }
      case ShowWhenExpr.FieldRef ref -> {
        if (!fieldKeys.contains(ref.key())) {
          throw new ResolutionException(
              "clause '" + clauseId + "' showWhen references undeclared field '" + ref.key() + "'");
        }
      }
      case ShowWhenExpr.Num ignored -> {}
      case ShowWhenExpr.Str ignored -> {}
      case ShowWhenExpr.Bool ignored -> {}
    }
  }
}
