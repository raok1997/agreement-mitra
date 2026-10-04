package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.AbuseLimitsProperties.ClassLimits;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The refusal shape and the lockout events of the route-class filter
 * (anonymous-surface-abuse-controls tasks 10.6, 10.9).
 */
class RouteRateLimitFilterTest {

  private static final String EXISTING = "3f2c1b9e-8d4a-4c1e-9f0a-1b2c3d4e5f60";
  private static final String MISSING = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

  private final SlidingWindowRateLimiterTest.TestClock clock =
      new SlidingWindowRateLimiterTest.TestClock();

  private RouteRateLimitFilter filter(boolean enabled, int perSource) {
    Map<RouteClass, ClassLimits> classes = new EnumMap<>(RouteClass.class);
    for (RouteClass c : RouteClass.values()) {
      if (!c.limited()) {
        continue;
      }
      boolean perResource = c == RouteClass.CAPABILITY_READ || c == RouteClass.CAPABILITY_WRITE;
      classes.put(
          c,
          new ClassLimits(
              perSource,
              perResource ? perSource * 2 : null,
              Duration.ofMinutes(1),
              c == RouteClass.BOOTSTRAP || c == RouteClass.LIVE_PREVIEW
                  ? Duration.ZERO
                  : Duration.ofMinutes(5)));
    }
    return new RouteRateLimitFilter(
        new AbuseLimitsProperties(enabled, 1000, classes),
        new SlidingWindowRateLimiter(Duration.ofMinutes(6), 1000, clock, Runnable::run),
        new ClientSourceResolver(64),
        new SecurityEvents(clock, Runnable::run));
  }

