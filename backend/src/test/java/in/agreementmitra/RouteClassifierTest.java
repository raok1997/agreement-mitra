package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.RouteClassifier.Classification;
import in.agreementmitra.RouteClassifier.Entry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;

/**
 * The route-class table (anonymous-surface-abuse-controls tasks 10.4, 10.5). The enumeration of
 * every registered endpoint against this table lives in {@code AbuseLimitsIntegrationTest}, which
 * has the handler mapping.
 */
class RouteClassifierTest {

  private static final String ID = "3f2c1b9e-8d4a-4c1e-9f0a-1b2c3d4e5f60";

  private static RouteClass classOf(String method, String path) {
    return RouteClassifier.classify(method, path).routeClass();
  }

  @Test
  void eachDesignRouteMapsToItsClass() {
    assertThat(classOf("POST", "/api/templates/document/preview")).isEqualTo(RouteClass.RENDER);
    assertThat(classOf("GET", "/api/agreements/" + ID + "/preview")).isEqualTo(RouteClass.RENDER);
    assertThat(classOf("POST", "/api/agreements/" + ID + "/document")).isEqualTo(RouteClass.RENDER);
    assertThat(classOf("POST", "/api/agreements")).isEqualTo(RouteClass.ANONYMOUS_WRITE);
    assertThat(classOf("GET", "/api/auth/google/start")).isEqualTo(RouteClass.AUTH);
    assertThat(classOf("GET", "/api/auth/google/callback")).isEqualTo(RouteClass.AUTH);
    assertThat(classOf("POST", "/api/auth/session/exchange")).isEqualTo(RouteClass.AUTH);
    // Logout is never locked out (a refused logout leaves the session live on a shared machine).
    assertThat(classOf("POST", "/api/auth/logout")).isEqualTo(RouteClass.BOOTSTRAP);
    for (String write :
        new String[] {"/finalise", "/payment/order", "/payment/callback", "/draft"}) {
      assertThat(classOf("POST", "/api/agreements/" + ID + write))
          .as(write)
          .isEqualTo(RouteClass.CAPABILITY_WRITE);
    }
    assertThat(classOf("PATCH", "/api/agreements/" + ID + "/contacts"))
        .isEqualTo(RouteClass.CAPABILITY_WRITE);
    for (String read : new String[] {"", "/payment", "/stamp-quote", "/signed-document"}) {
      assertThat(classOf("GET", "/api/agreements/" + ID + read))
          .as(read)
          .isEqualTo(RouteClass.CAPABILITY_READ);
    }
    assertThat(classOf("GET", "/api/signing/" + ID + "/progress"))
        .isEqualTo(RouteClass.CAPABILITY_READ);
  }

  @Test
  void theStatelessPreviewsHtmlVariantIsTheLivePreviewClassAndItsPdfVariantIsRender() {
    String preview = "/api/templates/document/preview";
    assertThat(RouteClassifier.classify("POST", preview, "text/html").routeClass())
        .isEqualTo(RouteClass.LIVE_PREVIEW);
    assertThat(RouteClassifier.classify("POST", preview, "application/pdf").routeClass())
        .isEqualTo(RouteClass.RENDER);
    assertThat(RouteClassifier.classify("POST", preview, null).routeClass())
        .isEqualTo(RouteClass.RENDER);
    // Only that route: an HTML Accept on a real render route changes nothing.
    assertThat(
            RouteClassifier.classify("GET", "/api/agreements/" + ID + "/preview", "text/html")
                .routeClass())
        .isEqualTo(RouteClass.RENDER);
  }

  @Test
  void theBootstrapRoutesCarryTheHighCeilingClass() {
    for (String path :
        new String[] {
          "/api/templates",
          "/api/templates/form",
          "/api/templates/" + ID,
          "/api/jurisdictions",
          "/api/auth/csrf"
        }) {
      assertThat(classOf("GET", path)).as(path).isEqualTo(RouteClass.BOOTSTRAP);
    }
  }

