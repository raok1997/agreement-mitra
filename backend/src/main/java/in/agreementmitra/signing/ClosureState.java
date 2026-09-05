package in.agreementmitra.signing;

/**
 * The agreement's terminal <b>fulfilment</b> state (design D1): is there work outstanding?
 *
 * <p>This is NOT a signing state. The signing FSM answers "what happened to the signatures" and its
 * terminal states are a legal record; this answers an operational question that continues past
 * signing and also applies to an agreement that never signed at all. It sits on the agreement
 * beside {@link PaymentState}, which already established that fulfilment state lives there.
 *
 * <p>{@link #CLOSED} is terminal: a closed agreement never returns to {@link #OPEN}. Closure means
 * "no work outstanding" - it is not archival, retention expiry, or deletion, and it withdraws no
 * access. A party can still retrieve their signed agreement indefinitely afterwards.
 */
public enum ClosureState {
  OPEN,
  CLOSED
}
