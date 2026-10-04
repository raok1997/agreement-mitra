package in.agreementmitra;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The request-body ceiling for every unsafe API request (anonymous-surface-abuse-controls D9).
 *
 * <p>A servlet filter ordered <b>ahead of</b> the security chain, so an oversized body is refused
 * before any session lookup or CSRF check reads from it. It applies {@code maxBytes} to every
 * {@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE} under {@code /api/} except the two
 * multipart upload routes, which keep their own {@code spring.servlet.multipart} ceilings (10 MB
 * file, 11 MB request). The ceiling is independent of field validation: a field constrained only as
 * non-blank accepts a body of any size.
 *
 * <p>Enforced twice, because a declared length is optional. A declared over-limit {@code
 * Content-Length} is refused here at once with {@code 413} problem+json. A body sent without one
 * (chunked) is read through a counting stream that throws {@link RequestBodyTooLargeException} once
 * it passes the ceiling; Spring wraps that read failure in {@code HttpMessageNotReadableException},
 * and {@link GlobalExceptionHandler} finds it in the cause chain and answers the same {@code 413}.
 * Nothing is persisted on either path, since the handler never receives a body.
 */
final class RequestBodyGuard extends OncePerRequestFilter {

  static final String TYPE = "urn:agreementmitra:problem:payload-too-large";

  static final String TITLE = "Payload too large";

  static final String DETAIL = "The request body exceeds the maximum allowed size.";

  private static final String BODY =
      "{\"type\":\""
          + TYPE
          + "\",\"title\":\""
          + TITLE
          + "\",\"status\":413,\"detail\":\""
          + DETAIL
          + "\",\"instance\":\""
          + TYPE
          + "\"}";

  private static final Set<String> UNSAFE_METHODS =
      Set.of(
          HttpMethod.POST.name(),
          HttpMethod.PUT.name(),
          HttpMethod.PATCH.name(),
          HttpMethod.DELETE.name());

  /** POST routes carrying a multipart upload, bounded by their own multipart ceilings. */
  private static final List<PathPattern> UPLOAD_ROUTES =
      List.of(
          PathPatternParser.defaultInstance.parse("/api/agreements/{id}/draft"),
          PathPatternParser.defaultInstance.parse("/api/staff/estamp"));

  private final long maxBytes;

  RequestBodyGuard(long maxBytes) {
    this.maxBytes = maxBytes;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!UNSAFE_METHODS.contains(request.getMethod())) {
      return true;
    }
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (!path.startsWith("/api/")) {
      return true;
    }
    if (!HttpMethod.POST.matches(request.getMethod())) {
      return false;
    }
    PathContainer container = PathContainer.parsePath(path);
    return UPLOAD_ROUTES.stream().anyMatch(route -> route.matches(container));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > maxBytes) {
      response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(BODY);
      return;
    }
    chain.doFilter(new BoundedRequest(request, maxBytes), response);
  }

  /** Thrown from a body read once the bytes read pass the ceiling. */
  static final class RequestBodyTooLargeException extends IOException {

    RequestBodyTooLargeException() {
      super("request body exceeds the configured ceiling");
    }
  }

  private static final class BoundedRequest extends HttpServletRequestWrapper {

    private final long maxBytes;
    private ServletInputStream stream;

    BoundedRequest(HttpServletRequest request, long maxBytes) {
      super(request);
      this.maxBytes = maxBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (stream == null) {
        stream = new BoundedInputStream(super.getInputStream(), maxBytes);
      }
      return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
      String encoding = getCharacterEncoding();
      Charset charset;
      try {
        charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
      } catch (IllegalArgumentException unsupported) {
        // What the container's own getReader() does for an unknown charset: a read failure.
        throw new java.io.UnsupportedEncodingException("unsupported request charset");
      }
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }

  private static final class BoundedInputStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final long maxBytes;
    private long read;

    BoundedInputStream(ServletInputStream delegate, long maxBytes) {
      this.delegate = delegate;
      this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
      int b = delegate.read();
      if (b != -1) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = delegate.read(buffer, offset, length);
      if (n > 0) {
        count(n);
      }
      return n;
    }

    private void count(int n) throws RequestBodyTooLargeException {
      read += n;
      if (read > maxBytes) {
        throw new RequestBodyTooLargeException();
      }
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      delegate.setReadListener(listener);
    }
  }
}