  @Test
  void recoveryAndBothWebhooksAreExcludedAndHealthIsNotLimited() {
    assertThat(classOf("POST", "/api/agreements/recovery")).isEqualTo(RouteClass.EXCLUDED);
    assertThat(classOf("POST", "/api/webhooks/esign")).isEqualTo(RouteClass.EXCLUDED);
    assertThat(classOf("POST", "/api/webhooks/razorpay")).isEqualTo(RouteClass.EXCLUDED);
    assertThat(classOf("GET", "/actuator/health")).isEqualTo(RouteClass.NOT_LIMITED);
    assertThat(RouteClass.EXCLUDED.limited()).isFalse();
    assertThat(RouteClass.NOT_LIMITED.limited()).isFalse();
  }

  @Test
  void anUnlistedRouteIsTheLimitedDefaultClassLabelledDefault() {
    Classification staff = RouteClassifier.classify("POST", "/api/staff/payments/" + ID + "/waive");
    assertThat(staff.routeClass()).isEqualTo(RouteClass.DEFAULT);
    assertThat(staff.route()).isEqualTo("default").doesNotContain(ID);
    assertThat(staff.resource()).isNull();
    assertThat(RouteClass.DEFAULT.limited()).isTrue();
    // A method the table does not list for a known path is also the default class.
    assertThat(classOf("DELETE", "/api/agreements/" + ID)).isEqualTo(RouteClass.DEFAULT);
  }

  @Test
  void noTwoEntriesForOneMethodOverlapWithoutADeterministicWinner() {
    // Two entries may both match one request only when specificity ranks them (e.g.
    // /api/templates/form over /api/templates/{id}); entries the comparator cannot rank must never
    // match the same path.
    List<Entry> table = RouteClassifier.TABLE;
    for (int i = 0; i < table.size(); i++) {
      for (int j = i + 1; j < table.size(); j++) {
        Entry a = table.get(i);
        Entry b = table.get(j);
        if (!a.method().equals(b.method())) {
          continue;
        }
        assertThat(a.pattern().getPatternString())
            .as("duplicate entry")
            .isNotEqualTo(b.pattern().getPatternString());
        if (PathPattern.SPECIFICITY_COMPARATOR.compare(a.pattern(), b.pattern()) == 0) {
          assertThat(a.pattern().matches(PathContainer.parsePath(sample(b))))
              .as(a.pattern() + " overlaps " + b.pattern())
              .isFalse();
          assertThat(b.pattern().matches(PathContainer.parsePath(sample(a))))
              .as(b.pattern() + " overlaps " + a.pattern())
              .isFalse();
        }
      }
    }
    assertThat(RouteClassifier.classify("GET", "/api/templates/form").route())
        .isEqualTo("/api/templates/form");
    assertThat(RouteClassifier.classify("GET", "/api/templates/" + ID).route())
        .isEqualTo("/api/templates/{id}");
  }

  private static String sample(Entry entry) {
    return entry.pattern().getPatternString().replace("{id}", ID);
  }

  @Test
  void theSameIdInDifferentLetterCaseIsOneResourceBucket() {
    String lower = RouteClassifier.classify("GET", "/api/agreements/" + ID).resource();
    String upper =
        RouteClassifier.classify("GET", "/api/agreements/" + ID.toUpperCase()).resource();
    assertThat(lower).isEqualTo(upper).isEqualTo(ID);
  }

  @Test
  void anUnparseableIdGetsNoResourceBucket() {
    Classification c = RouteClassifier.classify("GET", "/api/agreements/not-an-id/payment");
    assertThat(c.routeClass()).isEqualTo(RouteClass.CAPABILITY_READ);
    assertThat(c.resource()).isNull();
  }

  @Test
  void theIdIsExtractedWhereverItSitsInThePath() {
    assertThat(RouteClassifier.classify("GET", "/api/signing/" + ID + "/progress").resource())
        .isEqualTo(ID);
    assertThat(RouteClassifier.classify("GET", "/api/agreements/" + ID + "/payment").resource())
        .isEqualTo(ID);
    // Source-only classes carry no resource even when the path names an agreement.
    assertThat(RouteClassifier.classify("GET", "/api/agreements/" + ID + "/preview").resource())
        .isNull();
  }
}
