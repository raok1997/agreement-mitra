/**
 * Public HTTP + API surface of the {@code documents} module.
 *
 * <p>This is the templating stack's first public/named-interface surface, mirroring {@code
 * signing.api}. It exposes two ports and their DTOs, both implemented package-private in {@code
 * in.agreementmitra.documents.template} so no definition-record visibility is widened:
 *
 * <ul>
 *   <li>{@link in.agreementmitra.documents.api.TemplateFormApi} ({@code formFor(state, type) ->
 *       FormSchema}) with the {@link in.agreementmitra.documents.api.FormSchema} DTO tree -- the
 *       form-projection JSON contract;
 *   <li>{@link in.agreementmitra.documents.api.DocumentProjectionApi} (the {@code preview} and
 *       {@code generate} projections) with its request/result DTOs -- the document-projection port
 *       that resolves, validates, compiles, and renders, feeding the same compiled HTML to both the
 *       live pane and the PDF (parity). Its stateless HTTP surface is {@link
 *       in.agreementmitra.documents.api.DocumentProjectionController}.
 * </ul>
 *
 * <p>A {@code FormSchema} is system-owned <i>schema metadata</i> -- field keys, labels, widget
 * hints, types, defaults, validation bounds, enum options, groups -- and carries no signer PII: it
 * describes the <i>shape</i> of data a form will collect, never an instance of it. A {@code
 * DocumentProjectionRequest}, by contrast, carries the <i>instance</i> data (party PII): it is
 * escaped at compile time, served {@code no-store}, and never logged.
 */
@org.springframework.modulith.NamedInterface("api")
package in.agreementmitra.documents.api;
