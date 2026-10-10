package in.agreementmitra.signing.staffalert;

import java.net.URI;

/**
 * Everything a staff alert may say, as fields - the adapter renders them. Deliberately nothing
 * else: no agreement id (a bearer credential), no party name, contact, address or amount, and no
 * user-entered text.
 *
 * @param trackingReference the agreement's tracking reference
 * @param stateCode two capital letters, or {@code null} when the template state is not one
 * @param siteLink the public site, or {@code null} when it is not an absolute https address
 */
record StaffAlertMessage(String trackingReference, String stateCode, URI siteLink) {}
