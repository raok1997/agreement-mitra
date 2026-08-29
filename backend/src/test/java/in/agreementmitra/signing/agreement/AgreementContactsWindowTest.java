package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.agreementmitra.ConflictException;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.signing.ClosureReason;
import in.agreementmitra.signing.PaymentConfirmation;
import in.agreementmitra.signing.SigningRequestQuery;
import in.agreementmitra.signing.api.ContactsUpdateRequest;
import in.agreementmitra.signing.api.ContactsUpdateRequest.PartyContact;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The window in which party contacts may be changed - a plain unit test, mocked repository, no
 * Spring and no database.
 *
 * <p><b>What this pins down.</b> Contacts are editable until PAYMENT settles, not until the order
 * is placed. The two used to be the same line, and sharing it cost a customer whose first payment
 * failed any route back: the retry re-enters the contact step, whose save was refused from the
 * moment the order existed, so the pay button was permanently unreachable. It also meant a mistyped
 * address could never be corrected. Contacts are not terms - they do not appear in the rendered
 * agreement - so the freeze that protects the document should never have covered them.
 *
 * <p>The load-bearing assertion is therefore the NEGATIVE one: placing an order must NOT freeze
 * contacts. Everything else here guards the boundaries either side of that.
 */
@ExtendWith(MockitoExtension.class)
class AgreementContactsWindowTest {

  @Mock private AgreementRepository repository;
  @Mock private TemplateCatalogApi templateCatalog;
  @Mock private SigningRequestQuery signingRequestQuery;

  @InjectMocks private AgreementService service;

  @Test
  void contactsAreEditableOnAnUnpaidAgreementEvenAfterTheOrderIsPlaced() {
    Agreement agreement = aFinalisedAgreement();
    UUID signerId = agreement.signers().get(0).id();
    whenLoaded(agreement);
    when(repository.save(any(Agreement.class))).thenAnswer(inv -> inv.getArgument(0));

    service.updateContacts(agreement.getId(), null, contacts(signerId, "corrected@example.com"));

    assertThat(agreement.signers().get(0).email()).isEqualTo("corrected@example.com");
    // The order existing is not the question any more, so the signing module is never consulted.
    verifyNoInteractions(signingRequestQuery);
  }

  @Test
  void changingAContactLeavesTheDocumentPinnedAndTheTermsAlone() {
    Agreement agreement = aFinalisedAgreement();
    agreement.attachDraft("drafts/pinned.pdf");
    UUID signerId = agreement.signers().get(0).id();
    whenLoaded(agreement);
    when(repository.save(any(Agreement.class))).thenAnswer(inv -> inv.getArgument(0));

    service.updateContacts(agreement.getId(), null, contacts(signerId, "corrected@example.com"));

    // Contacts are not terms: the document the parties were shown must be the one they keep.
    assertThat(agreement.draftPdfKey()).isEqualTo("drafts/pinned.pdf");
    assertThat(agreement.monthlyRent()).isEqualByComparingTo("18000.00");
    assertThat(agreement.propertyAddress()).isEqualTo("9 Park Street, Kolkata");
    assertThat(agreement.signers()).hasSize(2); // never introduces or drops a party
  }

  @Test
  void contactsAreFrozenOncePaid() {
    Agreement agreement = aFinalisedAgreement();
    UUID signerId = agreement.signers().get(0).id();
    agreement.recordPayment(
        new PaymentConfirmation(
            agreement.getId(), new BigDecimal("499.00"), "INR", "pay_x", Instant.now()),
        null);
    whenLoaded(agreement);

    assertThatThrownBy(
            () ->
                service.updateContacts(
                    agreement.getId(), null, contacts(signerId, "toolate@example.com")))
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((ConflictException) e).kind())
        .isEqualTo(ConflictException.Kind.CONTACTS_FROZEN);

    assertThat(agreement.signers().get(0).email()).isEqualTo("asha@example.com");
    verify(repository, never()).save(any());
  }

  @Test
  void contactsAreFrozenOnceWaived() {
    // A waiver is staff asserting the money question is settled; fulfilment proceeds from there
    // exactly as if paid, so the same freeze has to apply - otherwise the widest editing window
    // would sit on the least-supervised path.
    Agreement agreement = aFinalisedAgreement();
    UUID signerId = agreement.signers().get(0).id();
    agreement.waivePayment(UUID.randomUUID(), Instant.now());
    whenLoaded(agreement);

    assertThatThrownBy(
            () ->
                service.updateContacts(
                    agreement.getId(), null, contacts(signerId, "toolate@example.com")))
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((ConflictException) e).kind())
        .isEqualTo(ConflictException.Kind.CONTACTS_FROZEN);
    verify(repository, never()).save(any());
  }

  @Test
  void contactsAreFrozenOnAClosedAgreement() {
    // Checked EXPLICITLY now. The old signing-request gate refused a closed agreement only as a
    // side effect, so dropping it without this check would have opened contacts on an abandoned
    // order. That side effect is exactly what went missing, hence its own test.
    Agreement agreement = aFinalisedAgreement();
    UUID signerId = agreement.signers().get(0).id();
    agreement.close(ClosureReason.ABANDONED_STAMP_FAILED, Instant.now());
    whenLoaded(agreement);

    assertThatThrownBy(
            () ->
                service.updateContacts(
                    agreement.getId(), null, contacts(signerId, "toolate@example.com")))
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((ConflictException) e).kind())
        .isEqualTo(ConflictException.Kind.AGREEMENT_CLOSED);
    verify(repository, never()).save(any());
  }

  @Test
  void aClosedAndPaidAgreementReportsClosedNotPaid() {
    // Ordering matters: closed is terminal whatever the payment state, and reporting "already paid"
    // would send whoever reads it after the wrong fact.
    Agreement agreement = aFinalisedAgreement();
    UUID signerId = agreement.signers().get(0).id();
    agreement.recordPayment(
        new PaymentConfirmation(
            agreement.getId(), new BigDecimal("499.00"), "INR", "pay_y", Instant.now()),
        null);
    agreement.close(ClosureReason.COMPLETED, Instant.now());
    whenLoaded(agreement);

    assertThatThrownBy(
            () ->
                service.updateContacts(
                    agreement.getId(), null, contacts(signerId, "toolate@example.com")))
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((ConflictException) e).kind())
        .isEqualTo(ConflictException.Kind.AGREEMENT_CLOSED);
  }

  private void whenLoaded(Agreement agreement) {
    when(repository.findByIdForUpdate(agreement.getId())).thenReturn(Optional.of(agreement));
  }

  private static ContactsUpdateRequest contacts(UUID signerId, String email) {
    return new ContactsUpdateRequest(List.of(new PartyContact(signerId, email, "")));
  }

  /**
   * An unowned agreement with two parties. "Finalised" is represented by the states the method
   * under test actually reads - it never asks whether a signing request exists, which is the point.
   */
  private static Agreement aFinalisedAgreement() {
    Agreement agreement =
        Agreement.create(
            "9 Park Street, Kolkata",
            new BigDecimal("18000.00"),
            new BigDecimal("36000.00"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 1));
    agreement.addSigner(
        "Asha Rao", "Asha", "Rao", "Ravi Rao", "1 A St", "asha@example.com", null, Role.OWNER);
    agreement.addSigner(
        "Tara Sen", "Tara", "Sen", "Hari Sen", "3 C St", "tara@example.com", null, Role.TENANT);
    return agreement;
  }
}
