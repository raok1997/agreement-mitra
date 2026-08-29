package in.agreementmitra.documents.template;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

/**
 * Compiles an {@link EffectiveTemplate}, a submitted data map, and a <b>resolved execution date</b>
 * into a self-contained HTML document laid out as the reference rental-agreement artifact: a
 * centred document header from {@code meta.document}, section bodies drawn in the style each
 * section declares (its {@link RenderKind}), a serif Latin body that keeps the bundled Noto faces
 * for Indic shaping, and real page margins. This is the safety-critical markup/data boundary: the
 * document skeleton (the header, section headings, party cards, tables, ordered and bulleted lists,
 * the signature block) is <b>system-owned markup</b>, and every value that comes from the data map
 * -- a clause's {@code {{slot}}} fills, the header execution-line slots, and a field's value cell
 * -- is <b>HTML-escaped</b>, so a user value containing markup renders as literal text and can
 * never become document structure or active content. Every piece of system-authored template text
 * (the header title / subtitle / execution-line text, field labels, clause text) is escaped as
 * literal text too.
 *
 * <p>Conditional clauses are included or dropped <b>solely</b> through the resolution engine's
 * sandboxed boolean DSL ({@link ShowWhenParser} + {@link ShowWhenEvaluator}) -- never Thymeleaf,
 * SpringEL, or any expression engine, so wiring conditions to a render adds no code-execution
 * surface. A clause whose {@code showWhen} references an absent/uncoercible value is treated
 * deterministically (the clause is dropped) and never raises an exception that could leak a value;
 * a dropped clause simply does not appear, and the surrounding {@code <ol>} numbering closes up.
 *
 * <p>A slot (or a field value cell) whose value is missing or blank renders an <b>escaped
 * placeholder</b> built from the field's label ({@code [ label ]}) -- never a bare {@code null} and
 * never unescaped. The composed HTML is self-contained: fonts are referenced by family name and the
 * Noto faces are embedded as data-URIs, with no external URLs, preserving the offline /
 * Chromium-network-denied render guarantee. Neither the composed HTML nor any submitted value is
 * ever logged.
 *
 * <p><b>Pure function.</b> The compiler is a pure function of {@code (effective template, data,
 * resolved execution date)}: it reads no clock and no ambient state. The projection layer resolves
 * the execution date once (submitted {@code agreementDate} when present, else the current system
 * date from an injected {@code Clock}) and passes the concrete value in; the compiler binds it
 * under the {@linkplain #EXECUTION_DATE_KEY reserved date-binding key} so the header execution line
 * (and any clause slot referencing that key) fills from the resolved value.
 *
 * <p>Package-private. Constructed with the {@code @font-face} CSS that embeds the Noto faces as
 * data-URIs so the single compiled HTML is self-contained for the browser <b>and</b> identical for
 * both render tiers (the parity non-negotiable, design D-A); the faces are loaded once at bean init
 * ({@link DocumentFonts}) and injected here. Unit tests construct it with no faces (pure, no I/O).
 */
final class TemplateCompiler {

  /**
   * Matches a {@code {{slotKey}}} placeholder; captures the key. Mirrors {@link
   * TemplateNodeBinder#SLOT}.
   */
  private static final Pattern SLOT = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

  /**
   * The reserved date-binding key (design D4). The projection layer resolves the execution date --
   * the submitted {@code agreementDate} value when present and non-blank, else the current system
   * date (SYSDATE) from an injected {@code Clock} -- and passes the concrete value into {@link
   * #compile}, which binds it under this key on a <b>copy</b> of the data map, <b>overriding</b>
   * any submitted {@code agreementDate}, so the header execution line authored as {@code ...
   * {{agreementDate}} ...} (and any clause slot referencing it) fills from the resolved value. The
   * request's data map is never mutated. Documented so M5 authors the execution line against this
   * key and the SYSDATE fallback is not hidden behavior.
   */
  static final String EXECUTION_DATE_KEY = "agreementDate";

