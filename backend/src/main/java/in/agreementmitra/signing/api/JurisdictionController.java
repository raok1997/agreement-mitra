package in.agreementmitra.signing.api;

import in.agreementmitra.signing.agreement.JurisdictionEligibility;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which jurisdictions can be stamped and eSigned, so the SPA can mark the rest as
 * <b>draft-and-download only</b> at the point of template selection - rather than letting a
 * customer fill in an entire agreement and meet the refusal at checkout.
 *
 * <p><b>Disclosure, not enforcement.</b> The authoritative control is the server-side refusal at
 * the four gated steps; this endpoint exists so the limit is visible early. A client that ignores
 * it is still refused.
 *
 * <p><b>Deliberately static and anonymous.</b> It takes no agreement id and returns nothing
 * specific to any agreement, so it can never become an unauthenticated read on agreement data. It
 * discloses only which states we sell in - which the template picker and the published terms both
 * disclose anyway - and it names only what IS eligible, never what is under consideration.
 *
 * <p>It lives in {@code signing}, not on the {@code documents} catalog API: which jurisdictions we
 * can fulfil is a signing/fulfilment policy, not a property of a template, and putting a flag on
 * the catalog would invert the module dependency.
 */
@RestController
@RequestMapping("/api/jurisdictions")
class JurisdictionController {

  private final JurisdictionEligibility eligibility;

  JurisdictionController(JurisdictionEligibility eligibility) {
    this.eligibility = eligibility;
  }

  /**
   * The state codes eligible for paid fulfilment. Sorted so the response is stable rather than
   * dependent on set iteration order.
   */
  @GetMapping
  EligibleJurisdictionsResponse eligible() {
    return new EligibleJurisdictionsResponse(eligibility.eligible().stream().sorted().toList());
  }

  /**
   * @param eligible state codes that can be stamped and eSigned; every other jurisdiction is
   *     draft-and-download only. An empty list is meaningful, not an error: it means nothing is
   *     currently stampable.
   */
  record EligibleJurisdictionsResponse(List<String> eligible) {}
}
