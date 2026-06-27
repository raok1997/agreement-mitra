package in.agreementmitra.signing.leegality;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Leegality adapter configuration, bound from {@code esign.leegality.*}. Secrets ({@code baseUrl},
 * {@code authToken}, {@code webhookSecret}) come from env vars; {@code profileId} is non-secret
 * config (a dashboard-created profile reference). Internal to the signing module.
 *
 * <p>{@code baseUrl} is the host root only (e.g. {@code https://sandbox.leegality.com/api/}); the
 * adapter appends the per-endpoint version (create = {@code v3.0}, details = {@code v3.3}).
 */
@ConfigurationProperties(prefix = "esign.leegality")
record LeegalityProperties(
    String baseUrl, String authToken, String webhookSecret, String profileId) {}