  /**
   * The single, locale-fixed display format for every rendered date ({@code dd-MMM-yyyy} -> {@code
   * 13-Jul-2026}). Applied to a {@code DATE} field's ISO value at render time only; the data map
   * keeps ISO so {@code showWhen} evaluation is unaffected. Locale-fixed so the output is
   * deterministic regardless of host locale (parity + reproducibility).
   */
  private static final DateTimeFormatter DISPLAY_DATE =
      DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

  /**
   * The {@code @font-face} CSS injected ahead of the family-name stylesheet; {@code ""} when no
   * faces are embedded (the pure unit-test case), leaving the output free of any {@code url(...)}.
   */
  private final String fontFaceCss;

  /** Compiler with no embedded faces: pure, no I/O -- for unit tests. */
  TemplateCompiler() {
    this("");
  }

  TemplateCompiler(String fontFaceCss) {
    this.fontFaceCss = fontFaceCss == null ? "" : fontFaceCss;
  }

  /**
   * Convenience overload for callers with no resolved execution date (pure unit fixtures whose
   * template declares no execution line). Delegates with a {@code null} resolved date, so no
   * reserved-key binding is applied.
   */
  String compile(EffectiveTemplate effective, Map<String, Object> data) {
    return compile(effective, data, null);
  }

  /**
   * Convenience overload for callers that add no optional sections (the empty active set): every
   * mandatory section renders and every optional section is skipped. Delegates with an empty active
   * set.
   */
  String compile(
      EffectiveTemplate effective, Map<String, Object> data, String resolvedExecutionDate) {
    return compile(effective, data, resolvedExecutionDate, Set.of());
  }

  /**
   * Compile {@code effective} against {@code data}, {@code resolvedExecutionDate}, and the {@code
   * activeSections} of added optional section titles into a self-contained HTML string. {@code
   * data} maps a field {@code key} to its (typically already coerced) value; a missing or blank
   * value renders an escaped placeholder rather than failing. {@code resolvedExecutionDate}
   * (nullable) is bound under the {@linkplain #EXECUTION_DATE_KEY reserved key} on a copy of {@code
   * data} -- the request map is never mutated.
   *
   * <p><b>Section gating (design D2):</b> a section renders <b>iff</b> it is mandatory ({@code
   * section.optional() == false}) <b>or</b> its {@code title} is present in {@code activeSections}
   * (an added optional section). A section that fails the predicate is skipped entirely -- no
   * {@code <section>}, no header, no field table, no clause list. Title matching is an exact,
   * case-sensitive match on the declared section {@code title}; a title in {@code activeSections}
   * that matches no declared section is ignored (renders nothing, raises nothing -- no existence
   * oracle). Section activation is the outer gate; a skipped section evaluates none of its clauses'
   * {@code showWhen}. The active set can only toggle a template-declared optional section on -- it
   * can never introduce structure the template did not declare. A {@code null} active set is
   * treated as empty. The compiler stays a pure function of {@code (effective template, data,
   * resolved date, active set)}.
   */
  String compile(
      EffectiveTemplate effective,
      Map<String, Object> data,
      String resolvedExecutionDate,
      Set<String> activeSections) {
    return compile(effective, data, resolvedExecutionDate, activeSections, null, null);
  }

