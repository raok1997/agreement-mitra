package in.agreementmitra.signing.leegality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.signing.EsignProvider;
import in.agreementmitra.signing.SignRequest;
import in.agreementmitra.signing.SignSession;
import in.agreementmitra.signing.SignatureStatus;
import in.agreementmitra.signing.SignedDocument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Leegality (sandbox) adapter. Internal to the signing module — nothing outside the module
 * references this class; callers depend on {@link EsignProvider}. All vendor specifics live here:
 * the {@code X-Auth-Token} auth header, the per-endpoint API version, the request/response shapes,
 * and the webhook MAC algorithm.
 *
 * <p>Security: the webhook MAC is {@code HMAC-SHA1(documentId, webhookSecret)} and is carried in
 * the JSON body. It covers only the document id, so the rest of a webhook payload is untrusted —
 * {@link #getStatus} re-reads authoritative state. Never log document ids, signer PII, signing
 * URLs, or webhook payloads verbatim.
 */
@Component
class LeegalityEsignProvider implements EsignProvider {

  private static final Logger log = LoggerFactory.getLogger(LeegalityEsignProvider.class);

  private static final String CREATE_PATH = "v3.0/sign/request";
  private static final String DETAILS_PATH = "v3.3/document/details";
  private static final String HMAC_ALGORITHM = "HmacSHA1";
  private static final String UNSIGNED_PDF_NAME = "agreement.pdf";

  private final RestClient client;
  private final LeegalityProperties properties;
  private final ObjectMapper objectMapper;

  LeegalityEsignProvider(
      RestClient leegalityRestClient, LeegalityProperties properties, ObjectMapper objectMapper) {
    this.client = leegalityRestClient;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Override
  public SignSession createSignRequest(SignRequest request) {
    String base64Pdf = Base64.getEncoder().encodeToString(request.unsignedPdf());
    List<InvitePart> inviteParts =
        request.invitees().stream()
            .map(
                i ->
                    new InvitePart(
                        i.name(), i.email(), i.phone(), new AadhaarConfig(i.verifyName())))
            .toList();
    var body =
        new CreateBody(
            properties.profileId(), new FilePart(UNSIGNED_PDF_NAME, base64Pdf), inviteParts);

    log.debug("Creating Leegality sign request for {} invitee(s)", inviteParts.size());

    CreateResponse response =
        client
            .post()
            .uri(CREATE_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(CreateResponse.class);

    if (response == null || response.data() == null || response.data().documentId() == null) {
      throw new IllegalStateException("Leegality create response missing document id");
    }
    CreateData data = response.data();

    // The response invitees are returned in request order and carry no email; zip them back to the
    // request invitees so each signing URL keeps its owner's email.
    List<RespInvitee> respInvitees = data.invitees() == null ? List.of() : data.invitees();
    List<SignSession.InviteeSession> sessions = new ArrayList<>();
    for (int i = 0; i < respInvitees.size() && i < request.invitees().size(); i++) {
      sessions.add(
          new SignSession.InviteeSession(
              request.invitees().get(i).email(),
              respInvitees.get(i).signUrl(),
              respInvitees.get(i).expiryDate()));
    }
    log.debug(
        "Leegality created document {} with {} URL(s)", redact(data.documentId()), sessions.size());
    return new SignSession(data.documentId(), sessions);
  }

  @Override
  public SignatureStatus getStatus(String providerDocumentId) {
    DetailsResponse response =
        client
            .get()
            .uri(uri -> uri.path(DETAILS_PATH).queryParam("documentId", providerDocumentId).build())
            .retrieve()
            .body(DetailsResponse.class);

    String vendorStatus =
        response != null && response.data() != null && response.data().document() != null
            ? response.data().document().status()
            : null;
    SignatureStatus mapped = mapStatus(vendorStatus);
    log.debug(
        "Leegality document {} authoritative status -> {}", redact(providerDocumentId), mapped);
    return mapped;
  }

  @Override
  public SignedDocument download(String providerDocumentId) {
    // OUT OF SCOPE for CR-3: signed-PDF + audit-trail download and object storage are a later CR
    // (alongside stamp-composition / documents). Kept an explicit not-yet-supported stub.
    throw new UnsupportedOperationException("TODO: Leegality signed-document download (later CR)");
  }

  @Override
  public Optional<String> verifyWebhook(String payload) {
    try {
      JsonNode root = objectMapper.readTree(payload);
      String documentId = text(root, "documentId");
      String mac = text(root, "mac");
      if (documentId == null || mac == null) {
        log.warn("Webhook rejected: missing documentId or mac");
        return Optional.empty();
      }
      String expected = hmacSha1Hex(documentId, properties.webhookSecret());
      boolean ok =
          MessageDigest.isEqual(
              expected.getBytes(StandardCharsets.UTF_8), mac.getBytes(StandardCharsets.UTF_8));
      if (!ok) {
        log.warn("Webhook rejected: MAC mismatch for document {}", redact(documentId));
        return Optional.empty();
      }
      return Optional.of(documentId);
    } catch (Exception e) {
      // Never log the payload; the message is generic on purpose.
      log.warn("Webhook rejected: payload could not be verified");
      return Optional.empty();
    }
  }

  /**
   * Map a Leegality {@code document.status} to our vendor-neutral FSM status. Terminal outcomes map
   * to {@link SignatureStatus#SIGNED}/{@link SignatureStatus#FAILED}/{@link
   * SignatureStatus#EXPIRED}; any in-flight or unknown value maps to the non-terminal {@link
   * SignatureStatus#SIGN_REQUESTED} (a safe no-op for the FSM driver).
   */
  static SignatureStatus mapStatus(String vendorStatus) {
    if (vendorStatus == null) {
      return SignatureStatus.SIGN_REQUESTED;
    }
    return switch (vendorStatus.trim().toUpperCase(Locale.ROOT)) {
      case "COMPLETED", "SIGNED" -> SignatureStatus.SIGNED;
      case "REJECTED", "FAILED", "DECLINED" -> SignatureStatus.FAILED;
      case "EXPIRED" -> SignatureStatus.EXPIRED;
      default -> SignatureStatus.SIGN_REQUESTED; // DRAFT / SENT / in-flight / unknown
    };
  }

  static String hmacSha1Hex(String data, String secret) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(
          new SecretKeySpec(
              secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8),
              HMAC_ALGORITHM));
      byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(raw.length * 2);
      for (byte b : raw) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("HMAC computation failed", e);
    }
  }

  /** Redact a document id for logs — keep only the last 4 chars so a leak is not exploitable. */
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

  // --- Vendor wire shapes (kept private; never leak outside the adapter) ---

  private record CreateBody(String profileId, FilePart file, List<InvitePart> invitees) {}

  private record FilePart(String name, String file) {}

  private record InvitePart(String name, String email, String phone, AadhaarConfig aadhaarConfig) {}

  private record AadhaarConfig(boolean verifyName) {}

  private record CreateResponse(String status, CreateData data) {}

  private record CreateData(String documentId, List<RespInvitee> invitees) {}

  private record RespInvitee(String signUrl, String expiryDate) {}

  private record DetailsResponse(DetailsData data) {}

  private record DetailsData(DocumentInfo document) {}

  private record DocumentInfo(String status) {}
}
