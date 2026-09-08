package in.agreementmitra.signing.signingrequest;

import in.agreementmitra.ConflictException;
import in.agreementmitra.InvalidUploadException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.StampFailedException;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.ClosureReason;
import in.agreementmitra.signing.ClosureState;
import in.agreementmitra.signing.PaymentState;
import in.agreementmitra.signing.SignatureStatus;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.JurisdictionEligibility;
import in.agreementmitra.signing.agreement.StaffAgreementView;
import in.agreementmitra.signing.agreement.StampInfo;
import in.agreementmitra.signing.api.StampIntakeResponse;
import in.agreementmitra.signing.api.StampQueueEntry;
import in.agreementmitra.signing.payment.PaymentGate;
import in.agreementmitra.signing.stamp.CertificateNumbers;
import in.agreementmitra.signing.stamp.CertificateScanValidator;
import in.agreementmitra.signing.stamp.StampCertificate;
import in.agreementmitra.signing.stamp.StampProvider;
import in.agreementmitra.signing.stamp.StampResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Staff-driven e-stamp intake: attach a certificate that a staff member bought on the SHCIL portal,
 * printed, scanned, and uploaded. This service owns the {@code PDF_GENERATED -> STAMPED} (or {@code
 * -> STAMP_FAILED}) transition; the signing flow only reads the result (design D2). Putting the
 * transition next to the event that causes it means a composition failure surfaces to the staff
 * member who can fix it, not to a customer who cannot.
 *
 * <p><b>Order of operations is the design.</b> Everything that can reject the upload runs before
 * anything is written: resolve the agreement, refuse an already-stamped one, validate the scan,
 * confirm a draft exists. Only then is a signing-request row touched, only then is anything
 * composited, and only then do bytes reach object storage. A rejected upload therefore leaves the
 * request exactly where it was, in {@code PDF_GENERATED}, and staff can simply retry.
 *
 * <p><b>The single-use rule is the database's job.</b> The duplicate-certificate 409 comes from
 * catching the unique-index violation, never from a read-then-write check: two staff uploading the
 * same certificate concurrently must not both succeed, because that would spend one purchased stamp
 * on two instruments - a legal defect that stays invisible until challenged.
 *
 * <p><b>Logging.</b> The certificate number appears only redacted (last four characters); no scan
 * bytes and no transcribed metadata value are ever logged.
 */
@Service
public class StampIntakeService {

  private static final Logger log = LoggerFactory.getLogger(StampIntakeService.class);

  private static final String CONTENT_TYPE_PDF = "application/pdf";

  /**
   * Upper bound on one page of the queue. A fulfilment queue that has grown past this is an
   * operations problem, not a paging problem -- but an unbounded query on a staff screen is how a
   * slow morning becomes an outage.
   */
  private static final int QUEUE_LIMIT = 200;

  // Fixed audit outcome tokens. Constants, never derived from input.
  private static final String OUTCOME_ACCEPTED = "ACCEPTED";
  private static final String OUTCOME_UNKNOWN_AGREEMENT = "REJECTED_UNKNOWN_AGREEMENT";
  private static final String OUTCOME_ALREADY_STAMPED = "REJECTED_ALREADY_STAMPED";
  private static final String OUTCOME_INVALID_SCAN = "REJECTED_INVALID_SCAN";
  private static final String OUTCOME_DRAFT_MISSING = "REJECTED_DRAFT_MISSING";
  private static final String OUTCOME_DUPLICATE_CERTIFICATE = "REJECTED_DUPLICATE_CERTIFICATE";
  private static final String OUTCOME_PAYMENT_REQUIRED = "REJECTED_PAYMENT_REQUIRED";
  private static final String OUTCOME_JURISDICTION_UNSUPPORTED =
      "REJECTED_JURISDICTION_UNSUPPORTED";
  private static final String OUTCOME_COMPOSITION_FAILED = "REJECTED_COMPOSITION_FAILED";
  private static final String OUTCOME_ERROR = "REJECTED_ERROR";

  private final AgreementService agreementService;
  private final StampProvider stampProvider;
  private final CertificateScanValidator scanValidator;
  private final SigningRequestPersistence persistence;
  private final BlobStore blobStore;
  private final StampIntakeAuditor auditor;
  private final PaymentGate paymentGate;
  private final JurisdictionEligibility jurisdiction;

