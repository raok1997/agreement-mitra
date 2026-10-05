package in.agreementmitra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link OperatingEntity}. Root package: the operator is a fact about the whole service,
 * not about any one module.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperatingEntity.class)
public class OperatingEntityConfig {}
