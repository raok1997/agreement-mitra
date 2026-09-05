package in.agreementmitra.signing.zoop;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.signing.ArtifactHosts;
import in.agreementmitra.signing.DocumentStatusView;
import in.agreementmitra.signing.EsignProvider;
import in.agreementmitra.signing.InviteeStatus;
import in.agreementmitra.signing.SignRequest;
import in.agreementmitra.signing.SignSession;
import in.agreementmitra.signing.SignedDocument;
import in.agreementmitra.signing.WebhookHeaders;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * ZOOP eSign <b>v5</b> adapter. Internal to the signing module - nothing outside the module
 * references this class; callers depend on {@link EsignProvider}. Active only when {@code
 * esign.provider=zoop}.
 *
 * <p>All vendor specifics live here: the {@code app-id}/{@code api-key} auth headers, the {@code
 * v5} paths, the request/response shapes, the {@code webhook-security-key} authentication
 * mechanism, and the anchor-to-coordinate translation.
 *
 * <p><b>One call invites everybody.</b> {@code POST /v5/init} carries a {@code signers[]} array,
 * {@code esign_type: AADHAAR}, {@code signing_type: SEQUENTIAL} (the owner signs, then the tenant),
 * and {@code send_invite: true} so ZOOP - not us - emails each party. One transaction id ({@code
 * group_id}) comes back, plus a {@code request_id} per signer for status correlation, and a
 * per-transaction {@code webhook_security_key}.
 *
 * <p><b>Security.</b> The webhook credential is a per-transaction shared key presented in an HTTP
 * header; it proves possession of the key and binds nothing to the payload, so the body stays
 * entirely untrusted and authoritative state is always re-read from {@code /v5/fetch/group} (design
 * D2). The key is compared in constant time here and stored encrypted by the module. Never logged:
 * transaction ids (redacted), signing URLs (bearer capabilities), payloads, the key, or any
 * eKYC-derived signer field ({@code fetched_name}, {@code given_name}, {@code postal_code}, {@code
 * name_match_score}).
 *
 * <p><b>PII surface kept narrow.</b> {@code location_capture} and {@code photo_capture} are
 * deliberately never set - they widen the PII surface for marginal benefit and nothing requires
 * them.
 */
@Component
@ConditionalOnProperty(name = "esign.provider", havingValue = "zoop")
class ZoopEsignProvider implements EsignProvider {

  private static final Logger log = LoggerFactory.getLogger(ZoopEsignProvider.class);

  private static final String INIT_PATH = "v5/init";
  private static final String FETCH_GROUP_PATH = "v5/fetch/group";
  private static final String FETCH_AUDIT_TRAIL_PATH = "v5/fetch/audit-trail";
  private static final String INCREASE_EXPIRY_PATH = "v5/increase-expiry-time";
  private static final String SEND_INVITATION_PATH = "v5/send-esign-invitation";

  /** The header ZOOP presents its per-transaction webhook credential in. */
  static final String WEBHOOK_KEY_HEADER = "webhook-security-key";

  private static final String ESIGN_TYPE_AADHAAR = "AADHAAR";
  private static final String SIGNING_TYPE_SEQUENTIAL = "SEQUENTIAL";
  private static final String DOCUMENT_NAME = "agreement.pdf";

  /**
   * The vendor requires {@code document.info} to be at least 15 characters. A fixed, non-PII
   * sentence: it appears in the signer-facing UI, so it must never carry party or property data.
   */
  static final String DOCUMENT_INFO = "Rental agreement for Aadhaar eSign execution";

  /**
   * Mandatory per-signer field. Fixed and non-PII, for the same reason as {@link #DOCUMENT_INFO}.
   */
  private static final String SIGNER_PURPOSE = "Execution of rental agreement";

  /**
   * The vendor's documented ceiling on {@code document.data}: 14 MB of <b>base64</b>. A real
   * constraint rather than a theoretical one now that the stamped PDF carries a scanned certificate
   * page, so it is checked before the call rather than discovered as a vendor error.
   */
  static final int MAX_ENCODED_DOCUMENT_BYTES = 14 * 1024 * 1024;

  private final RestClient client;
  private final ZoopProperties properties;
  private final ObjectMapper objectMapper;

