package in.agreementmitra.documents.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.agreementmitra.ResourceNotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test of {@link TemplateFormController}'s HTTP contract in isolation (the {@link
 * TemplateFormApi} port is mocked): the strong {@code ETag} = content hash, {@code 304} on a
 * matching {@code If-None-Match}, the {@code 404} no-echo path for unknown dimensions, and the
 * required-parameter {@code 400}. Security filters are disabled here ({@code addFilters = false});
 * the public-read permit is exercised end-to-end by {@code TemplateFormApiIntegrationTest}.
 */
@WebMvcTest(TemplateFormController.class)
@AutoConfigureMockMvc(addFilters = false)
class TemplateFormControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private TemplateFormApi templateForm;

  private static FormSchema schema(String contentHash) {
    return new FormSchema(
        new FormSchema.Dimensions("TG", "residential"),
        "rental-base",
        1,
        contentHash,
        List.of(
            new FormSection(
                "Financial",
                List.of(
                    new FormField(
                        "monthlyRent",
                        "Monthly rent",
                        "money",
                        "money",
                        true,
                        null,
                        null,
                        null,
                        new FormField.Validation(0L, null, null, null, null),
                        null)),
                false,
                "keyvalue")));
  }

  @Test
  void returns200WithStrongEtagEqualToContentHash() throws Exception {
    when(templateForm.formFor("TG", "residential")).thenReturn(schema("abc123"));

    mockMvc
        .perform(get("/api/templates/form").param("state", "TG").param("type", "residential"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ETAG, "\"abc123\""))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
        .andExpect(jsonPath("$.contentHash").value("abc123"))
        .andExpect(jsonPath("$.sections[0].title").value("Financial"))
        .andExpect(jsonPath("$.sections[0].fields[0].widget").value("money"));
  }

  @Test
  void returns304WhenIfNoneMatchMatchesTheContentHash() throws Exception {
    when(templateForm.formFor("TG", "residential")).thenReturn(schema("abc123"));

    mockMvc
        .perform(
            get("/api/templates/form")
                .param("state", "TG")
                .param("type", "residential")
                .header(HttpHeaders.IF_NONE_MATCH, "\"abc123\""))
        .andExpect(status().isNotModified())
        .andExpect(header().string(HttpHeaders.ETAG, "\"abc123\""));
  }

  @Test
  void returns404WithoutEchoingDimensionsForUnknownSelection() throws Exception {
    when(templateForm.formFor("XX", "spaceship"))
        .thenThrow(new ResourceNotFoundException("no template for the requested dimensions"));

    mockMvc
        .perform(get("/api/templates/form").param("state", "XX").param("type", "spaceship"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(not(containsString("XX"))))
        .andExpect(content().string(not(containsString("spaceship"))));
  }

  @Test
  void returns400WhenARequiredDimensionIsMissing() throws Exception {
    mockMvc
        .perform(get("/api/templates/form").param("state", "TG"))
        .andExpect(status().isBadRequest());
  }
}
