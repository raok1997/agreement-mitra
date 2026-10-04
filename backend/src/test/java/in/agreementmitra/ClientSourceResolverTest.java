package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Unit tests for client-source resolution (anonymous-surface-abuse-controls task 10.3). */
class ClientSourceResolverTest {

  private final ClientSourceResolver resolver = new ClientSourceResolver(64);

  @Test
  void anIpv4SourceIsKeyedOnTheAddressAndRedactedToItsSlash24() {
    ClientSource source = resolver.resolve("203.0.113.77");
    assertThat(source.key()).isEqualTo("203.0.113.77");
    assertThat(source.redacted()).isEqualTo("203.0.113.0/24");
  }

  @Test
  void ipv6AddressesInOneSlash64YieldOneKey() {
    ClientSource a = resolver.resolve("2001:db8:1:2:aaaa::1");
    ClientSource b = resolver.resolve("2001:db8:1:2:ffff:ffff:ffff:ffff");
    assertThat(a.key()).isEqualTo(b.key()).isEqualTo("2001:db8:1:2:0:0:0:0/64");
    assertThat(resolver.resolve("2001:db8:1:3::1").key()).isNotEqualTo(a.key());
    assertThat(a.redacted()).isEqualTo("2001:db8:1:0:0:0:0:0/48");
  }

  @Test
  void anUnresolvableOrNonLiteralAddressFallsBackToTheSharedPeerBucket() {
    for (String value :
        new String[] {
          null, "", "localhost", "example.com", "fe80::zz", "999", "999.1.1.1", "1.2.3.256"
        }) {
      assertThat(resolver.resolve(value).key()).as(String.valueOf(value)).isEqualTo("unresolved");
    }
  }

  @Test
  void itReadsTheResolvedRemoteAddressAndNoClientHeader() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.4");
    request.addHeader("X-Forwarded-For", "203.0.113.9");
    request.addHeader("X-Real-IP", "203.0.113.10");
    request.addHeader("Forwarded", "for=203.0.113.11");
    assertThat(resolver.resolve(request).key()).isEqualTo("198.51.100.4");
  }

  @Test
  void thePrefixLengthIsConfigurableWithinBounds() {
    assertThat(new ClientSourceResolver(56).resolve("2001:db8:1:2ff::1").key())
        .isEqualTo("2001:db8:1:200:0:0:0:0/56");
    assertThatThrownBy(() -> new ClientSourceResolver(32))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
