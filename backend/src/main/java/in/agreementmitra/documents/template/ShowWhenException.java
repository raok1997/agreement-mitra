package in.agreementmitra.documents.template;

/**
 * Raised by the {@code showWhen} DSL on a syntax error while parsing, or on an operand-type error
 * while evaluating. During resolution the validator translates a parse failure into a {@link
 * ResolutionException} (location-only, naming the clause). The message is structural only -- it
 * describes the grammar fault or the type mismatch, never a signer data value.
 */
final class ShowWhenException extends RuntimeException {

  ShowWhenException(String message) {
    super(message);
  }
}
