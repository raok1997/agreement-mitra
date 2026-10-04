package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The cross-site refusal ahead of the rate limits (anonymous-surface-abuse-controls D4). */
class CrossSiteRequestGuardTest {

  private final CrossSiteRequestGuard guard = new CrossSiteRequestGuard();

  private MockHttpServletResponse call(String method, String uri, String secFetchSite)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    if (secFetchSite != null) {
      request.addHeader("Sec-Fetch-Site", secFetchSite);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    guard.doFilter(request, response, chain);
    if (chain.getRequest() != null) {
      response.setStatus(200); // reached the rest of the chain
    }
    return response;
  }

  @Test
  void aCrossSiteApiRequestIsRefusedWithAFixedProblemBody() throws Exception {
    MockHttpServletResponse refused = call("GET", "/api/agreements/abc", "cross-site");
    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getContentType()).startsWith("application/problem+json");
    assertThat(refused.getContentAsString())
        .contains("urn:agreementmitra:problem:cross-site")
        .doesNotContain("abc");
  }

  @Test
  void sameOriginSameSiteDirectAndHeaderlessRequestsPass() throws Exception {
    for (String site : new String[] {"same-origin", "same-site", "none", null}) {
      assertThat(call("GET", "/api/agreements/abc", site).getStatus()).as(site).isEqualTo(200);
    }
  }

  @Test
  void theGoogleCallbackAndTheWebhooksAreExempt() throws Exception {
    assertThat(call("GET", "/api/auth/google/callback", "cross-site").getStatus()).isEqualTo(200);
    assertThat(call("POST", "/api/webhooks/esign", "cross-site").getStatus()).isEqualTo(200);
    assertThat(call("POST", "/api/webhooks/razorpay", "cross-site").getStatus()).isEqualTo(200);
    // Exempt by method + path, not by prefix.
    assertThat(call("GET", "/api/auth/google/start", "cross-site").getStatus()).isEqualTo(403);
  }

  @Test
  void nonApiPathsAreUntouched() throws Exception {
    assertThat(call("GET", "/actuator/health", "cross-site").getStatus()).isEqualTo(200);
  }
}