  /**
   * As {@link #compile(EffectiveTemplate, Map, String, Set)}, additionally emitting a system-owned
   * <b>provenance line at the document foot</b>: the escaped {@code reference} (a tracking number
   * or a preview marker) and the escaped {@code platformUrl}, each part omitted when blank. Both
   * are values passed in -- the compiler reads no configuration -- rendered as literal text, so
   * they appear <b>identically in the preview HTML and the PDF</b> (parity, they are body content)
   * and cannot inject markup. A {@code null}/blank {@code reference} and {@code platformUrl} emit
   * no line, so a pre-identifier preview can carry only the URL, or nothing.
   */
  String compile(
      EffectiveTemplate effective,
      Map<String, Object> data,
      String resolvedExecutionDate,
      Set<String> activeSections,
      String reference,
      String platformUrl) {
    Set<String> active = activeSections == null ? Set.of() : activeSections;
    // Bind the resolved execution date under the reserved key on a COPY (the request data is never
    // mutated); the resolved value overrides any submitted agreementDate.
    Map<String, Object> values = new LinkedHashMap<>(data == null ? Map.of() : data);
    if (resolvedExecutionDate != null) {
      values.put(EXECUTION_DATE_KEY, resolvedExecutionDate);
    }
    TemplateDefinition template = effective.template();
    Map<String, Field> fieldsByKey = index(template.fields());
    Map<String, Clause.Inline> clausesById = inlineClauses(template.clauses());

    StringBuilder html = new StringBuilder(2048);
    html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\"/>\n")
        .append("<title>Document</title>\n")
        .append("<style>\n")
        .append(fontFaceCss)
        .append(STYLE)
        .append("</style>\n</head>\n<body>\n");

    appendHeader(html, template.meta().document(), fieldsByKey, values);

    for (Section section : template.sections()) {
      // Section gating (design D2): render iff mandatory OR its title is an added optional. An
      // optional section absent from the active set contributes nothing -- no <section>, header,
      // field table, or clause list. Exact, case-sensitive title match; an active-set title that
      // matches no section simply never fires here (ignored, no error -- no existence oracle).
      if (section.optional() && !active.contains(section.title())) {
        continue;
      }
      html.append("<section>\n<h2>").append(escape(section.title())).append("</h2>\n");
      appendSectionBody(html, section, fieldsByKey, clausesById, values);
      html.append("</section>\n");
    }