  /**
   * The signing flow, used ONLY for the optional kick-off after a stamp is attached. Intake still
   * owns no part of the signing FSM (design D2): it calls the same public entry point a staff
   * member would, after its own work is finished and durable.
   */
  private final SigningRequestService signingRequestService;

  StampIntakeService(
      AgreementService agreementService,
      StampProvider stampProvider,
      CertificateScanValidator scanValidator,
      SigningRequestPersistence persistence,
      BlobStore blobStore,
      StampIntakeAuditor auditor,
      PaymentGate paymentGate,
      JurisdictionEligibility jurisdiction,
      SigningRequestService signingRequestService) {
    this.agreementService = agreementService;
    this.stampProvider = stampProvider;
    this.scanValidator = scanValidator;
    this.persistence = persistence;
    this.blobStore = blobStore;
    this.auditor = auditor;
    this.paymentGate = paymentGate;
    this.jurisdiction = jurisdiction;
    this.signingRequestService = signingRequestService;
  }

  /**
   * Attach the uploaded certificate to the agreement its staff reference names.
   *
   * @throws ResourceNotFoundException if the reference resolves to no agreement (404)
   * @throws InvalidUploadException if the scan is not an acceptable image (400)
   * @throws ConflictException if the agreement already has a stamp, has no draft, or the
   *     certificate number has already been used (409)
   * @throws StampFailedException if composition fails; the request is driven to {@code
   *     STAMP_FAILED} first (422)
   */
  public StampIntakeResponse attach(UUID staffIdentityId, StampIntakeCommand command) {
    String reference = command.agreementReference();
    Optional<StaffAgreementView> resolved =
        agreementService.findByTrackingReference(command.agreementReference());
    if (resolved.isEmpty()) {
      // Unknown, mistyped (check character failed), or a display-only tracking number submitted as
      // if it were a key. All three are the same 404 - the response reveals nothing either way.
      auditor.record(staffIdentityId, null, reference, OUTCOME_UNKNOWN_AGREEMENT);
      throw new ResourceNotFoundException("No agreement for the submitted staff reference");
    }
    StaffAgreementView agreement = resolved.get();
    try {
      return attachResolved(staffIdentityId, reference, agreement, command);
    } catch (ConflictException e) {
      auditor.record(staffIdentityId, agreement.agreementId(), reference, outcomeFor(e));
      throw e;
    } catch (InvalidUploadException e) {
      auditor.record(staffIdentityId, agreement.agreementId(), reference, OUTCOME_INVALID_SCAN);
      throw e;
    } catch (StampFailedException e) {
      auditor.record(
          staffIdentityId, agreement.agreementId(), reference, OUTCOME_COMPOSITION_FAILED);
      throw e;
    } catch (RuntimeException e) {
      auditor.record(staffIdentityId, agreement.agreementId(), reference, OUTCOME_ERROR);
      throw e;
    }
  }

