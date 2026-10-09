package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import in.agreementmitra.DocumentDataInvalidException;
import in.agreementmitra.FieldErrorDetail;
import in.agreementmitra.documents.api.FormSchema;
import in.agreementmitra.documents.api.FormSection;
import in.agreementmitra.support.TemplateParity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Resolves + compiles the production rental layer set ({@code documents/template/sets/rental/}) for
 * both dimensions the catalog seeds: national {@code (IN, residential)} and {@code (TG,
 * residential)}. Pure -- real resource I/O but no Spring context, no Testcontainers, no Docker --
 * so it runs in {@code ./gradlew test} regardless of the local Docker daemon.
 *
 * <p>Guards the M5 {@code rental-document-content-v2} structure: (1) the set composes and
 * re-validates cleanly (no dangling entries / bad slots / bad defaults) for IN and TG; (2) the
 * document reads like the reference Leave-and-Licence artifact -- a {@code meta.document} header,
 * split Owner/Tenant party cards, a Schedule of Property, Term/Financial key/value sets, a
 * document-only numbered "Now This Agreement Witnesseth" covenant list, and an optional annexure --
 * with the mandatory vs optional section split and per-section render kinds declared in content;
 * (3) the Telangana overlay applies over the new structure (statutory section last, TG law/stamp
 * clauses in, the generic national stamp clause out, Hyderabad jurisdiction default, nothing
 * dangling); (4) optional add-on sections are gated by {@code activeSections} (M2); (5) the
 * field-less witnesseth section is omitted from the capture {@link FormSchema} (M3) while still
 * rendering; and (6) the PARITY CONTRACT holds -- a generate projection fed the aggregate-backed
 * field keys plus the user-answered {@code subletting} validates and compiles without a
 * missing-required error; and (7) the sub-letting choice renders exactly one covenant per option.
 */
class ProductionRentalLayerSetTest {

  private static final String ROOT = "documents/template/sets/rental/";

  // The national (IN, residential) section set after M5: mandatory Owner/Tenant party cards, the
  // Schedule/Term/Financial key-value sets, the optional coarse add-on sections, the document-only
  // witnesseth covenant list, and the optional annexure.
  private static final List<String> BASE_SECTIONS =
      List.of(
          "Owner",
          "Tenant",
          "Schedule of Property",
          "Term",
          "Financial",
          "Charges & Utilities",
          "Occupancy & Use",
          "Dispute Resolution",
          "Now This Agreement Witnesseth",
          "Annexure",
          "In Witness Whereof",
          "Witnesses");

  // The Telangana (TG, residential) set: the national sections re-ordered with the (optional)
  // statutory overlay, then the (mandatory) signature block, then the optional Witnesses section.
  private static final List<String> TG_SECTIONS =
      List.of(
          "Owner",
          "Tenant",
          "Schedule of Property",
          "Term",
          "Financial",
          "Charges & Utilities",
          "Occupancy & Use",
          "Dispute Resolution",
          "Now This Agreement Witnesseth",
          "Annexure",
          "Statutory (Telangana)",
          "In Witness Whereof",
          "Witnesses");

  // The declared mandatory (always-render) sections for the NATIONAL (IN) set; every other section
  // is optional (opt-in). This set is asserted against the IN resolution only.
  //
  // In the Telangana set "Statutory (Telangana)" is ALSO mandatory as of 2026-09-07 (reversing the
  // 2026-07-13 opt-in decision): the TG residential layer re-authors the witnesseth list without
  // the
  // national `stampRegistrationClause`, so while the statutory section was opt-in a Telangana deed
  // rendered with no stamp/registration clause at all -- strictly worse than the national template.
  //
  // The "In Witness Whereof" execution block is MANDATORY in both sets (agreement-execution-block
  // CR -- a generated draft must carry the signature zones + eSign anchors to be signable). The
  // optional "Witnesses" add-on is off by default in both.
  private static final Set<String> MANDATORY_SECTIONS =
      Set.of(
          "Owner",
          "Tenant",
          "Schedule of Property",
          "Term",
          "Financial",
          "Now This Agreement Witnesseth",
          "In Witness Whereof");

