package in.agreementmitra.documents.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One form input in a {@link FormSection}, projected from a definition field. Carries everything
 * the client needs to render a widget and validate input from metadata alone -- and <b>no user
 * data</b>.
 *
 * <ul>
 *   <li>{@code key} / {@code label} -- the field's stable key and its display label.
 *   <li>{@code widget} -- the resolved widget vocabulary token ({@code text | textarea | number |
 *       money | date | checkbox | select}); the client renders this without re-deriving it.
 *   <li>{@code type} -- the raw field-type token ({@code text | longtext | int | money | date |
 *       bool | enum}) so the client can parse/format.
 *   <li>{@code required} -- whether the field must be filled.
 *   <li>{@code defaultValue} (JSON {@code "default"}) -- a type-typed literal ({@code Boolean} /
 *       {@code Long} / {@code java.math.BigDecimal} / {@code String}) when present.
 *   <li>{@code options} -- present only for {@code enum}; each allowed {@code value} with its human
 *       display {@code label} (Initial Caps, {@code _} replaced with a space, acronyms upper-case).
 *       The client shows the label and submits the value.
 *   <li>{@code group} -- optional grouping hint.
 *   <li>{@code validation} -- the projected declarative bounds sufficient for client-side checks.
 *   <li>{@code showWhen} -- an optional conditional-visibility expression carried through
 *       <b>verbatim and unevaluated</b> (reserved for document projection; never fired here).
 * </ul>
 *
 * Optional members are omitted from JSON when absent ({@link JsonInclude.Include#NON_NULL}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormField(
    String key,
    String label,
    String widget,
    String type,
    boolean required,
    @JsonProperty("default") Object defaultValue,
    List<Option> options,
    String group,
    Validation validation,
    String showWhen) {

  public FormField {
    options = options == null ? null : List.copyOf(options);
  }

  /**
   * One enum choice: the stored {@code value} the client submits and the human {@code label} it
   * displays in the list box. The label is a presentation-only, deterministic function of the value
   * derived in the form projection; the value is authoritative for submission, validation,
   * defaults, and {@code showWhen}.
   */
  public record Option(String value, String label) {}

  /**
   * Declarative validation bounds for client-side checks, projected verbatim from the definition's
   * field validation. {@code min}/{@code max} bound numeric ({@code int}/{@code money}) values;
   * {@code minLength}/{@code maxLength}/{@code pattern} bound text. All parts are optional (null =
   * unbounded / omitted). This is data, never an executable expression -- client validation is a UX
   * affordance, not the trust boundary (authoritative validation is server-side, in document
   * projection).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Validation(
      Long min, Long max, Integer minLength, Integer maxLength, String pattern) {}
}
