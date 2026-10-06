package in.agreementmitra.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The insecure-cookie startup guard through REAL property binding (cookie-session-auth task 5.4):
 * unlike {@link AuthPropertiesTest}, this proves Boot binds {@code auth.cookie.secure} into the
 * record and that the guard's message survives as the startup failure's cause. A context runner,
 * not a {@code @SpringBootTest}, to protect the suite's time budget.
 */
class CookieSecureGuardTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AuthProperties.class)
  static class Props {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Props.class);

  @Test
  void insecureWithAnHttpsCallbackFailsStartupNamingTheSwitch() {
    runner
        .withPropertyValues(
            "auth.cookie.secure=false",
            "auth.google.redirect-uri=http://localhost:8090/api/auth/google/callback",
            "auth.google.spa-callback-uri=https://agreementmitra.in/auth/callback")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .hasMessageContaining("AUTH_COOKIE_SECURE");
            });
  }

  @Test
  void insecureWithHttpUrisStarts() {
    runner
        .withPropertyValues(
            "auth.cookie.secure=false",
            "auth.google.redirect-uri=http://localhost:8090/api/auth/google/callback",
            "auth.google.spa-callback-uri=http://localhost:5173/auth/callback")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(AuthProperties.class).cookie().secure()).isFalse();
            });
  }
}
