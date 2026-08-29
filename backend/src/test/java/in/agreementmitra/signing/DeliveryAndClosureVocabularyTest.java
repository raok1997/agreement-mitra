package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The two small vocabularies the closure predicate is built from.
 *
 * <p>They are worth pinning because the whole capability leans on them: "closes only when every
 * recipient is {@code SENT}" is just {@code outstanding()}, and "abandoned stays distinguishable
 * from completed" is just {@code abandoned()}. If either drifted, an agreement could close with a
 * party still undelivered, or a failed agreement could be reported as a completed one.
 */
class DeliveryAndClosureVocabularyTest {

  @Test
  void onlyASentDeliveryIsNotOutstanding() {
    assertThat(DeliveryStatus.SENT.outstanding()).isFalse();
    for (DeliveryStatus status : DeliveryStatus.values()) {
      if (status != DeliveryStatus.SENT) {
        assertThat(status.outstanding())
            .as("%s must count as outstanding work, so it blocks closure", status)
            .isTrue();
      }
    }
  }

  @Test
  void onlyAPendingDeliveryIsClaimable() {
    // The claim is what makes delivery exactly-once. A SENT row must never be re-claimable, and an
    // IN_PROGRESS row belongs to somebody else.
    assertThat(DeliveryStatus.PENDING.claimable()).isTrue();
    assertThat(DeliveryStatus.IN_PROGRESS.claimable()).isFalse();
    assertThat(DeliveryStatus.SENT.claimable()).isFalse();
    assertThat(DeliveryStatus.FAILED.claimable()).isFalse();
    assertThat(DeliveryStatus.UNRESOLVABLE.claimable()).isFalse();
  }

  @Test
  void failedAndUnresolvableAreTheStatesAHumanMustLookAt() {
    assertThat(DeliveryStatus.FAILED.needsAttention()).isTrue();
    assertThat(DeliveryStatus.UNRESOLVABLE.needsAttention()).isTrue();
    assertThat(DeliveryStatus.SENT.needsAttention()).isFalse();
    assertThat(DeliveryStatus.PENDING.needsAttention()).isFalse();
  }

  @Test
  void abandonedIsDistinguishableFromCompleted() {
    assertThat(ClosureReason.COMPLETED.abandoned()).isFalse();
    assertThat(ClosureReason.ABANDONED_SIGNING_FAILED.abandoned()).isTrue();
    assertThat(ClosureReason.ABANDONED_SIGNING_EXPIRED.abandoned()).isTrue();
    assertThat(ClosureReason.ABANDONED_STAMP_FAILED.abandoned()).isTrue();
  }

  @Test
  void everyTerminalSigningFailureHasItsOwnAbandonmentReason() {
    // One reason per terminal failure, so a report can say WHY work was abandoned rather than only
    // that it was. Collapsing them would make a rejected signature indistinguishable from a stamp
    // that could not be composited - different problems, different people fix them.
    assertThat(ClosureReason.values()).hasSize(4);
    assertThat(ClosureReason.ABANDONED_SIGNING_FAILED)
        .isNotEqualTo(ClosureReason.ABANDONED_SIGNING_EXPIRED)
        .isNotEqualTo(ClosureReason.ABANDONED_STAMP_FAILED);
  }

  @Test
  void onlyTheSignedAgreementIsEverDeliverable() {
    // The audit trail carries eKYC-derived detail and is deliberately not a deliverable artifact.
    // A second member here would be a decision someone made, not a slip.
    assertThat(DeliveryArtifact.values()).containsExactly(DeliveryArtifact.SIGNED_AGREEMENT);
  }

  @Test
  void anEmailMessageNeverRendersItsRecipientBodyOrAttachmentBytes() {
    EmailMessage message =
        new EmailMessage(
            "asha@example.com",
            "Your signed rental agreement",
            "private body text",
            new EmailAttachment("signed.pdf", "application/pdf", "PDFBYTES".getBytes()));
    assertThat(message.toString())
        .doesNotContain("asha@example.com")
        .doesNotContain("private body text")
        .doesNotContain("PDFBYTES");
    assertThat(message.attachment().toString()).doesNotContain("PDFBYTES");
  }
}
