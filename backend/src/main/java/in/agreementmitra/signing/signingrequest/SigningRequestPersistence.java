package in.agreementmitra.signing.signingrequest;

import in.agreementmitra.signing.SignatureStatus;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional persistence steps of the signing-request lifecycle, kept as a separate bean so
 * each step is its own short transaction — the provider HTTP call happens in {@link
 * SigningRequestService}, <em>between</em> {@link #createPending} and {@link #markRequested}, with
 * no transaction held open across the network round-trip (design D9).
 */
@Component
class SigningRequestPersistence {

  private static final Logger log = LoggerFactory.getLogger(SigningRequestPersistence.class);

  private final SigningRequestRepository repository;

  SigningRequestPersistence(SigningRequestRepository repository) {
    this.repository = repository;
  }

  /**
   * tx1: persist the pre-request row before the provider call, so a later failure is recoverable.
   */
  @Transactional
  UUID createPending(UUID agreementId) {
    SigningRequest request = SigningRequest.createPending(agreementId);
    return repository.save(request).getId();
  }

  /** tx2: attach the provider document id + per-signer URLs and move to {@code SIGN_REQUESTED}. */
  @Transactional
  void markRequested(UUID id, String providerDocumentId, List<SigningRequestInvitee> inviteeRows) {
    SigningRequest request =
        repository
            .findById(id)
            .orElseThrow(() -> new IllegalStateException("Signing request vanished: " + id));
    request.markRequested(providerDocumentId, inviteeRows);
    repository.save(request);
  }

  /**
   * Drive the FSM off the authoritative provider status. No-op for an unknown document id (the
   * webhook must stay indistinguishable) or a non-terminal status. Optimistic-lock contention from
   * a concurrent delivery is swallowed — the winning transition already applied a single legal
   * terminal state.
   */
  @Transactional
  void applyAuthoritativeStatus(String providerDocumentId, SignatureStatus authoritative) {
    if (!isTerminal(authoritative)) {
      return; // still in flight — safe no-op
    }
    var maybe = repository.findByProviderDocumentId(providerDocumentId);
    if (maybe.isEmpty()) {
      return; // unknown document — indistinguishable no-op
    }
    try {
      SigningRequest request = maybe.get();
      request.transitionTo(authoritative);
      repository.save(request);
    } catch (ObjectOptimisticLockingFailureException e) {
      // A concurrent delivery already committed a terminal transition; nothing to do.
      log.debug("Concurrent transition lost the race for one document; ignoring.");
    }
  }

  private static boolean isTerminal(SignatureStatus status) {
    return status == SignatureStatus.SIGNED
        || status == SignatureStatus.FAILED
        || status == SignatureStatus.EXPIRED;
  }
}
