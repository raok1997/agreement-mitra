package in.agreementmitra.identity.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import in.agreementmitra.identity.AuthProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Exchanges a Google authorization {@code code} for tokens at Google's token endpoint, server-side.
 * Sends the client id + client secret (env-sourced) and the PKCE {@code code_verifier}; returns the
 * raw ID token for validation. The {@code code}, the client secret, and the returned tokens are
 * used only here and are NEVER logged or returned to the SPA.
 *
 * <p>The token URI is configurable so integration tests point it at a stubbed Google with no live
 * call. A non-2xx or missing {@code id_token} surfaces as {@link InvalidLoginException} (no token
 * or body echoed).
 */
@Component
class GoogleTokenExchange {

  private final RestClient restClient;
  private final AuthProperties.Google google;

  GoogleTokenExchange(AuthProperties properties) {
    this.restClient = RestClient.create();
    this.google = properties.google();
  }

  /** Exchange the code for tokens and return the raw ID token. Never logs the code or tokens. */
  String exchangeForIdToken(String code, String codeVerifier) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("code", code);
    form.add("redirect_uri", google.redirectUri());
    form.add("client_id", google.clientId());
    form.add("client_secret", google.clientSecret());
    form.add("code_verifier", codeVerifier);

    TokenResponse response;
    try {
      response =
          restClient
              .post()
              .uri(google.tokenUri())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(TokenResponse.class);
    } catch (RestClientException e) {
      // Network/non-2xx from Google. Do not include the response body (may echo the code).
      throw new InvalidLoginException("Google token exchange failed", e);
    }

    if (response == null || response.idToken() == null || response.idToken().isBlank()) {
      throw new InvalidLoginException("Google token response carried no id_token");
    }
    return response.idToken();
  }

  /** Minimal projection of Google's token response -- we only need the ID token. */
  record TokenResponse(@JsonProperty("id_token") String idToken) {}
}