  private StampIntakeResponse attachResolved(
      UUID staffIdentityId,
      String reference,
      StaffAgreementView agreement,
      StampIntakeCommand command) {
    UUID agreementId = agreement.agreementId();

    // (1) The order must already exist and still be awaiting a stamp. The signing request is
    // created
    // when the CUSTOMER finalises, never here: intake ADVANCES PDF_GENERATED -> STAMPED, it does
    // not
    // bring an order into being. An agreement that was never finalised, or that has already moved
    // on, is refused here -- long before any write, so an existing stamp can never be overwritten.
    // (1a) CLOSURE IS TERMINAL (signed-delivery-and-closure CR, design D1). A closed agreement is
    // finished or abandoned, so no further fulfilment action may be taken on it - and stamping one
    // would spend a real, unrecoverable SHCIL certificate on work that is over. Checked here,
    // before the scan is validated, before any blob is written, and before any state changes.
    agreementService.requireOpen(agreementId);

    UUID signingRequestId = requireAwaitingStamp(agreementId);

    // (1b) PAYMENT GATE (design D6). This is the step where REAL MONEY LEAVES - a staff member is
    // about to spend an SHCIL certificate on this instrument, and a purchased certificate cannot be
    // un-bought. So the gate sits HERE, not only before signing, and it is evaluated before the
    // scan is validated, before any blob is written, and before any state changes: a refused upload
    // leaves the request exactly where it was.
    //
    // The gate now ships REQUIRED, so an unpaid order is genuinely refused here (409
    // payment-required, audited as its own outcome). The unpaid entry still APPEARS on the queue -
    // it is deliberately not filtered out, so staff can see what is waiting on money rather than
    // wondering where an order went. The console shows its payment state; a STAFF waiver is the
    // out-of-band escape hatch when money arrives some other way.
    paymentGate.require(agreementId);

    // (1b) JURISDICTION GATE. Independent of the payment gate above, NOT implied by it: a staff
    // waiver sets WAIVED, which satisfies that gate, so "paid" does not imply "fulfillable". Stamp
    // duty is state law, so an agreement without an eligible duty jurisdiction has no state whose
    // duty could have been paid and no defined place this certificate could have been bought.
    // Checked before the scan is stored and before any state changes, so a refusal writes nothing.
    jurisdiction.require(agreementId);

    // (2) The scan is untrusted input: magic bytes, byte ceiling, and DECODED pixel bounds. Rejects
    // with 400 having written nothing and changed no state -- the request stays in PDF_GENERATED
    // and
    // staff simply retry.
    String scanContentType = scanValidator.validate(command.scan());

    // (3) The instrument to stamp. A missing draft is a clean 409 with nothing written.
    String draftKey =
        agreementService.draftPdfKey(agreementId).orElseThrow(ConflictException::draftRequired);
    byte[] draft = blobStore.get(draftKey);

    StampCertificate certificate = certificateFrom(command);
    StampResult result;
    try {
      result = stampProvider.attach(draft, command.scan(), certificate);
    } catch (StampFailedException e) {
      // Accepted for processing, then failed to composite -> the terminal STAMP_FAILED branch. No
      // partial stamped PDF is stored, because nothing has been written yet.
      persistence.markStampFailed(signingRequestId);
      // ...and the agreement closes as ABANDONED. STAMP_FAILED is terminal for signing and there is
      // no signature to speak of, so this order can never complete; leaving it open would leave
      // dead
      // work sitting in the staff queue indistinguishable from work still in progress. Abandoned
      // stays distinguishable from completed in every read - it is a filter, not an eraser.
      agreementService.close(agreementId, ClosureReason.ABANDONED_STAMP_FAILED);
      throw e;
    }

    // (5) Blobs first, then the database (design D9: no transaction spans a network write). The
    // scan
    // is RETAINED as the evidence artifact (D6) -- re-acquiring a purchased certificate is not
    // possible, so the source of the composite must outlive it.
    String scanKey = "estamp-scans/" + agreementId;
    String stampedKey = "stamped/" + agreementId + ".pdf";
    blobStore.put(scanKey, command.scan(), scanContentType);
    blobStore.put(stampedKey, result.stampedPdf(), CONTENT_TYPE_PDF);

    StampInfo info =
        new StampInfo(
            result.certificateNumber(),
            stampedKey,
            scanKey,
            result.dutyAmount(),
            result.jurisdiction(),
            certificate.issueDate(),
            certificate.documentDescription(),
            certificate.purchasedBy(),
            result.dutyPaid(),
            Instant.now());
    try {
      persistence.markStamped(signingRequestId, agreementId, info);
    } catch (DataIntegrityViolationException e) {
      // The unique index on the normalised certificate number fired: this certificate is already
      // spent. The whole transaction rolled back, so the other agreement's stamp is untouched and
      // this one keeps empty stamp info. The response names no agreement.
      throw ConflictException.certificateAlreadyUsed();
    }

    auditor.record(staffIdentityId, agreementId, reference, OUTCOME_ACCEPTED);
    log.debug(
        "e-Stamp attached to agreement {} (certificate {})",
        agreementId,
        CertificateNumbers.redact(result.certificateNumber()));
    StampIntakeResponse attached =
        StampIntakeResponse.stamped(
            agreementId,
            agreement.trackingReference(),
            agreement.propertyCity(),
            agreement.agreementStartDate(),
            CertificateNumbers.redact(result.certificateNumber()));
    if (!command.initiateSigning()) {
      return attached;
    }
    SigningOutcome outcome = startSigning(agreementId);
    return attached.withSigning(outcome.initiated(), outcome.reason());
  }

