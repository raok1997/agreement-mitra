package in.agreementmitra.signing.agreement;

import in.agreementmitra.ConflictException;
import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.documents.api.TemplateDetail;
import in.agreementmitra.signing.ClosureReason;
import in.agreementmitra.signing.ClosureState;
import in.agreementmitra.signing.PaymentConfirmation;
import in.agreementmitra.signing.PaymentState;
import in.agreementmitra.signing.SigningRequestQuery;
import in.agreementmitra.signing.api.AgreementDisplayStatus;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.AgreementResponse.SignerResponse;
import in.agreementmitra.signing.api.AgreementSummaryResponse;
import in.agreementmitra.signing.api.ClosureStateResponse;
import in.agreementmitra.signing.api.ContactsUpdateRequest;
import in.agreementmitra.signing.api.CreateAgreementRequest;
import in.agreementmitra.signing.api.PaymentStateResponse;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the agreement aggregate. Java-{@code public} so the {@code api}
 * controller (a different package in the same module) can inject it; still Modulith-internal (not
 * exported through a named interface).
 *
 * <p>Methods are {@code @Transactional} and map the entity to a response DTO <em>inside</em> the
 * transaction — {@code open-in-view: false}, so the lazy signer collection must be initialized
 * before the boundary or it would throw {@code LazyInitializationException}. No entity (or lazy
 * proxy) crosses back to the controller.
 */
@Service
public class AgreementService {

  private final AgreementRepository repository;
  private final TemplateCatalogApi templateCatalog;
  private final SigningRequestQuery signingRequestQuery;

  AgreementService(
      AgreementRepository repository,
      TemplateCatalogApi templateCatalog,
      SigningRequestQuery signingRequestQuery) {
    this.repository = repository;
    this.templateCatalog = templateCatalog;
    this.signingRequestQuery = signingRequestQuery;
  }

  @Transactional
  public AgreementResponse create(CreateAgreementRequest request) {
    // Resolve the catalog selection BEFORE any persistence so an unknown (state, type) rejects
    // cleanly with nothing stored. The server owns the template UUID end-to-end (never client-set).
    UUID selectedTemplateId = resolveSelectedTemplate(request.state(), request.type());
    Agreement agreement =
        Agreement.create(
            request.propertyAddress(),
            request.monthlyRent(),
            request.securityDeposit(),
            request.startDate(),
            request.endDate());
    request
        .signers()
        .forEach(
            s ->
                agreement.addSigner(
                    fullName(s),
                    s.firstName(),
                    s.lastName(),
                    s.fatherName(),
                    s.currentAddress(),
                    s.email(),
                    s.mobile(),
                    s.role()));
    if (selectedTemplateId != null) {
      agreement.selectTemplate(selectedTemplateId);
    }
    // Store the full capture state (M5): the working-set map + added optional sections.
    // Server-managed
    // keys are stripped (anti-mass-assignment, D2); a null/empty map persists a null capture state
    // so an
    // API client sending only the fixed fields renders via the fixed-column fallback exactly as
    // before.
    agreement.replaceCaptureState(
        sanitizeCaptureData(request.captureData()), request.activeSections());
    return toResponse(repository.save(agreement));
  }

  /**
   * Server-managed keys that must never round-trip through the user-content capture map
   * (anti-mass-assignment, D2): the id, owner, creation timestamp, derived duration, and the
   * template pin. They stay server-managed -- stripped here so a stored blob can never carry a
   * value for them (both snake_case and camelCase spellings, so neither shape smuggles one in). The
   * fixed typed columns remain authoritative at render (D3), independently of this strip.
   */
  private static final Set<String> SERVER_MANAGED_CAPTURE_KEYS =
      Set.of(
          "id",
          "ownerIdentityId",
          "owner_identity_id",
          "createdAt",
          "created_at",
          "durationMonths",
          "termMonths",
          "duration",
          "templateId",
          "template_id",
          "templateContentHash",
          "template_content_hash",
          "templateLayerVersions",
          "template_layer_versions",
          "trackingNumber",
          "trackingReference",
          "tracking_reference",
          "staffReference",
          "staff_reference");

