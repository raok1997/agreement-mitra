package in.agreementmitra.signing.agreement;

/**
 * A party to a rental agreement who must sign it. An agreement may have any number of each role.
 *
 * <p>Java-{@code public} so the module's {@code api} request/response records can name it, but the
 * enclosing {@code agreement} package is Modulith-internal — no other module may depend on it.
 */
public enum Role {
  OWNER,
  TENANT
}
