package in.agreementmitra.documents.template;

import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.documents.api.FormSchema;
import in.agreementmitra.documents.api.TemplateFormApi;
import org.springframework.stereotype.Service;

/**
 * Package-private implementation of the public {@link TemplateFormApi} port. Resolves {@code
 * (state, type)} to an {@link EffectiveTemplate} via the {@link TemplateResolver}, then projects it
 * to a {@link FormSchema} with the {@link FormProjector}. Kept in {@code documents.template} (same
 * package as the records and the projector) so no definition-record visibility is widened; Spring
 * wires it as the {@code TemplateFormApi} bean the controller consumes.
 */
@Service
class TemplateFormService implements TemplateFormApi {

  private final TemplateResolver resolver;
  private final FormProjector projector;

  TemplateFormService(TemplateResolver resolver, FormProjector projector) {
    this.resolver = resolver;
    this.projector = projector;
  }

  @Override
  public FormSchema formFor(String state, String type) {
    EffectiveTemplate effective;
    try {
      effective = resolver.resolve(new Dimensions(state, type));
    } catch (ResolutionException e) {
      // No effective template resolves for these dimensions (e.g. no base layer). Surface as the
      // app-wide 404 contract; GlobalExceptionHandler maps it to an RFC 9457 ProblemDetail whose
      // detail is a fixed constant and never echoes the requested state/type.
      throw new ResourceNotFoundException("no template for the requested dimensions");
    }
    return projector.project(effective);
  }
}