  /**
   * Start the signing workflow for an agreement whose stamp has just been attached.
   *
   * <p><b>Runs last, and cannot undo what came before.</b> The certificate is spent and the stamped
   * PDF is stored by the time this is reached; a signing failure must leave both exactly where they
   * are, with the request in {@code STAMPED} so it can simply be started again. So every failure is
   * caught here and turned into a reported outcome - never a thrown one, which would present a
   * successful stamp to the operator as a failed upload.
   *
   * <p>The reason returned is a short fixed token. A vendor's own error text can carry a document
   * id, an invitee address, or an entire payload, and none of that may reach a staff screen or a
   * log line.
   */
  private SigningOutcome startSigning(UUID agreementId) {
    try {
      signingRequestService.create(agreementId);
      return new SigningOutcome(true, null);
    } catch (ConflictException e) {
      // A gate refused it - unpaid, unreachable party, already requested. The operator can act on
      // this, so name which gate without quoting anything from the request.
      log.warn("Signing not started after stamp intake: refused by a precondition");
      return new SigningOutcome(false, "PRECONDITION_" + e.kind().name());
    } catch (RuntimeException e) {
      // Provider or transport failure. The stamp stands; signing can be retried.
      log.warn("Signing not started after stamp intake: the request could not be created");
      return new SigningOutcome(false, "PROVIDER_UNAVAILABLE");
    }
  }

  /** Whether signing started, and if not, a fixed non-PII reason token. */
  private record SigningOutcome(boolean initiated, String reason) {}

  /**
   * The signing request this upload may advance, or a 409. Three distinct refusals, because they
   * need three distinct fixes:
   *
   * <ul>
   *   <li>a stamp is already attached -> {@code stampAlreadyAttached} (do not overwrite a spent
   *       certificate);
   *   <li>the request has moved past {@code PDF_GENERATED} -> {@code stampAlreadyAttached} (a
   *       re-upload after a failed composition would corrupt the FSM);
   *   <li>no order exists at all -> {@code orderNotPlaced} (the customer has not finalised; intake
   *       must not conjure an order into being on their behalf).
   * </ul>
   */
  private UUID requireAwaitingStamp(UUID agreementId) {
    boolean alreadyStamped =
        agreementService
            .stampInfo(agreementId)
            .filter(info -> info.certificateNumber() != null || info.stampedPdfKey() != null)
            .isPresent();
    if (alreadyStamped) {
      throw ConflictException.stampAlreadyAttached();
    }
    Optional<SignatureStatus> status = persistence.currentStatus(agreementId);
    if (status.isEmpty()) {
      throw ConflictException.orderNotPlaced();
    }
    if (status.get() != SignatureStatus.PDF_GENERATED) {
      throw ConflictException.stampAlreadyAttached();
    }
    return persistence
        .awaitingStampRequestId(agreementId)
        .orElseThrow(ConflictException::stampAlreadyAttached);
  }

