package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link TemplateCompiler}: the pure markup/data boundary. No Spring context, no I/O
 * -- fixtures are built directly from the (package-private) definition records. Covers escaping (a
 * user value can never become structure or active content), the sandboxed {@code showWhen}
 * include/drop with numbering closing up, partial-data placeholders (never a bare {@code null}),
 * and the never-log invariant.
 */
class TemplateCompilerTest {

  // --- 3.1 escaping ---------------------------------------------------------

  @Test
  void escapesSlotValuesSoMarkupRendersAsLiteralText() {
    String hostile = "<script>alert('xss')</script>";
    String html =
        new TemplateCompiler().compile(referenceTemplate(), dataWith("ownerName", hostile));

    // The value survives only as escaped literal text; it never re-enters the document as
    // structure.
    assertThat(html).contains("&lt;script&gt;");
    assertThat(html).doesNotContain("<script>alert");
  }

  @Test
  void missingSlotRendersEscapedPlaceholderNotNull() {
    // ownerName absent: its clause slot and its field cell both fall back to a labelled
    // placeholder.
    String html = new TemplateCompiler().compile(referenceTemplate(), Map.of());

    assertThat(html).contains("[ Owner name ]");
    assertThat(html).doesNotContain("null");
  }

  // --- 3.2 showWhen ---------------------------------------------------------

  @Test
  void trueShowWhenIncludesClauseFalseOneDropsAndNumberingClosesUp() {
    // furnished == true -> the furnished clause is in, the unfurnished clause is out.
    String html =
        new TemplateCompiler().compile(referenceTemplate(), dataWith("furnished", Boolean.TRUE));

    assertThat(html).contains("The property is let furnished.");
    assertThat(html).doesNotContain("The property is let unfurnished.");
    // Numbering closes up: the ordered clause list holds only the surviving clauses (rent + one of
    // the furnished/unfurnished pair), never an empty <li> for the dropped one.
    assertThat(countOccurrences(html, "<li>")).isEqualTo(2);
  }

  @Test
  void falseShowWhenDropsClause() {
    String html =
        new TemplateCompiler().compile(referenceTemplate(), dataWith("furnished", Boolean.FALSE));

    assertThat(html).contains("The property is let unfurnished.");
    assertThat(html).doesNotContain("The property is let furnished.");
  }

  @Test
  void conditionsRunOnlyThroughSandboxedDslNeverAsCode() {
    // A showWhen written as an expression-engine construct (property navigation / method-style) is
    // not part of the closed DSL grammar: the sandboxed parser rejects it, the clause drops
    // deterministically, and nothing is ever handed to Thymeleaf/SpringEL to evaluate as code.
    Clause.Inline exprEngineClause =
        new Clause.Inline(
            "cExpr", "This clause must never appear.", "notes.length() > 0", List.of());
    List<Clause> clauses = List.of(exprEngineClause);
    List<Section> sections =
        List.of(new Section("Terms", List.of("cExpr"), false, RenderKind.KEYVALUE));
    EffectiveTemplate template =
        effectiveOf(
            List.of(new Field("notes", "Notes", FieldType.TEXT, false, null, null, null, null)),
            clauses,
            sections);

    String html = new TemplateCompiler().compile(template, Map.of("notes", "some notes"));

    assertThat(html).doesNotContain("This clause must never appear.");
  }

  @Test
  void showWhenReferencingAbsentValueDropsClauseDeterministicallyWithoutLeaking() {
    // furnished absent -> the DSL evaluation cannot resolve it; both conditional clauses drop
    // rather
    // than raising an exception that could carry a value. Only the unconditional rent clause
    // remains.
    String html = new TemplateCompiler().compile(referenceTemplate(), Map.of("ownerName", "Asha"));

    assertThat(html).doesNotContain("furnished");
    assertThat(countOccurrences(html, "<li>")).isEqualTo(1);
  }

  // --- 3.3 partial data -----------------------------------------------------

