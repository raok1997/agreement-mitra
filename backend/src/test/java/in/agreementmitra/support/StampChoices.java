package in.agreementmitra.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Checkout bodies for fixtures whose subject is not stamp duty (state-stamp-duty-quoting). Since
 * checkout requires a stamp choice, these read the agreement's real stamp quote and choose an INR
 * 100 stamp value -- the single INR 100 paper when the duty exceeds it (acknowledged), else the
 * recommended option. Either way the published pricing rule charges exactly the base fee (INR 499),
 * so tests about payment mechanics keep their long-standing 49900-paise amounts.
 */
public final class StampChoices {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long INR_100 = 10_000L;

  private StampChoices() {}

  /** The checkout body for an agreement, read with the caller's own headers (auth, if any). */
  public static Map<String, Object> baseFeeChoice(
      TestRestTemplate rest, UUID agreementId, HttpHeaders callerHeaders) {
    ResponseEntity<String> quote =
        rest.exchange(
            "/api/agreements/" + agreementId + "/stamp-quote",
            HttpMethod.GET,
            new HttpEntity<>(callerHeaders == null ? new HttpHeaders() : callerHeaders),
            String.class);
    Map<String, Object> body = new LinkedHashMap<>();
    try {
      JsonNode options = JSON.readTree(quote.getBody()).path("options");
      JsonNode chosen = null;
      for (JsonNode option : options) {
        if (option.path("stampValueMinorUnits").asLong() == INR_100) {
          chosen = option;
        }
      }
      if (chosen == null) {
        for (JsonNode option : options) {
          if (option.path("recommended").asBoolean()) {
            chosen = option;
          }
        }
      }
      if (chosen == null) {
        return body; // not quotable: let checkout report why
      }
      body.put("stampValueMinorUnits", chosen.path("stampValueMinorUnits").asLong());
      if (chosen.path("belowDuty").asBoolean()) {
        body.put(
            "underStampAcknowledgement",
            Map.of(
                "warningVersion", JSON.readTree(quote.getBody()).path("warningVersion").asText()));
      }
    } catch (Exception e) {
      throw new IllegalStateException("could not read the stamp quote in a test fixture", e);
    }
    return body;
  }

  /**
   * A JSON request entity for checkout carrying {@link #baseFeeChoice} and the caller's headers.
   */
  public static HttpEntity<Map<String, Object>> checkoutEntity(
      TestRestTemplate rest, UUID agreementId, HttpHeaders callerHeaders) {
    HttpHeaders headers = new HttpHeaders();
    if (callerHeaders != null) {
      headers.putAll(callerHeaders);
    }
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(baseFeeChoice(rest, agreementId, callerHeaders), headers);
  }
}