  private static MockHttpServletResponse call(
      RouteRateLimitFilter filter, String method, String uri) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setRemoteAddr("203.0.113.77");
    request.setContent("{\"secret\":\"body\"}".getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test
  void aRefusalIs429ProblemJsonWithRetryAfterAndEchoesNoPathOrBody() throws Exception {
    RouteRateLimitFilter filter = filter(true, 1);
    assertThat(call(filter, "GET", "/api/agreements/" + EXISTING).getStatus()).isEqualTo(200);
    MockHttpServletResponse refused = call(filter, "GET", "/api/agreements/" + EXISTING);

    assertThat(refused.getStatus()).isEqualTo(429);
    assertThat(refused.getContentType()).startsWith("application/problem+json");
    assertThat(refused.getHeader("Retry-After")).isEqualTo("300");
    assertThat(refused.getContentAsString())
        .contains("\"type\":\"urn:agreementmitra:problem:rate-limited\"")
        .contains("\"instance\":\"urn:agreementmitra:problem:rate-limited\"")
        .doesNotContain(EXISTING)
        .doesNotContain("/api/")
        .doesNotContain("secret");
  }

  @Test
  void aRefusalIsIdenticalForAnExistingAndANonExistentResource() throws Exception {
    RouteRateLimitFilter filter = filter(true, 1);
    call(filter, "GET", "/api/agreements/" + EXISTING);
    MockHttpServletResponse forExisting = call(filter, "GET", "/api/agreements/" + EXISTING);
    // Same source, same class: refused whichever agreement it now names.
    MockHttpServletResponse forMissing = call(filter, "GET", "/api/agreements/" + MISSING);

    assertThat(forMissing.getStatus()).isEqualTo(forExisting.getStatus()).isEqualTo(429);
    assertThat(forMissing.getContentAsString()).isEqualTo(forExisting.getContentAsString());
    assertThat(forMissing.getHeader("Retry-After")).isEqualTo(forExisting.getHeader("Retry-After"));
  }

  @Test
  void anExcludedRouteIsNeverRefused() throws Exception {
    RouteRateLimitFilter filter = filter(true, 1);
    for (int i = 0; i < 5; i++) {
      assertThat(call(filter, "POST", "/api/webhooks/razorpay").getStatus()).isEqualTo(200);
      assertThat(call(filter, "POST", "/api/webhooks/esign").getStatus()).isEqualTo(200);
      assertThat(call(filter, "POST", "/api/agreements/recovery").getStatus()).isEqualTo(200);
    }
  }

  @Test
  void anUnclassifiedRouteIsLimitedAsTheDefaultClass() throws Exception {
    RouteRateLimitFilter filter = filter(true, 2);
    call(filter, "GET", "/api/auth/me");
    call(filter, "GET", "/api/auth/me");
    assertThat(call(filter, "GET", "/api/auth/me").getStatus()).isEqualTo(429);
  }

  @Test
  void theBootstrapClassRefusesWithinItsWindowButNeverLocksOut() throws Exception {
    RouteRateLimitFilter filter = filter(true, 2);
    try (SecurityEventCapture capture = new SecurityEventCapture()) {
      call(filter, "GET", "/api/auth/csrf");
      call(filter, "GET", "/api/auth/csrf");
      MockHttpServletResponse refused = call(filter, "GET", "/api/auth/csrf");
      assertThat(refused.getStatus()).isEqualTo(429);
      assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isLessThanOrEqualTo(60);
      assertThat(capture.lines()).isEmpty();
    }
    clock.advance(Duration.ofMinutes(1));
    assertThat(call(filter, "GET", "/api/auth/csrf").getStatus()).isEqualTo(200);
  }

  @Test
  void theLivePreviewRefusesWithinItsWindowButNeverLocksOut() throws Exception {
    RouteRateLimitFilter filter = filter(true, 2);
    try (SecurityEventCapture capture = new SecurityEventCapture()) {
      for (int i = 0; i < 10; i++) {
        livePreview(filter);
      }
      MockHttpServletResponse refused = livePreview(filter);
      assertThat(refused.getStatus()).isEqualTo(429);
      assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isLessThanOrEqualTo(60);
      assertThat(capture.lines()).isEmpty(); // no lockout began
    }
    clock.advance(Duration.ofMinutes(1));
    assertThat(livePreview(filter).getStatus()).isEqualTo(200);
  }

  private static MockHttpServletResponse livePreview(RouteRateLimitFilter filter) throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/templates/document/preview");
    request.setRemoteAddr("203.0.113.77");
    request.addHeader("Accept", "text/html");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test
  void aLockoutEmitsExactlyOneEventHoweverManyRequestsItRefuses() throws Exception {
    RouteRateLimitFilter filter = filter(true, 2);
    try (SecurityEventCapture capture = new SecurityEventCapture()) {
      for (int i = 0; i < 20; i++) {
        call(filter, "POST", "/api/templates/document/preview");
      }
      assertThat(capture.lines()).singleElement().asString().contains("class=render");
    }
  }

  @Test
  void aDefaultClassLockoutOnAnIdBearingPathLogsTheDefaultLabelAndNoId() throws Exception {
    RouteRateLimitFilter filter = filter(true, 1);
    try (SecurityEventCapture capture = new SecurityEventCapture()) {
      for (int i = 0; i < 3; i++) {
        call(filter, "POST", "/api/staff/payments/" + EXISTING + "/waive");
      }
      String line = capture.lines().get(0);
      assertThat(line).contains("route=default").contains("class=default");
      assertThat(SecurityEventsTest.UUID_SHAPED.matcher(line).find()).isFalse();
    }
  }

  @Test
  void aCapabilityLockoutLogsThePatternNeverThePath() throws Exception {
    RouteRateLimitFilter filter = filter(true, 1);
    try (SecurityEventCapture capture = new SecurityEventCapture()) {
      for (int i = 0; i < 3; i++) {
        call(filter, "POST", "/api/agreements/" + EXISTING.toUpperCase() + "/finalise");
      }
      assertThat(capture.lines()).isNotEmpty();
      for (String line : capture.lines()) {
        assertThat(line).contains("route=/api/agreements/{id}/finalise");
        assertThat(SecurityEventsTest.UUID_SHAPED.matcher(line).find()).isFalse();
      }
    }
  }

  @Test
  void disabledLimitsPassEverything() throws Exception {
    RouteRateLimitFilter filter = filter(false, 1);
    for (int i = 0; i < 5; i++) {
      assertThat(call(filter, "POST", "/api/agreements").getStatus()).isEqualTo(200);
    }
  }
}
