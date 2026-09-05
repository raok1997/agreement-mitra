package in.agreementmitra.signing;

import java.util.List;

/**
 * Vendor-neutral request to start an eSign for one agreement. Multi-invitee: an agreement is
 * multi-party (owners + tenants), and one provider call creates the request for all of them. No
 * Aadhaar/OTP data lives here — each signer authenticates on the ESP page, not through our API.
 *
 * @param agreementId our internal agreement id
 * @param unsignedPdf bytes of the rendered, unsigned agreement (server-sourced — never a
 *     client-supplied field)
 * @param invitees the signers to invite, in a stable order
 */
public record SignRequest(String agreementId, byte[] unsignedPdf, List<Invitee> invitees) {

  /**
   * Where one signature goes on the instrument.
   *
   * <p>Deliberately expressed as an <b>anchor plus a page scope</b>, never as coordinates: turning
   * this into a position is the adapter's job, because only the adapter knows its provider's origin
   * convention and how large a box that provider draws.
   *
   * @param anchor the stable, non-PII {@code esign:<role>} token the renderer emitted at this
   *     signer's signature zone. Null for a placement that is not located by text — see {@link
   *     #everyPageFooter()}.
   * @param pageScope which pages this placement applies to
   */
  public record Placement(String anchor, PageScope pageScope) {

    /** The signature block on whichever page the anchor turns out to be on. */
    public static Placement anchored(String anchor) {
      return new Placement(anchor, PageScope.ANCHOR_PAGE);
    }

    /**
     * A strip in the bottom margin of every page, including any page prepended at stamp intake.
     *
     * <p>Carries no anchor by design: there is no per-page token, and emitting invisible markers on
     * every page of a legal instrument to get one would be worse than computing a margin offset.
     * The adapter derives the position from the page box and the signer's ordinal, so two parties'
     * strips do not overlap.
     */
    public static Placement everyPageFooter() {
      return new Placement(null, PageScope.ALL_PAGES);
    }
  }

  /** Which pages a {@link Placement} applies to. */
  public enum PageScope {
    /** Only the page the placement's anchor was located on. */
    ANCHOR_PAGE,
    /** Every page of the instrument. */
    ALL_PAGES
  }

  /**
   * One signer to invite to the eSign.
   *
   * @param name display name
   * @param email where to send the signing invite
   * @param phone optional contact number (may be null in sandbox)
   * @param verifyName whether the provider should verify the signer's name against Aadhaar
   * @param placements where this signer signs, in order. <b>The first is the primary</b> (the
   *     signature block); later ones are supplementary. An adapter whose provider supports a single
   *     position SHALL take the first and say so — never drop the rest silently. Empty only for a
   *     signer with no signable zone, which is rejected before the provider call.
   */
  public record Invitee(
      String name, String email, String phone, boolean verifyName, List<Placement> placements) {

    public Invitee {
      placements = placements == null ? List.of() : List.copyOf(placements);
    }

    /** Convenience for callers/tests that do not bind a placement. */
    public Invitee(String name, String email, String phone, boolean verifyName) {
      this(name, email, phone, verifyName, List.of());
    }

    /** Convenience for the common single-anchor case: one anchored block placement. */
    public Invitee(String name, String email, String phone, boolean verifyName, String anchor) {
      this(
          name,
          email,
          phone,
          verifyName,
          anchor == null ? List.of() : List.of(Placement.anchored(anchor)));
    }

    /**
     * The primary placement's anchor, or null when this signer has no anchored placement. For
     * adapters and gates that only need to know "is there a signable zone".
     */
    public String primaryAnchor() {
      return placements.stream()
          .filter(p -> p.anchor() != null)
          .map(Placement::anchor)
          .findFirst()
          .orElse(null);
    }
  }
}