    // The witness/signature block is emitted as the fixed system-owned fallback ONLY when the
    // template does not declare its own signatures section. A template that declares a
    // render:signatures section owns the block's placement and optionality (mandatory renders it in
    // the section loop above; optional renders it only when added to the active set), so appending
    // the fallback too would double it. Templates that declare no signatures section (the reference
    // fixtures) keep the always-appended block unchanged.
    boolean hasSignaturesSection =
        template.sections().stream().anyMatch(s -> s.render() == RenderKind.SIGNATURES);
    if (!hasSignaturesSection) {
      html.append(SIGNATURE_BLOCK);
    }
    html.append(provenanceLine(reference, platformUrl));
    html.append("</body>\n</html>\n");
    return html.toString();
  }

  /**
   * A system-owned provenance line for the document foot: the escaped {@code reference} and escaped
   * {@code platformUrl} separated by a middot, each omitted when blank; the empty string when both
   * are blank. Rendered as literal text (it cannot inject markup).
   *
   * <p>It is a <b>screen-only</b> body element ({@code .doc-provenance} is {@code display:none} by
   * default, shown only under {@code @media screen}): the on-screen preview iframe (screen media)
   * shows it, so the reader sees the reference + URL in the preview; the Gotenberg PDF renders as
   * print media, which hides it -- so it never orphans onto its own page or overlaps content. In
   * the PDF the same reference + URL are stamped per page as footer furniture in the reserved
   * margin.
   */
  private static String provenanceLine(String reference, String platformUrl) {
    String ref = reference == null ? "" : reference.strip();
    String url = platformUrl == null ? "" : platformUrl.strip();
    if (ref.isEmpty() && url.isEmpty()) {
      return "";
    }
    StringBuilder line = new StringBuilder("<div class=\"doc-provenance\">");
    if (!ref.isEmpty()) {
      line.append("<span>").append(escape(ref)).append("</span>");
    }
    if (!ref.isEmpty() && !url.isEmpty()) {
      line.append("<span> &middot; </span>");
    }
    if (!url.isEmpty()) {
      line.append("<span>").append(escape(url)).append("</span>");
    }
    return line.append("</div>\n").toString();
  }

  /**
   * Emit the centred document header from {@code meta.document} (design D1): the escaped {@code
   * title}, the escaped {@code subtitle}, and the execution line whose {@code {{slot}}} fills go
   * through the shared slot-fill path (each substituted value and every literal segment escaped).
   * All three are system-authored template text escaped exactly as clause text is. When the
   * definition declares no header ({@code document == null}) nothing is emitted.
   */
  private static void appendHeader(
      StringBuilder html,
      DocumentMeta document,
      Map<String, Field> fieldsByKey,
      Map<String, Object> values) {
    if (document == null) {
      return; // the definition declares no header
    }
    html.append("<div class=\"doc-header\">\n");
    if (document.title() != null && !document.title().isBlank()) {
      html.append("<h1 class=\"doc-title\">").append(escape(document.title())).append("</h1>\n");
    }
    if (document.subtitle() != null && !document.subtitle().isBlank()) {
      html.append("<div class=\"doc-subtitle\">")
          .append(escape(document.subtitle()))
          .append("</div>\n");
    }
    if (document.executionLine() != null && !document.executionLine().isBlank()) {
      html.append("<p class=\"doc-exec\">")
          .append(renderSlotText(document.executionLine(), fieldsByKey, values))
          .append("</p>\n");
    }
    html.append("</div>\n");
  }

  /**
   * Dispatch a section's body on its declared {@link RenderKind} (design D2): {@code PARTIES} -> a
   * party card, {@code KEYVALUE} -> the label/value table (the existing rendering, retained),
   * {@code CLAUSES} -> a numbered ordered list, {@code ANNEXURE} -> a bulleted list. A {@code null}
   * kind (a hand-built fixture that omits it; resolution defaults it to {@code KEYVALUE}) falls
   * back to {@code KEYVALUE} so a render never throws. Field labels, field values, and clause text
   * stay HTML-escaped in every kind; {@code showWhen} still gates each clause through the sandboxed
   * DSL.
   */
  private static void appendSectionBody(
      StringBuilder html,
      Section section,
      Map<String, Field> fieldsByKey,
      Map<String, Clause.Inline> clausesById,
      Map<String, Object> values) {
    RenderKind kind = section.render() == null ? RenderKind.KEYVALUE : section.render();
    switch (kind) {
      case PARTIES -> appendPartyCard(html, section, fieldsByKey, values);
      case CLAUSES -> appendClauseList(html, section, fieldsByKey, clausesById, values);
      case ANNEXURE -> appendAnnexure(html, section, fieldsByKey, clausesById, values);
      case SIGNATURES -> appendSignatures(html, section, fieldsByKey, values);
      case KEYVALUE -> appendKeyValueBody(html, section, fieldsByKey, clausesById, values);
    }
  }

  /**
   * {@code SIGNATURES} body: the execution / signature block. Opens with the system-owned "IN
   * WITNESS WHEREOF" paragraph, then renders one signature <b>zone</b> per section entry -- each
   * entry is a signer <b>name field key</b> ({@code ownerName}, {@code tenantName}). A zone shows a
   * signature area, the signer's name value (escaped, from the data map), and the field's role
   * label. The label states the role only: nothing here verifies the captured name against the
   * Aadhaar record, so the document must not claim that it matches one. There is no date/place line
   * either - an eSigned instrument takes its date from the eSign appearance, so those blanks could
   * never be completed.
   *
   * <p><b>The anchor sits INSIDE the signature area</b>, because its position IS the position the
   * signature occupies: the {@code signing} module locates the token {@code esign:<role>} and asks
   * the provider to sign there. Emitted after the block (as it once was) it marked the bottom of
   * the zone, and every signature landed on the line below the name. Keep it in the area.
   *
   * <p>The anchor is a stable, non-PII text token whose role is derived from the entry key ({@code
   * ownerName -> owner}); the compiler stays eSign-agnostic (it emits a token, not a provider
   * field). It is painted in the page colour so a reader never sees it - see the {@code
   * .sign-anchor} rule for why it must stay in the text layer. Domain-neutral: the compiler holds
   * no party model; it renders whatever signatory name keys the section declares, each value
   * escaped.
   */
  private static void appendSignatures(
      StringBuilder html,
      Section section,
      Map<String, Field> fieldsByKey,
      Map<String, Object> values) {
    html.append(
        "<p>IN WITNESS WHEREOF the parties have set their hands to this Agreement on the date"
            + " first above written, having read and understood its contents. Executed"
            + " electronically via Aadhaar eSign; the eSign audit trail evidences the identity and"
            + " consent of each party.</p>\n");
    html.append("<div class=\"sign-grid\">\n");
    for (String entry : section.entries()) {
      Field field = fieldsByKey.get(entry);
      if (field == null) {
        continue; // a signatures section lists signer name field keys; ignore anything else
      }
      html.append("<div class=\"sign-zone\">\n")
          .append("<div class=\"sign-area\"><span class=\"sign-anchor\">esign:")
          .append(escape(anchorRole(entry)))
          .append("</span></div>\n")
          .append("<div class=\"sign-name\">")
          .append(escape(valueOrPlaceholder(field, values.get(field.key()))))
          .append("</div>\n")
          .append("<div class=\"sign-meta\">")
          .append(escape(field.label()))
          .append("</div>\n")
          .append("</div>\n");
    }
    html.append("</div>\n");
  }

  /**
   * Derive the non-PII eSign anchor role from a signer name field key: strip a trailing {@code
   * Name} and lower-case ({@code ownerName -> owner}, {@code tenantName -> tenant}). The {@code
   * signing} module derives the same {@code esign:<role>} token from its signer roles, so the
   * anchor a zone emits and the provider field {@code signing} maps agree without either module
   * sharing a type.
   */
  private static String anchorRole(String fieldKey) {
    String base =
        fieldKey.endsWith("Name")
            ? fieldKey.substring(0, fieldKey.length() - "Name".length())
            : fieldKey;
    return base.toLowerCase(Locale.ROOT);
  }

  /**
   * {@code KEYVALUE} body: render the section's entries in authored order, grouping consecutive
   * field entries into a key/value table and consecutive (included) clause entries into a numbered
   * list. A field entry closes any open clause list; an included clause entry closes any open field
   * table; a clause dropped by its {@code showWhen} leaves the current grouping untouched and
   * simply does not appear. This is the existing table/list rendering, retained.
   */
  private static void appendKeyValueBody(
      StringBuilder html,
      Section section,
      Map<String, Field> fieldsByKey,
      Map<String, Clause.Inline> clausesById,
      Map<String, Object> values) {
    boolean inTable = false;
    boolean inList = false;
    for (String entry : section.entries()) {
      Field field = fieldsByKey.get(entry);
      if (field != null) {
        if (inList) {
          html.append("</ol>\n");
          inList = false;
        }
        if (!inTable) {
          html.append("<table class=\"kv\">\n");
          inTable = true;
        }
        appendKvRow(html, field, values);
        continue;
      }
      Clause.Inline clause = clausesById.get(entry);
      if (clause == null || !included(clause, values)) {
        continue; // opaque ref, unknown entry, or a showWhen-dropped clause: emit nothing
      }
      if (inTable) {
        html.append("</table>\n");
        inTable = false;
      }
      if (!inList) {
        html.append("<ol class=\"clauses\">\n");
        inList = true;
      }
      html.append("<li>").append(renderClauseText(clause, fieldsByKey, values)).append("</li>\n");
    }
    if (inTable) {
      html.append("</table>\n");
    }
    if (inList) {
      html.append("</ol>\n");
    }
  }

  /**
   * {@code PARTIES} body: a party card -- a label/value block of the section's field entries (used
   * for the separate Owner and Tenant sections). Non-field entries are ignored (a party section
   * carries fields). Every label and value is HTML-escaped.
   */
  private static void appendPartyCard(
      StringBuilder html,
      Section section,
      Map<String, Field> fieldsByKey,
      Map<String, Object> values) {
    html.append("<div class=\"party-card\">\n");
    for (String entry : section.entries()) {
      Field field = fieldsByKey.get(entry);
      if (field == null) {
        continue; // a party card renders field entries only
      }
      html.append("<div class=\"party-row\"><span class=\"label\">")
          .append(escape(field.label()))
          .append("</span><span class=\"value\">")
          .append(escape(valueOrPlaceholder(field, values.get(field.key()))))
          .append("</span></div>\n");
    }
    html.append("</div>\n");
  }

  /**
   * {@code CLAUSES} body: a numbered ordered list of the section's included clauses (the "Now This
   * Agreement Witnesseth" list). Each clause is still gated by {@code showWhen} through the
   * sandboxed DSL; a dropped clause closes up the numbering. Non-clause entries are ignored.
   */
  private static void appendClauseList(
      StringBuilder html,
      Section section,
      Map<String, Field> fieldsByKey,
      Map<String, Clause.Inline> clausesById,
      Map<String, Object> values) {
    html.append("<ol class=\"clauses\">\n");
    for (String entry : section.entries()) {
      Clause.Inline clause = clausesById.get(entry);
      if (clause == null || !included(clause, values)) {
        continue;
      }
      html.append("<li>").append(renderClauseText(clause, fieldsByKey, values)).append("</li>\n");
    }
    html.append("</ol>\n");
  }

  /**
   * {@code ANNEXURE} body: a bulleted list of the section's entries. A field entry renders its
   * escaped label and value; a clause entry (still {@code showWhen}-gated) renders its escaped
   * text. Every label, value, and clause text is HTML-escaped.
   */
  private static void appendAnnexure(
      StringBuilder html,
      Section section,
      Map<String, Field> fieldsByKey,
      Map<String, Clause.Inline> clausesById,
      Map<String, Object> values) {
    html.append("<ul class=\"annexure\">\n");
    for (String entry : section.entries()) {
      Field field = fieldsByKey.get(entry);
      if (field != null) {
        html.append("<li>")
            .append(escape(field.label()))
            .append(": ")
            .append(escape(valueOrPlaceholder(field, values.get(field.key()))))
            .append("</li>\n");
        continue;
      }
      Clause.Inline clause = clausesById.get(entry);
      if (clause == null || !included(clause, values)) {
        continue;
      }
      html.append("<li>").append(renderClauseText(clause, fieldsByKey, values)).append("</li>\n");
    }
    html.append("</ul>\n");
  }

  private static void appendKvRow(StringBuilder html, Field field, Map<String, Object> values) {
    html.append("<tr><td class=\"label\">")
        .append(escape(field.label()))
        .append("</td><td>")
        .append(escape(valueOrPlaceholder(field, values.get(field.key()))))
        .append("</td></tr>\n");
  }

  /**
   * Evaluate a clause's {@code showWhen} through the sandboxed DSL only. A clause with no condition
   * is always included. Any parse or evaluation failure (a missing or uncoercible referenced value,
   * a type mismatch) drops the clause deterministically -- never propagating an exception that
   * could carry a data value.
   */
  private static boolean included(Clause.Inline clause, Map<String, Object> values) {
    String showWhen = clause.showWhen();
    if (showWhen == null || showWhen.isBlank()) {
      return true;
    }
    try {
      return ShowWhenEvaluator.evaluate(ShowWhenParser.parse(showWhen), values);
    } catch (RuntimeException e) {
      // Deterministic drop; never leak the value or the failure detail into the document.
      return false;
    }
  }

  /**
   * Render a clause's plain text with each {@code {{slot}}} replaced by its escaped value -- the
   * same slot-fill discipline the header execution line uses ({@link #renderSlotText}).
   */
  private static String renderClauseText(
      Clause.Inline clause, Map<String, Field> fieldsByKey, Map<String, Object> values) {
    return renderSlotText(clause.text(), fieldsByKey, values);
  }

  /**
   * Fill each {@code {{slot}}} in a piece of system-authored text (a clause body or the header
   * execution line) with its value. Both the literal segments between slots and every substituted
   * value are HTML-escaped, so neither boilerplate nor a user value can inject structure, and a
   * value that itself looks like {@code {{other}}} is never re-substituted. A missing slot renders
   * an escaped {@code [ label ]} placeholder.
   */
  private static String renderSlotText(
      String text, Map<String, Field> fieldsByKey, Map<String, Object> values) {
    StringBuilder out = new StringBuilder(text.length() + 32);
    Matcher matcher = SLOT.matcher(text);
    int last = 0;
    while (matcher.find()) {
      out.append(escape(text.substring(last, matcher.start())));
      String key = matcher.group(1);
      out.append(escape(valueOrPlaceholder(fieldsByKey.get(key), values.get(key))));
      last = matcher.end();
    }
    out.append(escape(text.substring(last)));
    return out.toString();
  }

  /**
   * The unescaped slot/cell text for a value: its string form, or a {@code [ label ]} placeholder
   * when the value is missing or blank. A {@code DATE} field's ISO value is formatted for display
   * as {@code dd-MMM-yyyy} ({@code 13-Jul-2026}), an {@code ENUM} field's token is humanised
   * ({@code bank_transfer -> Bank Transfer}), and a {@code BOOL} field renders as {@code Yes} /
   * {@code No}; every other field renders its raw string form. Never returns {@code null}. The
   * caller escapes the result.
   */
  private static String valueOrPlaceholder(Field field, Object value) {
    if (value == null || (value instanceof String s && s.isBlank())) {
      String label = field != null ? field.label() : null;
      return "[ " + (label != null && !label.isBlank() ? label : "value") + " ]";
    }
    return formatForDisplay(field, value);
  }

  /**
   * Presentation-only formatting of a resolved (present, non-blank) value. A {@code DATE} field's
   * ISO value is rendered as {@code dd-MMM-yyyy}; an {@code ENUM} field's stored token is rendered
   * through the shared {@link OptionLabels} humaniser (the same label the form list box shows), so
   * the document body and the form read identically. The underlying data map keeps the raw value,
   * so {@code showWhen} still evaluates on it -- only the rendered output changes. Defensive: a
   * date that does not parse as ISO renders raw rather than throwing (a render never throws or
   * leaks). Every other field is returned unchanged.
   */
  private static String formatForDisplay(Field field, Object value) {
    String raw = String.valueOf(value);
    if (field == null) {
      return raw;
    }
    return switch (field.type()) {
      case DATE -> formatIsoDate(raw);
      case ENUM -> OptionLabels.humanize(raw);
      // "Pets allowed: false" is a machine value on the face of a legal instrument. The stored
      // value stays boolean - showWhen still evaluates on it - only the rendering changes.
      case BOOL -> Boolean.parseBoolean(raw) ? "Yes" : "No";
      default -> raw;
    };
  }

  /** An ISO date rendered as {@code dd-MMM-yyyy}, or the raw value when it does not parse. */
  private static String formatIsoDate(String raw) {
    try {
      return LocalDate.parse(raw).format(DISPLAY_DATE);
    } catch (RuntimeException e) {
      return raw; // not an ISO date (should not occur post-validation) -- render as-is
    }
  }

  private static Map<String, Field> index(List<Field> fields) {
    Map<String, Field> byKey = new LinkedHashMap<>();
    for (Field field : fields) {
      byKey.put(field.key(), field);
    }
    return byKey;
  }

  private static Map<String, Clause.Inline> inlineClauses(List<Clause> clauses) {
    Map<String, Clause.Inline> byId = new LinkedHashMap<>();
    for (Clause clause : clauses) {
      if (clause instanceof Clause.Inline inline) {
        byId.put(inline.id(), inline);
      }
    }
    return byId;
  }

  private static String escape(String value) {
    return HtmlUtils.htmlEscape(value, "UTF-8");
  }

  // Self-contained stylesheet: a serif Latin body that keeps the Noto Indic families in the stack
  // for Devanagari/Telugu shaping (design D5, the Chromium/Noto gotcha); real page margins via CSS
  // @page (the Gotenberg PDF) plus body padding (the browser live pane) so both tiers frame the
  // content identically. Fonts referenced by family name only -- no @font-face url() and no
  // external
  // link beyond the data-URI faces DocumentFonts injects ahead of this block, so the render loads
  // no
  // remote resource (the offline guarantee).
  private static final String STYLE =
      """
      * { box-sizing: border-box; }
      @page { margin: 24mm 20mm; }
      body {
        font-family: "Noto Serif", Georgia, "Times New Roman", "Noto Sans Devanagari", "Noto Serif Telugu", serif;
        color: #111; font-size: 12px; line-height: 1.55; margin: 0; padding: 24mm 20mm;
      }
      .doc-header { text-align: center; margin: 0 0 22px; }
      .doc-title { font-size: 18px; letter-spacing: 0.5px; text-transform: uppercase; margin: 0 0 4px; }
      .doc-subtitle { font-size: 12px; color: #555; margin: 0 0 8px; }
      .doc-exec { font-size: 12px; margin: 6px 0 0; }
      h2 {
        font-size: 13px; text-transform: uppercase; letter-spacing: 0.4px;
        border-bottom: 1px solid #999; padding-bottom: 2px; margin: 18px 0 8px;
      }
      table.kv { width: 100%; border-collapse: collapse; }
      table.kv td { vertical-align: top; padding: 2px 6px 2px 0; }
      table.kv td.label { width: 34%; color: #555; }
      .party-card { margin: 4px 0 0; }
      .party-card .party-row { display: flex; padding: 2px 0; }
      .party-card .label { width: 34%; color: #555; }
      .party-card .value { flex: 1; }
      ol.clauses { margin: 4px 0 0; padding-left: 20px; }
      ol.clauses > li { margin: 7px 0; text-align: justify; }
      ul.annexure { margin: 4px 0 0; padding-left: 20px; }
      ul.annexure > li { margin: 5px 0; }
      .signatures { margin-top: 26px; }
      .sign-line { margin-top: 34px; border-top: 1px solid #333; padding-top: 3px; font-size: 11px; width: 48%; }
      .sign-grid { display: flex; flex-wrap: wrap; gap: 28px; margin-top: 22px; }
      .sign-zone { flex: 1 1 40%; min-width: 200px; }
      .sign-area { height: 46px; border-bottom: 1px solid #333; display: flex; align-items: flex-end; }
      .sign-name { margin-top: 5px; font-weight: 600; }
      .sign-meta { font-size: 11px; color: #555; margin-top: 2px; }
      /* Painted in the page colour: a reader must never see a machine token on a legal
         instrument, but the glyphs MUST stay in the PDF text layer - that layer is how the
         signing module locates where to place a signature. display:none / visibility:hidden
         would drop the glyphs and every signing request would then be refused. */
      .sign-anchor { font-size: 8px; color: #ffffff; letter-spacing: 0.4px; padding-bottom: 2px; }
      .doc-provenance { display: none; margin-top: 18px; padding-top: 8px; border-top: 1px solid #ccc; font-size: 10px; color: #666; text-align: center; }
      @media screen { .doc-provenance { display: block; } }
      """;

  // System-owned closing block: a witness paragraph and blank signature lines. Domain-neutral (the
  // compiler holds no party model); the eSign audit trail evidences identity and consent.
  private static final String SIGNATURE_BLOCK =
      """
      <section class="signatures">
      <h2>In Witness Whereof</h2>
      <p>The parties have set their hands to this Agreement on the date first above written, \
      having read and understood its contents. Executed electronically via Aadhaar eSign; the \
      eSign audit trail evidences the identity and consent of each party.</p>
      <div class="sign-line">Signature</div>
      <div class="sign-line">Signature</div>
      </section>
      """;
}
