package in.agreementmitra.signing.signingrequest;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduling support for every {@code @Scheduled} job in the application. Kept in
 * the signing-request package because {@link SigningReconciliationJob} was the first; the payment
 * reconciliation, delivery retry, draft retention and staff alert dispatch jobs rely on it too.
 * Each job is conditional on its own property, and each needs its own thread in {@code
 * spring.task.scheduling.pool.size}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {}
