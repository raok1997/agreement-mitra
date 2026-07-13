package in.agreementmitra.documents.template;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A hand-written recursive-descent parser for the closed {@code showWhen} grammar:
 *
 * <pre>
 *   expr := or
 *   or   := and ( '||' and )*
 *   and  := not ( '&amp;&amp;' not )*
 *   not  := '!' not | cmp
 *   cmp  := atom ( ('=='|'!='|'&lt;'|'&lt;='|'&gt;'|'&gt;=') atom )?
 *   atom := field | number | string | bool | '(' expr ')'
 * </pre>
 *
 * <p>Deliberately <b>not</b> an expression library and <b>never</b> Thymeleaf SpringEL. The grammar
 * has no method call, property navigation, indexing, function call, or assignment, and the
 * tokenizer rejects any character outside the grammar (so {@code .}, {@code [}, a lone {@code =},
 * {@code +}, etc. are syntax errors). A parse failure raises {@link ShowWhenException}.
 */
final class ShowWhenParser {

  private ShowWhenParser() {}

  /** Parse a {@code showWhen} source into an AST, or raise {@link ShowWhenException}. */
  static ShowWhenExpr parse(String source) {
    List<Token> tokens = tokenize(source);
    Parser parser = new Parser(tokens);
    ShowWhenExpr expr = parser.parseOr();
    parser.expectEnd();
    return expr;
  }

  // --- tokenizer -------------------------------------------------------------

  private enum Kind {
    IDENT,
    NUMBER,
    STRING,
    BOOL,
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE,
    AND,
    OR,
    NOT,
    LPAREN,
    RPAREN,
    END
  }

  private record Token(Kind kind, String text) {}

  private static List<Token> tokenize(String s) {
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    int n = s.length();
    while (i < n) {
      char c = s.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (Character.isLetter(c) || c == '_') {
        int start = i;
        while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
          i++;
        }
        String word = s.substring(start, i);
        if (word.equals("true") || word.equals("false")) {
          tokens.add(new Token(Kind.BOOL, word));
        } else {
          tokens.add(new Token(Kind.IDENT, word));
        }
      } else if (Character.isDigit(c)) {
        int start = i;
        while (i < n && Character.isDigit(s.charAt(i))) {
          i++;
        }
        if (i < n && s.charAt(i) == '.') {
          i++;
          if (i >= n || !Character.isDigit(s.charAt(i))) {
            throw new ShowWhenException("malformed number literal");
          }
          while (i < n && Character.isDigit(s.charAt(i))) {
            i++;
          }
        }
        tokens.add(new Token(Kind.NUMBER, s.substring(start, i)));
      } else if (c == '"') {
        StringBuilder value = new StringBuilder();
        i++;
        boolean closed = false;
        while (i < n) {
          char d = s.charAt(i);
          if (d == '\\' && i + 1 < n) {
            char e = s.charAt(i + 1);
            if (e == '"' || e == '\\') {
              value.append(e);
              i += 2;
              continue;
            }
            throw new ShowWhenException("unsupported string escape");
          }
          if (d == '"') {
            closed = true;
            i++;
            break;
          }
          value.append(d);
          i++;
        }
        if (!closed) {
          throw new ShowWhenException("unterminated string literal");
        }
        tokens.add(new Token(Kind.STRING, value.toString()));
      } else {
        i = operator(s, i, n, tokens);
      }
    }
    tokens.add(new Token(Kind.END, ""));
    return tokens;
  }

  private static int operator(String s, int i, int n, List<Token> tokens) {
    char c = s.charAt(i);
    char next = i + 1 < n ? s.charAt(i + 1) : '\0';
    switch (c) {
      case '(' -> tokens.add(new Token(Kind.LPAREN, "("));
      case ')' -> tokens.add(new Token(Kind.RPAREN, ")"));
      case '=' -> {
        if (next != '=') {
          throw new ShowWhenException("unexpected '='; assignment is not part of the grammar");
        }
        tokens.add(new Token(Kind.EQ, "=="));
        return i + 2;
      }
      case '!' -> {
        if (next == '=') {
          tokens.add(new Token(Kind.NE, "!="));
          return i + 2;
        }
        tokens.add(new Token(Kind.NOT, "!"));
      }
      case '<' -> {
        if (next == '=') {
          tokens.add(new Token(Kind.LE, "<="));
          return i + 2;
        }
        tokens.add(new Token(Kind.LT, "<"));
      }
      case '>' -> {
        if (next == '=') {
          tokens.add(new Token(Kind.GE, ">="));
          return i + 2;
        }
        tokens.add(new Token(Kind.GT, ">"));
      }
      case '&' -> {
        if (next != '&') {
          throw new ShowWhenException("unexpected '&'; did you mean '&&'?");
        }
        tokens.add(new Token(Kind.AND, "&&"));
        return i + 2;
      }
      case '|' -> {
        if (next != '|') {
          throw new ShowWhenException("unexpected '|'; did you mean '||'?");
        }
        tokens.add(new Token(Kind.OR, "||"));
        return i + 2;
      }
      default ->
          throw new ShowWhenException(
              "unexpected character '" + c + "'; not part of the showWhen grammar");
    }
    return i + 1;
  }

  // --- recursive-descent parser ----------------------------------------------

  private static final class Parser {

    private final List<Token> tokens;
    private int pos;

    Parser(List<Token> tokens) {
      this.tokens = tokens;
    }

    private Token peek() {
      return tokens.get(pos);
    }

    private Token advance() {
      return tokens.get(pos++);
    }

    private boolean match(Kind kind) {
      if (peek().kind() == kind) {
        pos++;
        return true;
      }
      return false;
    }

    void expectEnd() {
      if (peek().kind() != Kind.END) {
        throw new ShowWhenException("unexpected trailing token '" + peek().text() + "'");
      }
    }

    ShowWhenExpr parseOr() {
      ShowWhenExpr left = parseAnd();
      while (match(Kind.OR)) {
        left = new ShowWhenExpr.Or(left, parseAnd());
      }
      return left;
    }

    private ShowWhenExpr parseAnd() {
      ShowWhenExpr left = parseNot();
      while (match(Kind.AND)) {
        left = new ShowWhenExpr.And(left, parseNot());
      }
      return left;
    }

    private ShowWhenExpr parseNot() {
      if (match(Kind.NOT)) {
        return new ShowWhenExpr.Not(parseNot());
      }
      return parseCmp();
    }

    private ShowWhenExpr parseCmp() {
      ShowWhenExpr left = parseAtom();
      ShowWhenExpr.CompareOp op =
          switch (peek().kind()) {
            case EQ -> ShowWhenExpr.CompareOp.EQ;
            case NE -> ShowWhenExpr.CompareOp.NE;
            case LT -> ShowWhenExpr.CompareOp.LT;
            case LE -> ShowWhenExpr.CompareOp.LE;
            case GT -> ShowWhenExpr.CompareOp.GT;
            case GE -> ShowWhenExpr.CompareOp.GE;
            default -> null;
          };
      if (op == null) {
        return left;
      }
      advance();
      return new ShowWhenExpr.Compare(op, left, parseAtom());
    }

    private ShowWhenExpr parseAtom() {
      Token token = peek();
      switch (token.kind()) {
        case LPAREN -> {
          advance();
          ShowWhenExpr inner = parseOr();
          if (!match(Kind.RPAREN)) {
            throw new ShowWhenException("expected ')'");
          }
          return inner;
        }
        case IDENT -> {
          advance();
          return new ShowWhenExpr.FieldRef(token.text());
        }
        case NUMBER -> {
          advance();
          return new ShowWhenExpr.Num(new BigDecimal(token.text()));
        }
        case STRING -> {
          advance();
          return new ShowWhenExpr.Str(token.text());
        }
        case BOOL -> {
          advance();
          return new ShowWhenExpr.Bool(token.text().equals("true"));
        }
        default ->
            throw new ShowWhenException(
                "expected a field, literal, or '(' but found '" + token.text() + "'");
      }
    }
  }
}
