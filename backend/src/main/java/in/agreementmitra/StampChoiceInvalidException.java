package in.agreementmitra;

/**
 * A checkout request's stamp choice is missing or not acceptable (state-stamp-duty-quoting): not
 * one of the server-recomputed stamp options, or a below-duty choice without an acknowledgement of
 * the current warning. Maps to 400. Carries only a fixed reason code and the public warning version
 * -- never an amount or anything else the client sent.
 */
public class StampChoiceInvalidException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why the choice was refused. Stable codes a client can branch on. */
  public enum Reason {
    CHOICE_REQUIRED,
    NOT_AN_OPTION,
    ACKNOWLEDGEMENT_REQUIRED,
    WARNING_VERSION_STALE
  }

  private final Reason reason;
  private final String currentWarningVersion;

  public StampChoiceInvalidException(Reason reason, String currentWarningVersion) {
    super("stamp choice refused: " + reason);
    this.reason = reason;
    this.currentWarningVersion = currentWarningVersion;
  }

  public Reason reason() {
    return reason;
  }

  public String currentWarningVersion() {
    return currentWarningVersion;
  }
}
