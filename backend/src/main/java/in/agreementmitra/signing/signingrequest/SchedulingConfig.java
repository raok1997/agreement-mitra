package in.agreementmitra.signing.signingrequest;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduling support for the signing module's reconciliation job. Module-scoped
 * (kept in the signing-request package); the only scheduled bean is {@link
 * SigningReconciliationJob} (itself conditional on {@code signing.reconciliation.enabled}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {}
