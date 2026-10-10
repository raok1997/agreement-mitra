package in.agreementmitra.signing.staffalert;

import in.agreementmitra.signing.staffalert.StaffAlertDeliveryException.Kind;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Posts staff alerts to a Discord channel through an incoming webhook.
 *
 * <p><b>The webhook URL is a secret, and the token is in its path.</b> Everything here is shaped by
 * keeping it out of logs:
 *
 * <ul>
 *   <li>The {@link URI} is built and checked once, at construction, and a parse failure is
 *       discarded - its message would quote the value.
 *   <li>The request is made with {@code .uri(URI)} and read with {@code exchange}, so no error
 *       handler builds a status exception around the URL.
 *   <li>Spring's I/O-error message embeds the request URL, so {@link #send} catches every {@link
 *       RuntimeException} itself and rethrows only a {@link StaffAlertDeliveryException}, which has
 *       no cause. Only the exception's class name is logged.
 *   <li>The client comes from the static {@code RestClient.builder()}, not the injected one, so no
 *       observation registry sees the URL.
 * </ul>
 *
 * <p>Redirects are never followed: a 3xx would otherwise read as success, and following one would
 * carry the alert somewhere the operator did not configure.
 */
class DiscordStaffNotifier implements StaffNotifier {

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  static final Duration READ_TIMEOUT = Duration.ofSeconds(7);

  /** Discord message flag: do not unfurl links into embeds. */
  private static final int SUPPRESS_EMBEDS = 4;

  /** A literal address only: {@code 127.evil.example} is a remote host that merely starts alike. */
  private static final Pattern LOOPBACK_IPV4 = Pattern.compile("127(\\.\\d{1,3}){3}");

  private static final Logger log = LoggerFactory.getLogger(DiscordStaffNotifier.class);

  /** {@code null} when the configured value is blank or unusable - staff alerts are then off. */
  private final URI endpoint;

  private final RestClient client;

  DiscordStaffNotifier(String webhookUrl, Duration connectTimeout, Duration readTimeout) {
    this.endpoint = usableEndpoint(webhookUrl);
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(readTimeout);
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public boolean configured() {
    return endpoint != null;
  }

  @Override
  public void send(StaffAlertMessage message) {
    if (endpoint == null) {
      throw new StaffAlertDeliveryException(Kind.PERMANENT, StaffAlertDeliveryException.NO_STATUS);
    }
    int status;
    try {
      status =
          client
              .post()
              .uri(endpoint)
              .contentType(MediaType.APPLICATION_JSON)
              .body(body(message))
              .exchange((request, response) -> response.getStatusCode().value());
    } catch (RuntimeException e) {
      // Never the exception itself: its message carries the webhook URL.
      log.warn("Staff alert send failed before a response ({})", e.getClass().getSimpleName());
      throw new StaffAlertDeliveryException(Kind.TRANSIENT, StaffAlertDeliveryException.NO_STATUS);
    }
    if (status >= 200 && status < 300) {
      return;
    }
    Kind kind = status == 429 || status >= 500 ? Kind.TRANSIENT : Kind.PERMANENT;
    throw new StaffAlertDeliveryException(kind, status);
  }

  /** The webhook payload: the text, no mention parsing, no link previews. */
  static Map<String, Object> body(StaffAlertMessage message) {
    return Map.of(
        "content",
        content(message),
        "allowed_mentions",
        Map.of("parse", List.of()),
        "flags",
        SUPPRESS_EMBEDS);
  }

  static String content(StaffAlertMessage message) {
    StringBuilder text = new StringBuilder("Paid order waiting for a stamp: **");
    text.append(message.trackingReference()).append("**");
    if (message.stateCode() != null) {
      text.append(" (").append(message.stateCode()).append(')');
    }
    if (message.siteLink() != null) {
      text.append('\n').append(message.siteLink());
    }
    return text.toString();
  }

  /**
   * The endpoint to post to, or {@code null}. Usable means absolute, with a host, and https - or
   * http to this machine only, which is what a local stub needs and never sends the token anywhere.
   * Without this check a scheme-less value would parse, fail every send as transient and burn every
   * alert's attempts.
   */
  private static URI usableEndpoint(String webhookUrl) {
    if (webhookUrl == null || webhookUrl.isBlank()) {
      return null;
    }
    URI uri = null;
    try {
      uri = new URI(webhookUrl.trim());
    } catch (URISyntaxException discarded) {
      // The exception message quotes the value; it is dropped, not logged.
    }
    if (uri != null && uri.isAbsolute() && uri.getHost() != null) {
      String scheme = uri.getScheme();
      if ("https".equalsIgnoreCase(scheme)
          || ("http".equalsIgnoreCase(scheme) && isLoopback(uri.getHost()))) {
        return uri;
      }
    }
    log.error(
        "The staff alert webhook URL is set but not usable (it must be an absolute https URL);"
            + " staff alerts are off");
    return null;
  }

  /** By name, never by resolving: a DNS lookup of a secret-bearing host is its own leak. */
  private static boolean isLoopback(String host) {
    return "localhost".equalsIgnoreCase(host)
        || LOOPBACK_IPV4.matcher(host).matches()
        || "[::1]".equals(host)
        || "::1".equals(host);
  }
}
