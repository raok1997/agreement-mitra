package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@code showWhen} parser semantics (7.5): valid conditions parse; anything outside the closed
 * grammar -- method call, property access, index, function call, assignment -- is a syntax error;
 * boolean precedence and parentheses parse correctly. Pure unit tests, no I/O. This is the RCE
 * guard: the grammar cannot even represent code.
 */
class ShowWhenParserTest {

  @Test
  void aComparisonWithBooleanCombinatorsParses() {
    ShowWhenExpr expr = ShowWhenParser.parse("escalationPct > 0 && furnished == true");

    assertThat(expr).isInstanceOf(ShowWhenExpr.And.class);
    ShowWhenExpr.And and = (ShowWhenExpr.And) expr;
    assertThat(and.left()).isInstanceOf(ShowWhenExpr.Compare.class);
    assertThat(and.right()).isInstanceOf(ShowWhenExpr.Compare.class);
  }

  @Test
  void aBareBooleanFieldParsesAsAFieldReference() {
    assertThat(ShowWhenParser.parse("furnished")).isInstanceOf(ShowWhenExpr.FieldRef.class);
  }

  @Test
  void orHasLowerPrecedenceThanAnd() {
    // a || b && c  ==  a || (b && c)
    ShowWhenExpr expr = ShowWhenParser.parse("a || b && c");

    assertThat(expr).isInstanceOf(ShowWhenExpr.Or.class);
    ShowWhenExpr.Or or = (ShowWhenExpr.Or) expr;
    assertThat(or.left()).isInstanceOf(ShowWhenExpr.FieldRef.class);
    assertThat(or.right()).isInstanceOf(ShowWhenExpr.And.class);
  }

  @Test
  void parenthesesOverrideDefaultPrecedence() {
    // (a || b) && c
    ShowWhenExpr expr = ShowWhenParser.parse("(a || b) && c");

    assertThat(expr).isInstanceOf(ShowWhenExpr.And.class);
    ShowWhenExpr.And and = (ShowWhenExpr.And) expr;
    assertThat(and.left()).isInstanceOf(ShowWhenExpr.Or.class);
    assertThat(and.right()).isInstanceOf(ShowWhenExpr.FieldRef.class);
  }

  @Test
  void notBindsTighterThanAnd() {
    // !a && b  ==  (!a) && b
    ShowWhenExpr expr = ShowWhenParser.parse("!a && b");

    assertThat(expr).isInstanceOf(ShowWhenExpr.And.class);
    assertThat(((ShowWhenExpr.And) expr).left()).isInstanceOf(ShowWhenExpr.Not.class);
  }

  @Test
  void aMethodCallIsASyntaxError() {
    assertThatThrownBy(() -> ShowWhenParser.parse("rent.doubleValue() > 0"))
        .isInstanceOf(ShowWhenException.class);
  }

  @Test
  void aPropertyAccessIsASyntaxError() {
    assertThatThrownBy(() -> ShowWhenParser.parse("owner.name == \"x\""))
        .isInstanceOf(ShowWhenException.class);
  }

  @Test
  void anIndexIsASyntaxError() {
    assertThatThrownBy(() -> ShowWhenParser.parse("options[0] == \"a\""))
        .isInstanceOf(ShowWhenException.class);
  }

  @Test
  void aFunctionCallIsASyntaxError() {
    assertThatThrownBy(() -> ShowWhenParser.parse("max(rent, 0) > 0"))
        .isInstanceOf(ShowWhenException.class);
  }

  @Test
  void anAssignmentIsASyntaxError() {
    assertThatThrownBy(() -> ShowWhenParser.parse("rent = 0"))
        .isInstanceOf(ShowWhenException.class);
  }

  @Test
  void anUnterminatedStringIsASyntaxError() {
    assertThatThrownBy(() -> ShowWhenParser.parse("purpose == \"resi"))
        .isInstanceOf(ShowWhenException.class);
  }
}
