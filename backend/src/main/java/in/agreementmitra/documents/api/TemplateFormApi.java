package in.agreementmitra.documents.api;

/**
 * Public port of the {@code documents} module for <b>form projection</b>: resolve a {@code (state,
 * type)} pair to its effective template and project it to a {@link FormSchema}.
 *
 * <p>The projection is a pure, deterministic, <b>data-independent</b> function of the effective
 * template -- it reads no user data and evaluates no {@code showWhen}. The implementation is
 * package-private in {@code in.agreementmitra.documents.template}; callers depend only on this
 * port.
 */
public interface TemplateFormApi {

  /**
   * Project the effective template for {@code (state, type)} to its {@link FormSchema}.
   *
   * @param state the state/jurisdiction dimension token
   * @param type the agreement-type dimension token
   * @return the immutable form schema for the resolved effective template
   * @throws in.agreementmitra.ResourceNotFoundException if {@code (state, type)} resolves to no
   *     template. The root {@code GlobalExceptionHandler} maps this to a 404 RFC 9457 {@code
   *     ProblemDetail} whose detail is a fixed constant and never echoes the requested input.
   */
  FormSchema formFor(String state, String type);
}