  /**
   * The staff fulfilment queue: every agreement awaiting stamp intake, longest-waiting first. The
   * signing side supplies the ordering and the waiting time; the agreement side supplies the
   * purchasing context - the pinned template's name + state and the parties by role, which is what
   * the vendor's certificate form asks for. Stamped, signed, closed and terminally-failed work is
   * excluded by construction (those requests are in some other state), so the queue only ever shows
   * outstanding work.
   *
   * <p>Rows carry <b>party names</b> and are STAFF-only for that reason; the filter chain enforces
   * it ahead of this code. Nothing on this path may be logged - see {@code StampQueueEntry}.
   *
   * <p>Every row carries its <b>payment state</b>, and unpaid rows are <b>deliberately not filtered
   * out</b> even though the gate now ships {@code REQUIRED} and will refuse them. Hiding them would
   * turn "waiting on payment" into "vanished", which is the harder problem to diagnose: staff need
   * to see that the work exists and why it cannot proceed. The console renders the state, so a
   * blocked row reads as awaiting payment rather than as a broken button.
   */
  public List<StampQueueEntry> awaitingStampQueue() {
    List<SigningRequestPersistence.AwaitingStamp> waiting =
        persistence.awaitingStampQueue(QUEUE_LIMIT);
    List<UUID> agreementIds =
        waiting.stream().map(SigningRequestPersistence.AwaitingStamp::agreementId).toList();
    Map<UUID, StaffAgreementView> views = agreementService.staffViewsByAgreementId(agreementIds);
    // Payment state per row: unpaid orders reach this queue in BOTH modes, so the console has to
    // show which ones nobody has paid for - under REQUIRED they are visible and unstampable.
    Map<UUID, PaymentState> payments = agreementService.paymentStatesByAgreementId(agreementIds);
    // Closed agreements are excluded (signed-delivery-and-closure CR, 4.5): a queue is a list of
    // outstanding work, and a closed agreement - completed or abandoned - has none. Filtered here
    // as well as by construction (a closed order is normally in some non-PDF_GENERATED state
    // anyway), so closure alone is sufficient to take dead work off an operator's screen.
    Map<UUID, ClosureState> closures = agreementService.closureStatesByAgreementId(agreementIds);
    Instant now = Instant.now();
    List<StampQueueEntry> entries = new ArrayList<>(waiting.size());
    for (SigningRequestPersistence.AwaitingStamp row : waiting) {
      StaffAgreementView view = views.get(row.agreementId());
      if (view == null) {
        continue; // the agreement vanished under us; nothing actionable to show
      }
      if (closures.get(row.agreementId()) == ClosureState.CLOSED) {
        continue; // closed: no work outstanding, so it does not belong on a work queue
      }
      entries.add(
          new StampQueueEntry(
              view.agreementId(),
              view.trackingReference(),
              view.templateName(),
              view.templateState(),
              view.parties().stream()
                  .map(
                      party ->
                          new StampQueueEntry.StampPartyEntry(
                              party.role().name(), party.name(), party.fatherName()))
                  .toList(),
              view.propertyCity(),
              view.agreementStartDate(),
              row.awaitingSince(),
              Math.max(0L, Duration.between(row.awaitingSince(), now).toSeconds()),
              payments.getOrDefault(row.agreementId(), PaymentState.UNPAID).name()));
    }
    return entries;
  }

  /**
   * The certificate as it will be stored: the number normalised (trim + collapse whitespace +
   * uppercase) so casing/spacing variants collide on the unique index exactly as they should, and
   * the optional free-text fields blank-normalised to null.
   */
  private static StampCertificate certificateFrom(StampIntakeCommand command) {
    String normalized = CertificateNumbers.normalize(command.certificateNumber());
    if (!CertificateNumbers.isWellFormed(normalized)) {
      throw new InvalidUploadException("certificate number has an unacceptable shape");
    }
    return new StampCertificate(
        normalized,
        command.issueDate(),
        command.dutyAmount(),
        command.jurisdiction().trim(),
        blankToNull(command.documentDescription()),
        blankToNull(command.purchasedBy()));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String outcomeFor(ConflictException e) {
    return switch (e.kind()) {
      case STAMP_ALREADY_ATTACHED -> OUTCOME_ALREADY_STAMPED;
      case CERTIFICATE_ALREADY_USED -> OUTCOME_DUPLICATE_CERTIFICATE;
      case DRAFT_REQUIRED -> OUTCOME_DRAFT_MISSING;
      // Payment is a distinct audit outcome, not "some error": an operator reading the trail must
      // be able to see that the gate stopped this upload, not a bad scan or a spent certificate.
      case PAYMENT_REQUIRED -> OUTCOME_PAYMENT_REQUIRED;
      // Same argument as payment above, and it needs stating because this switch HAS a default and
      // would therefore have swallowed this case silently: an operator reading the trail must see
      // that the jurisdiction gate stopped this upload, not "some error".
      case JURISDICTION_UNSUPPORTED -> OUTCOME_JURISDICTION_UNSUPPORTED;
      default -> OUTCOME_ERROR;
    };
  }
}
