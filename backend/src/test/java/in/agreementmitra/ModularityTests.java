package in.agreementmitra;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifies module boundaries: no module reaches into another's internals. Keep this green. It also
 * generates C4-style module docs into build/spring-modulith-docs when {@code writeDocumentation}
 * runs.
 */
class ModularityTests {

  static final ApplicationModules modules = ApplicationModules.of(AgreementMitraApplication.class);

  @Test
  void verifiesModuleBoundaries() {
    modules.verify();
  }

  @Test
  void writeDocumentation() {
    new Documenter(modules).writeDocumentation();
  }
}
