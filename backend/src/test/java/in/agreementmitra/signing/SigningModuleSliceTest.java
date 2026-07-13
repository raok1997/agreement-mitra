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

  @Test
  void signingModuleBootsInIsolation() {
    // Context start = the slice mechanism is wired (documents' DocumentProjectionApi mocked).
  }
}
