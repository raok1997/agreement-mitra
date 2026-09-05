package in.agreementmitra.signing.payment;

import java.util.UUID;

/**
 * Published once, after commit, when an agreement's payment is confirmed.
 *
 * <p><b>Why an event rather than a direct call.</b> Three producers can confirm a payment - the
 * browser callback, the webhook, and the reconciliation job - and each must have the same
 * downstream effect. Wiring the effect into each of them means three places to forget it; wiring it
 * to the single point where the transition actually happens means one. {@code
 * PaymentConfirmations.apply} already reports {@code CONFIRMED} exactly once per order, whichever
 * producer got there first, so the event inherits that idempotence.
 *
 * <p><b>After commit, deliberately.</b> Listeners send outbound messages. Running them inside the
 * confirming transaction would hold a row lock across a third party's availability, and would send
 * a message about a payment that could still roll back.
 *
 * @param agreementId the agreement whose payment was confirmed
 */
public record PaymentConfirmedEvent(UUID agreementId) {}
