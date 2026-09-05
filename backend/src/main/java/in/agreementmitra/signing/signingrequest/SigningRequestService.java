package in.agreementmitra.signing.signingrequest;

import in.agreementmitra.ConflictException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.DocumentStatusView;
import in.agreementmitra.signing.EsignProvider;
import in.agreementmitra.signing.InviteeStatus;
import in.agreementmitra.signing.SignRequest;
import in.agreementmitra.signing.SignSession;
import in.agreementmitra.signing.SignedDocument;
import in.agreementmitra.signing.WebhookHeaders;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.StampInfo;
import in.agreementmitra.signing.api.AgreementDisplayStatus;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.FinaliseResponse;
import in.agreementmitra.signing.api.SigningProgressResponse;
import in.agreementmitra.signing.api.SigningRequestResponse;
import in.agreementmitra.signing.api.SigningRequestResponse.InviteeView;
import in.agreementmitra.signing.contact.PartyReachability;
import in.agreementmitra.signing.delivery.SignedDocumentDeliveryService;
import in.agreementmitra.signing.payment.PaymentGate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Application service for the signing-request aggregate. Java-{@code public} so the {@code api}
 * controllers (a different package in the same module) can inject it; still Modulith-internal.
 *
 * <p>Deliberately NOT {@code @Transactional}: it orchestrates two short transactions (via {@link
 * SigningRequestPersistence}) around the provider HTTP call so no transaction spans the network
 * round-trip and a provider-success / DB-failure split leaves a recoverable pre-request row (D9).
 * eSign is asynchronous — this never blocks on a signature; the webhook drives completion.
 */
@Service
public class SigningRequestService {

  private static final Logger log = LoggerFactory.getLogger(SigningRequestService.class);

  private final AgreementService agreementService;
  private final EsignProvider esignProvider;
  private final SigningRequestPersistence persistence;
  private final BlobStore blobStore;
  private final PaymentGate paymentGate;
  private final SignedDocumentDeliveryService deliveryService;
  private final PartyReachability reachability;

  SigningRequestService(
      AgreementService agreementService,
      EsignProvider esignProvider,
      SigningRequestPersistence persistence,
      BlobStore blobStore,
      PaymentGate paymentGate,
      SignedDocumentDeliveryService deliveryService,
      PartyReachability reachability) {
    this.agreementService = agreementService;
    this.esignProvider = esignProvider;
    this.persistence = persistence;
    this.blobStore = blobStore;
    this.paymentGate = paymentGate;
    this.deliveryService = deliveryService;
    this.reachability = reachability;
  }

