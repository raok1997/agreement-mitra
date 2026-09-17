package in.agreementmitra.rules;

/**
 * Identifies the duty rule a quote was computed under.
 *
 * @param contentHash SHA-256 of the rule's merged computational content; changes whenever the
 *     calculation it describes changes
 * @param reviewed true only when the rule carries a counsel review whose recorded {@code
 *     contentHash} equals this rule's hash -- so editing a reviewed rule invalidates its review
 */
public record RuleRef(String id, String legalReference, String contentHash, boolean reviewed) {}
