package in.agreementmitra.signing.signingrequest;

import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.DocumentStatusView;
import in.agreementmitra.signing.EsignProvider;
import in.agreementmitra.signing.SignRequest;
import in.agreementmitra.signing.SignSession;
import in.agreementmitra.signing.SignedDocument;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.SigningRequestResponse;
import in.agreementmitra.signing.api.SigningRequestResponse.InviteeView;
import java.nio.charset.StandardCharsets;
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

  // Server-sourced placeholder document for this tracer bullet (sandbox/dummy only). Real PDF
  // rendering via the documents module lands in a later CR. Never a client-supplied field.
  private static final byte[] DUMMY_PDF =
      "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

  private final AgreementService agreementService;
  private final EsignProvider esignProvider;
  private final SigningRequestPersistence persistence;
  private final BlobStore blobStore;

  SigningRequestService(
      AgreementService agreementService,
      EsignProvider esignProvider,
      SigningRequestPersistence persistence,
      BlobStore blobStore) {
    this.agreementService = agreementService;
    this.esignProvider = esignProvider;
    this.persistence = persistence;
    this.blobStore = blobStore;
  }

  /**
   * Start an eSign for an agreement: load it, persist a pre-request row, call the provider, then
   * record the document id + per-signer URLs and move to {@code SIGN_REQUESTED}.
   *
   * @throws ResourceNotFoundException if no such agreement exists (mapped to 404)
   */
  public SigningRequestResponse create(UUID agreementId) {
    AgreementResponse agreement =
        agreementService
            .findById(agreementId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Agreement not found: " + agreementId));

    // tx1: persist intent before the provider call (recoverable if the later steps fail).
    UUID signingRequestId = persistence.createPending(agreementId);

    // Provider call — OUTSIDE any transaction. On failure the pre-request row remains for
    // reconciliation; the exception propagates to the caller.
    SignSession session = esignProvider.createSignRequest(buildSignRequest(agreement));

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

  private SignRequest buildSignRequest(AgreementResponse agreement) {
    Function<AgreementResponse.SignerResponse, SignRequest.Invitee> toInvitee =
        s -> new SignRequest.Invitee(s.name(), s.email(), null, true);
    List<SignRequest.Invitee> invitees = agreement.signers().stream().map(toInvitee).toList();
    return new SignRequest(agreement.id().toString(), DUMMY_PDF, invitees);
  }
}
