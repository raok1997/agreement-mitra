package in.agreementmitra.signing.staffalert;

import java.io.Serializable;
import java.util.UUID;

/**
 * The key of a {@link StaffAlert}: its subject and its kind, so one payment order can hold one
 * alert of each kind.
 *
 * <p><b>Safe as a record only because {@link StaffAlertRepository} is the bare {@code
 * Repository}.</b> Spring Data's {@code save} and {@code delete} fill an id-class instance through
 * setters, and a record has none. Nothing here saves or deletes an entity.
 *
 * <p>{@link #toString()} never prints the id: for a paid-order alert it is the agreement id, a
 * bearer credential.
 *
 * @param id the alert's subject: the agreement id for {@link StaffAlertKind#ORDER_PAID}, the
 *     payment order id for every other kind
 */
record StaffAlertKey(UUID id, StaffAlertKind kind) implements Serializable {

  @Override
  public String toString() {
    return "StaffAlertKey{kind=" + kind + "}";
  }
}
