package in.agreementmitra;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The route-class table (anonymous-surface-abuse-controls D4): (method, {@link PathPattern})
 * entries written with {@code {id}}, each supplying the class, the {@code route} label security
 * events carry, and -- through {@code matchAndExtract} -- the agreement id wherever it sits in the
 * path. MVC's best-matching pattern does not exist yet where the limiter runs, so this pattern is
 * the only label available; a request in the default class is labelled {@value #DEFAULT_ROUTE},
 * never with its URI, which carries the id.
 *
 * <p>Where two entries for one method overlap ({@code /api/templates/{id}} and {@code
 * /api/templates/form}) the more specific wins, deterministically. A test enumerates every
 * registered {@code /api} endpoint against this table, so a new endpoint fails the build until it
 * is classified or consciously left to the default class.
 */
final class RouteClassifier {

  static final String DEFAULT_ROUTE = "default";

  /** One table row. */
  record Entry(HttpMethod method, PathPattern pattern, RouteClass routeClass) {}

  /**
   * A classified request.
   *
   * @param routeClass the class whose budget applies
   * @param route the label events carry: the entry's pattern, or {@value #DEFAULT_ROUTE}
   * @param resource the canonical agreement id for a per-resource class, or null when the class is
   *     source-only or the {@code {id}} did not parse as a UUID
   */
  record Classification(RouteClass routeClass, String route, String resource) {}

  static final List<Entry> TABLE =
      List.of(
          entry(HttpMethod.POST, "/api/templates/document/preview", RouteClass.RENDER),
          entry(HttpMethod.GET, "/api/agreements/{id}/preview", RouteClass.RENDER),
          entry(HttpMethod.POST, "/api/agreements/{id}/document", RouteClass.RENDER),
          entry(HttpMethod.POST, "/api/agreements", RouteClass.ANONYMOUS_WRITE),
          entry(HttpMethod.GET, "/api/auth/google/start", RouteClass.AUTH),
          entry(HttpMethod.GET, "/api/auth/google/callback", RouteClass.AUTH),
          entry(HttpMethod.POST, "/api/auth/session/exchange", RouteClass.AUTH),
          entry(HttpMethod.PATCH, "/api/agreements/{id}/contacts", RouteClass.CAPABILITY_WRITE),
          entry(HttpMethod.POST, "/api/agreements/{id}/finalise", RouteClass.CAPABILITY_WRITE),
          entry(HttpMethod.POST, "/api/agreements/{id}/payment/order", RouteClass.CAPABILITY_WRITE),
          entry(
              HttpMethod.POST,
              "/api/agreements/{id}/payment/callback",
              RouteClass.CAPABILITY_WRITE),
          entry(HttpMethod.POST, "/api/agreements/{id}/draft", RouteClass.CAPABILITY_WRITE),
          entry(HttpMethod.GET, "/api/agreements/{id}", RouteClass.CAPABILITY_READ),
          entry(HttpMethod.GET, "/api/agreements/{id}/payment", RouteClass.CAPABILITY_READ),
          entry(HttpMethod.GET, "/api/agreements/{id}/stamp-quote", RouteClass.CAPABILITY_READ),
          entry(HttpMethod.GET, "/api/agreements/{id}/signed-document", RouteClass.CAPABILITY_READ),
          entry(HttpMethod.GET, "/api/signing/{id}/progress", RouteClass.CAPABILITY_READ),
          entry(HttpMethod.GET, "/api/templates", RouteClass.BOOTSTRAP),
          entry(HttpMethod.GET, "/api/templates/{id}", RouteClass.BOOTSTRAP),
          entry(HttpMethod.GET, "/api/templates/form", RouteClass.BOOTSTRAP),
          entry(HttpMethod.GET, "/api/jurisdictions", RouteClass.BOOTSTRAP),
          entry(HttpMethod.GET, "/api/auth/csrf", RouteClass.BOOTSTRAP),
          // Logout must never be refused by a lockout: a 429 leaves the HttpOnly session cookie
          // live on a shared machine. High ceiling, no lockout, like the CSRF bootstrap.
          entry(HttpMethod.POST, "/api/auth/logout", RouteClass.BOOTSTRAP),
          entry(HttpMethod.GET, "/actuator/health", RouteClass.NOT_LIMITED),
          entry(HttpMethod.GET, "/error", RouteClass.NOT_LIMITED),
          entry(HttpMethod.POST, "/error", RouteClass.NOT_LIMITED),
          entry(HttpMethod.POST, "/api/agreements/recovery", RouteClass.EXCLUDED),
          entry(HttpMethod.POST, "/api/webhooks/esign", RouteClass.EXCLUDED),
          entry(HttpMethod.POST, "/api/webhooks/razorpay", RouteClass.EXCLUDED));

  /** Most specific first, so overlapping entries resolve the same way every time. */
  private static final List<Entry> BY_SPECIFICITY = sortedBySpecificity(TABLE);

  private RouteClassifier() {}

  /** The stateless preview route, whose HTML variant is {@link RouteClass#LIVE_PREVIEW}. */
  private static final String STATELESS_PREVIEW = "/api/templates/document/preview";

  /**
   * As {@link #classify(String, String)}, telling the stateless preview's HTML variant (the live
   * pane, no render) apart from its PDF variant by the same {@code Accept} test the controller
   * uses.
   */
  static Classification classify(String method, String path, String accept) {
    Classification classification = classify(method, path);
    if (classification.routeClass() == RouteClass.RENDER
        && STATELESS_PREVIEW.equals(classification.route())
        && accept != null
        && accept.toLowerCase(Locale.ROOT).contains("text/html")) {
      return new Classification(RouteClass.LIVE_PREVIEW, STATELESS_PREVIEW + " (html)", null);
    }
    return classification;
  }

  /** The class, label and resource of a request. Never null: unlisted routes are the default. */
  static Classification classify(String method, String path) {
    PathContainer container = PathContainer.parsePath(path);
    for (Entry entry : BY_SPECIFICITY) {
      if (!entry.method().matches(method)) {
        continue;
      }
      PathPattern.PathMatchInfo match = entry.pattern().matchAndExtract(container);
      if (match != null) {
        return new Classification(
            entry.routeClass(),
            entry.pattern().getPatternString(),
            perResource(entry.routeClass())
                ? canonicalId(match.getUriVariables().get("id"))
                : null);
      }
    }
    return new Classification(RouteClass.DEFAULT, DEFAULT_ROUTE, null);
  }

  private static boolean perResource(RouteClass routeClass) {
    return routeClass == RouteClass.CAPABILITY_READ || routeClass == RouteClass.CAPABILITY_WRITE;
  }

  /**
   * The id parsed as a UUID and re-printed canonically, so case, leading zeros, or encoding cannot
   * mint a fresh resource bucket. A value that does not parse gets none (source only).
   */
  static String canonicalId(String raw) {
    if (raw == null || raw.length() > 36) {
      return null;
    }
    try {
      return UUID.fromString(raw).toString().toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException notAnId) {
      return null;
    }
  }

  private static Entry entry(HttpMethod method, String pattern, RouteClass routeClass) {
    return new Entry(method, PathPatternParser.defaultInstance.parse(pattern), routeClass);
  }

  private static List<Entry> sortedBySpecificity(List<Entry> entries) {
    List<Entry> sorted = new ArrayList<>(entries);
    sorted.sort(Comparator.comparing(Entry::pattern, PathPattern.SPECIFICITY_COMPARATOR));
    return List.copyOf(sorted);
  }
}
