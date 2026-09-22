package in.agreementmitra.rules.duty;

/**
 * Malformed stamp duty rule or stamp paper catalog data. Thrown while loading, so the application
 * refuses to start rather than quoting from a broken rule mid-checkout.
 */
class RuleSetDefinitionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  RuleSetDefinitionException(String subject, String source, String defect) {
    super(subject + " (" + source + "): " + defect);
  }
}