  ZoopEsignProvider(
      RestClient zoopRestClient, ZoopProperties properties, ObjectMapper objectMapper) {
    this.client = zoopRestClient;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  private static SignCoordinatePart part(ZoopSignCoordinate coordinate) {
    return new SignCoordinatePart(coordinate.pageNum(), coordinate.xCoord(), coordinate.yCoord());
  }

  /**
   * The page carrying this signer's signature block, or 0 when they have no anchored placement.
   *
   * <p>Used only to keep the every-page strip OFF that page: the block is already there, and a
   * second signature beside it reads as a mistake on a legal instrument.
   */
  private static int blockPageFor(
      SignRequest.Invitee invitee, Map<String, AnchorPosition> anchors) {
    return invitee.placements().stream()
        .filter(p -> p.pageScope() == SignRequest.PageScope.ANCHOR_PAGE && p.anchor() != null)
        .map(p -> anchors.get(p.anchor().toLowerCase(Locale.ROOT)))
        .filter(java.util.Objects::nonNull)
        .mapToInt(AnchorPosition::pageNumber)
        .findFirst()
        .orElse(0);
  }

  // --- create ----------------------------------------------------------------

  @Override
  public SignSession createSignRequest(SignRequest request) {
    // Placement first: locating the anchors can REFUSE the request, and refusing before the call
    // is the whole point - a signature placed at a guessed position on a legal instrument is worse
    // than no signature at all (design D4).
    Map<String, AnchorPosition> anchors =
        EsignAnchorLocator.locate(
            request.unsignedPdf(),
            request.invitees().stream()
                .flatMap(i -> i.placements().stream())
                .map(SignRequest.Placement::anchor)
                .filter(java.util.Objects::nonNull)
                .toList());
    // Measured once: page count, the smallest page, and the body column the strips align to. The
    // strip is placed against the SMALLEST page rather than the page an anchor landed on - a stamp
    // certificate scanned at a different size than the agreement would otherwise push it off the
    // short page.
    DocumentGeometry geometry = EsignAnchorLocator.geometry(request.unsignedPdf());

    List<SignerPart> signers = new ArrayList<>();
    int lane = 0;
    for (SignRequest.Invitee invitee : request.invitees()) {
      List<SignCoordinatePart> coordinates = new ArrayList<>();
      for (SignRequest.Placement placement : invitee.placements()) {
        switch (placement.pageScope()) {
          case ANCHOR_PAGE -> {
            String anchor =
                placement.anchor() == null ? null : placement.anchor().toLowerCase(Locale.ROOT);
            AnchorPosition position = anchor == null ? null : anchors.get(anchor);
            if (position == null) {
              // Fail closed. No default position, no "middle of the last page", no silent
              // omission.
              throw new IllegalStateException(
                  "eSign anchor missing from the stamped document; refusing to place a signature");
            }
            coordinates.add(part(ZoopSignCoordinate.from(position)));
          }
          // Every page EXCEPT the one already carrying this signer's block. The vendor's "all
          // pages" page number cannot exclude one, so the pages are enumerated - which is also what
          // keeps a second, redundant signature off the execution page.
          case ALL_PAGES -> {
            int blockPage = blockPageFor(invitee, anchors);
            for (int page = 1; page <= geometry.pageCount(); page++) {
              if (page != blockPage) {
                coordinates.add(part(ZoopSignCoordinate.everyPageFooter(geometry, lane, page)));
              }
            }
          }
        }
      }
      if (coordinates.isEmpty()) {
        // A signer with no placement at all would be invited to sign nowhere.
        throw new IllegalStateException(
            "no eSign placement for a signer; refusing to place a signature");
      }
      signers.add(new SignerPart(invitee.name(), invitee.email(), SIGNER_PURPOSE, coordinates));
      lane++;
    }

    String base64Pdf = encodeWithinCeiling(request.unsignedPdf());

    InitBody body =
        new InitBody(
            new DocumentPart(DOCUMENT_NAME, base64Pdf, DOCUMENT_INFO),
            signers,
            ESIGN_TYPE_AADHAAR,
            SIGNING_TYPE_SEQUENTIAL,
            true,
            properties.txnExpiryMin(),
            properties.responseUrl(),
            properties.redirectUrl(),
            request.agreementId(),
            new EmailTemplatePart(properties.orgName()));

    log.debug("Creating ZOOP v5 transaction for {} signer(s)", signers.size());

    InitResponse response =
        client
            .post()
            .uri(INIT_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(InitResponse.class);

    if (response == null || response.groupId() == null) {
      throw new IllegalStateException("ZOOP init response missing group_id");
    }

    // Zip the vendor's per-signer results back to the request invitees so each signing URL keeps
    // its owner's email. Sorted by the vendor's own signing_order where present, so the recorded
    // order is the order signatures will actually be collected in.
    List<InitRequestEntry> entries =
        response.requests() == null
            ? List.of()
            : response.requests().stream()
                .sorted(
                    Comparator.comparingInt(e -> e.signingOrder() == null ? 0 : e.signingOrder()))
                .toList();
    List<SignSession.InviteeSession> sessions = new ArrayList<>();
    for (int i = 0; i < entries.size() && i < request.invitees().size(); i++) {
      InitRequestEntry entry = entries.get(i);
      String email =
          entry.signerEmail() != null ? entry.signerEmail() : request.invitees().get(i).email();
      sessions.add(
          new SignSession.InviteeSession(
              email, entry.signingUrl(), response.expiresAt(), entry.requestId()));
    }

    log.debug(
        "ZOOP created transaction {} with {} invitee(s)",
        redact(response.groupId()),
        sessions.size());
    // The per-transaction webhook key rides out through SignSession; the adapter stores nothing.
    return new SignSession(response.groupId(), sessions, response.webhookSecurityKey());
  }

  /**
   * Base64-encode the document, refusing before the call if it exceeds the vendor's ceiling. The
   * size is checked on the ENCODED length - that is what the vendor bounds - and computed without
   * allocating a second copy when it would be hopeless.
   */
  private static String encodeWithinCeiling(byte[] pdf) {
    long encodedLength = 4L * ((pdf.length + 2L) / 3L);
    if (encodedLength > MAX_ENCODED_DOCUMENT_BYTES) {
      throw new IllegalStateException(
          "Document exceeds the provider's 14 MB encoded ceiling; refusing to submit it");
    }
    return Base64.getEncoder().encodeToString(pdf);
  }

  // --- status ----------------------------------------------------------------

  @Override
  public DocumentStatusView getStatus(String providerDocumentId) {
    GroupResponse response = fetchGroup(providerDocumentId);
    List<GroupRequestEntry> entries =
        response == null || response.requests() == null ? List.of() : response.requests();
    List<DocumentStatusView.InviteeStatusView> views = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      GroupRequestEntry entry = entries.get(i);
      views.add(
          new DocumentStatusView.InviteeStatusView(
              entry.requestId(), i, mapInviteeStatus(entry.effectiveStatus())));
    }
    log.debug(
        "ZOOP transaction {} authoritative status -> {} invitee(s)",
        redact(providerDocumentId),
        views.size());
    return new DocumentStatusView(views);
  }

  /**
   * Map a ZOOP per-signer status to our vendor-neutral {@link InviteeStatus}. Anything in flight or
   * unrecognised maps to {@link InviteeStatus#PENDING} - a safe non-terminal, so an unknown vendor
   * value can never drive a terminal FSM transition by accident.
   */
  static InviteeStatus mapInviteeStatus(String vendorStatus) {
    if (vendorStatus == null) {
      return InviteeStatus.PENDING;
    }
    return switch (vendorStatus.trim().toUpperCase(Locale.ROOT)) {
      case "SIGNED", "COMPLETED", "SUCCESS" -> InviteeStatus.SIGNED;
      case "REJECTED", "FAILED", "DECLINED", "CANCELLED", "REVOKED" -> InviteeStatus.REJECTED;
      case "EXPIRED" -> InviteeStatus.EXPIRED;
      default -> InviteeStatus.PENDING; // PENDING / INPROGRESS / in flight / unknown
    };
  }

  // --- download --------------------------------------------------------------

  @Override
  public SignedDocument download(String providerDocumentId) {
    GroupResponse response = fetchGroup(providerDocumentId);
    if (response == null || response.completeSignedUrl() == null) {
      throw new IllegalStateException("ZOOP group response missing complete_signed_url");
    }
    Artifact signed = fetchArtifact(response.completeSignedUrl());
    Artifact auditTrail = fetchAuditTrail(providerDocumentId);
    log.debug("ZOOP transaction {} artifacts downloaded", redact(providerDocumentId));
    return new SignedDocument(
        providerDocumentId,
        signed.bytes(),
        signed.contentType(),
        auditTrail.bytes(),
        auditTrail.contentType());
  }

  private GroupResponse fetchGroup(String groupId) {
    return client
        .get()
        .uri(uri -> uri.path(FETCH_GROUP_PATH).queryParam("group_id", groupId).build())
        .retrieve()
        .body(GroupResponse.class);
  }

  /** The audit trail, fetched from the vendor's own endpoint (not a response-supplied URL). */
  private Artifact fetchAuditTrail(String groupId) {
    ResponseEntity<byte[]> entity =
        client
            .get()
            .uri(uri -> uri.path(FETCH_AUDIT_TRAIL_PATH).queryParam("group_id", groupId).build())
            .retrieve()
            .toEntity(byte[].class);
    return toArtifact(entity);
  }

  /**
   * Fetch an artifact from a provider-supplied URL after pinning its host against the configured
   * ALLOWLIST (SSRF guard). ZOOP's signed-document links live on a different host from its API, so
   * a single-host pin would break - but the allowlist is never weakened to "any URL the vendor
   * sent".
   */
  private Artifact fetchArtifact(String url) {
    ArtifactHosts.require(url, allowedArtifactHosts());
    return toArtifact(client.get().uri(URI.create(url)).retrieve().toEntity(byte[].class));
  }

  /**
   * The allowlist, falling back to the API host when none is configured (fail closed, not open).
   */
  private List<String> allowedArtifactHosts() {
    if (!properties.artifactHosts().isEmpty()) {
      return properties.artifactHosts();
    }
    return List.of(String.valueOf(URI.create(properties.baseUrl()).getHost()));
  }

  /**
   * Bytes plus the provider's declared content type; {@code application/octet-stream} when absent.
   */
  private static Artifact toArtifact(ResponseEntity<byte[]> entity) {
    byte[] bytes = entity.getBody() == null ? new byte[0] : entity.getBody();
    MediaType contentType = entity.getHeaders().getContentType();
    return new Artifact(
        bytes,
        contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType.toString());
  }

  private record Artifact(byte[] bytes, String contentType) {}

  // --- extend / re-invite ----------------------------------------------------

  /**
   * Extend a pending transaction's window on the SAME {@code group_id}. Sequential signing means a
   * stalled owner blocks the tenant entirely, so this is the ordinary remedy - and doing it on the
   * existing transaction is what keeps it free: a new {@code /v5/init} would be a second document
   * and a second charge.
   */
  @Override
  public void extendExpiry(String providerDocumentId, int additionalMinutes) {
    client
        .post()
        .uri(INCREASE_EXPIRY_PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ExtendBody(providerDocumentId, additionalMinutes))
        .retrieve()
        .toBodilessEntity();
    log.debug("ZOOP transaction {} expiry extended", redact(providerDocumentId));
  }

  /** Ask ZOOP to re-send its invitation emails for the same transaction. No new transaction. */
  @Override
  public void resendInvitations(String providerDocumentId) {
    client
        .post()
        .uri(SEND_INVITATION_PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new GroupBody(providerDocumentId))
        .retrieve()
        .toBodilessEntity();
    log.debug("ZOOP transaction {} invitations re-sent", redact(providerDocumentId));
  }

  // --- webhook ---------------------------------------------------------------

  /**
   * Parse the {@code group_id} an UNVERIFIED body claims. Untrusted: its only use is to let the
   * module load that transaction's stored key so this adapter can compare against it - the adapter
   * itself never touches persistence (design D1).
   */
  @Override
  public Optional<String> parseWebhookTransactionId(String payload) {
    try {
      JsonNode root = objectMapper.readTree(payload);
      String groupId = text(root, "group_id");
      if (groupId == null) {
        groupId = text(root, "groupId");
      }
      return Optional.ofNullable(groupId);
    } catch (Exception e) {
      // Never log the payload; the message is generic on purpose.
      return Optional.empty();
    }
  }

  /**
   * Verify the {@code webhook-security-key} header against the key stored for the transaction the
   * body names.
   *
   * <p>This is <b>not</b> an HMAC: the header value is a directly-presented secret that binds
   * nothing to the payload. That is exactly why the body remains untrusted and the caller re-reads
   * authoritative state (design D2) - the header only decides whether we bother to look.
   *
   * <p>A missing header, a wrong key, a key that is genuine but belongs to a DIFFERENT transaction,
   * and an unknown transaction (which yields no stored key at all) are all the same rejection, so
   * the endpoint is not an existence oracle.
   */
  @Override
  public Optional<String> verifyWebhook(
      String payload, WebhookHeaders headers, String storedWebhookKey) {
    Optional<String> claimed = parseWebhookTransactionId(payload);
    if (claimed.isEmpty()) {
      log.warn("Webhook rejected: no transaction id in body");
      return Optional.empty();
    }
    String presented = headers == null ? null : headers.value(WEBHOOK_KEY_HEADER).orElse(null);
    if (presented == null || storedWebhookKey == null) {
      // Indistinguishable from a wrong key: no hint about whether the transaction exists.
      log.warn("Webhook rejected: credential missing or unknown transaction");
      return Optional.empty();
    }
    if (!constantTimeEquals(presented, storedWebhookKey)) {
      log.warn("Webhook rejected: credential mismatch for transaction {}", redact(claimed.get()));
      return Optional.empty();
    }
    return claimed;
  }

  /**
   * Constant-time comparison of two directly-presented secrets.
   *
   * <p>Both sides are hashed first and the fixed-width digests compared with {@link
   * MessageDigest#isEqual}. Hashing matters here: {@code isEqual} short-circuits on a <b>length</b>
   * mismatch, which would leak the length of a live credential to an attacker who can time us. A
   * MAC digest is fixed-width so that never mattered; a vendor-issued key is not.
   */
  static boolean constantTimeEquals(String presented, String stored) {
    return MessageDigest.isEqual(sha256(presented), sha256(stored));
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  /** Redact an identifier for logs - keep only the last 4 chars so a leak is not exploitable. */
  static String redact(String id) {
    if (id == null || id.length() <= 4) {
      return "****";
    }
    return "****" + id.substring(id.length() - 4);
  }

  private static String text(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    String value = node.asText();
    return value.isBlank() ? null : value;
  }

  // --- Vendor wire shapes (kept private; never leak outside the adapter) ------
  //
  // Only the fields we actually need are modelled. In particular NOTHING here binds the
  // eKYC-derived signer fields the vendor returns (fetched_name, given_name, postal_code,
  // name_match_score): if they are never deserialised they can never be logged, echoed, or
  // accidentally persisted.

  private record InitBody(
      DocumentPart document,
      List<SignerPart> signers,
      @JsonProperty("esign_type") String esignType,
      @JsonProperty("signing_type") String signingType,
      @JsonProperty("send_invite") boolean sendInvite,
      @JsonProperty("txn_expiry_min") Integer txnExpiryMin,
      @JsonProperty("response_url") String responseUrl,
      @JsonProperty("redirect_url") String redirectUrl,
      @JsonProperty("task_id") String taskId,
      @JsonProperty("email_template") EmailTemplatePart emailTemplate) {}

  private record DocumentPart(String name, String data, String info) {}

  /**
   * One signer. {@code location_capture} / {@code photo_capture} are deliberately absent - an
   * optional evidence feature that widens the PII surface is not enabled without a stated
   * requirement.
   */
  private record SignerPart(
      @JsonProperty("signer_name") String signerName,
      @JsonProperty("signer_email") String signerEmail,
      @JsonProperty("signer_purpose") String signerPurpose,
      @JsonProperty("sign_coordinates") List<SignCoordinatePart> signCoordinates) {}

  private record SignCoordinatePart(
      @JsonProperty("page_num") int pageNum,
      @JsonProperty("x_coord") int xCoord,
      @JsonProperty("y_coord") int yCoord) {}

  private record EmailTemplatePart(@JsonProperty("org_name") String orgName) {}

  private record ExtendBody(
      @JsonProperty("group_id") String groupId, @JsonProperty("txn_expiry_min") int txnExpiryMin) {}

  private record GroupBody(@JsonProperty("group_id") String groupId) {}

  private record InitResponse(
      List<InitRequestEntry> requests,
      @JsonProperty("group_id") String groupId,
      @JsonProperty("webhook_security_key") String webhookSecurityKey,
      @JsonProperty("expires_at") String expiresAt) {}

  private record InitRequestEntry(
      @JsonProperty("request_id") String requestId,
      @JsonProperty("signer_email") String signerEmail,
      @JsonProperty("signing_order") Integer signingOrder,
      @JsonProperty("signing_url") String signingUrl) {}

  private record GroupResponse(
      List<GroupRequestEntry> requests,
      @JsonProperty("transaction_status") String transactionStatus,
      @JsonProperty("complete_signed_url") String completeSignedUrl) {}

  private record GroupRequestEntry(
      @JsonProperty("request_id") String requestId,
      String status,
      @JsonProperty("transaction_status") String transactionStatus) {

    /** The vendor spells the per-signer status differently across endpoints; accept either. */
    String effectiveStatus() {
      return status != null ? status : transactionStatus;
    }
  }
}
