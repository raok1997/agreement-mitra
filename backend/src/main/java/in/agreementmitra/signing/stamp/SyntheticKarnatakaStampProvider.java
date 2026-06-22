package in.agreementmitra.signing.stamp;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * v1 {@link StampProvider}: a <b>synthetic</b> Karnataka ₹100 non-judicial e-stamp. No real duty is
 * paid ({@code dutyPaid = false}) and no external service or credential is used — sandbox/dummy
 * data only. The serial is derived deterministically from the agreement id ({@link StampSerials});
 * the stamp is composited onto the draft by {@link PdfStampComposer}. A real SHCIL / state-portal
 * adapter (with actual duty payment) would replace this behind the same interface.
 */
@Component
class SyntheticKarnatakaStampProvider implements StampProvider {

  private static final String JURISDICTION = "KA";
  private static final int DENOMINATION_RUPEES = 100;

  private final PdfStampComposer composer;

  SyntheticKarnatakaStampProvider(PdfStampComposer composer) {
    this.composer = composer;
  }

  @Override
  public StampResult procure(UUID agreementId, byte[] draftPdf) {
    String serial = StampSerials.forAgreement(agreementId);
    byte[] stampedPdf = composer.compose(draftPdf, serial);
    return new StampResult(serial, JURISDICTION, DENOMINATION_RUPEES, false, stampedPdf);
  }
}