  @Test
  void emptyDataCompilesToCoherentDocumentOfPlaceholders() {
    String html = new TemplateCompiler().compile(referenceTemplate(), Map.of());

    assertThat(html).startsWith("<!DOCTYPE html>");
    assertThat(html).contains("<h2>Parties</h2>");
    assertThat(html).contains("[ Owner name ]");
    assertThat(html).doesNotContain("null");
  }

  @Test
  void partiallyFilledDataMixesValuesAndPlaceholders() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ownerName", "Asha Rao");
    data.put("monthlyRent", new BigDecimal("15000"));
    // furnished + notes absent -> placeholders / dropped conditional clauses.
    String html = new TemplateCompiler().compile(referenceTemplate(), data);

    assertThat(html).contains("Asha Rao");
    assertThat(html).contains("15000");
    assertThat(html).contains("[ Notes ]");
    assertThat(html).doesNotContain("null");
  }

  @Test
  void outputIsSelfContainedWithNoExternalUrls() {
    String html = new TemplateCompiler().compile(referenceTemplate(), Map.of());

    // Fonts by family name only; no remote resource of any scheme is referenced.
    assertThat(html).contains("font-family:");
    assertThat(html).doesNotContain("http://").doesNotContain("https://").doesNotContain("url(");
  }

  // --- 3.6 never-log --------------------------------------------------------

  @Test
  void compilingLeavesNoHtmlOrSubmittedValueInLogs() {
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      String piiValue = "SENSITIVE_PII_VALUE_9f3a";
      String html =
          new TemplateCompiler().compile(referenceTemplate(), dataWith("ownerName", piiValue));

      assertThat(html).contains(piiValue); // the value IS in the rendered document...
      // ...but nothing about the render reaches any log at any level.
      assertThat(appender.list)
          .noneMatch(event -> event.getFormattedMessage().contains(piiValue))
          .noneMatch(event -> event.getFormattedMessage().contains(html));
    } finally {
      root.detachAppender(appender);
    }
  }

  // --- 1.2 header from meta.document ---------------------------------------

  @Test
  void headerRendersEscapedTitleSubtitleAndExecutionLineWithSlotsFilled() {
    DocumentMeta document =
        new DocumentMeta(
            "Residential Rental Agreement",
            "Leave and Licence",
            "Executed on this {{agreementDate}} at {{place}}.");
    List<Field> fields =
        List.of(
            new Field(
                "agreementDate", "Agreement date", FieldType.DATE, false, null, null, null, null),
            new Field("place", "Place", FieldType.TEXT, false, null, null, null, null));
    EffectiveTemplate template = effectiveOf(document, fields, List.of(), List.of());

    String html =
        new TemplateCompiler().compile(template, dataWith("place", "Hyderabad"), "13 July 2026");

    assertThat(html).contains("<h1 class=\"doc-title\">Residential Rental Agreement</h1>");
    assertThat(html).contains("<div class=\"doc-subtitle\">Leave and Licence</div>");
    // The resolved date fills {{agreementDate}}; {{place}} fills from data; both escaped.
    assertThat(html).contains("Executed on this 13 July 2026 at Hyderabad.");
  }

  @Test
  void headerTextContainingMarkupRendersAsLiteralText() {
    // The header title/subtitle are system-authored template text, escaped exactly as clause text.
    DocumentMeta document =
        new DocumentMeta("<b>Injected</b>", "<i>Sub</i>", "On {{agreementDate}}");
    EffectiveTemplate template = effectiveOf(document, List.of(), List.of(), List.of());

    String html = new TemplateCompiler().compile(template, Map.of(), "1 Jan 2026");

    assertThat(html).contains("&lt;b&gt;Injected&lt;/b&gt;").doesNotContain("<b>Injected</b>");
    assertThat(html).contains("&lt;i&gt;Sub&lt;/i&gt;");
  }

  @Test
  void headerExecutionLineMissingSlotRendersEscapedPlaceholder() {
    DocumentMeta document = new DocumentMeta("T", null, "At {{place}} on {{agreementDate}}");
    List<Field> fields =
        List.of(new Field("place", "Place", FieldType.TEXT, false, null, null, null, null));
    EffectiveTemplate template = effectiveOf(document, fields, List.of(), List.of());

    String html = new TemplateCompiler().compile(template, Map.of(), "2 Feb 2026");

    assertThat(html).contains("At [ Place ] on 2 Feb 2026");
    assertThat(html).doesNotContain("null");
  }

  @Test
  void noDocumentMetaEmitsNoHeader() {
    // referenceTemplate() declares no meta.document -> no header block at all.
    String html = new TemplateCompiler().compile(referenceTemplate(), Map.of(), "1 Jan 2026");

    // The stylesheet always carries the .doc-header rule; what must be absent is the header block.
    assertThat(html).doesNotContain("<div class=\"doc-header\">");
  }

  // --- 3.2/3.3 reserved date-binding key (compiler stays pure) --------------

  @Test
  void resolvedExecutionDateOverridesSubmittedAgreementDateAndDoesNotMutateData() {
    DocumentMeta document = new DocumentMeta("T", null, "On {{agreementDate}}");
    List<Field> fields =
        List.of(
            new Field(
                "agreementDate", "Agreement date", FieldType.DATE, false, null, null, null, null));
    EffectiveTemplate template = effectiveOf(document, fields, List.of(), List.of());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("agreementDate", "2020-01-01"); // a submitted value...
    // ...the resolved date wins (bound under the reserved key on a copy of the map).
    String html = new TemplateCompiler().compile(template, data, "13 July 2026");

    assertThat(html).contains("On 13 July 2026");
    assertThat(html).doesNotContain("2020-01-01");
    // The compiler binds on a COPY: the caller's map is never mutated.
    assertThat(data).containsEntry("agreementDate", "2020-01-01");
  }

  // --- 2.2 section body dispatched by render kind --------------------------

  @Test
  void partiesKindRendersAnEscapedPartyCard() {
    List<Field> fields =
        List.of(new Field("ownerName", "Owner name", FieldType.TEXT, true, null, null, null, null));
    List<Section> sections =
        List.of(new Section("Owner", List.of("ownerName"), false, RenderKind.PARTIES));
    EffectiveTemplate template = effectiveOf(null, fields, List.of(), sections);

    String html = new TemplateCompiler().compile(template, dataWith("ownerName", "<b>Asha</b>"));

    assertThat(html).contains("<div class=\"party-card\">");
    assertThat(html).contains("<span class=\"label\">Owner name</span>");
    // Injection-as-data stays inert in a party card.
    assertThat(html).contains("&lt;b&gt;Asha&lt;/b&gt;").doesNotContain("<b>Asha</b>");
  }

  @Test
  void keyvalueKindRendersAnEscapedLabelValueTable() {
    List<Field> fields =
        List.of(
            new Field("rentDueDay", "Rent due day", FieldType.INT, false, null, null, null, null));
    List<Section> sections =
        List.of(new Section("Terms", List.of("rentDueDay"), false, RenderKind.KEYVALUE));
    EffectiveTemplate template = effectiveOf(null, fields, List.of(), sections);

    String html = new TemplateCompiler().compile(template, dataWith("rentDueDay", "<x>"));

    assertThat(html).contains("<table class=\"kv\">");
    assertThat(html).contains("<td class=\"label\">Rent due day</td>");
    assertThat(html).contains("&lt;x&gt;").doesNotContain("<x>");
  }

  @Test
  void clausesKindRendersANumberedListWithShowWhenClosingUp() {
    List<Clause> clauses =
        List.of(
            new Clause.Inline("cA", "Clause A.", null, List.of()),
            new Clause.Inline("cB", "Clause B.", "furnished == true", List.of()),
            new Clause.Inline("cC", "Clause C.", null, List.of()));
    List<Field> fields =
        List.of(new Field("furnished", "Furnished", FieldType.BOOL, false, null, null, null, null));
    List<Section> sections =
        List.of(new Section("Witnesseth", List.of("cA", "cB", "cC"), false, RenderKind.CLAUSES));
    EffectiveTemplate template = effectiveOf(null, fields, clauses, sections);

    String html = new TemplateCompiler().compile(template, dataWith("furnished", Boolean.FALSE));

    assertThat(html).contains("<ol class=\"clauses\">");
    assertThat(html).contains("<li>Clause A.</li>").contains("<li>Clause C.</li>");
    assertThat(html).doesNotContain("Clause B."); // false showWhen dropped...
    assertThat(countOccurrences(html, "<li>")).isEqualTo(2); // ...and numbering closes up
  }

  @Test
  void annexureKindRendersAnEscapedBulletedList() {
    List<Field> fields =
        List.of(new Field("noteA", "Note A", FieldType.TEXT, false, null, null, null, null));
    List<Clause> clauses = List.of(new Clause.Inline("cN", "Annex clause.", null, List.of()));
    List<Section> sections =
        List.of(new Section("Annexure", List.of("noteA", "cN"), false, RenderKind.ANNEXURE));
    EffectiveTemplate template = effectiveOf(null, fields, clauses, sections);

    String html = new TemplateCompiler().compile(template, dataWith("noteA", "<i>v</i>"));

    assertThat(html).contains("<ul class=\"annexure\">");
    assertThat(html).contains("<li>Note A: &lt;i&gt;v&lt;/i&gt;</li>").doesNotContain("<i>v</i>");
    assertThat(html).contains("<li>Annex clause.</li>");
  }

  @Test
  void nullRenderKindFallsBackToKeyValueAndNeverThrows() {
    // A hand-built section with a null render kind (resolution defaults it to KEYVALUE) must not
    // throw; the compiler defensively renders the key/value table.
    List<Field> fields =
        List.of(new Field("a", "A", FieldType.TEXT, false, null, null, null, null));
    List<Section> sections = List.of(new Section("S", List.of("a"), false, null));
    EffectiveTemplate template = effectiveOf(null, fields, List.of(), sections);

    String html = new TemplateCompiler().compile(template, dataWith("a", "v"));

    assertThat(html).contains("<table class=\"kv\">");
  }

  // --- signatures render: per-signer zones + eSign anchors -----------------

  @Test
  void signaturesKindRendersOneZoneAndAnchorPerSigner() {
    EffectiveTemplate template = signaturesTemplate();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ownerName", "Asha Rao");
    data.put("tenantName", "Ravi Kumar");
    String html = new TemplateCompiler().compile(template, data);

    // The execution block opens with the system-owned wording...
    assertThat(html).contains("IN WITNESS WHEREOF");
    // ...one signature zone per name-field entry...
    assertThat(countOccurrences(html, "class=\"sign-zone\"")).isEqualTo(2);
    // ...each carrying the signer's name and a stable, role-derived, non-PII anchor.
    assertThat(html).contains("Asha Rao").contains("Ravi Kumar");
    assertThat(html).contains("esign:owner").contains("esign:tenant");
  }

  @Test
  void signaturesKindEscapesSignerNamesSoMarkupStaysInert() {
    EffectiveTemplate template = signaturesTemplate();

    String html =
        new TemplateCompiler()
            .compile(template, dataWith("ownerName", "<script>alert(1)</script>"));

    assertThat(html).contains("&lt;script&gt;").doesNotContain("<script>alert");
  }

  @Test
  void signaturesAnchorRoleDerivesFromTheNameFieldKey() {
    // A signatory key other than owner/tenant still derives esign:<role> by stripping "Name".
    List<Field> fields =
        List.of(
            new Field(
                "guarantorName", "Guarantor name", FieldType.TEXT, false, null, null, null, null));
    List<Section> sections =
        List.of(
            new Section(
                "In Witness Whereof", List.of("guarantorName"), false, RenderKind.SIGNATURES));
    EffectiveTemplate template = effectiveOf(null, fields, List.of(), sections);

    String html = new TemplateCompiler().compile(template, dataWith("guarantorName", "Meera"));

    assertThat(html).contains("esign:guarantor");
  }

  // --- M2 section gating: render iff mandatory OR active -------------------

  @Test
  void mandatorySectionRendersRegardlessOfTheActiveSet() {
    EffectiveTemplate template = optionalSetTemplate();

    // Empty active set...
    String empty = new TemplateCompiler().compile(template, Map.of(), null, Set.of());
    // ...and an active set naming only other sections.
    String others =
        new TemplateCompiler().compile(template, Map.of(), null, Set.of("Something Else"));

    assertThat(empty).contains("<h2>Terms</h2>");
    assertThat(others).contains("<h2>Terms</h2>");
  }

  @Test
  void unaddedOptionalSectionContributesNothing() {
    // "Pets" is optional and absent from the active set: none of its content appears.
    String html = new TemplateCompiler().compile(optionalSetTemplate(), petData(), null, Set.of());

    assertThat(html).doesNotContain("<h2>Pets</h2>"); // no header
    assertThat(html).doesNotContain("Permitted pet"); // no field row
    assertThat(html).doesNotContain("may keep one"); // no clause
  }

  @Test
  void addedOptionalSectionRenders() {
    // The same "Pets" section, once its title is in the active set, renders in full.
    String html =
        new TemplateCompiler().compile(optionalSetTemplate(), petData(), null, Set.of("Pets"));

    assertThat(html).contains("<h2>Pets</h2>");
    assertThat(html).contains("Permitted pet"); // its field row
    assertThat(html).contains("The Tenant may keep one Cat on the premises."); // its clause, filled
  }

  @Test
  void unknownActiveSetTitleIsIgnoredNeverAnError() {
    // A title matching no declared section: compilation succeeds, adds nothing for it, and the
    // output is byte-for-byte the empty-active-set output (no existence oracle).
    String baseline =
        new TemplateCompiler().compile(optionalSetTemplate(), petData(), null, Set.of());
    String withUnknown =
        new TemplateCompiler()
            .compile(optionalSetTemplate(), petData(), null, Set.of("No Such Section"));

    assertThat(withUnknown).isEqualTo(baseline);
    assertThat(withUnknown).doesNotContain("<h2>Pets</h2>");
  }

  @Test
  void titleMatchingIsExactAndCaseSensitive() {
    // A differently-cased title does not activate the optional section.
    String html =
        new TemplateCompiler().compile(optionalSetTemplate(), petData(), null, Set.of("pets"));

    assertThat(html).doesNotContain("<h2>Pets</h2>");
  }

  // --- 4.2 serif body + kept Noto faces + real page margins ----------------

  @Test
  void stylesheetCarriesSerifBodyWithNotoIndicRetainedAndPageMargins() {
    String html = new TemplateCompiler().compile(referenceTemplate(), Map.of());

    // Serif Latin stack ending in the generic serif family (no longer sans-serif)...
    assertThat(html).contains("serif;").doesNotContain("sans-serif");
    // ...with the Noto Indic family retained in the stack for Devanagari shaping.
    assertThat(html).contains("Noto Sans Devanagari");
    // Real page margins: @page for the Gotenberg PDF, body padding for the browser live pane.
    assertThat(html).contains("@page { margin:");
    assertThat(html).contains("padding: 24mm 20mm;");
  }

  // --- date display formatting (dd-MMM-yyyy) -------------------------------

  @Test
  void dateFieldValueRendersAsDdMmmYyyyInBothCellAndSlot() {
    List<Field> fields =
        List.of(
            new Field("startDate", "Start date", FieldType.DATE, false, null, null, null, null));
    List<Clause> clauses =
        List.of(
            new Clause.Inline("cTerm", "Commencing on {{startDate}}.", null, List.of("startDate")));
    List<Section> sections =
        List.of(
            new Section("Term", List.of("startDate"), false, RenderKind.KEYVALUE),
            new Section("Body", List.of("cTerm"), false, RenderKind.CLAUSES));
    EffectiveTemplate template = effectiveOf(fields, clauses, sections);

    String html = new TemplateCompiler().compile(template, dataWith("startDate", "2026-08-05"));

    // Zero-padded day + title-case month, in the kv cell and the clause slot; raw ISO is gone.
    assertThat(html).contains("05-Aug-2026");
    assertThat(html).doesNotContain("2026-08-05");
  }

  @Test
  void dateShowWhenEvaluatesOnTheIsoValueNotTheDisplayForm() {
    // Formatting is output-only: the data map keeps ISO, so a date showWhen still gates correctly.
    List<Field> fields =
        List.of(
            new Field("startDate", "Start date", FieldType.DATE, false, null, null, null, null));
    List<Clause> clauses =
        List.of(
            new Clause.Inline(
                "cIso", "Matched the ISO date.", "startDate == \"2026-08-05\"", List.of()));
    List<Section> sections =
        List.of(new Section("Body", List.of("cIso"), false, RenderKind.CLAUSES));
    EffectiveTemplate template = effectiveOf(fields, clauses, sections);

    String html = new TemplateCompiler().compile(template, dataWith("startDate", "2026-08-05"));

    // The clause is included -> the condition compared the ISO value (the map was not reformatted).
    assertThat(html).contains("Matched the ISO date.");
  }

  @Test
  void unparseableDateValueRendersRawAndNeverThrows() {
    List<Field> fields =
        List.of(
            new Field("startDate", "Start date", FieldType.DATE, false, null, null, null, null));
    List<Section> sections =
        List.of(new Section("Term", List.of("startDate"), false, RenderKind.KEYVALUE));
    EffectiveTemplate template = effectiveOf(fields, List.of(), sections);

    // A value that is not an ISO date renders unchanged (should not occur post-validation).
    String html = new TemplateCompiler().compile(template, dataWith("startDate", "not-a-date"));

    assertThat(html).contains("not-a-date");
  }

  // --- enum value humanisation in the document body ------------------------

  @Test
  void enumFieldValueRendersHumanisedInBothCellAndSlot() {
    List<Field> fields =
        List.of(
            new Field(
                "paymentMode",
                "Payment mode",
                FieldType.ENUM,
                false,
                null,
                List.of("bank_transfer", "upi", "cash"),
                null,
                null));
    List<Clause> clauses =
        List.of(
            new Clause.Inline("cPay", "Payable by {{paymentMode}}.", null, List.of("paymentMode")));
    List<Section> sections =
        List.of(
            new Section("Financial", List.of("paymentMode"), false, RenderKind.KEYVALUE),
            new Section("Body", List.of("cPay"), false, RenderKind.CLAUSES));
    EffectiveTemplate template = effectiveOf(fields, clauses, sections);

    String html =
        new TemplateCompiler().compile(template, dataWith("paymentMode", "bank_transfer"));

    // Humanised in the kv cell and the clause slot; the raw token never appears.
    assertThat(html).contains("Bank Transfer");
    assertThat(html).doesNotContain("bank_transfer");
  }

  @Test
  void enumShowWhenEvaluatesOnTheRawTokenNotTheHumanisedLabel() {
    // Humanisation is output-only: the data map keeps the raw token, so an enum showWhen still
    // gates.
    List<Field> fields =
        List.of(
            new Field(
                "parkingType",
                "Parking",
                FieldType.ENUM,
                false,
                null,
                List.of("none", "two_wheeler"),
                null,
                null));
    List<Clause> clauses =
        List.of(
            new Clause.Inline(
                "cPark", "A parking space is allotted.", "parkingType != \"none\"", List.of()));
    List<Section> sections =
        List.of(new Section("Body", List.of("cPark"), false, RenderKind.CLAUSES));
    EffectiveTemplate template = effectiveOf(fields, clauses, sections);

    String html = new TemplateCompiler().compile(template, dataWith("parkingType", "two_wheeler"));

    assertThat(html).contains("A parking space is allotted."); // gated on the raw token
  }

  // --- fixtures -------------------------------------------------------------

  /**
   * A small reference template: a Parties section of two fields (a kv table) and a Terms section of
   * three clauses (an ordered list) -- one unconditional rent clause and a furnished/unfurnished
   * conditional pair keyed on the {@code furnished} field.
   */
  private static EffectiveTemplate referenceTemplate() {
    List<Field> fields =
        List.of(
            new Field("ownerName", "Owner name", FieldType.TEXT, true, null, null, null, null),
            new Field("notes", "Notes", FieldType.TEXT, false, null, null, null, null),
            new Field("monthlyRent", "Monthly rent", FieldType.MONEY, true, null, null, null, null),
            new Field("furnished", "Furnished", FieldType.BOOL, false, null, null, null, null));
    List<Clause> clauses =
        List.of(
            new Clause.Inline(
                "cRent",
                "The rent is INR {{monthlyRent}}, payable by {{ownerName}}.",
                null,
                List.of("monthlyRent", "ownerName")),
            new Clause.Inline(
                "cFurnished", "The property is let furnished.", "furnished == true", List.of()),
            new Clause.Inline(
                "cUnfurnished",
                "The property is let unfurnished.",
                "furnished == false",
                List.of()));
    List<Section> sections =
        List.of(
            new Section("Parties", List.of("ownerName", "notes"), false, RenderKind.KEYVALUE),
            new Section(
                "Terms",
                List.of("cRent", "cFurnished", "cUnfurnished"),
                false,
                RenderKind.KEYVALUE));
    return effectiveOf(fields, clauses, sections);
  }

  /**
   * A template with one <b>mandatory</b> section ("Terms", a kv table) and one <b>optional</b>
   * section ("Pets", a field + a clause) -- the fixture for the M2 section-gating tests.
   */
  private static EffectiveTemplate optionalSetTemplate() {
    List<Field> fields =
        List.of(
            new Field("rentDueDay", "Rent due day", FieldType.INT, false, null, null, null, null),
            new Field("petType", "Permitted pet", FieldType.TEXT, false, null, null, null, null));
    List<Clause> clauses =
        List.of(
            new Clause.Inline(
                "petsClause",
                "The Tenant may keep one {{petType}} on the premises.",
                null,
                List.of("petType")));
    List<Section> sections =
        List.of(
            new Section("Terms", List.of("rentDueDay"), false, RenderKind.KEYVALUE),
            new Section("Pets", List.of("petType", "petsClause"), true, RenderKind.KEYVALUE));
    return effectiveOf(fields, clauses, sections);
  }

  /**
   * A template whose single mandatory section is a {@code SIGNATURES} block declaring two
   * signatories by their name field keys ({@code ownerName}, {@code tenantName}) -- the fixture for
   * the execution-block / eSign-anchor tests.
   */
  private static EffectiveTemplate signaturesTemplate() {
    List<Field> fields =
        List.of(
            new Field("ownerName", "Owner name", FieldType.TEXT, true, null, null, null, null),
            new Field("tenantName", "Tenant name", FieldType.TEXT, true, null, null, null, null));
    List<Section> sections =
        List.of(
            new Section(
                "In Witness Whereof",
                List.of("ownerName", "tenantName"),
                false,
                RenderKind.SIGNATURES));
    return effectiveOf(null, fields, List.of(), sections);
  }

  private static Map<String, Object> petData() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rentDueDay", 5);
    data.put("petType", "Cat");
    return data;
  }

  private static Map<String, Object> dataWith(String key, Object value) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put(key, value);
    return data;
  }

  private static EffectiveTemplate effectiveOf(
      List<Field> fields, List<Clause> clauses, List<Section> sections) {
    return effectiveOf(null, fields, clauses, sections);
  }

  private static EffectiveTemplate effectiveOf(
      DocumentMeta document, List<Field> fields, List<Clause> clauses, List<Section> sections) {
    Dimensions dimensions = new Dimensions("TG", "residential");
    Meta meta = new Meta("rental-base", dimensions, 1, TemplateStatus.PUBLISHED, document);
    TemplateDefinition definition =
        new TemplateDefinition(meta, fields, clauses, sections, "hash-abc");
    return new EffectiveTemplate(definition, dimensions, Map.of("rental-base", 1), "hash-abc");
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int from = 0;
    for (int i; (i = haystack.indexOf(needle, from)) >= 0; from = i + needle.length()) {
      count++;
    }
    return count;
  }
}
