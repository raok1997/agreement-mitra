package in.agreementmitra.documents.template;

import java.math.BigDecimal;

/**
 * The abstract syntax tree of a parsed {@code showWhen} condition -- a <b>closed</b> boolean
 * grammar over declared field references and number/string/bool literals. There is deliberately
 * <b>no</b> node for a method call, property navigation, index, function call, or assignment: the
 * grammar cannot express them, so the tree cannot represent code. This is the RCE guard.
 *
 * <p>Boolean-valued nodes: {@link Or}, {@link And}, {@link Not}, {@link Compare}, and a {@link
 * Bool} literal or a boolean-typed {@link FieldRef} used on its own. Atoms usable as comparison
 * operands: {@link FieldRef}, {@link Num}, {@link Str}, {@link Bool}.
 */
sealed interface ShowWhenExpr
    permits ShowWhenExpr.Or,
        ShowWhenExpr.And,
        ShowWhenExpr.Not,
        ShowWhenExpr.Compare,
        ShowWhenExpr.FieldRef,
        ShowWhenExpr.Num,
        ShowWhenExpr.Str,
        ShowWhenExpr.Bool {

  /** The comparison operators the grammar admits. */
  enum CompareOp {
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE
  }

  /** {@code left || right}. */
  record Or(ShowWhenExpr left, ShowWhenExpr right) implements ShowWhenExpr {}

  /** {@code left && right}. */
  record And(ShowWhenExpr left, ShowWhenExpr right) implements ShowWhenExpr {}

  /** {@code !expr}. */
  record Not(ShowWhenExpr expr) implements ShowWhenExpr {}

  /** {@code left <op> right}, where both operands are atoms. */
  record Compare(CompareOp op, ShowWhenExpr left, ShowWhenExpr right) implements ShowWhenExpr {}

  /** A reference to a declared field, by {@code key}. */
  record FieldRef(String key) implements ShowWhenExpr {}

  /** A numeric literal (integer or decimal). */
  record Num(BigDecimal value) implements ShowWhenExpr {}

  /** A string literal. */
  record Str(String value) implements ShowWhenExpr {}

  /** A boolean literal. */
  record Bool(boolean value) implements ShowWhenExpr {}
}
