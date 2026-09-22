package in.agreementmitra.signing;

import in.agreementmitra.documents.api.DocumentProjectionApi;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.support.HarnessTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the Spring Modulith slice-test <em>mechanism</em> wires up: the {@code signing} module
 * bootstraps in isolation against the harness, without loading unrelated modules. This is a boot
 * smoke only — there is no behavioral assertion.
 *
 * <p>The {@code signing} module depends on the {@code documents} module's public {@link
 * DocumentProjectionApi} interface (for agreement rendering). In a standalone module slice that
 * collaborator module is not loaded, so its bean is supplied as a {@link MockitoBean} — the
 * Modulith-idiomatic way to isolate a slice from another module.
 *
 * <p>{@code disabledWithoutDocker = true} makes this skip (not fail) without a Docker daemon.
 */
@ApplicationModuleTest
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SigningModuleSliceTest {

  @MockitoBean private DocumentProjectionApi documentProjection;

  // The signing module also consumes the documents module's TemplateCatalogApi (agreement rendering
  // resolves template metadata through it); in a standalone slice that module is not loaded, so its
  // bean is mocked too -- same isolation idiom as DocumentProjectionApi above.
  @MockitoBean private TemplateCatalogApi templateCatalog;

  // Stamp intake re-renders the deed with the certificate duty only for a still-current template,
  // whose content hash it reads through TemplateFormApi (stamp-duty-amount-from-certificate).
  @MockitoBean private in.agreementmitra.documents.api.TemplateFormApi templateForms;

  // Stamp quoting (state-stamp-duty-quoting) consumes the rules module's StampDutyCalculator; that
  // module is not loaded in a standalone slice either, so its public interface is mocked the same
  // way.
  @MockitoBean private in.agreementmitra.rules.StampDutyCalculator stampDutyCalculator;

  @Test
  void signingModuleBootsInIsolation() {
    // Context start = the slice mechanism is wired (documents' DocumentProjectionApi mocked).
  }
}
