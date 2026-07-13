package in.agreementmitra.documents.template;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * A <b>pure</b> evaluator for a parsed {@code showWhen} condition: {@code evaluate(expr, values) ->
 * boolean}, reading only the supplied field-value map, with no IO, no template access, and no side
 * effect. Delivered and unit-tested here, but <b>not wired to any render</b> in this capability --
 * document projection will call it with real data in a later CR.
 *
 * <p>Operand-type rules: boolean operators ({@code && || !}) and a bare boolean field require
 * boolean operands; equality ({@code == !=}) compares operands of matching type; ordering ({@code
 * &lt; &lt;= &gt; &gt;=}) requires numeric operands or ISO-date strings on both sides. Any type
 * mismatch or a missing field value raises {@link ShowWhenException}.
 */
final class ShowWhenEvaluator {

  private ShowWhenEvaluator() {}

  /** Evaluate a condition against a field-value map. */
  static boolean evaluate(ShowWhenExpr expr, Map<String, Object> values) {
    return asBoolean(expr, values);
  }

  private static boolean asBoolean(ShowWhenExpr expr, Map<String, Object> values) {
    return switch (expr) {
      case ShowWhenExpr.Or o -> asBoolean(o.left(), values) || asBoolean(o.right(), values);
      case ShowWhenExpr.And a -> asBoolean(a.left(), values) && asBoolean(a.right(), values);
      case ShowWhenExpr.Not n -> !asBoolean(n.expr(), values);
      case ShowWhenExpr.Compare c -> compare(c, values);
      case ShowWhenExpr.Bool b -> b.value();
      case ShowWhenExpr.FieldRef f -> {
        Object value = resolve(f, values);
        if (!(value instanceof Boolean b)) {
          throw new ShowWhenException(
              "field '" + f.key() + "' is not boolean in a boolean context");
        }
        yield b;
      }
      case ShowWhenExpr.Num ignored ->
          throw new ShowWhenException("a number is not a boolean expression");
      case ShowWhenExpr.Str ignored ->
          throw new ShowWhenException("a string is not a boolean expression");
    };
  }

  private static boolean compare(ShowWhenExpr.Compare c, Map<String, Object> values) {
    Object left = value(c.left(), values);
    Object right = value(c.right(), values);
    return switch (c.op()) {
      case EQ -> equalsTyped(left, right);
      case NE -> !equalsTyped(left, right);
      case LT -> ordering(left, right) < 0;
      case LE -> ordering(left, right) <= 0;
      case GT -> ordering(left, right) > 0;
      case GE -> ordering(left, right) >= 0;
    };
  }

  /**
   * An atom's value: a literal, or a field value normalized to {@link BigDecimal}/Boolean/String.
   */
  private static Object value(ShowWhenExpr atom, Map<String, Object> values) {
    return switch (atom) {
      case ShowWhenExpr.Num n -> n.value();
      case ShowWhenExpr.Str s -> s.value();
      case ShowWhenExpr.Bool b -> b.value();
      case ShowWhenExpr.FieldRef f -> resolve(f, values);
      default ->
          throw new ShowWhenException("a compound expression cannot be a comparison operand");
    };
  }

  private static Object resolve(ShowWhenExpr.FieldRef ref, Map<String, Object> values) {
    Object raw = values.get(ref.key());
    if (raw == null) {
      throw new ShowWhenException("field '" + ref.key() + "' has no value");
    }
    if (raw instanceof Boolean b) {
      return b;
    }
    if (raw instanceof BigDecimal d) {
      return d;
    }
    if (raw instanceof Number number) {
      return new BigDecimal(number.toString());
    }
    if (raw instanceof String s) {
      return s;
    }
    throw new ShowWhenException("field '" + ref.key() + "' has an unsupported value type");
  }

  private static boolean equalsTyped(Object left, Object right) {
    if (left instanceof BigDecimal l && right instanceof BigDecimal r) {
      return l.compareTo(r) == 0;
    }
    if (left instanceof Boolean l && right instanceof Boolean r) {
      return l.equals(r);
    }
    if (left instanceof String l && right instanceof String r) {
      return l.equals(r);
    }
    throw new ShowWhenException("cannot compare values of different types for equality");
  }

  private static int ordering(Object left, Object right) {
    if (left instanceof BigDecimal l && right instanceof BigDecimal r) {
      return l.compareTo(r);
    }
    if (left instanceof String l && right instanceof String r) {
      LocalDate ld = asDate(l);
      LocalDate rd = asDate(r);
      if (ld != null && rd != null) {
        return ld.compareTo(rd);
      }
    }
    throw new ShowWhenException("ordering comparison requires numeric or ISO-date operands");
  }

  private static LocalDate asDate(String value) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
