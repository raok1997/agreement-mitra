package in.agreementmitra.signing.signingrequest;

import in.agreementmitra.ConflictException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.StampFailedException;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.DocumentStatusView;
import in.agreementmitra.signing.EsignProvider;
import in.agreementmitra.signing.SignRequest;
import in.agreementmitra.signing.SignSession;
import in.agreementmitra.signing.SignedDocument;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.StampInfo;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.SigningRequestResponse;
import in.agreementmitra.signing.api.SigningRequestResponse.InviteeView;
import in.agreementmitra.signing.stamp.StampProvider;
import in.agreementmitra.signing.stamp.StampResult;
import java.time.Instant;
import java.util.ArrayList;
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
  private final StampProvider stampProvider;
  private final SigningRequestPersistence persistence;
  private final BlobStore blobStore;

  SigningRequestService(
      AgreementService agreementService,
      EsignProvider esignProvider,
      StampProvider stampProvider,
      SigningRequestPersistence persistence,
      BlobStore blobStore) {
    this.agreementService = agreementService;
    this.esignProvider = esignProvider;
    this.stampProvider = stampProvider;
    this.persistence = persistence;
    this.blobStore = blobStore;
  }

  /**
   * Start an eSign for an agreement: load it, persist a pre-request row, call the provider, then
   * record the document id + per-signer URLs and move to {@code SIGN_REQUESTED}.
   *
   * @throws ResourceNotFoundException if no such agreement exists (mapped to 404)
   * @throws ConflictException if the agreement has no uploaded draft (mapped to 409)
   */
  public SigningRequestResponse create(UUID agreementId) {
    AgreementResponse agreement =
        agreementService
            .findById(agreementId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Agreement not found: " + agreementId));

    // Server-sourced unsigned PDF: the agreement's uploaded draft (anti-mass-assignment). Loaded
    // BEFORE persisting any row or calling the provider, so a missing draft is a clean 409 with no
    // signing-request row and no provider call.
    byte[] draft = loadDraft(agreementId);

    // tx1: persist intent before the provider call (recoverable if the later steps fail).
    UUID signingRequestId = persistence.createPending(agreementId);

    // Stamp step (auto, transparent): composite the e-stamp onto the draft and move to STAMPED
    // before the provider call. The document submitted to the provider is the STAMPED PDF, never
    // the bare draft. A stamp failure drives STAMP_FAILED and stops here (no provider call).
    byte[] stampedPdf = ensureStamped(agreement, signingRequestId, draft);

    // Provider call — OUTSIDE any transaction. On failure the pre-request row remains for
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
              invitee.providerInviteeId()));
      views.add(
          new InviteeView(signerId, invitee.email(), invitee.signUrl(), invitee.expiryDate()));
    }

    // tx2: attach provider result and advance the FSM to SIGN_REQUESTED.
    persistence.markRequested(signingRequestId, session.providerDocumentId(), rows);

    log.debug("Signing request {} created for agreement {}", signingRequestId, agreementId);
    return new SigningRequestResponse(session.providerDocumentId(), views);
  }

  /**
   * Handle an inbound webhook. Returns {@code true} if the webhook is authentic (and was acted on
   * or safely ignored), {@code false} if verification failed. A verified webhook is acknowledged
   * indistinguishably whether or not its document is known, and a Details-API / download failure is
   * acked too (completion is left to the reconciliation fallback) — neither leaks internal state
   * nor induces vendor re-delivery storms.
   */
  public boolean handleWebhook(String payload) {
    Optional<String> documentId = esignProvider.verifyWebhook(payload);
    if (documentId.isEmpty()) {
      return false; // rejected — no side effect
    }
    try {
      completeDocument(documentId.get());
    } catch (RuntimeException e) {
      // Details/download step failed — ack and defer to reconciliation. No payload logged.
      log.warn("Webhook acked without completion: status/download step failed");
    }
    return true;
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
   * Ensure a stamp is attached and return the stamped PDF bytes. If the agreement has no stamp yet,
   * procure one, store the stamped PDF (object storage, no tx), then attach the stamp data + move
   * the request to {@code STAMPED} (one short tx). If a stamp already exists, reuse it
   * (idempotent).
   *
   * <p>The reuse branch is dormant under v1 lock-forever (exactly one signing request per agreement
   * ⇒ stamp info is always empty here); it defaults to safe reuse for the future supersede flow. A
   * procurement/composition failure drives the request to the terminal {@code STAMP_FAILED} and
   * rethrows (the provider is never called).
   */
  private byte[] ensureStamped(AgreementResponse agreement, UUID signingRequestId, byte[] draft) {
    UUID agreementId = agreement.id();
    Optional<StampInfo> existing = agreementService.stampInfo(agreementId);
    if (existing.isPresent() && existing.get().stampedPdfKey() != null) {
      persistence.markStamped(signingRequestId); // reuse — dormant in v1
      return blobStore.get(existing.get().stampedPdfKey());
    }

    StampResult result;
    try {
      result = stampProvider.procure(agreementId, draft);
    } catch (StampFailedException e) {
      persistence.markStampFailed(signingRequestId);
      throw e;
    }

    String stampedKey = "stamped/" + agreementId + ".pdf";
    blobStore.put(stampedKey, result.stampedPdf(), "application/pdf"); // network — no tx
    StampInfo info =
        new StampInfo(
            result.serial(),
            stampedKey,
            result.denomination(),
            result.jurisdiction(),
            result.dutyPaid(),
            Instant.now());
    persistence.markStamped(signingRequestId, agreementId, info); // short tx: attach + transition
    log.debug("Stamped agreement {} for signing request {}", agreementId, signingRequestId);
    return result.stampedPdf();
  }

  /** Load the agreement's uploaded draft bytes, or 409 if none has been uploaded. */
  private byte[] loadDraft(UUID agreementId) {
    String key =
        agreementService.draftPdfKey(agreementId).orElseThrow(ConflictException::draftRequired);
    return blobStore.get(key);
  }

  private SignRequest buildSignRequest(AgreementResponse agreement, byte[] unsignedPdf) {
    Function<AgreementResponse.SignerResponse, SignRequest.Invitee> toInvitee =
        s -> new SignRequest.Invitee(s.name(), s.email(), null, true);
    List<SignRequest.Invitee> invitees = agreement.signers().stream().map(toInvitee).toList();
    return new SignRequest(agreement.id().toString(), unsignedPdf, invitees);
  }
}