  /**
   * Start an eSign for an agreement: load it, check every precondition (contactable parties,
   * something signable, a stored draft, and an <b>already-attached e-stamp</b>), call the provider,
   * then record the document id + per-signer URLs and move to {@code SIGN_REQUESTED}.
   *
   * <p>Stamping is NOT part of this flow any more. A real e-stamp is bought from the SHCIL portal
   * by a person, out-of-band, and uploaded by staff (see {@code StampIntakeService}); this call
   * cannot make one appear, so it refuses rather than pretending. The document submitted to the
   * provider is the stored stamped PDF, never the bare draft, and the stamp is not re-composited
   * here.
   *
   * @throws ResourceNotFoundException if no such agreement exists (mapped to 404)
   * @throws ConflictException if the agreement has no uploaded draft, or no attached stamp (409)
   */
  public SigningRequestResponse create(UUID agreementId) {
    AgreementResponse agreement =
        agreementService
            .findById(agreementId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Agreement not found: " + agreementId));

    // Every party must be reachable on an ENABLED delivery channel before the provider call —
    // contact is optional at draft (CR-1) and enforced at the payment gate, so this is defence in
    // depth. Checked before any row / stamp / provider side effect.
    requireContacts(agreement);

    // Nothing signable (no signer yields an eSign anchor) -> fail clearly before any row, stamp, or
    // provider side effect; no partial request is ever submitted (agreement-execution-block CR).
    if (agreement.signers().stream().noneMatch(s -> esignAnchorFor(s) != null)) {
      throw ConflictException.notSignable();
    }

    // CLOSURE IS TERMINAL (design D1). A closed agreement is finished or abandoned; starting an
    // eSign on one would be a second vendor charge against work that is over. Checked before any
    // row, blob or provider side effect, exactly like the payment gate.
    agreementService.requireOpen(agreementId);

    // A stored draft must exist. Checked BEFORE the stamp gate so the operator sees the FIRST
    // missing precondition, and before any provider call.
    requireDraft(agreementId);

    // PAYMENT GATE (design D6). Each signature is a billable vendor transaction, so an unpaid
    // agreement must not reach the provider when the gate is REQUIRED. Checked here - before any
    // signing-request row is touched and before the provider call - so a refusal costs nothing and
    // incurs no vendor charge. In OPTIONAL mode (today's default) this always passes.
    paymentGate.require(agreementId);

    // An attached e-stamp is a PRECONDITION, not something this flow can create. Distinct 409 kind
    // (stamp-required) so an operator can tell it apart from a missing draft or an uncontactable
    // party -- a different person fixes each. Checked before any signing-request row is persisted
    // and before any provider call.
    byte[] stampedPdf = loadStampedPdf(agreementId);

    // The row already exists in STAMPED: the staff upload created it and drove the transition
    // (design D2). This flow only advances it.
    UUID signingRequestId =
        persistence.stampedRequestId(agreementId).orElseThrow(ConflictException::stampRequired);

    // Provider call - OUTSIDE any transaction. On failure the stamped row remains for
    // reconciliation; the exception propagates to the caller.
    SignSession session = esignProvider.createSignRequest(buildSignRequest(agreement, stampedPdf));

    Map<String, UUID> signerIdByEmail =
        agreement.signers().stream()
            .collect(
                Collectors.toMap(
                    s -> s.email().toLowerCase(Locale.ROOT),
                    AgreementResponse.SignerResponse::id,
                    (a, b) -> a));

    List<SigningRequestInvitee> rows = new ArrayList<>();
    List<InviteeView> views = new ArrayList<>();
    List<SignSession.InviteeSession> invitees = session.invitees();
    for (int ordinal = 0; ordinal < invitees.size(); ordinal++) {
      SignSession.InviteeSession invitee = invitees.get(ordinal);
      UUID signerId = signerIdByEmail.get(invitee.email().toLowerCase(Locale.ROOT));
      if (signerId == null) {
        throw new IllegalStateException("Provider returned a URL for an unknown signer");
      }
      rows.add(
          SigningRequestInvitee.create(
              signerId,
              invitee.signUrl(),
              invitee.expiryDate(),
              ordinal,
              invitee.providerInviteeId(),
              // The address the provider ACTUALLY issued this invitation to. Recorded here, at the
              // moment the invitation is created, because it is the only address the signed
              // agreement may later be emailed to - and only once this same invitee is observed
              // SIGNED (design D2). Never re-read from the draft at delivery time.
              invitee.email()));
      views.add(
          new InviteeView(signerId, invitee.email(), invitee.signUrl(), invitee.expiryDate()));
    }

    // tx2: attach provider result and advance the FSM to SIGN_REQUESTED. The provider's
    // per-transaction webhook credential (null for a provider that issues none) rides along and is
    // encrypted on the way into the database - the adapter never touches persistence itself.
    persistence.markRequested(
        signingRequestId, session.providerDocumentId(), rows, session.webhookKey());

    log.debug("Signing request {} created for agreement {}", signingRequestId, agreementId);
    return new SigningRequestResponse(session.providerDocumentId(), views);
  }

  /**
   * <b>Finalise (place the order).</b> The end of the customer's involvement: the terms freeze, the
   * signing request is created in the durable {@code PDF_GENERATED} state, and the customer is
   * given their tracking reference. From here it is staff work - buy the e-stamp, scan it, upload
   * it - and the customer does nothing until they are invited to sign.
   *
   * <p>Creating the request here, rather than at stamp intake, is what makes the freeze mean
   * something: the document staff stamp and the parties sign is exactly the document the customer
   * finalised, with no editable window in between (the existing draft-freeze rule keys off the
   * presence of a signing request, so it starts biting at this instant).
   *
   * <p>Idempotent: finalising twice returns the same reference and does not place a second order.
   *
   * @throws ResourceNotFoundException if no such agreement exists (mapped to 404)
   * @throws ConflictException if the agreement has no generated/uploaded draft to finalise (409)
   */
  public FinaliseResponse finalise(UUID agreementId) {
    AgreementResponse agreement =
        agreementService
            .findById(agreementId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Agreement not found: " + agreementId));

    // A closed agreement takes no further fulfilment action - it cannot be re-ordered into life.
    agreementService.requireOpen(agreementId);

    // There must be something to finalise. Checked before the order row exists, so a premature
    // finalise leaves the agreement fully editable rather than freezing an empty order.
    requireDraft(agreementId);

    persistence.placeOrder(agreementId);
    log.debug("Order placed for agreement {}", agreementId);
    return new FinaliseResponse(
        agreementId,
        agreement.trackingNumber(),
        AgreementDisplayStatus.from(persistence.currentStatus(agreementId)).name());
  }

  /**
   * Handle an inbound webhook. Returns {@code true} if the webhook is authentic (and was acted on
   * or safely ignored), {@code false} if verification failed. A verified webhook is acknowledged
   * indistinguishably whether or not its document is known, and a Details-API / download failure is
   * acked too (completion is left to the reconciliation fallback) — neither leaks internal state
   * nor induces vendor re-delivery storms.
   */
  public boolean handleWebhook(String payload, WebhookHeaders headers) {
    // PARSE-THEN-VERIFY (design D1). The adapter parses the transaction id the UNTRUSTED body
    // claims; the module - not the adapter - loads that transaction's stored key and decrypts it;
    // the adapter compares the presented credential against it in constant time. The parsed id is
    // used for NOTHING else: it drives no state change and is not authentication.
    //
    // An unknown transaction simply yields no key, so verification fails exactly as a wrong key
    // does - the caller cannot tell the two apart, so this is not an existence oracle.
    String storedKey =
        esignProvider
            .parseWebhookTransactionId(payload)
            .flatMap(persistence::webhookKeyFor)
            .orElse(null);
    Optional<String> documentId = esignProvider.verifyWebhook(payload, headers, storedKey);
    if (documentId.isEmpty()) {
      return false; // rejected — no side effect
    }
    try {
      // THE WEBHOOK BODY IS NEVER TRUSTED, AND THIS IS NOW THE PRIMARY SECURITY CONTROL (design
      // D2). ZOOP's `webhook-security-key` header proves only that the caller HOLDS the key; unlike
      // an HMAC it binds nothing to the payload, so anyone with that key could post an arbitrary
      // body. Re-reading authoritative per-invitee status via getStatus is what makes a forged body
      // inert. DO NOT "optimise" this by reading a status straight out of the payload - that would
      // silently convert a leaked key into the ability to drive our FSM.
      completeDocument(documentId.get());
    } catch (RuntimeException e) {
      // Details/download step failed — ack and defer to reconciliation. No payload logged.
      log.warn("Webhook acked without completion: status/download step failed");
    }
    return true;
  }

  /**
   * Extend a still-pending signing window on the <b>same</b> provider transaction.
   *
   * <p>Sequential signing means a stalled first signer blocks everyone behind them, so this is the
   * ordinary remedy. Doing it on the existing transaction is the whole point: creating a new one
   * would be a second document and a second vendor charge for the same agreement.
   *
   * @throws ResourceNotFoundException if the agreement has no in-flight signing request (404)
   */
  public void extendSigningWindow(UUID agreementId, int additionalMinutes) {
    esignProvider.extendExpiry(pendingTransaction(agreementId), additionalMinutes);
    log.debug("Signing window extended for agreement {}", agreementId);
  }

  /**
   * Ask the provider to re-send its invitations for a still-pending transaction - the fallback when
   * an invitation email does not arrive. Same transaction, so no second charge.
   *
   * @throws ResourceNotFoundException if the agreement has no in-flight signing request (404)
   */
  public void resendInvitations(UUID agreementId) {
    esignProvider.resendInvitations(pendingTransaction(agreementId));
    log.debug("Invitations re-sent for agreement {}", agreementId);
  }

  /** The in-flight provider transaction id, or a 404 - never a redacted-id-bearing message. */
  private String pendingTransaction(UUID agreementId) {
    return persistence
        .pendingProviderDocumentId(agreementId)
        .orElseThrow(
            () -> new ResourceNotFoundException("No pending signing request for the agreement"));
  }

  /**
   * Per-party signing progress for an agreement, with authorization applied.
   *
   * <p>Ownership scoping mirrors the agreement read (D5): a STAFF caller may read any agreement;
   * any other caller sees an <b>unowned</b> agreement (the unguessable id is a bearer capability)
   * or one they own, and gets the same 404 as an unknown id otherwise - so progress is not a
   * cross-customer oracle either.
   *
   * <p>The view carries no eKYC-derived signer data, no signing URL, and no provider credential.
   */
  public SigningProgressResponse progress(UUID agreementId, UUID callerIdentityId, boolean staff) {
    AgreementResponse agreement =
        (staff
                ? agreementService.findById(agreementId)
                : agreementService.findByIdForReader(agreementId, callerIdentityId))
            .orElseThrow(
                () -> new ResourceNotFoundException("Agreement not found: " + agreementId));

    Optional<SigningRequestPersistence.Progress> progress = persistence.progressFor(agreementId);
    Map<UUID, InviteeStatus> statusBySigner =
        progress.map(SigningRequestPersistence.Progress::statusBySignerId).orElse(Map.of());

    List<SigningProgressResponse.PartyProgress> parties =
        agreement.signers().stream()
            .sorted(SIGNING_ORDER)
            .map(
                s ->
                    new SigningProgressResponse.PartyProgress(
                        s.id(),
                        s.role() == null ? null : s.role().name(),
                        statusBySigner.getOrDefault(s.id(), InviteeStatus.PENDING).name()))
            .toList();

    return new SigningProgressResponse(
        agreementId,
        AgreementDisplayStatus.from(progress.map(SigningRequestPersistence.Progress::status)),
        parties);
  }

  /**
   * The shared completion path, reused by the webhook handler and the reconciliation job: re-read
   * authoritative per-invitee status (the webhook body is untrusted), apply it + aggregate the FSM
   * (tx_a), then — if now {@code SIGNED} with artifacts not yet stored — download + store them
   * outside any transaction (the D9 split), idempotently. An unknown document id is an
   * indistinguishable no-op.
   */
  public void completeDocument(String providerDocumentId) {
    DocumentStatusView view = esignProvider.getStatus(providerDocumentId);
    persistence
        .applyAuthoritativeStatus(providerDocumentId, view)
        .ifPresent(
            signingRequestId -> fetchAndStoreArtifacts(signingRequestId, providerDocumentId));
    // FULFILMENT, strictly downstream of the legal record (design D5). Deliberately outside the
    // "needsArtifacts" branch above: this line runs on EVERY entry, including a re-delivered
    // webhook
    // and a reconciliation pass over an already-complete request. That is not waste - it is what
    // makes the retry path work, and it is exactly the re-entrancy the per-recipient claim exists
    // to
    // survive. Exactly-once lives in the delivery record, never in an assumption about this call.
    //
    // It cannot throw: a signature has already happened by the time we get here, and a mail outage
    // must not be able to look like - let alone cause - a completion failure.
    deliveryService.onSigningCompleted(providerDocumentId);
  }

  /**
   * Download the signed artifacts and store them under deterministic, internal-id-derived keys,
   * then record the keys (tx_b). Network calls happen outside any transaction; a failure here
   * leaves the row {@code SIGNED} with null keys for the reconciliation fallback to retry.
   */
  private void fetchAndStoreArtifacts(UUID signingRequestId, String providerDocumentId) {
    SignedDocument document = esignProvider.download(providerDocumentId);
    String signedPdfKey = "signed/" + signingRequestId + ".pdf";
    String auditTrailKey = "audit/" + signingRequestId;
    blobStore.put(signedPdfKey, document.signedPdf(), document.signedPdfContentType());
    blobStore.put(auditTrailKey, document.auditTrail(), document.auditTrailContentType());
    persistence.storeArtifactKeys(signingRequestId, signedPdfKey, auditTrailKey);
    log.debug("Stored artifacts for signing request {}", signingRequestId);
  }

  /**
   * 409 (draft-required) if the agreement has no stored draft. Reads the key only, not the bytes.
   */
  private void requireDraft(UUID agreementId) {
    agreementService.draftPdfKey(agreementId).orElseThrow(ConflictException::draftRequired);
  }

  /**
   * The stored stamped PDF - what the provider must receive, never the bare draft. Empty stamp info
   * means staff have not uploaded a purchased e-stamp yet, which is a distinct 409 (stamp-required)
   * raised here, before any signing-request row is persisted and before any provider call.
   */
  private byte[] loadStampedPdf(UUID agreementId) {
    String stampedKey =
        agreementService
            .stampInfo(agreementId)
            .map(StampInfo::stampedPdfKey)
            .orElseThrow(ConflictException::stampRequired);
    return blobStore.get(stampedKey);
  }

  /**
   * The order invitees are submitted in, and therefore the order a SEQUENTIAL provider collects
   * signatures in: <b>owner before tenant</b>. Derived from the role rather than the capture order,
   * so a form that happened to collect the tenant first cannot invert the signing sequence on a
   * legal instrument. A role-less signer sorts last (it yields no anchor and is filtered earlier).
   */
  private static final Comparator<AgreementResponse.SignerResponse> SIGNING_ORDER =
      Comparator.comparingInt(s -> s.role() == null ? Integer.MAX_VALUE : s.role().ordinal());

  private SignRequest buildSignRequest(AgreementResponse agreement, byte[] unsignedPdf) {
    // Address the invite to both channels the party provided (email + mobile as phone), and bind
    // each signer to the eSign anchor its signature zone rendered (esign:<role>). Sorted
    // owner-first so a sequential provider collects the signatures in the legally intended order.
    //
    // TWO placements per signer, in order: the signature block on the execution page, then a strip
    // in the bottom margin of EVERY page. The strip is not legally required - an Aadhaar eSign
    // covers the whole instrument however many marks it carries - but a reader who checks page by
    // page (a bank desk, a society office, a registrar's counter) expects one, and this is the
    // convention every ESP-signed agreement in the market follows.
    Function<AgreementResponse.SignerResponse, SignRequest.Invitee> toInvitee =
        s -> {
          String anchor = esignAnchorFor(s);
          List<SignRequest.Placement> placements =
              anchor == null
                  ? List.of()
                  : List.of(
                      SignRequest.Placement.anchored(anchor),
                      SignRequest.Placement.everyPageFooter());
          return new SignRequest.Invitee(s.name(), s.email(), s.mobile(), true, placements);
        };
    List<SignRequest.Invitee> invitees =
        agreement.signers().stream().sorted(SIGNING_ORDER).map(toInvitee).toList();
    return new SignRequest(agreement.id().toString(), unsignedPdf, invitees);
  }

  /**
   * The stable, non-PII eSign anchor for a signer: {@code esign:<role>} (e.g. {@code esign:owner}),
   * derived from the signer's role. Mirrors the token the {@code documents} renderer emits at each
   * signature zone (derived there from the signatory name key), so the anchor a zone renders and
   * the provider field this maps agree -- without either module sharing a type.
   */
  private static String esignAnchorFor(AgreementResponse.SignerResponse signer) {
    return signer.role() == null ? null : "esign:" + signer.role().name().toLowerCase(Locale.ROOT);
  }

  /**
   * Reject (409) if any party is not reachable on an <b>enabled delivery channel</b>.
   *
   * <p>This used to accept email <b>or</b> mobile, which was wrong for its own purpose: signed
   * documents are delivered by email only, so a party carrying just a mobile passed this gate,
   * signed, and could never be sent the finished agreement. The rule now comes from {@link
   * PartyReachability} - the single definition shared with the payment gate, so the two cannot
   * drift (design D14).
   *
   * <p>Expect this to reject agreements the old rule admitted. That is the correction landing, not
   * a regression. In practice the payment gate refuses them earlier, which leaves this check as
   * defence in depth rather than the first line.
   */
  private void requireContacts(AgreementResponse agreement) {
    boolean allContactable =
        agreement.signers().stream().allMatch(s -> reachability.isReachable(s.email(), s.mobile()));
    if (!allContactable) {
      throw ConflictException.contactRequired();
    }
  }
}
