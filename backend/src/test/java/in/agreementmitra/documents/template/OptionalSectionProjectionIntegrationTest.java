package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.documents.DocumentFooterProperties;
import in.agreementmitra.documents.HtmlPdfRenderer;
import in.agreementmitra.documents.api.DocumentDimensions;
import in.agreementmitra.documents.api.DocumentProjectionRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Integration test (task 5.1) over the real {@code sets/optional} layer set on the classpath: via
 * {@link ClasspathLayerSource}, resolve {@code (TG, residential)} end-to-end (base + the state-TG
 * patch that adds an OPTIONAL "Pets" section) and compile the stateless preview twice for the
 * <b>same</b> {@code (dimensions, data)} -- once with "Pets" in {@code activeSections}, once
 * without. Asserts the two compiled documents differ <b>exactly</b> by that section's content: the
 * with-run carries the section's header + its field/clause, the without-run carries none of it, and
 * every other section is byte-for-byte identical. An unknown active-set title leaves the output
 * unchanged.
 *
 * <p>Real resource I/O (classpath layer set), but HTML-only -- no Spring context, no Gotenberg, no
 * Testcontainers -- so it runs regardless of Docker. Guards the M2 section-gating rule end-to-end
 * through the resolver + compiler both projection faces share.
 */
class OptionalSectionProjectionIntegrationTest {

  /** Preview never renders a PDF; this stub satisfies the constructor without any I/O. */
  private static final HtmlPdfRenderer NO_PDF = (html, reference) -> new byte[0];

  private static final Clock FIXED_CLOCK =
      Clock.fixed(
          LocalDate.of(2026, 7, 13).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

  private final DocumentProjectionService service =
      new DocumentProjectionService(
          new TemplateResolver(new ClasspathLayerSource("documents/template/testsets/optional/")),
          new TemplateCompiler(),
          NO_PDF,
          new DocumentFooterProperties("agreementmitra.com", ""),
          FIXED_CLOCK);

  private static Map<String, Object> data() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ownerName", "Asha Rao");
    data.put("tenantName", "Bhaskar Rao");
    data.put("petType", "Cat");
    return data;
  }

  private DocumentProjectionRequest request(List<String> activeSections) {
    return new DocumentProjectionRequest(
        new DocumentDimensions("TG", "residential"), data(), activeSections);
  }

  @Test
  void previewDiffersByExactlyTheActiveOptionalSection() {
    String withPets = service.previewHtml(request(List.of("Pets")));
    String withoutPets = service.previewHtml(request(List.of()));

    // The with-run carries the whole Pets section; the without-run carries none of it.
    assertThat(withPets).contains("<h2>Pets</h2>");
    assertThat(withPets).contains("Permitted pet"); // its field row
    assertThat(withPets).contains("The Tenant may keep one Cat on the premises."); // its clause
    assertThat(withoutPets).doesNotContain("<h2>Pets</h2>");
    assertThat(withoutPets).doesNotContain("Permitted pet");
    assertThat(withoutPets).doesNotContain("may keep one");

    // Every other section is byte-for-byte identical: removing the Pets <section> block from the
    // with-run reproduces the without-run exactly.
    assertThat(removeSection(withPets, "Pets")).isEqualTo(withoutPets);

    // The mandatory Parties section is present in both regardless of the active set.
    assertThat(withPets).contains("<h2>Parties</h2>");
    assertThat(withoutPets).contains("<h2>Parties</h2>");
  }

  @Test
  void anUnknownActiveSetTitleLeavesTheOutputUnchanged() {
    String withUnknown = service.previewHtml(request(List.of("No Such Section")));
    String baseline = service.previewHtml(request(List.of()));

    assertThat(withUnknown).isEqualTo(baseline); // ignored, no error, nothing added
    assertThat(withUnknown).doesNotContain("<h2>Pets</h2>");
  }

  /** Remove the {@code <section>...</section>} block whose {@code <h2>} is {@code title}. */
  private static String removeSection(String html, String title) {
    String marker = "<section>\n<h2>" + title + "</h2>\n";
    int start = html.indexOf(marker);
    if (start < 0) {
      return html;
    }
    String close = "</section>\n";
    int end = html.indexOf(close, start) + close.length();
    return html.substring(0, start) + html.substring(end);
  }
}
