package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The single highest-consequence rule in this capability: <b>which address the signed agreement may
 * be emailed to</b> (design D2).
 *
 * <p>The signed PDF names both parties, the property address, the financial terms, and carries a
 * stamp certificate naming both. A mistyped draft-time address would disclose all of that to an
 * uninvolved stranger, irreversibly. So the only acceptable address is one an invitation was issued
 * to <em>and</em> at which that party then completed signing - the invitation plus the completed
 * Aadhaar authentication are what make it evidence rather than a claim.
 *
 * <p>Note what this view structurally cannot express: there is no draft-time address on it at all,
 * so there is nothing for a fallback to fall back to.
 */
class RecipientResolutionTest {

  private static SigningCompletionView.Party party(String invitedEmail, InviteeStatus status) {
    return new SigningCompletionView.Party(UUID.randomUUID(), invitedEmail, status);
  }

  @Test
  void theInvitedAddressIsUsedOnceThatPartyHasSigned() {
    assertThat(party("asha@example.com", InviteeStatus.SIGNED).verifiedEmail())
        .contains("asha@example.com");
  }

  @Test
  void anInvitedAddressAtWhichNobodySignedIsNotVerified() {
    // Invited but never exercised. An invitation alone proves nothing about who reads that mailbox.
    for (InviteeStatus notSigned :
        List.of(InviteeStatus.PENDING, InviteeStatus.REJECTED, InviteeStatus.EXPIRED)) {
      assertThat(party("asha@example.com", notSigned).verifiedEmail())
          .as("status %s must not yield a verified address", notSigned)
          .isEmpty();
    }
  }

  @Test
  void aPartyWithNoInvitedAddressIsUnresolvableRatherThanGuessedAt() {
    // A row from before invited addresses were recorded, or a correlation that never landed. The
    // honest outcome is "we do not know", which escalates - never a substituted draft address.
    assertThat(party(null, InviteeStatus.SIGNED).verifiedEmail()).isEmpty();
    assertThat(party("   ", InviteeStatus.SIGNED).verifiedEmail()).isEmpty();
  }

  @Test
  void aViewIsDeliverableOnlyWhenSignedAndTheArtifactIsStored() {
    UUID requestId = UUID.randomUUID();
    UUID agreementId = UUID.randomUUID();
    List<SigningCompletionView.Party> parties =
        List.of(party("asha@example.com", InviteeStatus.SIGNED));

    assertThat(
            new SigningCompletionView(
                    requestId, agreementId, SignatureStatus.SIGNED, "signed/x.pdf", parties)
                .deliverable())
        .isTrue();
    // Signed but the download has not landed yet: there is nothing to attach, so nothing is sent.
    assertThat(
            new SigningCompletionView(requestId, agreementId, SignatureStatus.SIGNED, null, parties)
                .deliverable())
        .isFalse();
    assertThat(
            new SigningCompletionView(
                    requestId, agreementId, SignatureStatus.SIGN_REQUESTED, null, parties)
                .deliverable())
        .isFalse();
  }

  @Test
  void neitherTheViewNorAPartyRendersAnAddress() {
    // An accidental log.info("{}", view) must not leak a party's mailbox.
    SigningCompletionView.Party one = party("asha@example.com", InviteeStatus.SIGNED);
    assertThat(one.toString()).doesNotContain("asha@example.com");
    assertThat(
            new SigningCompletionView(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    SignatureStatus.SIGNED,
                    "signed/x.pdf",
                    List.of(one))
                .toString())
        .doesNotContain("asha@example.com");
  }
}
