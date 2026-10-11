package in.agreementmitra.signing.signingrequest;

import org.springframework.web.client.RestClientResponseException;

/**
 * A loggable description of a completion failure that carries no PII: the exception type, the HTTP
 * status when a provider call was answered with an error, and the first frame of our own code on
 * the stack. The exception <b>message is never included</b> - a RestClient message can quote the
 * response body, and our own messages can carry ids.
 */
final class FailureSummary {

  private static final String OWN_PACKAGE = "in.agreementmitra.";

  private FailureSummary() {}

  static String of(Throwable e) {
    StringBuilder summary = new StringBuilder(e.getClass().getSimpleName());
    if (e instanceof RestClientResponseException response) {
      summary.append(" HTTP ").append(response.getStatusCode().value());
    }
    for (StackTraceElement frame : e.getStackTrace()) {
      if (frame.getClassName().startsWith(OWN_PACKAGE)) {
        String className = frame.getClassName();
        summary
            .append(" at ")
            .append(className.substring(className.lastIndexOf('.') + 1))
            .append('.')
            .append(frame.getMethodName())
            .append(':')
            .append(frame.getLineNumber());
        break;
      }
    }
    return summary.toString();
  }
}
