/**
 * Signing module: agreements, signing requests, the status state machine,
 * webhook intake, and the {@link in.agreementmitra.signing.EsignProvider}
 * abstraction with its vendor adapters. The heart of the application.
 *
 * <p>Only types in this package and {@code .api} are the module's public API.
 * The {@code .leegality} adapter package is internal.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Signing")
package in.agreementmitra.signing;
