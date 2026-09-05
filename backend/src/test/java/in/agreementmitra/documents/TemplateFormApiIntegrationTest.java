package in.agreementmitra.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.support.HarnessTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-pipeline integration test for {@code GET /api/templates/form} against the real reference
 * layer set: the HTTP surface, the security permit, the resolver, the projector, and JSON
 * serialization. Resolves {@code (TG, residential)} end-to-end, asserting the projected FormSchema,
 * the strong {@code ETag} = effective-template content hash, the conditional {@code 304}, and that
 * a different contributing-layer set yields a different {@code ETag} (stale schema not served).
 * {@code disabledWithoutDocker = true} makes it skip (not fail) without a Docker daemon, like the
 * rest of the suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class TemplateFormApiIntegrationTest {

  @Autowired private TestRestTemplate rest;

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void resolvesProjectsAndSerializesTheReferenceForm() throws Exception {
    ResponseEntity<String> resp =
        rest.getForEntity("/api/templates/form?state=TG&type=residential", String.class);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = mapper.readTree(resp.getBody());

    assertThat(body.path("dimensions").path("state").asText()).isEqualTo("TG");
    assertThat(body.path("dimensions").path("type").asText()).isEqualTo("residential");
    assertThat(body.path("templateId").asText()).isEqualTo("rental-base");
    assertThat(body.path("version").asInt()).isEqualTo(1);

    // Sections in composed order (state_type layer reorders to Parties, Financial, Term,
    // Statutory).
    assertThat(sectionTitles(body)).containsExactly("Parties", "Financial", "Term", "Statutory");

    // A clause-id entry (e.g. "parties") is not a field; only field-key entries are projected.
    assertThat(widget(body, "Parties", "ownerName")).isEqualTo("text");
    assertThat(widget(body, "Financial", "monthlyRent")).isEqualTo("money");
    assertThat(widget(body, "Term", "durationMonths")).isEqualTo("number");
    assertThat(widget(body, "Term", "furnished")).isEqualTo("checkbox");
    assertThat(widget(body, "Statutory", "stampDuty")).isEqualTo("money");
    assertThat(field(body, "Parties", "parties")).isNull();

    // Validation metadata is carried (durationMonths: 1..60 from the base).
    JsonNode duration = field(body, "Term", "durationMonths");
    assertThat(duration.path("validation").path("min").asInt()).isEqualTo(1);
    assertThat(duration.path("validation").path("max").asInt()).isEqualTo(60);

    // Strong ETag equals the effective-template content hash carried in the body.
    String etag = resp.getHeaders().getETag();
    assertThat(etag).isEqualTo("\"" + body.path("contentHash").asText() + "\"");
    assertThat(resp.getHeaders().getCacheControl()).contains("public");
  }

  @Test
  void servedSectionsCarryOptionalFlagAndRenderKindAndNoFieldLessSection() throws Exception {
    // The literal HTTP surface: every served reference section is mandatory (optional == false) and
    // carries a renderKind (the examples layers declare no explicit render, so M0's default token
    // "keyvalue" is projected verbatim). No served section is field-less -- the omission rule never
    // leaks an empty capture card.
    ResponseEntity<String> resp =
        rest.getForEntity("/api/templates/form?state=TG&type=residential", String.class);
    JsonNode body = mapper.readTree(resp.getBody());

    for (JsonNode section : body.path("sections")) {
      assertThat(section.hasNonNull("optional")).as("optional present").isTrue();
      assertThat(section.path("optional").asBoolean())
          .as("reference sections are mandatory")
          .isFalse();
      assertThat(section.path("renderKind").asText()).isEqualTo("keyvalue");
      assertThat(section.path("fields")).as("no field-less section is served").isNotEmpty();
    }
  }

  @Test
  void conditionalGetWithMatchingIfNoneMatchReturns304() {
    ResponseEntity<String> first =
        rest.getForEntity("/api/templates/form?state=TG&type=residential", String.class);
    String etag = first.getHeaders().getETag();

    HttpHeaders headers = new HttpHeaders();
    headers.setIfNoneMatch(etag);
    ResponseEntity<String> second =
        rest.exchange(
            "/api/templates/form?state=TG&type=residential",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(second.getHeaders().getETag()).isEqualTo(etag);
  }

  @Test
  void differentContributingLayerSetYieldsADifferentEtag() {
    // (IN, residential) resolves to base + type-residential; (TG, residential) adds the state and
    // state+type layers -- different content, so the pin (ETag) must differ. A cached (IN) schema
    // is
    // never served for (TG).
    String etagIn =
        rest.getForEntity("/api/templates/form?state=IN&type=residential", String.class)
            .getHeaders()
            .getETag();
    String etagTg =
        rest.getForEntity("/api/templates/form?state=TG&type=residential", String.class)
            .getHeaders()
            .getETag();

    assertThat(etagIn).isNotBlank();
    assertThat(etagTg).isNotBlank();
    assertThat(etagTg).isNotEqualTo(etagIn);
  }

  // --- helpers --------------------------------------------------------------

  private static java.util.List<String> sectionTitles(JsonNode body) {
    java.util.List<String> titles = new java.util.ArrayList<>();
    body.path("sections").forEach(section -> titles.add(section.path("title").asText()));
    return titles;
  }

  private static JsonNode field(JsonNode body, String sectionTitle, String fieldKey) {
    for (JsonNode section : body.path("sections")) {
      if (section.path("title").asText().equals(sectionTitle)) {
        for (JsonNode field : section.path("fields")) {
          if (field.path("key").asText().equals(fieldKey)) {
            return field;
          }
        }
      }
    }
    return null;
  }

  private static String widget(JsonNode body, String sectionTitle, String fieldKey) {
    JsonNode field = field(body, sectionTitle, fieldKey);
    return field == null ? null : field.path("widget").asText();
  }
}
