package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.documents.DocumentFooterProperties;
import in.agreementmitra.documents.api.DocumentDimensions;
import in.agreementmitra.documents.api.DocumentProjectionRequest;
import in.agreementmitra.documents.api.FormField;
import in.agreementmitra.documents.api.FormSchema;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Derived fields ({@code source: derived}), change {@code
 * derived-tenancy-term}: the declaration binds, the field stays in the
 * capture form but read-only, a submitted value is discarded, and the term the document states is
 * always computed from the dates -- on the preview path as well as the generate path.
 *
 * <p>The parity case is the reason this change exists: the live preview used to compile the TYPED
 * {@code durationMonths} while the generated draft compiled the date-derived one, so an agreement
 * could preview as an 11-month term and sign as a 24-month one. {@link
 * #previewAndGenerateStateTheSameTerm} is the regression test for exactly that.
 *
 * <p>Pure: no Spring, no Gotenberg (the renderer is a capturing stub).
 */
class DerivedFieldTest {

  private static final String RENTAL = "documents/template/sets/rental/";
  private static final String COMMERCIAL = "documents/template/sets/commercial/";

  private static final String DEFINITION =
      """
      meta:
        id: t
        dimensions: { state: IN, type: residential }
        version: 1
        status: draft
      fields:
        - { key: startDate, label: Start, type: date, required: true }
        - { key: endDate, label: End, type: date, required: true }
        - { key: durationMonths, label: Term, type: int, required: false, source: derived }
      clauses:
        - { id: term, text: "A term of {{durationMonths}} month(s)." }
      sections:
        - { title: Term, entries: [ startDate, endDate, durationMonths, term ] }
      """;

  private final TemplateDefinitionLoader loader = new TemplateDefinitionLoader();

  /** Captures the HTML handed to the PDF renderer. */
  private String lastHtml;

  private final Clock clock =
      Clock.fixed(
          LocalDate.of(2026, 9, 18).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

  // --- 5.1 declaration ------------------------------------------------------------

  @Test
  void theSourceBindsAndOnlyTheDeclaredFieldIsDerived() {
    TemplateDefinition definition = loader.load(DEFINITION);

    assertThat(field(definition.fields(), "durationMonths").derived()).isTrue();
    assertThat(field(definition.fields(), "durationMonths").systemSourced()).isFalse();
    assertThat(field(definition.fields(), "startDate").derived()).isFalse();
    assertThat(field(definition.fields(), "startDate").source()).isNull();
  }

  @Test
  void anUnknownSourceTokenIsStillRejectedByTheSchema() {
    assertThatThrownBy(() -> loader.load(DEFINITION.replace("source: derived", "source: user")))
        .isInstanceOf(TemplateDefinitionException.class);
  }

  // --- 5.2 the validator distinguishes the two server-supplied provenances ---------

  @Test
  void aRequiredDerivedFieldIsAccepted() {
    // Unlike a system-sourced field, a derived one is always present in the output, so declaring it
    // required is not an authoring error -- the projector normalizes requiredness instead.
    TemplateDefinition definition =
        loader.load(
            DEFINITION.replace(
                "required: false, source: derived", "required: true, source: derived"));

    assertThat(field(definition.fields(), "durationMonths").derived()).isTrue();
  }

  @Test
  void aRequiredSystemSourcedFieldIsStillRejected() {
    String systemRequired =
        DEFINITION.replace("required: false, source: derived", "required: true, source: system");

    assertThatThrownBy(() -> loader.load(systemRequired))
        .isInstanceOf(TemplateDefinitionException.class)
        .hasMessageContaining("durationMonths");
  }

  // --- 5.3 the form projection ------------------------------------------------------

  @Test
  void aDerivedFieldIsProjectedReadOnlyAndNotRequired() {
    FormSchema schema = new FormProjector().project(effective(loader.load(DEFINITION)));

    FormField term = formField(schema, "durationMonths");
    assertThat(term.readOnly()).isTrue();
    assertThat(term.required()).isFalse();
  }

  @Test
  void aRequiredDerivedFieldIsStillProjectedNotRequired() {
    FormSchema schema =
        new FormProjector()
            .project(
                effective(
                    loader.load(
                        DEFINITION.replace(
                            "required: false, source: derived",
                            "required: true, source: derived"))));

    assertThat(formField(schema, "durationMonths").required()).isFalse();
  }

  @Test
  void anOrdinaryFieldCarriesNoReadOnlyMarkerAtAll() {
    FormSchema schema = new FormProjector().project(effective(loader.load(DEFINITION)));

    // null, not FALSE: NON_NULL then omits the member, so a schema without a derived field
    // serializes exactly as it did before the member existed.
    assertThat(formField(schema, "startDate").readOnly()).isNull();
  }

  // --- 5.4 the whole-month boundary table -------------------------------------------
  // KEEP IN SYNC with the frontend table in frontend/src/views/formModel.test.ts (tenancyMonths).

  @ParameterizedTest(name = "{0} to {1} is {2} months")
  @CsvSource({
    // The reported case: a two-year span against a form still showing 11.
    "2026-01-08, 2028-01-08, 24",
    // The end date is the tenancy's LAST DAY (inclusive): 1 Sep to 31 Jul is eleven months,
    // not ten -- the reported case for the end-exclusive count.
    "2026-09-01, 2027-07-31, 11",
    "2026-01-01, 2026-11-30, 11",
    "2026-01-01, 2026-12-31, 12",
    // Exactly eleven months -- the registrability line, and the common Indian tenancy.
    "2026-01-01, 2026-12-01, 11",
    "2026-01-01, 2027-01-01, 12",
    // Just past a twelve-month registration threshold (KA): thirteen, not twelve.
    "2026-01-01, 2027-01-31, 13",
    // A trailing partial month is truncated, never rounded up.
    "2026-01-01, 2026-12-20, 11",
    "2026-01-01, 2026-01-30, 0",
    "2026-01-01, 2026-01-31, 1",
    // Month-end clamping: 31 Jan to 28 Feb is one month (and must not overflow to March).
    "2026-01-31, 2026-02-27, 0",
    "2026-01-31, 2026-02-28, 1",
    "2026-01-31, 2026-03-31, 2",
    // Leap years: 29 Feb to 28 Feb the following year is a full year.
    "2028-02-29, 2029-02-28, 12",
    "2024-02-29, 2025-03-01, 12",
    // Same day is a zero-month term.
    "2026-06-01, 2026-06-01, 0",
  })
  void wholeMonthsBetweenDates(String start, String end, long expected) {
    assertThat(TermMonths.between(start, end)).isEqualTo(expected);
  }

  @Test
  void anAbsentOrUnparseableDateLeavesTheTermUndetermined() {
    assertThat(TermMonths.between(null, "2026-12-01")).isNull();
    assertThat(TermMonths.between("2026-01-01", null)).isNull();
    assertThat(TermMonths.between("", "2026-12-01")).isNull();
    assertThat(TermMonths.between("   ", "2026-12-01")).isNull();
    assertThat(TermMonths.between("not a date", "2026-12-01")).isNull();
    assertThat(TermMonths.between("01/01/2026", "2026-12-01")).isNull();
    assertThat(TermMonths.between(11, "2026-12-01")).isNull();
  }

  @Test
  void anOutOfRangeYearLeavesTheTermUndeterminedRatherThanThrowing() {
    // ISO_LOCAL_DATE accepts a signed, >4-digit year, so LocalDate.MAX parses -- and the
    // end-inclusive count then adds a day to it, which overflows. This runs BEFORE the
    // submitted-data validator, so throwing would turn a request the date validator answers with
    // a clean 400 into a 500.
    assertThat(TermMonths.between("2026-01-01", "+999999999-12-31")).isNull();
    assertThat(TermMonths.between("-999999999-01-01", "2026-01-01")).isNotNull();
  }

  // --- a non-positive term is never substituted --------------------------------------

  @Test
  void aNegativeTermLeavesTheKeyUnsetRatherThanRenderingIt() {
    // durationMonths lost its `validation: { min: 1 }` when it became derived, so nothing
    // downstream rejects a negative count. Leaving the key unset renders it as any other unfilled
    // field instead of compiling "a term of -4 month(s)" into the document body.
    EffectiveTemplate effective = effective(loader.load(DEFINITION));

    Map<String, Object> reversed = new LinkedHashMap<>();
    reversed.put("startDate", "2026-06-01");
    reversed.put("endDate", "2026-01-01");

    assertThat(DocumentProjectionService.withSystemValues(effective, reversed, Map.of()))
        .doesNotContainKey("durationMonths");
  }

  @Test
  void aZeroMonthTermIsStillSubstitutedRatherThanLeftToThePlaceholder() {
    // A lawful sub-month tenancy is ZERO whole months and is creatable (@EndAfterStart only
    // requires end > start). Leaving the key unset would make the compiler render the
    // `[ Duration (months) ]` placeholder into the GENERATED draft -- a form artifact inside the
    // instrument that is then stamped and eSigned. "0 month(s)" is imprecise but not that.
    // See `sub-month-tenancy-term-wording` in the follow-up register.
    EffectiveTemplate effective = effective(loader.load(DEFINITION));

    Map<String, Object> sameMonth = new LinkedHashMap<>();
    sameMonth.put("startDate", "2026-01-01");
    sameMonth.put("endDate", "2026-01-20");
    assertThat(DocumentProjectionService.withSystemValues(effective, sameMonth, Map.of()))
        .containsEntry("durationMonths", 0L);

    Map<String, Object> sameDay = new LinkedHashMap<>();
    sameDay.put("startDate", "2026-06-01");
    sameDay.put("endDate", "2026-06-01");
    assertThat(DocumentProjectionService.withSystemValues(effective, sameDay, Map.of()))
        .containsEntry("durationMonths", 0L);
  }

  @Test
  void aNegativeSubmittedTermIsStillDiscarded() {
    // The guard must not accidentally let a client value survive when the derivation declines to
    // substitute one: the removal is unconditional, the put is not.
    EffectiveTemplate effective = effective(loader.load(DEFINITION));
    Map<String, Object> submitted = new LinkedHashMap<>();
    submitted.put("startDate", "2026-06-01");
    submitted.put("endDate", "2026-01-01");
    submitted.put("durationMonths", 11);

    assertThat(DocumentProjectionService.withSystemValues(effective, submitted, Map.of()))
        .doesNotContainKey("durationMonths");
  }

  @Test
  void aOneMonthTermIsStillSubstituted() {
    // The boundary the guard must not over-reach: 1 is positive and lawful.
    EffectiveTemplate effective = effective(loader.load(DEFINITION));
    Map<String, Object> submitted = new LinkedHashMap<>();
    submitted.put("startDate", "2026-01-01");
    submitted.put("endDate", "2026-01-31");

    assertThat(DocumentProjectionService.withSystemValues(effective, submitted, Map.of()))
        .containsEntry("durationMonths", 1L);
  }

  // --- 5.5 the substitution ----------------------------------------------------------

  @Test
  void aSubmittedTermDisagreeingWithTheDatesIsDiscardedAndRecomputed() {
    EffectiveTemplate effective = effective(loader.load(DEFINITION));
    Map<String, Object> submitted = new LinkedHashMap<>();
    submitted.put("startDate", "2026-01-08");
    submitted.put("endDate", "2028-01-08");
    submitted.put("durationMonths", 11); // the form's old default, against a 24-month span

    Map<String, Object> data =
        DocumentProjectionService.withSystemValues(effective, submitted, Map.of());

    assertThat(data).containsEntry("durationMonths", 24L);
  }

  @Test
  void aSubmittedTermIsDiscardedEvenWhenItAgrees() {
    // Unconditional: no client value reaches the document, so there is no path on which one could.
    EffectiveTemplate effective = effective(loader.load(DEFINITION));
    Map<String, Object> submitted = new LinkedHashMap<>();
    submitted.put("startDate", "2026-01-01");
    submitted.put("endDate", "2026-12-01");
    submitted.put("durationMonths", "11 and a bit"); // discarded before validation: no type error

    Map<String, Object> data =
        DocumentProjectionService.withSystemValues(effective, submitted, Map.of());

    assertThat(data).containsEntry("durationMonths", 11L);
  }

  @Test
  void aMissingDateLeavesTheTermUnsetRatherThanDefaulted() {
    EffectiveTemplate effective = effective(loader.load(DEFINITION));
    Map<String, Object> submitted = new LinkedHashMap<>();
    submitted.put("startDate", "2026-01-01");
    submitted.put("durationMonths", 11);

    Map<String, Object> data =
        DocumentProjectionService.withSystemValues(effective, submitted, Map.of());

    assertThat(data).doesNotContainKey("durationMonths");
  }

  // --- 5.8 parity: the case this change exists to close --------------------------------

  @Test
  void theDeedCountsTheEndDateAsTheLastDayOfTheTerm() {
    Map<String, Object> data = aggregateBackedData();
    data.put("startDate", "2026-09-01");
    data.put("endDate", "2027-07-31");

    String preview = service(RENTAL).previewHtml(request(data));

    assertThat(preview).contains("a term of 11 month(s)").doesNotContain("a term of 10 month(s)");
  }

  @Test
  void previewAndGenerateStateTheSameTerm() {
    DocumentProjectionService service = service(RENTAL);
    Map<String, Object> data = aggregateBackedData();
    data.put("startDate", "2026-01-08");
    data.put("endDate", "2028-01-08"); // 24 months
    data.put("durationMonths", 11); // what the form used to send

    String preview = service.previewHtml(request(data));
    service.generate(request(data));

    for (String html : List.of(preview, lastHtml)) {
      assertThat(html).contains("a term of 24 month(s)").doesNotContain("a term of 11 month(s)");
    }
  }

  @Test
  void bothProductionSetsDeclareTheTermDerivedAndProjectItReadOnly() {
    for (String[] set : new String[][] {{RENTAL, "residential"}, {COMMERCIAL, "commercial"}}) {
      EffectiveTemplate effective =
          new TemplateResolver(new ClasspathLayerSource(set[0]))
              .resolve(new Dimensions("IN", set[1]));

      assertThat(field(effective.template().fields(), "durationMonths").derived()).isTrue();

      FormField term = formField(new FormProjector().project(effective), "durationMonths");
      assertThat(term.readOnly()).as("%s durationMonths readOnly", set[1]).isTrue();
      assertThat(term.required()).as("%s durationMonths required", set[1]).isFalse();
      // The default is gone: it is what made the form show 11 against a 24-month date span.
      assertThat(term.defaultValue()).as("%s durationMonths default", set[1]).isNull();
    }
  }

  // --- helpers ---------------------------------------------------------------------

  private DocumentProjectionService service(String root) {
    return new DocumentProjectionService(
        new TemplateResolver(new ClasspathLayerSource(root)),
        new TemplateCompiler(),
        (html, reference) -> {
          lastHtml = html;
          return "%PDF-".getBytes(StandardCharsets.UTF_8);
        },
        new DocumentFooterProperties("agreementmitra.com", ""),
        clock);
  }

  private static DocumentProjectionRequest request(Map<String, Object> data) {
    return new DocumentProjectionRequest(
        new DocumentDimensions("IN", "residential"), data, List.of(), "AMPSFTXU5KV");
  }

  private static Map<String, Object> aggregateBackedData() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ownerName", "Asha Owner");
    data.put("tenantName", "Bhaskar Tenant");
    data.put("propertyAddress", "Plot 7, Jubilee Hills, Hyderabad");
    data.put("monthlyRent", new BigDecimal("25000.00"));
    data.put("securityDeposit", new BigDecimal("100000.00"));
    return data;
  }

  private static EffectiveTemplate effective(TemplateDefinition definition) {
    LayerSource.LayerSet set =
        new LayerSource.LayerSet(
            new LayerSource.LayerSet.Base(
                new LayerRef(
                    LayerKind.BASE,
                    definition.meta().dimensions(),
                    definition.meta().version(),
                    "base"),
                definition),
            List.of());
    return new TemplateResolver((s, t) -> set).resolve(new Dimensions("IN", "residential"));
  }

  private static Field field(List<Field> fields, String key) {
    return fields.stream()
        .filter(f -> f.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no field " + key));
  }

  private static FormField formField(FormSchema schema, String key) {
    return schema.sections().stream()
        .flatMap(section -> section.fields().stream())
        .filter(f -> f.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no form field " + key));
  }
}
