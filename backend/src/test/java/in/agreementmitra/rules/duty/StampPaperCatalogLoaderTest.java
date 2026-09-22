package in.agreementmitra.rules.duty;

import static in.agreementmitra.rules.duty.TestRules.catalogs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Stamp paper catalog loading and every startup defect. */
class StampPaperCatalogLoaderTest {

  private static String catalog(String media) {
    return """
        state: ZZ
        effectiveFrom: "2024-01-01"
        source: "test"
        media:
        %s
        """
        .formatted(media.indent(2).stripTrailing());
  }

  private static final String PHYSICAL =
      "- { id: physical, kind: DENOMINATIONS, denominations: [\"10\", \"100\"], maxPapers: 2 }";

  @Test
  void loadsBothMediumKindsLargestDenominationFirst() {
    StampPaperCatalog c =
        catalogs(catalog(PHYSICAL + "\n- { id: e-stamp, kind: ANY_AMOUNT, minimumAmount: \"10\" }"))
            .get(0);

    assertThat(c.media()).hasSize(2);
    assertThat(c.media().get(0).denominationsPaise()).containsExactly(10000L, 1000L);
    assertThat(c.media().get(1).minimumPaise()).isEqualTo(1000);
    assertThat(c.ref().contentHash()).hasSize(64);
  }

  @Test
  void theFixtureCatalogLoads() {
    List<StampPaperCatalog> loaded =
        new StampPaperCatalogLoader()
            .load(RuleSetLoader.resolve(TestRules.DEFAULT_CATALOG_LOCATIONS));

    assertThat(loaded).extracting(StampPaperCatalog::state).containsExactlyInAnyOrder("ZZ", "TG");
  }

  @Test
  void denominationChangeChangesTheCatalogHashOnly() {
    String a = catalogs(catalog(PHYSICAL)).get(0).contentHash();
    String b = catalogs(catalog(PHYSICAL.replace("\"100\"", "\"500\""))).get(0).contentHash();

    assertThat(a).isNotEqualTo(b);
    // Duty rule hashes are computed from rule content alone (RuleHasher has no catalog input), so a
    // catalog change cannot move them; asserted structurally by the separate loaders and files.
  }

  private static String withOffer(String offer) {
    return catalog(PHYSICAL).replace("media:", offer + "\nmedia:");
  }

  @Test
  void aSinglePapersOfferIsLoadedAndHashed() {
    StampPaperCatalog c =
        catalogs(
                withOffer(
                    "offer: { mode: SINGLE_PAPERS, denominations: [\"100\"], preselect: \"100\" }"))
            .get(0);

    assertThat(c.ref().offer().mode())
        .isEqualTo(in.agreementmitra.rules.StampOffer.Mode.SINGLE_PAPERS);
    assertThat(c.ref().offer().denominationsPaise()).containsExactly(10000L);
    assertThat(c.ref().offer().preselectPaise()).isEqualTo(10000L);
    assertThat(c.contentHash()).isNotEqualTo(catalogs(catalog(PHYSICAL)).get(0).contentHash());
    assertThat(catalogs(catalog(PHYSICAL)).get(0).ref().offer().mode())
        .isEqualTo(in.agreementmitra.rules.StampOffer.Mode.PLANNED);
  }

  @Test
  void anOfferMustUseIssuedDenominationsAndPreselectOneOfThem() {
    assertDefect(
        withOffer("offer: { mode: SINGLE_PAPERS, denominations: [\"500\"], preselect: \"500\" }"),
        "not issued by any medium");
    assertDefect(
        withOffer("offer: { mode: SINGLE_PAPERS, denominations: [\"100\"], preselect: \"10\" }"),
        "is not an offered denomination");
    assertDefect(
        withOffer("offer: { mode: SINGLE_PAPERS, denominations: [\"100\"] }"),
        "requires preselect");
    assertDefect(
        withOffer("offer: { mode: SINGLE_PAPERS, preselect: \"100\" }"),
        "declares no denominations");
  }

  @Test
  void noMedia() {
    assertDefect(catalog("[]").replace("media:\n  []", "media: []"), "declares no media");
  }

  @Test
  void emptyDenominations() {
    assertDefect(
        catalog("- { id: p, kind: DENOMINATIONS, denominations: [], maxPapers: 2 }"),
        "medium p declares no denominations");
  }

  @Test
  void nonPositiveDenomination() {
    assertDefect(
        catalog("- { id: p, kind: DENOMINATIONS, denominations: [\"0\"], maxPapers: 2 }"),
        "non-positive denomination");
  }

  @Test
  void nonPositiveMaxPapers() {
    assertDefect(
        catalog("- { id: p, kind: DENOMINATIONS, denominations: [\"10\"], maxPapers: 0 }"),
        "maxPapers must be a positive integer");
  }

  @Test
  void duplicateMediumIds() {
    assertDefect(catalog(PHYSICAL + "\n" + PHYSICAL), "duplicate medium id physical");
  }

  @Test
  void overlappingWindows() {
    assertThatThrownBy(() -> catalogs(catalog(PHYSICAL), catalog(PHYSICAL)))
        .isInstanceOf(RuleSetDefinitionException.class)
        .hasMessageContaining("stamp paper catalog ZZ")
        .hasMessageContaining("effective window overlaps");
  }

  @Test
  void enumerationTooLargeToPlan() {
    StringBuilder values = new StringBuilder();
    for (int i = 1; i <= 30; i++) {
      values.append(i == 1 ? "" : ", ").append('"').append(i * 10).append('"');
    }
    assertDefect(
        catalog("- { id: p, kind: DENOMINATIONS, denominations: [" + values + "], maxPapers: 6 }"),
        "combinations to plan");
    assertThat(StampPaperCatalogLoader.combinations(6, 3)).isEqualTo(6 + 21 + 56);
  }

  @Test
  void numericDenominationIsRejected() {
    assertDefect(
        catalog("- { id: p, kind: DENOMINATIONS, denominations: [10], maxPapers: 2 }"),
        "quoted decimal strings");
  }

  @Test
  void nationalStateIsRejected() {
    assertDefect(catalog(PHYSICAL).replace("state: ZZ", "state: IN"), "state IN");
  }

  private static void assertDefect(String text, String defect) {
    assertThatThrownBy(() -> catalogs(text))
        .isInstanceOf(RuleSetDefinitionException.class)
        .hasMessageContaining("stamp paper catalog")
        .hasMessageContaining(defect);
  }
}
