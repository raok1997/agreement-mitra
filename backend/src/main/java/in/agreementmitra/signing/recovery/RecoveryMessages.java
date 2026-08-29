package in.agreementmitra.signing.recovery;

/**
 * The text of the recovery message.
 *
 * <p><b>Reference, link, and the revocation notice. Nothing else.</b> Unlike the draft message,
 * which exists so the parties read the attached agreement, this one is a permanent credential
 * sitting in a mailbox: it does not expire, and it opens the agreement to whoever holds it. So a
 * forwarded copy, a shared inbox, or a mailbox compromised years later must not itself disclose who
 * the parties are, where the property is, or what the rent is (design D7).
 *
 * <p>Party names, the property address, rent, and deposit are therefore absent by construction, and
 * a test asserts their absence. If a future change wants to make this message friendlier by naming
 * the property, that change is wrong.
 *
 * <p><b>This is why the salutation is neutral</b> and not "Dear &lt;name&gt;" as in the draft
 * covering letter. The two messages differ on purpose: the draft is sent to a party about their own
 * agreement and names them, whereas this one is a bearer credential that may outlive the mailbox it
 * lands in, so it must not identify anybody. The tone is formal in both; only this one is
 * anonymous.
 *
 * <p>The confidentiality line earns its place for the same reason: the link IS the capability, and
 * a reader who does not know that may forward it without a thought.
 */
final class RecoveryMessages {

  private RecoveryMessages() {}

  static String subject(String reference) {
    return "Access link for your rental agreement - " + reference;
  }

  static String body(String reference, String link) {
    return """
        Dear Sir or Madam,

        Please use the link below to open your rental agreement.

        %s

        Reference: %s

        We recommend that you retain this email. The link remains valid until you sign in and \
        save the agreement to an account; from that point the agreement should be opened by \
        signing in instead.

        Please treat this link as confidential. Anyone in possession of it can open the \
        agreement.

        If you did not request this email, no action is required.

        Yours sincerely,
        AgreementMitra
        """
        .formatted(link, reference);
  }
}