  /**
   * Drop any server-managed key from the user-content capture map (anti-mass-assignment). Null in
   * -> null out (no capture state supplied). The copy is defensive; the aggregate copies again.
   */
  private static Map<String, String> sanitizeCaptureData(Map<String, String> data) {
    if (data == null) {
      return null;
    }
    Map<String, String> clean = new LinkedHashMap<>(data);
    clean.keySet().removeAll(SERVER_MANAGED_CAPTURE_KEYS);
    return clean;
  }

  /**
   * Resolve the published catalog template for the client-picked {@code (state, type)} to its
   * server-owned UUID. Both dimensions must be present to select; when either is absent the
   * agreement keeps today's default behaviour (no selection, {@code null}). An {@code (state,
   * type)} that no published template covers is rejected via the app-wide no-oracle 404 contract --
   * the catalog is the dimension-validation authority, and the {@code GlobalExceptionHandler}
   * renders a fixed detail that never echoes the requested dimensions.
   */
  private UUID resolveSelectedTemplate(String state, String type) {
    if (isBlank(state) || isBlank(type)) {
      return null;
    }
    return templateCatalog
        .publishedTemplateIdFor(state, type)
        .map(UUID::fromString)
        .orElseThrow(
            () ->
                new ResourceNotFoundException("no published template for the selected dimensions"));
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** Full name as per Aadhaar: the override when supplied (non-blank), else first + last name. */
  private static String fullName(CreateAgreementRequest.SignerRequest s) {
    String override = s.name();
    if (override != null && !override.isBlank()) {
      return override.trim();
    }
    return (s.firstName().trim() + " " + s.lastName().trim()).trim();
  }

  @Transactional(readOnly = true)
  public Optional<AgreementResponse> findById(UUID id) {
    return repository.findById(id).map(this::toResponse);
  }

  /**
   * Read an agreement with owner scoping (D5): an <b>unowned</b> agreement stays
   * capability-readable by anyone presenting the id; once <b>claimed</b>, only its owner may read
   * it. A non-owner (or anonymous, {@code callerIdentityId == null}) caller gets empty -- the
   * {@code api} layer renders the same 404 as an unknown id, so ownership can never be probed.
   */
  @Transactional(readOnly = true)
  public Optional<AgreementResponse> findByIdForReader(UUID id, UUID callerIdentityId) {
    return repository
        .findById(id)
        .filter(a -> a.ownerIdentityId() == null || a.ownerIdentityId().equals(callerIdentityId))
        .map(this::toResponse);
  }

  /**
   * Whether {@code callerIdentityId} may act on this agreement, using the <b>same</b> owner-scoping
   * rule as {@link #findByIdForReader} (D5): an <b>unowned</b> agreement is reachable by anyone
   * presenting the id (the unguessable id is a bearer capability, which is what keeps the anonymous
   * drafting + finalise flow working), and a <b>claimed</b> one only by its owner.
   *
   * <p>An unknown agreement is {@code false}, so the caller renders the same 404 either way and
   * ownership can never be probed. Used by the payment surface to decide who may start a payment or
   * read its progress; STAFF are authorized separately, before this is consulted.
   */
  @Transactional(readOnly = true)
  public boolean isAccessibleBy(UUID id, UUID callerIdentityId) {
    return repository
        .findById(id)
        .filter(a -> a.ownerIdentityId() == null || a.ownerIdentityId().equals(callerIdentityId))
        .isPresent();
  }

  /**
   * Claim (save) an unowned agreement for {@code ownerIdentityId} (D2). Idempotent for the same
   * owner; an unknown id or one owned by a <b>different</b> identity both raise {@link
   * ResourceNotFoundException} (404) -- claim is not an ownership/existence oracle. The row is
   * loaded under a write lock so two concurrent claims cannot both win. Owner is server-sourced
   * (the authenticated principal), never a client body field.
   */
  @Transactional
  public void claim(UUID agreementId, UUID ownerIdentityId) {
    Agreement agreement =
        repository
            .findByIdForUpdate(agreementId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Agreement not found: " + agreementId));
    try {
      agreement.claimBy(ownerIdentityId);
    } catch (IllegalStateException ownedByAnother) {
      // Owned by a different identity -> same 404 as unknown (no ownership oracle).
      throw new ResourceNotFoundException("Agreement not found: " + agreementId);
    }
    repository.save(agreement);
  }

  /**
   * The caller's agreements as list summaries, most-recent first (D3): each carries the
   * <b>derived</b> {@link AgreementDisplayStatus} (projected from the signing side, never stored)
   * and the {@code editable} flag. Only the caller's rows are returned; unowned drafts never
   * appear.
   */
  @Transactional(readOnly = true)
  public List<AgreementSummaryResponse> listOwnedBy(UUID ownerIdentityId) {
    return repository.findByOwnerIdentityIdOrderByCreatedAtDesc(ownerIdentityId).stream()
        .map(this::toSummary)
        .toList();
  }

  /**
   * Edit (fully replace the mutable terms + party list of) an agreement the caller <b>owns</b>
   * (D4). Owner-scoped: an unknown id or one owned by another identity is a 404 (no oracle).
   * Allowed only while no signing request exists -- the same freeze the draft path enforces -- else
   * 409. Body validation/mapping reuse the create path (the controller applies the same
   * bean-validation); parties are replaced wholesale; the selected template is re-resolved from
   * {@code (state, type)}; and the pinned draft is cleared so the next generate regenerates from
   * the edited terms.
   */
  @Transactional
  public AgreementResponse update(
      UUID agreementId, UUID ownerIdentityId, CreateAgreementRequest request) {
    Agreement agreement =
        repository
            .findByIdForUpdate(agreementId)
            .filter(a -> ownerIdentityId.equals(a.ownerIdentityId()))
            .orElseThrow(
                () -> new ResourceNotFoundException("Agreement not found: " + agreementId));
    if (signingRequestQuery.existsForAgreement(agreementId)) {
      throw ConflictException.draftFrozen();
    }
    // Resolve the catalog selection only after the owner + freeze gates pass (a bad selection on a
    // frozen/non-owned agreement never masks the 409/404). Null when no (state, type) is supplied.
    UUID selectedTemplateId = resolveSelectedTemplate(request.state(), request.type());
    agreement.replaceTerms(
        request.propertyAddress(),
        request.monthlyRent(),
        request.securityDeposit(),
        request.startDate(),
        request.endDate());
    agreement.clearSigners();
    request
        .signers()
        .forEach(
            s ->
                agreement.addSigner(
                    fullName(s),
                    s.firstName(),
                    s.lastName(),
                    s.fatherName(),
                    s.currentAddress(),
                    s.email(),
                    s.mobile(),
                    s.role()));
    agreement.selectTemplate(selectedTemplateId); // may be null -> clears any prior selection
    // Replace the capture state wholesale (D4), like the party list -- server-managed keys
    // stripped.
    agreement.replaceCaptureState(
        sanitizeCaptureData(request.captureData()), request.activeSections());
    agreement.clearDraftPin();
    return toResponse(repository.save(agreement));
  }

  /**
   * Set party contacts on an <b>unowned</b> agreement, and nothing else (design D16).
   *
   * <p>The pre-payment contact step needs a write path, and the customer using it is anonymous by
   * construction. {@link #update} cannot serve it: that route is owner-scoped and replaces terms
   * and the party list wholesale, so opening it to anonymous callers would let anyone holding the
   * identifier rewrite the rent. This one can only set contacts, so the worst a caller can do is
   * change where their own agreement's notifications go - which the identifier already implies they
   * are entitled to do.
   *
   * <p>Serves an <b>unowned</b> agreement, and an owned one <b>for its own owner</b>. Refusing the
   * owner outright (as this once did) locked a signed-in customer out of the one step everybody
   * else can complete: the contacts screen is the same screen whether or not they signed in, so
   * they were left unable to save contacts and therefore unable to receive the draft that is sent
   * straight afterwards. An agreement owned by somebody else is refused, and refused as a 404 -
   * identical to an unknown id, so ownership cannot be probed here.
   *
   * <p><b>Editable until payment settles, NOT until the order is placed.</b> This is deliberately a
   * different line from the one {@link #update} enforces. Terms freeze when the order is placed;
   * contacts do not, because they are not terms and never appear in the rendered agreement. The old
   * shared freeze cost a customer whose first payment failed any way back: the retry re-enters the
   * same contact step, whose save was refused from the moment the order existed, so the pay button
   * became permanently unreachable. It also meant a mistyped address could not be corrected one
   * second after finalising.
   *
   * <p>Payment is the right line because every artifact addressed TO a contact - the recovery link,
   * the purchased e-stamp, the signing invitation - is produced at or after payment. Change an
   * address once any of those exists and the record disagrees with what was actually sent. Before
   * payment none of them exists, so a correction costs nothing.
   *
   * <p><b>Closure is checked explicitly</b>, not inherited. The old signing-request gate refused a
   * closed agreement only as a side effect (a closed one has a signing request); dropping that gate
   * without saying so would have opened contacts on an abandoned order. Closed is reported before
   * paid, because a closed agreement is terminal whatever its payment state and naming payment
   * would send the reader after the wrong fact.
   *
   * <p>An id that is not a party on this agreement is rejected, not added: this route may never
   * introduce a party.
   *
   * @param identityId the authenticated caller, or null when the caller is anonymous
   */
  @Transactional
  public AgreementResponse updateContacts(
      UUID agreementId, UUID identityId, ContactsUpdateRequest request) {
    Agreement agreement =
        repository
            .findByIdForUpdate(agreementId)
            .filter(a -> a.ownerIdentityId() == null || a.ownerIdentityId().equals(identityId))
            .orElseThrow(
                () -> new ResourceNotFoundException("Agreement not found: " + agreementId));
    if (agreement.closureState() == ClosureState.CLOSED) {
      throw ConflictException.agreementClosed();
    }
    // Read off the aggregate already loaded above - the signing module is not asked a question this
    // one can answer about itself. WAIVED counts as settled: a waiver is staff asserting the money
    // question is closed, and fulfilment proceeds from there exactly as if paid.
    PaymentState payment = agreement.paymentState();
    if (payment == PaymentState.PAID || payment == PaymentState.WAIVED) {
      throw ConflictException.contactsFrozen();
    }

    Map<UUID, Signer> byId = new LinkedHashMap<>();
    agreement.signers().forEach(signer -> byId.put(signer.id(), signer));
    for (ContactsUpdateRequest.PartyContact contact : request.contacts()) {
      Signer signer = byId.get(contact.signerId());
      if (signer == null) {
        throw new ResourceNotFoundException("Party not found on this agreement");
      }
      signer.updateContacts(contact.email(), contact.mobile());
    }
    // The draft pin is deliberately NOT cleared. Contacts are not terms and do not appear in the
    // rendered agreement, so the document the parties are about to be emailed is still the one
    // they were shown.
    return toResponse(repository.save(agreement));
  }

  /**
   * Find an agreement that may be recovered from its tracking reference.
   *
   * <p>Deliberately <b>separate</b> from {@link #findByTrackingReference}, which returns the narrow
   * {@code StaffAgreementView} and is a STAFF capability. Sharing that method would have meant
   * widening a staff projection to serve an anonymous flow, and the two want different things:
   * staff need a non-PII summary to work a queue, recovery needs the party contacts in order to
   * send them a link. Keeping them apart means neither can be loosened by a change to the other.
   *
   * <p>What this returns never reaches the caller who supplied the reference. It is used only to
   * decide who to contact; the recovery endpoint answers identically whether this is empty or not
   * (design D1).
   *
   * <p>Eligibility is both conditions from design D6, evaluated here so no caller can forget one:
   * paid (or waived), and not yet owned. A claimed agreement is recovered by its owner signing in.
   */
  @Transactional(readOnly = true)
  public Optional<AgreementResponse> findRecoverableByTrackingReference(String reference) {
    String normalized = TrackingReference.normalize(reference);
    // A malformed or retired reference is rejected as malformed - never treated as "not found",
    // which would make the shape of the failure depend on whether a row existed.
    if (TrackingReference.isLegacyDerivedFormat(normalized)
        || !TrackingReference.isValid(normalized)) {
      return Optional.empty();
    }
    return repository
        .findByTrackingReference(normalized)
        .filter(agreement -> agreement.ownerIdentityId() == null)
        .filter(
            agreement ->
                agreement.paymentState() == PaymentState.PAID
                    || agreement.paymentState() == PaymentState.WAIVED)
        .map(this::toResponse);
  }

  private AgreementSummaryResponse toSummary(Agreement agreement) {
    AgreementDisplayStatus status =
        AgreementDisplayStatus.from(
            signingRequestQuery.currentStatusForAgreement(agreement.getId()));
    return new AgreementSummaryResponse(
        agreement.getId(),
        agreement.trackingReference(),
        agreement.propertyAddress(),
        agreement.monthlyRent(),
        agreement.startDate(),
        agreement.endDate(),
        agreement.termMonths(),
        agreement.createdAt(),
        status,
        status.editable());
  }

  /**
   * The agreement's draft object-storage key, or empty if the agreement has no draft (or does not
   * exist). Internal accessor for the signing flow — the key is server-internal and is deliberately
   * NOT exposed on the public {@link AgreementResponse}.
   */
  @Transactional(readOnly = true)
  public Optional<String> draftPdfKey(UUID id) {
    return repository.findById(id).map(Agreement::draftPdfKey);
  }

  /**
   * The agreement's attached stamp data, or empty if no stamp has been uploaded (or the agreement
   * does not exist). Internal accessor for the signing flow - deliberately NOT on the public {@link
   * AgreementResponse}, mirroring {@link #draftPdfKey}.
   */
  @Transactional(readOnly = true)
  public Optional<StampInfo> stampInfo(UUID id) {
    return repository.findById(id).map(Agreement::stampInfo);
  }

  /**
   * Resolve the agreement named by its persisted {@link TrackingReference}, as the narrow non-PII
   * {@link StaffAgreementView}. The value is normalised (trimmed + uppercased) and its <b>check
   * character</b> is verified before any query runs, so a single mistyped character is refused
   * outright rather than resolving to a different agreement -- the whole point of the reference
   * (design D3). The retired {@code AM-<LAST6>-<DDMMYY>} derived form is explicitly NOT a lookup
   * key and yields empty, as does any unknown reference; the caller renders both as the same 404,
   * so this is never an existence oracle.
   */
  @Transactional(readOnly = true)
  public Optional<StaffAgreementView> findByTrackingReference(String reference) {
    String normalized = TrackingReference.normalize(reference);
    if (TrackingReference.isLegacyDerivedFormat(normalized)
        || !TrackingReference.isValid(normalized)) {
      return Optional.empty();
    }
    return repository.findByTrackingReference(normalized).map(AgreementService::toStaffView);
  }

  /**
   * The same staff view for a batch of agreement ids, keyed by id -- backs the staff stamp-intake
   * queue, whose ordering comes from the signing side. A missing id is simply absent from the
   * result.
   *
   * <p>This is the one path that resolves the pinned template's name + state, because it is the one
   * path that feeds the purchasing queue. Templates are resolved <b>once per distinct template
   * id</b> across the whole batch, not once per row: a page of orders in one state overwhelmingly
   * shares a handful of templates, and a per-row lookup would be an N+1 against the catalog for no
   * benefit.
   */
  @Transactional(readOnly = true)
  public Map<UUID, StaffAgreementView> staffViewsByAgreementId(Collection<UUID> agreementIds) {
    if (agreementIds == null || agreementIds.isEmpty()) {
      return Map.of();
    }
    List<Agreement> agreements = repository.findAllById(agreementIds);
    Map<UUID, TemplateDetail> templates = resolveTemplates(agreements);
    Map<UUID, StaffAgreementView> views = new LinkedHashMap<>();
    for (Agreement agreement : agreements) {
      views.put(agreement.getId(), toStaffView(agreement, templates.get(agreement.templateId())));
    }
    return views;
  }

  /**
   * The distinct templates pinned across a batch, resolved through the {@code documents} module's
   * public catalog port. An id that does not resolve is simply absent from the map.
   *
   * <p>Deliberately the non-throwing {@code find}, not {@code detail}. An agreement pinned to a
   * since-deprecated template is still outstanding work, so an unresolvable template must degrade
   * the row (no name, no state) rather than fail the queue. {@code detail}'s exception cannot give
   * that: it runs inside <em>this</em> transaction, so throwing marks the shared transaction
   * rollback-only and the commit then fails with {@code UnexpectedRollbackException} <b>even though
   * the exception was caught here</b>. Catching is not enough across a transactional boundary - the
   * exception has to not happen.
   */
  private Map<UUID, TemplateDetail> resolveTemplates(List<Agreement> agreements) {
    Map<UUID, TemplateDetail> resolved = new LinkedHashMap<>();
    agreements.stream()
        .map(Agreement::templateId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .forEach(
            templateId ->
                templateCatalog
                    .find(templateId.toString())
                    .ifPresent(detail -> resolved.put(templateId, detail)));
    return resolved;
  }

  /**
   * The staff fulfilment projection. Carries what buying a certificate needs -- the parties by role
   * and the template's state -- and deliberately not the rest of the agreement (see {@link
   * StaffAgreementView}).
   *
   * <p>The <b>state</b> comes from the pinned template's dimension, never from the address: stamp
   * duty follows the state whose law the instrument was drafted under, and the stored address is
   * free text. The property city is still derived from that address (its last comma-separated
   * segment) as human context, subordinate to the state.
   */
  private static StaffAgreementView toStaffView(Agreement agreement, TemplateDetail template) {
    String address = agreement.propertyAddress();
    String city = null;
    if (address != null) {
      int lastComma = address.lastIndexOf(',');
      String candidate = lastComma < 0 ? null : address.substring(lastComma + 1).trim();
      city = candidate == null || candidate.isEmpty() ? null : candidate;
    }
    return new StaffAgreementView(
        agreement.getId(),
        agreement.trackingReference(),
        city,
        agreement.startDate(),
        template == null ? null : template.name(),
        template == null || template.dimensions() == null ? null : template.dimensions().state(),
        parties(agreement));
  }

  /**
   * Every party, owners before tenants and stored order preserved within each role, so that "first
   * party" and "second party" mean the same thing on every refresh rather than following row order.
   * All parties of a role are listed: an agreement may carry several owners or several tenants, and
   * showing one would hide a name the certificate needs.
   */
  private static List<StaffPartyView> parties(Agreement agreement) {
    return agreement.signers().stream()
        .sorted(java.util.Comparator.comparingInt(signer -> signer.role() == Role.OWNER ? 0 : 1))
        .map(signer -> new StaffPartyView(signer.role(), signer.name(), signer.fatherName()))
        .toList();
  }

  /**
   * The staff view without template metadata, for the paths that resolve a single agreement by its
   * reference (the upload itself) rather than listing the purchasing queue.
   */
  private static StaffAgreementView toStaffView(Agreement agreement) {
    return toStaffView(agreement, null);
  }

  /**
   * Attach the uploaded e-stamp certificate's data to the agreement. Server-managed only (never
   * client-settable). Joins the caller's transaction (REQUIRED) so the stamp-attach and the
   * signing-request {@code STAMPED} transition commit together -- and so the database's unique
   * constraint on the certificate number rolls both back if the certificate was already spent.
   */
  @Transactional
  public void attachStamp(UUID id, StampInfo stampInfo) {
    Agreement agreement =
        repository
            .findById(id)
            .orElseThrow(() -> new IllegalStateException("Agreement vanished: " + id));
    agreement.attachStamp(stampInfo);
    repository.save(agreement);
  }

  /**
   * The agreement's payment state, or empty when the agreement does not exist. Read by the payment
   * gate before any gated step (stamp intake, eSign initiation), so a refusal happens before any
   * blob is written, any provider is called, and any vendor charge is incurred.
   */
  @Transactional(readOnly = true)
  public Optional<PaymentState> paymentState(UUID id) {
    return repository.findById(id).map(Agreement::paymentState);
  }

  /** Payment state for a batch of agreements, keyed by id - backs the staff fulfilment queue. */
  @Transactional(readOnly = true)
  public Map<UUID, PaymentState> paymentStatesByAgreementId(Collection<UUID> agreementIds) {
    if (agreementIds == null || agreementIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, PaymentState> states = new LinkedHashMap<>();
    repository
        .findAllById(agreementIds)
        .forEach(agreement -> states.put(agreement.getId(), agreement.paymentState()));
    return states;
  }

  /** The agreement's payment state as the read/report view. 404 for an unknown agreement. */
  @Transactional(readOnly = true)
  public PaymentStateResponse paymentView(UUID id) {
    return repository
        .findById(id)
        .map(AgreementService::toPaymentView)
        .orElseThrow(() -> new ResourceNotFoundException("Agreement not found: " + id));
  }

  /**
   * Record a payment confirmation against the agreement (server-managed; STAFF-only at the API
   * edge). The database's unique index on the normalised external reference fires here if the same
   * payment was already recorded elsewhere - the caller maps that violation to 409 rather than
   * pre-checking, so two concurrent confirmations cannot both win.
   */
  @Transactional
  public PaymentStateResponse recordPayment(
      UUID id, PaymentConfirmation confirmation, UUID actorIdentityId) {
    Agreement agreement =
        repository
            .findByIdForUpdate(id)
            .orElseThrow(() -> new ResourceNotFoundException("Agreement not found: " + id));
    agreement.recordPayment(confirmation, actorIdentityId);
    return toPaymentView(repository.save(agreement));
  }

  /**
   * Waive payment for the agreement (server-managed; STAFF-only at the API edge). Stays
   * distinguishable from a real payment everywhere: no amount, currency, or external reference is
   * invented.
   */
  @Transactional
  public PaymentStateResponse waivePayment(UUID id, UUID actorIdentityId) {
    Agreement agreement =
        repository
            .findByIdForUpdate(id)
            .orElseThrow(() -> new ResourceNotFoundException("Agreement not found: " + id));
    agreement.waivePayment(actorIdentityId, Instant.now());
    return toPaymentView(repository.save(agreement));
  }

  /**
   * Close the agreement, recording <b>when</b> and <b>why</b> (signed-delivery-and-closure CR).
   * Server-managed and idempotent: the completion path is re-entered by both the webhook and the
   * reconciliation job, so a second close must leave the original reason and time untouched rather
   * than rewriting them. Loaded under a write lock so two concurrent closes cannot both win.
   *
   * <p>An unknown agreement is a quiet {@code false}, not an exception: closure is downstream
   * bookkeeping on a delivery path that must never throw into the completion path.
   *
   * @return true if this call is the one that closed it
   */
  @Transactional
  public boolean close(UUID agreementId, ClosureReason reason) {
    return repository
        .findByIdForUpdate(agreementId)
        .map(
            agreement -> {
              boolean closed = agreement.close(reason, Instant.now());
              if (closed) {
                repository.save(agreement);
              }
              return closed;
            })
        .orElse(false);
  }

  /**
   * Refuse a fulfilment action on a closed agreement. {@code CLOSED} is terminal: a closed
   * agreement never returns to an in-progress state, and reopening one for revision is deliberately
   * out of scope (a superseding agreement is a separate instrument). Callers MUST invoke this
   * <b>before any side effect</b>, exactly like the payment gate.
   *
   * <p>An unknown agreement passes through: resolving it is the caller's job and its own 404 is the
   * honest answer, so this never turns a missing agreement into a closure problem.
   *
   * @throws ConflictException {@code AGREEMENT_CLOSED} when the agreement has closed
   */
  @Transactional(readOnly = true)
  public void requireOpen(UUID agreementId) {
    boolean closed =
        repository
            .findById(agreementId)
            .map(a -> a.closureState() == ClosureState.CLOSED)
            .orElse(false);
    if (closed) {
      throw ConflictException.agreementClosed();
    }
  }

  /** The agreement's closure state, or empty when the agreement does not exist. */
  @Transactional(readOnly = true)
  public Optional<ClosureState> closureState(UUID agreementId) {
    return repository.findById(agreementId).map(Agreement::closureState);
  }

  /** Closure state for a batch of agreements - backs the outstanding-work queue filter. */
  @Transactional(readOnly = true)
  public Map<UUID, ClosureState> closureStatesByAgreementId(Collection<UUID> agreementIds) {
    if (agreementIds == null || agreementIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, ClosureState> states = new LinkedHashMap<>();
    repository
        .findAllById(agreementIds)
        .forEach(agreement -> states.put(agreement.getId(), agreement.closureState()));
    return states;
  }

  /** The agreement's closure state as the read/report view. 404 for an unknown agreement. */
  @Transactional(readOnly = true)
  public ClosureStateResponse closureView(UUID agreementId) {
    return repository
        .findById(agreementId)
        .map(
            agreement ->
                new ClosureStateResponse(
                    agreement.getId(),
                    agreement.closureState().name(),
                    agreement.closureReason() == null ? null : agreement.closureReason().name(),
                    agreement.closedAt()))
        .orElseThrow(() -> new ResourceNotFoundException("Agreement not found: " + agreementId));
  }

  private static PaymentStateResponse toPaymentView(Agreement agreement) {
    return new PaymentStateResponse(
        agreement.getId(),
        agreement.paymentState().name(),
        agreement.paymentAmount(),
        agreement.paymentCurrency(),
        agreement.paymentReference(),
        agreement.paymentRecordedAt());
  }

  /**
   * Record the selected catalog template's id on the agreement. Server-managed only (never
   * client-settable): the id is sourced from the catalog selection at the {@code api} layer --
   * where it is validated as a published template (via {@code documents.api}'s {@code
   * TemplateCatalogApi}) -- and passed inward as a plain {@link UUID} value, so {@code signing}
   * holds no {@code documents.template} type (Modulith-clean). The effective-template hash +
   * layer-version pin stays at generate-as-draft (document projection) -- unchanged here.
   *
   * <p>INTEGRATION NOTE: the create/update request wiring that routes a client-chosen template
   * through catalog validation into this setter is deliberately NOT added in this CR (the
   * CreateAgreementRequest / controller surface is being edited concurrently). This setter is the
   * server-managed call site; wiring it into the create flow is a flagged follow-up.
   */
  @Transactional
  public void recordSelectedTemplate(UUID agreementId, UUID templateId) {
    Agreement agreement =
        repository
            .findById(agreementId)
            .orElseThrow(() -> new IllegalStateException("Agreement vanished: " + agreementId));
    agreement.selectTemplate(templateId);
    repository.save(agreement);
  }

  private AgreementResponse toResponse(Agreement agreement) {
    var signers =
        agreement.signers().stream()
            .map(
                s ->
                    new SignerResponse(
                        s.id(),
                        s.name(),
                        s.firstName(),
                        s.lastName(),
                        s.fatherName(),
                        s.currentAddress(),
                        s.email(),
                        s.mobile(),
                        s.role()))
            .toList();
    // Return the stored capture state (owner-scoped read/edit) so the form can restore the optional
    // sections + dynamic values; null when no capture state was stored (legacy /
    // fixed-fields-only).
    CaptureState capture = agreement.captureState();
    Map<String, String> captureData = capture == null ? null : capture.data();
    List<String> activeSections = capture == null ? null : capture.activeSections();
    return new AgreementResponse(
        agreement.getId(),
        agreement.trackingReference(),
        agreement.propertyAddress(),
        agreement.monthlyRent(),
        agreement.securityDeposit(),
        agreement.startDate(),
        agreement.endDate(),
        agreement.termMonths(),
        agreement.createdAt(),
        signers,
        captureData,
        activeSections);
  }
}
