package in.agreementmitra.signing.api;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.SignatureStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the derived display-status projection (D3) in isolation -- no Spring, no DB. The
 * agreement stays status-less; this maps the (optional) most-recent signing status to what the "My
 * Agreements" list shows, and derives {@code editable} from it.
 */
class AgreementDisplayStatusTest {

  @Test
  void noSigningRequestIsAnEditableDraft() {
    AgreementDisplayStatus status = AgreementDisplayStatus.from(Optional.empty());
    assertThat(status).isEqualTo(AgreementDisplayStatus.DRAFT);
    assertThat(status.editable()).isTrue();
  }

  @Test
  void everyNonTerminalRequestIsAFrozenInProgress() {
    for (SignatureStatus s :
        new SignatureStatus[] {
          SignatureStatus.PDF_GENERATED, SignatureStatus.STAMPED, SignatureStatus.SIGN_REQUESTED
        }) {
      AgreementDisplayStatus status = AgreementDisplayStatus.from(Optional.of(s));
      assertThat(status).as("%s -> IN_PROGRESS", s).isEqualTo(AgreementDisplayStatus.IN_PROGRESS);
      assertThat(status.editable()).as("%s not editable", s).isFalse();
    }
  }

  @Test
  void signedMapsToSignedAndIsNotEditable() {
    AgreementDisplayStatus status =
        AgreementDisplayStatus.from(Optional.of(SignatureStatus.SIGNED));
    assertThat(status).isEqualTo(AgreementDisplayStatus.SIGNED);
    assertThat(status.editable()).isFalse();
  }

  @Test
  void expiredMapsToExpired() {
    assertThat(AgreementDisplayStatus.from(Optional.of(SignatureStatus.EXPIRED)))
        .isEqualTo(AgreementDisplayStatus.EXPIRED);
  }

  @Test
  void failedAndStampFailedMapToActionNeeded() {
    assertThat(AgreementDisplayStatus.from(Optional.of(SignatureStatus.FAILED)))
        .isEqualTo(AgreementDisplayStatus.ACTION_NEEDED);
    assertThat(AgreementDisplayStatus.from(Optional.of(SignatureStatus.STAMP_FAILED)))
        .isEqualTo(AgreementDisplayStatus.ACTION_NEEDED);
  }
}
