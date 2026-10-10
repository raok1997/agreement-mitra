package in.agreementmitra.signing.staffalert;

import in.agreementmitra.signing.agreement.StaffAgreementView;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * Maps the staff view of an agreement to a {@link StaffAlertMessage}. <b>The one place in this
 * package that touches {@link StaffAgreementView}</b>: the view also carries party names, a city
 * and the agreement id, and none of those may reach a third-party channel.
 */
final class StaffAlertMessages {

  private static final Pattern STATE_CODE = Pattern.compile("^[A-Z]{2}$");

  private StaffAlertMessages() {}

  static StaffAlertMessage from(StaffAgreementView view, String publicBaseUrl) {
    return new StaffAlertMessage(
        view.trackingReference(), stateCode(view.templateState()), siteLink(publicBaseUrl));
  }

  /** The template state is a free string in template metadata; only a two-letter code is sent. */
  private static String stateCode(String templateState) {
    return templateState != null && STATE_CODE.matcher(templateState).matches()
        ? templateState
        : null;
  }

  /** The public site, only when it is an absolute https address - never the local http default. */
  private static URI siteLink(String publicBaseUrl) {
    if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
      return null;
    }
    try {
      URI uri = new URI(publicBaseUrl.trim());
      return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? uri : null;
    } catch (URISyntaxException notAUrl) {
      return null;
    }
  }
}