  // Exactly the keys AgreementDocumentMapper.toTemplateData emits from the persisted aggregate
  // (asserted equal to TemplateParity.AGGREGATE_KEYS). Generate also needs the user-answered
  // fields: see generateReadyData().
  private static Map<String, Object> aggregateBackedData() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ownerName", "Asha Owner");
    data.put("ownerFatherName", "Ravi Owner");
    data.put("ownerAddress", "1 First Street, Hyderabad");
    data.put("tenantName", "Bhaskar Tenant");
    data.put("tenantFatherName", "Kiran Tenant");
    data.put("tenantAddress", "2 Second Street, Hyderabad");
    data.put("propertyAddress", "Plot 7, Jubilee Hills, Hyderabad");
    data.put("monthlyRent", new BigDecimal("25000.00"));
    data.put("securityDeposit", new BigDecimal("100000.00"));
    // durationMonths is DERIVED: whatever is put here is discarded and recomputed from the dates.
    // It is left in deliberately, and left DISAGREEING with them: these dates span 11 whole months
    // counting the end date as the last day, and 10 is what the old end-exclusive count produced,
    // so a regression to either a submitted term or that count renders "10 month(s)", not "11".
    data.put("durationMonths", 10);
    data.put("startDate", "2026-08-01");
    data.put("endDate", "2027-06-30");
    return data;
  }

  private static Map<String, Object> generateReadyData() {
    return TemplateParity.withUserAnswers(aggregateBackedData());
  }

  @Test
  void theBaseLayerPinsItsAuthoredVersion() {
    // meta.version is load-bearing: Agreement.pinEffectiveTemplate records it, so two materially
    // different deeds must never report one authored version. v8 is the bump that replaced
    // maintenanceBorneBy with the four-arrangement maintenanceMode. Pinned explicitly because
    // every other assertion in this suite reads the version dynamically, which would let a revert
    // through silently.
    assertThat(new TemplateDefinitionLoader().loadResource(ROOT + "base.yaml").meta().version())
        .isEqualTo(8);
  }

  @Test
  void chargesBorneByOffersOnlyOwnerOrTenant() {
    // "borne by the {{x}}" renders the option label, so `shared` printed "borne by the Shared".
    for (String state : List.of("IN", "TG", "KA")) {
      EffectiveTemplate eff = resolve(state, "residential");
      assertThat(field(eff, "utilitiesBorneBy").options())
          .as("%s utilitiesBorneBy", state)
          .containsExactly("tenant", "owner");
    }
  }

  // --- Charges & Utilities: maintenance arrangements (maintenance-charge-basis) ---------------

  private static final String CHARGES = "Charges & Utilities";

  private static final Map<String, String> MAINTENANCE_CLAUSES =
      Map.of(
          "included_in_rent",
          "The monthly rent includes the society and building maintenance charges, which the Owner"
              + " shall pay to the society directly.",
          "fixed_amount",
          "In addition to the rent, the Tenant shall pay the Owner a maintenance charge of INR",
          "as_billed_by_society",
          "The Tenant shall pay the society and building maintenance charges directly to the"
              + " society, as billed by it during the tenancy.",
          "paid_by_owner",
          "The society and building maintenance charges shall be borne by the Owner, who shall pay"
              + " them to the society directly.");

  private static final String REVISION_CLAUSE = "If the society revises its maintenance charges";
  private static final String LEVY_CLAUSE = "Any one-time or capital levy raised by the society";

  /** Compile with Charges & Utilities active and return only that section's HTML. */
  private static String chargesSection(String state, Map<String, Object> extra) {
    EffectiveTemplate eff = resolve(state, "residential");
    Map<String, Object> data = new LinkedHashMap<>(generateReadyData());
    data.putAll(extra);
    Map<String, Object> coerced =
        SubmittedDataValidator.validateAndCoerce(eff, data, ProjectionMode.GENERATE);
    String html = new TemplateCompiler().compile(eff, coerced, null, Set.of(CHARGES));
    String heading = "<h2>Charges &amp; Utilities</h2>";
    int start = html.indexOf(heading);
    assertThat(start).as("%s: Charges & Utilities renders", state).isNotNegative();
    return html.substring(start, html.indexOf("</section>", start));
  }

  @Test
  void eachMaintenanceModeRendersItsOwnClauseAndNoOther() {
    for (String state : List.of("IN", "TG", "KA")) {
      for (String mode : MAINTENANCE_CLAUSES.keySet()) {
        String section =
            chargesSection(state, Map.of("maintenanceMode", mode, "maintenanceAmount", "3500"));
        for (Map.Entry<String, String> clause : MAINTENANCE_CLAUSES.entrySet()) {
          if (clause.getKey().equals(mode)) {
            assertThat(section).as("%s %s", state, mode).contains(clause.getValue());
          } else {
            assertThat(section)
                .as("%s %s must not carry %s", state, mode, clause.getKey())
                .doesNotContain(clause.getValue());
          }
        }
        assertThat(section.contains(REVISION_CLAUSE))
            .as("%s %s revision clause", state, mode)
            .isEqualTo(mode.equals("fixed_amount"));
      }
    }
  }

  @Test
  void aFixedAmountStatesWhoPaysWhomOnTopOfTheRent() {
    String section =
        chargesSection(
            "TG", Map.of("maintenanceMode", "fixed_amount", "maintenanceAmount", "3500"));
    assertThat(section)
        .contains(
            "In addition to the rent, the Tenant shall pay the Owner a maintenance charge of INR"
                + " 3500 per month, together with the rent.")
        .contains(REVISION_CLAUSE)
        .doesNotContain("payable amount to");
  }

  @Test
  void aFixedModeWithNoOrZeroAmountPrintsNeitherFixedClause() {
    Map<String, Object> blank = Map.of("maintenanceMode", "fixed_amount");
    Map<String, Object> zero = Map.of("maintenanceMode", "fixed_amount", "maintenanceAmount", "0");
    for (Map<String, Object> extra : List.of(blank, zero)) {
      String section = chargesSection("TG", extra);
      assertThat(section)
          .as("%s", extra)
          .doesNotContain(MAINTENANCE_CLAUSES.get("fixed_amount"))
          .doesNotContain(REVISION_CLAUSE);
    }
  }

  @Test
  void anAmountTypedUnderAnotherModeIsNotPrinted() {
    String section =
        chargesSection(
            "TG", Map.of("maintenanceMode", "included_in_rent", "maintenanceAmount", "3500"));
    assertThat(section).doesNotContain("3500");
  }

  @Test
  void theSocietyLevyClauseFollowsThePropertyType() {
    for (String mode : MAINTENANCE_CLAUSES.keySet()) {
      for (String type : List.of("apartment", "gated_community", "villa")) {
        assertThat(chargesSection("TG", Map.of("maintenanceMode", mode, "propertyType", type)))
            .as("%s %s", mode, type)
            .contains(
                "Any one-time or capital levy raised by the society, including sinking fund, corpus"
                    + " fund, major-repair and non-occupancy charges, shall be borne by the Owner,"
                    + " even where the society bills it to the Tenant.");
      }
      for (String type : List.of("independent_house", "pg_room")) {
        assertThat(chargesSection("TG", Map.of("maintenanceMode", mode, "propertyType", type)))
            .as("%s %s", mode, type)
            .doesNotContain(LEVY_CLAUSE);
      }
    }
  }

  @Test
  void chargesAndUtilitiesPrintsClausesAndNoBlanks() {
    for (String state : List.of("IN", "TG", "KA")) {
      String section = chargesSection(state, Map.of());
      assertThat(section)
          .as(state)
          .contains("<ol class=\"clauses\">")
          .doesNotContain("<table")
          .doesNotContain("[ ")
          .doesNotContain("Grace period")
          .doesNotContain("Maintenance amount")
          // The default mode is as billed by the society.
          .contains(MAINTENANCE_CLAUSES.get("as_billed_by_society"));
    }
  }

  @Test
  void aStoredDraftCarryingTheRemovedKeyStillValidates() {
    EffectiveTemplate eff = resolve("TG", "residential");
    Map<String, Object> data = new LinkedHashMap<>(generateReadyData());
    data.put("maintenanceBorneBy", "owner");
    for (ProjectionMode mode : ProjectionMode.values()) {
      Map<String, Object> coerced = SubmittedDataValidator.validateAndCoerce(eff, data, mode);
      assertThat(coerced).as("%s", mode).doesNotContainKey("maintenanceBorneBy");
    }
  }

  @Test
  void theMaintenanceModeIsAnOptionalDefaultedEnum() {
    for (String state : List.of("IN", "TG", "KA")) {
      EffectiveTemplate eff = resolve(state, "residential");
      Field mode = field(eff, "maintenanceMode");
      assertThat(mode.label()).isEqualTo("How is maintenance handled?");
      assertThat(mode.required()).isFalse();
      assertThat(mode.defaultValue()).isEqualTo("as_billed_by_society");
      assertThat(mode.options())
          .containsExactly(
              "included_in_rent", "fixed_amount", "as_billed_by_society", "paid_by_owner");
      Field amount = field(eff, "maintenanceAmount");
      assertThat(amount.required()).isFalse();
      assertThat(amount.label()).isEqualTo("Maintenance amount (INR / month) – only if Fixed");
      assertThat(fieldKeys(eff)).doesNotContain("maintenanceBorneBy");
      assertThat(clauseIds(eff))
          .doesNotContain("maintenanceClause", "maintenanceAmountClause")
          .contains(
              "maintenanceIncludedClause",
              "maintenanceFixedClause",
              "maintenanceRevisionClause",
              "maintenanceAsBilledClause",
              "maintenanceOwnerClause",
              "societyLeviesClause");
    }
  }

  @Test
  void eachRegistrationChargesChoiceRendersOneReadableBearerSentence() {
    String borne = "The stamp duty and registration charges shall be borne by the ";
    String shared =
        "The stamp duty and registration charges shall be shared equally between the Owner and the"
            + " Tenant.";
    for (String state : List.of("TG", "KA")) {
      EffectiveTemplate eff = resolve(state, "residential");
      Map<String, String> expected =
          Map.of("owner", borne + "Owner.", "tenant", borne + "Tenant.", "shared", shared);
      for (Map.Entry<String, String> option : expected.entrySet()) {
        Map<String, Object> data = new LinkedHashMap<>(aggregateBackedData());
        data.put("registrationChargesBorneBy", option.getKey());
        String html = new TemplateCompiler().compile(eff, data);
        assertThat(html).as("%s %s", state, option.getKey()).contains(option.getValue());
        assertThat(html.split("registration charges shall be", -1))
            .as("%s %s: exactly one bearer sentence", state, option.getKey())
            .hasSize(2);
        assertThat(html).doesNotContain("by the Shared");
      }
    }
  }

  @Test
  void aStoredSharedChargeChoiceIsRejectedInBothModes() {
    EffectiveTemplate eff = resolve("TG", "residential");
    Map<String, Object> data = new LinkedHashMap<>(aggregateBackedData());
    data.put("utilitiesBorneBy", "shared");
    for (ProjectionMode mode : ProjectionMode.values()) {
      DocumentDataInvalidException ex =
          catchThrowableOfType(
              DocumentDataInvalidException.class,
              () -> SubmittedDataValidator.validateAndCoerce(eff, data, mode));
      assertThat(ex).as("%s", mode).isNotNull();
      assertThat(ex.errors()).contains(new FieldErrorDetail("utilitiesBorneBy", "enum"));
    }
  }

  @Test
  void onlyAggregateBackedDefaultedOrUserAnsweredFieldsAreRequiredAndEveryPartyFieldIs() {
    // PARITY CONTRACT, both directions, for every published residential dimension. A required
    // undefaulted field must be aggregate-backed or user-answered (TemplateParity holds the rule),
    // and every aggregate-backed key except the derived durationMonths must be required -- in
    // particular the party fields the agreement API already requires non-blank, so the capture
    // form and the server agree.
    assertThat(aggregateBackedData().keySet()).isEqualTo(TemplateParity.AGGREGATE_KEYS);
    for (String state : List.of("IN", "TG", "KA")) {
      EffectiveTemplate eff = resolve(state, "residential");
      assertThat(SublettingCovenants.parityViolations(eff)).as("%s parity", state).isEmpty();
      for (String key : TemplateParity.AGGREGATE_KEYS) {
        if (!key.equals("durationMonths")) {
          assertThat(field(eff, key).required())
              .as("%s: aggregate-backed '%s' is required", state, key)
              .isTrue();
        }
      }
    }
  }

  @Test
  void theSublettingChoiceIsARequiredTermFieldWithNoDefault() {
    for (String state : List.of("IN", "TG", "KA")) {
      SublettingCovenants.assertRequiredTermFieldWithNoDefault(
          resolve(state, "residential"), "noticePeriodMonths");
    }
  }

  @Test
  void eachSublettingOptionRendersItsOwnCovenantAndOnlyThatOne() {
    for (String state : List.of("IN", "TG")) {
      EffectiveTemplate eff = resolve(state, "residential");
      SublettingCovenants.assertTheWitnessethListsAllThreeCovenants(eff);
      assertThat(clauseIds(eff))
          .containsAll(SublettingCovenants.CLAUSE_IDS)
          .doesNotContain("noSublettingClause");
      SublettingCovenants.assertEachOptionRendersItsOwnCovenant(
          eff, aggregateBackedData(), "Tenant", "Owner");
    }
  }

  @Test
  void aBlankSublettingChoicePreviewsWithAPlaceholderAndNoCovenant() {
    EffectiveTemplate eff = resolve("KA", "residential");
    for (Object blank : Arrays.asList(null, "")) {
      Map<String, Object> data = new LinkedHashMap<>(aggregateBackedData());
      data.put("subletting", blank);
      String html =
          new TemplateCompiler()
              .compile(
                  eff, SubmittedDataValidator.validateAndCoerce(eff, data, ProjectionMode.PREVIEW));
      assertThat(html).contains("[ Sub-letting ]");
      assertThat(SublettingCovenants.covenantCount(html)).isZero();
    }
  }

  @Test
  void generateRefusesABlankSublettingChoice() {
    EffectiveTemplate eff = resolve("TG", "residential");
    DocumentDataInvalidException ex =
        catchThrowableOfType(
            DocumentDataInvalidException.class,
            () ->
                SubmittedDataValidator.validateAndCoerce(
                    eff, aggregateBackedData(), ProjectionMode.GENERATE));
    assertThat(ex).isNotNull();
    assertThat(ex.errors()).containsExactly(new FieldErrorDetail("subletting", "required"));
  }

  @Test
  void aSublettingValueOutsideTheOptionsIsRejectedInBothModes() {
    EffectiveTemplate eff = resolve("TG", "residential");
    for (String bogus : List.of("Allowed", "sometimes")) {
      Map<String, Object> data = new LinkedHashMap<>(aggregateBackedData());
      data.put("subletting", bogus);
      for (ProjectionMode mode : ProjectionMode.values()) {
        DocumentDataInvalidException ex =
            catchThrowableOfType(
                DocumentDataInvalidException.class,
                () -> SubmittedDataValidator.validateAndCoerce(eff, data, mode));
        assertThat(ex).as("%s in %s", bogus, mode).isNotNull();
        assertThat(ex.errors()).contains(new FieldErrorDetail("subletting", "enum"));
      }
      // Compiled directly (bypassing validation), the bogus value matches no covenant either.
      assertThat(SublettingCovenants.covenantCount(new TemplateCompiler().compile(eff, data)))
          .isZero();
    }
  }

  @Test
  void theRenderedTermFollowsTheDatesNotTheSubmittedDuration() {
    EffectiveTemplate eff = resolve("IN", "residential");

    Map<String, Object> data =
        DocumentProjectionService.withSystemValues(eff, generateReadyData(), Map.of());
    String html =
        new TemplateCompiler()
            .compile(
                eff, SubmittedDataValidator.validateAndCoerce(eff, data, ProjectionMode.GENERATE));

    assertThat(html).contains("a term of 11 month(s)").doesNotContain("a term of 10 month(s)");
  }

  @Test
  void nationalResolvesWithTheAuthoredSectionsAndResidentialUsePinned() {
    EffectiveTemplate eff = resolve("IN", "residential");

    assertThat(sectionTitles(eff)).isEqualTo(BASE_SECTIONS);
    assertThat(fieldKeys(eff))
        .contains(
            "ownerName",
            "tenantName",
            "propertyAddress",
            "monthlyRent",
            "securityDeposit",
            "durationMonths",
            "startDate",
            "endDate");

    // type-residential override: permitted use is required and defaulted to residential.
    Field permittedUse = field(eff, "permittedUse");
    assertThat(permittedUse.required()).isTrue();
    assertThat(permittedUse.defaultValue()).isEqualTo("residential");
  }

  @Test
  void mandatoryAndOptionalFlagsAndRenderKindsAreDeclaredPerSection() {
    EffectiveTemplate eff = resolve("IN", "residential");

    // Mandatory set (always render) vs optional (opt-in); marking is per-section in the template.
    for (Section section : eff.template().sections()) {
      boolean shouldBeMandatory = MANDATORY_SECTIONS.contains(section.title());
      assertThat(section.optional())
          .as("section '%s' optional flag", section.title())
          .isEqualTo(!shouldBeMandatory);
    }

    // Render kinds drive the document layout, declared in content (never hardcoded).
    assertThat(section(eff, "Owner").render()).isEqualTo(RenderKind.PARTIES);
    assertThat(section(eff, "Tenant").render()).isEqualTo(RenderKind.PARTIES);
    assertThat(section(eff, "Schedule of Property").render()).isEqualTo(RenderKind.KEYVALUE);
    assertThat(section(eff, "Term").render()).isEqualTo(RenderKind.KEYVALUE);
    assertThat(section(eff, "Financial").render()).isEqualTo(RenderKind.KEYVALUE);
    assertThat(section(eff, "Now This Agreement Witnesseth").render())
        .isEqualTo(RenderKind.CLAUSES);
    assertThat(section(eff, "Annexure").render()).isEqualTo(RenderKind.ANNEXURE);
    assertThat(section(eff, "Charges & Utilities").render()).isEqualTo(RenderKind.CLAUSES);

    // A mandatory section may still hold optional fields: only its aggregate-backed ones are
    // required.
    Section schedule = section(eff, "Schedule of Property");
    assertThat(schedule.entries()).contains("carpetAreaSqft", "furnishingStatus");
    assertThat(field(eff, "carpetAreaSqft").required()).isFalse();
    assertThat(field(eff, "furnishingStatus").required()).isFalse();

    // The witnesseth section carries clause ids only (zero field entries) -- document structure.
    List<String> fieldKeys = fieldKeys(eff);
    assertThat(section(eff, "Now This Agreement Witnesseth").entries())
        .noneMatch(fieldKeys::contains);
  }

  @Test
  void nationalDeclaresTheReferenceHeaderAndTheRecitalStillRenders() {
    EffectiveTemplate eff = resolve("IN", "residential");

    DocumentMeta document = eff.template().meta().document();
    assertThat(document).isNotNull();
    assertThat(document.title()).isEqualTo("Rental Agreement");
    // Neutral subtitle: the "(Leave & Licence)" label was removed in base.yaml v2 -- it is the
    // Maharashtra form, and every other signal in this deed (the "lets" verb, Owner/Tenant, the
    // no-subletting covenant, the TG 1960 LEASE Act, Article 30 stamping) says lease.
    assertThat(document.subtitle()).isEqualTo("Residential Tenancy");
    assertThat(document.subtitle()).doesNotContain("Licence");
    assertThat(document.executionLine())
        .contains("{{agreementDate}}")
        .contains("in respect of the property in the Schedule below");

    // After the Owner/Tenant split, the "made between ... and ..." recital still renders (it lives
    // in the always-on witnesseth section).
    String html = new TemplateCompiler().compile(eff, aggregateBackedData());
    assertThat(html).contains("made between Asha Owner").contains("Bhaskar Tenant");
  }

  @Test
  void telanganaAppliesTheStatutoryOverlayLastOverTheNewStructure() {
    EffectiveTemplate eff = resolve("TG", "residential");

    List<String> titles = sectionTitles(eff);
    assertThat(titles).hasSize(13).isEqualTo(TG_SECTIONS);
    // The statutory overlay reads last among the substantive sections, immediately before the
    // (mandatory) signature block.
    assertThat(titles.indexOf("Statutory (Telangana)"))
        .isEqualTo(titles.indexOf("In Witness Whereof") - 1);

    assertThat(fieldKeys(eff)).contains("stampDutyAmount", "registrationChargesBorneBy");
    assertThat(clauseIds(eff))
        .contains("tgGoverningLaw", "tgStampRegistration", "tgEssentialServices")
        .doesNotContain("stampRegistrationClause"); // generic national clause dropped for TG

    // Telangana default jurisdiction.
    assertThat(field(eff, "jurisdictionCity").defaultValue()).isEqualTo("Hyderabad");
    // The witnesseth list no longer lists the removed national stamp clause -- nothing dangles.
    assertThat(section(eff, "Now This Agreement Witnesseth").entries())
        .doesNotContain("stampRegistrationClause");
  }

  @Test
  void generateParityHoldsWithTheAggregateKeysAndTheUserAnswersForBothDimensions() {
    // The generate projection fed the mapper's keys plus the user-answered subletting must
    // validate (defaults cover the rest) and compile for BOTH dimensions -- so a signed draft
    // renders without a missing-required error.
    for (String state : List.of("IN", "TG")) {
      EffectiveTemplate eff = resolve(state, "residential");

      Map<String, Object> coerced =
          SubmittedDataValidator.validateAndCoerce(
              eff, generateReadyData(), ProjectionMode.GENERATE);

      // Defaulted fields the aggregate never supplied are filled from the template.
      assertThat(coerced).containsEntry("permittedUse", "residential");

      assertThatCode(() -> new TemplateCompiler().compile(eff, coerced)).doesNotThrowAnyException();
    }

    // The always-on covenants render in a generated draft unconditionally; the opt-in TG statutory
    // overlay renders once its section is in the active set (it is now optional, per the
    // requester's
    // decision).
    EffectiveTemplate tg = resolve("TG", "residential");
    Map<String, Object> coerced =
        SubmittedDataValidator.validateAndCoerce(tg, generateReadyData(), ProjectionMode.GENERATE);
    assertThat(coerced).containsEntry("registrationChargesBorneBy", "tenant");
    String html =
        new TemplateCompiler().compile(tg, coerced, null, Set.of("Statutory (Telangana)"));
    assertThat(html)
        .contains("Statutory (Telangana)")
        .contains("Telangana Buildings")
        .contains("Hyderabad") // via the always-on dispute/jurisdiction covenant
        .contains("used for Residential purposes only"); // permittedUse enum humanised in the body
  }

  @Test
  void optionalAddOnSectionsAreGatedByTheActiveSet() {
    EffectiveTemplate eff = resolve("TG", "residential");
    Map<String, Object> coerced =
        SubmittedDataValidator.validateAndCoerce(eff, generateReadyData(), ProjectionMode.GENERATE);
    TemplateCompiler compiler = new TemplateCompiler();

    // Empty active set: mandatory sections render, optional add-ons do not. For Telangana the
    // statutory overlay is MANDATORY (2026-09-07), so it renders with no add-ons selected -- that
    // is
    // what guarantees a TG deed always carries a stamp/registration clause, since the TG layer
    // removes the national one. The signature block is MANDATORY too, so it renders by default with
    // the per-signer eSign anchors -- the draft is signable.
    String withoutAddOns = compiler.compile(eff, coerced);
    assertThat(withoutAddOns)
        .contains("made between Asha Owner") // mandatory recital (witnesseth)
        .contains("In Witness Whereof") // mandatory signature block renders by default
        .contains("esign:owner")
        .contains("esign:tenant") // ...with both eSign anchors
        .contains("Statutory (Telangana)") // mandatory TG statutory overlay renders by default
        // The clause the whole flag exists for: TG drops the national stampRegistrationClause, so
        // this is the only stamp/registration wording a Telangana deed can carry.
        .contains("where the law requires it, registered before the jurisdictional Sub-Registrar")
        // tgEssentialServices: the owner may not cut water/electricity during the tenancy.
        .contains("withhold or disconnect essential supplies")
        // tgGoverningLaw: the TG-specific statute, not just "laws of India".
        .contains("Telangana Buildings (Lease, Rent and Eviction) Control Act, 1960")
        .doesNotContain("shall not keep any pets") // optional Occupancy & Use add-on gated out
        .doesNotContain(LEVY_CLAUSE); // optional Charges & Utilities gated out

    // Add the optional Occupancy & Use section: its content (the default pets covenant) appears.
    String withOccupancy = compiler.compile(eff, coerced, null, Set.of("Occupancy & Use"));
    assertThat(withOccupancy).contains("shall not keep any pets");

    // Add the opt-in statutory overlay: it now appears alongside the always-on signature block.
    String withStatutory = compiler.compile(eff, coerced, null, Set.of("Statutory (Telangana)"));
    assertThat(withStatutory).contains("Statutory (Telangana)").contains("In Witness Whereof");
  }

  @Test
  void theFieldlessWitnessethSectionIsOmittedFromTheCaptureFormWhileOptionalAndRenderExposed() {
    for (String state : List.of("IN", "TG")) {
      EffectiveTemplate eff = resolve(state, "residential");
      FormSchema schema = new FormProjector().project(eff);
      List<String> formTitles = schema.sections().stream().map(FormSection::title).toList();

      // The zero-field witnesseth section is document structure, not a capture step -- omitted.
      assertThat(formTitles).doesNotContain("Now This Agreement Witnesseth");
      // Mandatory capture sections are present.
      assertThat(formTitles)
          .contains("Owner", "Tenant", "Schedule of Property", "Term", "Financial");

      // The per-section optional flag and render kind are exposed to the form surface verbatim.
      assertThat(formSection(schema, "Owner").renderKind()).isEqualTo("parties");
      assertThat(formSection(schema, "Owner").optional()).isFalse();
      assertThat(formSection(schema, "Charges & Utilities").optional()).isTrue();
      assertThat(formSection(schema, "Charges & Utilities").renderKind()).isEqualTo("clauses");
      // A clauses section still asks its fields in the capture form.
      assertThat(
              formSection(schema, "Charges & Utilities").fields().stream()
                  .map(f -> f.key())
                  .toList())
          .containsExactly(
              "maintenanceMode",
              "maintenanceAmount",
              "utilitiesBorneBy",
              "latePaymentPenalty",
              "gracePeriodDays");
    }
  }

  @Test
  void bothDimensionsCompileToTheArtifactLayoutInvariants() {
    // Resolve + compile the reference IN and TG sets and assert the artifact layout the M1 compiler
    // draws over the M5 content: the centred document header, the split Owner/Tenant party cards, a
    // key/value table, a numbered witnesseth clause list, the signature block, real page margins on
    // both tiers, and self-containment (no external URL).
    for (String state : List.of("IN", "TG")) {
      EffectiveTemplate eff = resolve(state, "residential");
      Map<String, Object> coerced =
          SubmittedDataValidator.validateAndCoerce(
              eff, generateReadyData(), ProjectionMode.GENERATE);
      // Compile with the bean-wired faces so this covers the one document actually handed to
      // Gotenberg (a resolved execution date threads through unchanged).
      String html =
          new TemplateCompiler(DocumentFonts.faceCss()).compile(eff, coerced, "13 July 2026");

      // Centred document header from meta.document.
      assertThat(html).contains("<div class=\"doc-header\">").contains("<h1 class=\"doc-title\">");
      assertThat(html).contains("Rental Agreement");
      assertThat(html).contains("in respect of the property in the Schedule below");
      // Split Owner/Tenant party cards (render: parties).
      assertThat(html).contains("<h2>Owner</h2>").contains("<h2>Tenant</h2>");
      assertThat(html).contains("<div class=\"party-card\">");
      // A key/value table and a numbered witnesseth clause list.
      assertThat(html).contains("<table class=\"kv\">");
      assertThat(html).contains("<ol class=\"clauses\">");
      // The signature block is a mandatory template-declared section for BOTH sets now: it always
      // renders, carrying the per-signer signature zones + the esign:<role> anchors that make the
      // draft signable (agreement-execution-block CR).
      assertThat(html)
          .contains("In Witness Whereof")
          .contains("esign:owner")
          .contains("esign:tenant");
      // Real page margins hold on both the PDF (@page) and the live pane (body padding).
      assertThat(html).contains("@page { margin:").contains("padding: 24mm 20mm;");
      // Self-contained: no external URL of any scheme (fonts are family-name / data-URI only).
      assertThat(html).doesNotContain("http://").doesNotContain("https://");
    }
  }

  @Test
  void previewToleratesMissingRequiredAndStillRendersDefaultsAndPlaceholders() {
    EffectiveTemplate eff = resolve("TG", "residential");

    Map<String, Object> coerced =
        SubmittedDataValidator.validateAndCoerce(eff, Map.of(), ProjectionMode.PREVIEW);
    // Add the opt-in statutory overlay so its (defaulted) content is exercised alongside the
    // placeholder tolerance.
    String html =
        new TemplateCompiler().compile(eff, coerced, null, Set.of("Statutory (Telangana)"));

    // Missing required field -> escaped placeholder, not a failure.
    assertThat(html).contains("[ Scheduled property address ]");
    // The added statutory overlay + the always-on jurisdiction covenant render.
    assertThat(html).contains("Telangana Buildings").contains("Hyderabad");
  }

  @Test
  void boilerplateClosingClausesRenderForBothDimensions() {
    for (String state : List.of("IN", "TG")) {
      EffectiveTemplate eff = resolve(state, "residential");
      Map<String, Object> coerced =
          SubmittedDataValidator.validateAndCoerce(
              eff, generateReadyData(), ProjectionMode.GENERATE);
      String html = new TemplateCompiler().compile(eff, coerced);

      assertThat(html)
          .as("boilerplate closing clauses for %s", state)
          .contains("governed by and construed in accordance with the laws of India")
          .contains("held to be invalid or unenforceable") // severability
          .contains("constitutes the entire agreement") // entire agreement / amendment
          .contains("Any notice required or permitted"); // service of notice
    }
  }

  @Test
  void theAlwaysOnJurisdictionCovenantIsUnfilledOnlyOnTheNationalDeed() {
    // KNOWN GAP, pinned deliberately -- see the follow-up register in docs/ROADMAP.md.
    //
    // disputeClause/disputeAlternativeClause sit in the MANDATORY witnesseth list, gated only on
    // disputeResolution (base default "courts"), so one of them renders on EVERY deed. The city it
    // names, jurisdictionCity, is declared required:false with NO national default and lives in the
    // OPTIONAL "Dispute Resolution" section. Every STATE layer patches the default; nothing patches
    // the base. So a deed generated without that optional section renders an operative
    // exclusive-jurisdiction covenant naming a placeholder instead of a court:
    //
    //     "...exclusive jurisdiction of the courts at [ Jurisdiction city ]."
    //
    // SCOPE OF THE GAP, CORRECTED 2026-09-18 (ka-rental-and-commercial-templates). This comment
    // used
    // to say the gap "reaches KARNATAKA ... KA matches no state patch, so a Karnataka agreement
    // resolves to this base alone". That was true when it was written and is now FALSE: Karnataka
    // has its own state + state_type layers, which default the city to Bengaluru. The gap is today
    // confined to the NATIONAL (IN) deed and to any future state shipped without a layer -- which
    // is why the Karnataka assertion below is here, not only in the Karnataka test: it is this
    // test's own statement of how far the hole reaches.
    //
    // This test asserts the CURRENT behaviour so the gap is visible and cannot regress silently. It
    // is expected to be rewritten by the CR that fixes it (the likely fix is a fallback clause
    // reading "courts of competent jurisdiction" when no city is set -- a drafting decision).
    EffectiveTemplate national = resolve("IN", "residential");
    String nationalHtml =
        new TemplateCompiler()
            .compile(
                national,
                SubmittedDataValidator.validateAndCoerce(
                    national, generateReadyData(), ProjectionMode.GENERATE));
    assertThat(nationalHtml)
        .as("national deed leaves the jurisdiction covenant unfilled")
        .contains("exclusive jurisdiction of the courts at [ Jurisdiction city ]");

    // Telangana is unaffected: the state+type layer defaults the city.
    EffectiveTemplate telangana = resolve("TG", "residential");
    String telanganaHtml =
        new TemplateCompiler()
            .compile(
                telangana,
                SubmittedDataValidator.validateAndCoerce(
                    telangana, generateReadyData(), ProjectionMode.GENERATE));
    assertThat(telanganaHtml)
        .as("Telangana names a court")
        .contains("exclusive jurisdiction of the courts at Hyderabad.")
        .doesNotContain("[ Jurisdiction city ]");

    // Karnataka is likewise unaffected as of 2026-09-18. Asserted here rather than only in the
    // Karnataka test so that this test -- the one that DEFINES how far the gap reaches -- fails if
    // the Karnataka layers are ever removed, instead of silently widening the hole again.
    EffectiveTemplate karnataka = resolve("KA", "residential");
    String karnatakaHtml =
        new TemplateCompiler()
            .compile(
                karnataka,
                SubmittedDataValidator.validateAndCoerce(
                    karnataka, generateReadyData(), ProjectionMode.GENERATE));
    assertThat(karnatakaHtml)
        .as("Karnataka names a court")
        .contains("exclusive jurisdiction of the courts at Bengaluru.")
        .doesNotContain("[ Jurisdiction city ]");
  }

  @Test
  void witnessesAreAnOptionalSectionOffByDefaultAndRenderAsDataWhenAdded() {
    EffectiveTemplate eff = resolve("IN", "residential");
    assertThat(section(eff, "Witnesses").optional()).isTrue();

    Map<String, Object> data = new LinkedHashMap<>(generateReadyData());
    data.put("witness1Name", "Wit Ness");
    Map<String, Object> coerced =
        SubmittedDataValidator.validateAndCoerce(eff, data, ProjectionMode.GENERATE);

    // Off by default (opt-in like every other optional section): no witness section, no witness
    // data.
    String withoutWitnesses = new TemplateCompiler().compile(eff, coerced);
    assertThat(withoutWitnesses).doesNotContain("<h2>Witnesses</h2>").doesNotContain("Wit Ness");

    // Opted in: the witness renders as printed (escaped) data, with NO eSign anchor (design D3).
    String withWitnesses = new TemplateCompiler().compile(eff, coerced, null, Set.of("Witnesses"));
    assertThat(withWitnesses).contains("<h2>Witnesses</h2>").contains("Wit Ness");
    assertThat(withWitnesses).doesNotContain("esign:witness");
  }

  // --- helpers ---------------------------------------------------------------

  private static EffectiveTemplate resolve(String state, String type) {
    TemplateDefinitionLoader definitionLoader = new TemplateDefinitionLoader();
    LayerPatchLoader patchLoader = new LayerPatchLoader();

    TemplateDefinition base = definitionLoader.loadResource(ROOT + "base.yaml");
    LayerSource.LayerSet.Base baseLayer =
        new LayerSource.LayerSet.Base(
            new LayerRef(LayerKind.BASE, base.meta().dimensions(), base.meta().version(), "base"),
            base);

    List<LayerSource.LayerSet.Patch> patches = new ArrayList<>();
    addIfPresent(patches, patchLoader, LayerKind.TYPE, ROOT + "type-" + type + ".patch.yaml");
    addIfPresent(patches, patchLoader, LayerKind.STATE, ROOT + "state-" + state + ".patch.yaml");
    addIfPresent(
        patches,
        patchLoader,
        LayerKind.STATE_TYPE,
        ROOT + "state_type-" + state + "-" + type + ".patch.yaml");

    LayerSource.LayerSet set = new LayerSource.LayerSet(baseLayer, patches);
    LayerSource source = (s, t) -> set;
    return new TemplateResolver(source).resolve(new Dimensions(state, type));
  }

  private static void addIfPresent(
      List<LayerSource.LayerSet.Patch> patches,
      LayerPatchLoader loader,
      LayerKind expected,
      String path) {
    if (ProductionRentalLayerSetTest.class.getClassLoader().getResource(path) == null) {
      return;
    }
    LayerPatch patch = loader.loadResource(path);
    LayerRef ref = new LayerRef(expected, patch.meta().dimensions(), patch.meta().version(), path);
    patches.add(new LayerSource.LayerSet.Patch(ref, patch));
  }

  private static List<String> sectionTitles(EffectiveTemplate eff) {
    return eff.template().sections().stream().map(Section::title).toList();
  }

  private static List<String> fieldKeys(EffectiveTemplate eff) {
    return eff.template().fields().stream().map(Field::key).toList();
  }

  private static List<String> clauseIds(EffectiveTemplate eff) {
    return eff.template().clauses().stream()
        .filter(Clause.Inline.class::isInstance)
        .map(c -> ((Clause.Inline) c).id())
        .toList();
  }

  private static Field field(EffectiveTemplate eff, String key) {
    return eff.template().fields().stream()
        .filter(f -> f.key().equals(key))
        .findFirst()
        .orElseThrow();
  }

  private static Section section(EffectiveTemplate eff, String title) {
    return eff.template().sections().stream()
        .filter(s -> s.title().equals(title))
        .findFirst()
        .orElseThrow();
  }

  private static FormSection formSection(FormSchema schema, String title) {
    return schema.sections().stream()
        .filter(s -> s.title().equals(title))
        .findFirst()
        .orElseThrow();
  }
}
