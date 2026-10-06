package in.agreementmitra;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the requester a per-source control is keyed on (anonymous-surface-abuse-controls D2).
 *
 * <p>Reads {@link HttpServletRequest#getRemoteAddr()} and nothing else. Behind the reverse proxy
 * that is the client address Tomcat's {@code RemoteIpValve} took from {@code X-Forwarded-For} --
 * which it honours only when the immediate peer is the trusted proxy ({@code
 * server.tomcat.remoteip.internal-proxies}), and which the proxy overwrites rather than appends to.
 * From any other peer the header is ignored and the peer is the source. No other client-sent header
 * ({@code X-Real-IP}, {@code True-Client-IP}, {@code Forwarded}) is ever read here: a limit keyed
 * on a value the caller chooses is not a limit.
 *
 * <p>Only IP <b>literals</b> are parsed, validated by shape before {@link InetAddress} sees them,
 * so no value can trigger a DNS lookup. An IPv6 source is keyed on its network prefix ({@code /64}
 * by default): one host typically controls a whole {@code /64}, and per-address keys would hand it
 * 2^64 buckets. Anything unparseable falls back to one shared bucket, never to no key.
 */
@Component
public class ClientSourceResolver {

  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

  /** An IPv6 literal: hex groups, colons, and an optional embedded IPv4 tail. No zone id. */
  private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

  private static final int IPV4_REDACTED_PREFIX = 24;
  private static final int IPV6_REDACTED_PREFIX = 48;

  private final int ipv6PrefixLength;

  public ClientSourceResolver(
      @Value("${abuse.source.ipv6-prefix-length:64}") int ipv6PrefixLength) {
    if (ipv6PrefixLength < IPV6_REDACTED_PREFIX || ipv6PrefixLength > 128) {
      throw new IllegalArgumentException(
          "abuse.source.ipv6-prefix-length must be between 48 and 128");
    }
    this.ipv6PrefixLength = ipv6PrefixLength;
  }

  /** The source of {@code request}. Never null. */
  public ClientSource resolve(HttpServletRequest request) {
    return resolve(request.getRemoteAddr());
  }

  /** The source for a resolved remote address. Package-private for unit tests. */
  ClientSource resolve(String remoteAddress) {
    InetAddress address = parseLiteral(remoteAddress);
    if (address == null) {
      return ClientSource.unresolved();
    }
    if (address instanceof Inet6Address) {
      return new ClientSource(
          network(address, ipv6PrefixLength) + "/" + ipv6PrefixLength,
          network(address, IPV6_REDACTED_PREFIX) + "/" + IPV6_REDACTED_PREFIX);
    }
    return new ClientSource(
        address.getHostAddress(),
        network(address, IPV4_REDACTED_PREFIX) + "/" + IPV4_REDACTED_PREFIX);
  }

  /** Parses an IP literal, or returns null. Never performs a lookup. */
  private static InetAddress parseLiteral(String value) {
    if (value == null || value.isEmpty() || value.length() > 45) {
      return null;
    }
    try {
      if (IPV4.matcher(value).matches()) {
        // Octets parsed here and handed over as bytes: getByAddress never resolves anything, and an
        // out-of-range octet ("999.1.1.1") is rejected rather than passed on as a hostname.
        String[] parts = value.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
          int octet = Integer.parseInt(parts[i]);
          if (octet > 255) {
            return null;
          }
          bytes[i] = (byte) octet;
        }
        return InetAddress.getByAddress(bytes);
      }
      // A colon makes getByName treat the value as an IPv6 literal: malformed input throws, it is
      // never looked up as a hostname.
      return IPV6.matcher(value).matches() ? InetAddress.getByName(value) : null;
    } catch (UnknownHostException | SecurityException | NumberFormatException e) {
      return null;
    }
  }

  private static String network(InetAddress address, int prefixLength) {
    byte[] bytes = address.getAddress();
    for (int bit = prefixLength; bit < bytes.length * 8; bit++) {
      bytes[bit / 8] &= (byte) ~(0x80 >>> (bit % 8));
    }
    try {
      return InetAddress.getByAddress(bytes).getHostAddress();
    } catch (UnknownHostException e) {
      throw new IllegalStateException("a masked address has a valid length", e);
    }
  }
}
