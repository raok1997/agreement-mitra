package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

/**
 * {@link OperatingEntity} through real binding of the committed {@code application.yml}. The system
 * environment is removed first, so an {@code OPERATOR_*} exported in the developer's shell cannot
 * leak in and the test reads only what the repository carries.
 */
class OperatingEntityBindingTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(
              context ->
                  context
                      .getEnvironment()
                      .getPropertySources()
                      .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(OperatingEntityConfig.class);

  @Test
  void theRepositoryCarriesTheLegalNameAndNoIdentifier() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          OperatingEntity entity = context.getBean(OperatingEntity.class);
          assertThat(entity.legalName()).isEqualTo("KAVISAT TEK LABS LLP");
          assertThat(entity.llpin()).isEmpty();
          assertThat(entity.gstin()).isEmpty();
        });
  }

  @Test
  void theEnvironmentCannotRenameTheOperator() {
    // Relaxed binding would map OPERATOR_LEGAL_NAME here, were there a property to map it to.
    runner
        .withPropertyValues("operator.legal-name=SOMEONE ELSE LLP")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(OperatingEntity.class).legalName())
                  .isEqualTo("KAVISAT TEK LABS LLP");
            });
  }

  @Test
  void aPlaceholderGstinRefusesStartupNamingThePropertyNotTheValue() {
    // BindFailureAnalyzer prints no "Value:" line for a constructor-bound failure, because
    // ValueObjectBinder clears the property before instantiating; so no message in the chain
    // carries the value either.
    runner
        .withPropertyValues("operator.gstin=XXXXXXXXXXXXXXX")
        .run(
            context -> {
              assertThat(context).hasFailed();
              Throwable failure = context.getStartupFailure();
              assertThat(failure).rootCause().hasMessageContaining("operator.gstin");
              for (Throwable t = failure; t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain("XXXXXXXXXXXXXXX");
              }
            });
  }
}
