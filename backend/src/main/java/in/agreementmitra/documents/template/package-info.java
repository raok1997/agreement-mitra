/**
 * Template-definition model (internal sub-package of the {@code documents} module).
 *
 * <p>A <b>template definition</b> is a declarative document -- {@code meta / fields / clauses /
 * sections} -- authored in YAML and compiled to a canonical JSON form from which both a capture
 * form and a rendered document will later be projected. This package lands only the
 * <i>foundation</i>: the immutable in-memory model ({@link
 * in.agreementmitra.documents.template.TemplateDefinition} and its parts), a two-stage
 * reject-or-nothing loader ({@link in.agreementmitra.documents.template.TemplateDefinitionLoader}:
 * YAML parse -&gt; JSON-Schema structural validation -&gt; semantic cross-reference validation
 * -&gt; canonical JSON + SHA-256 content hash), and the definition's {@code (id, version,
 * contentHash)} identity.
 *
 * <p>The definition/resolution/compiler records and services here are <b>package-private</b>: the
 * module's public API is the {@code documents.api} named interface (form projection + document
 * projection) plus the root-package {@link in.agreementmitra.documents.HtmlPdfRenderer} seam. The
 * {@link in.agreementmitra.documents.template.TemplateCompiler} composes an effective definition +
 * data map into the self-contained HTML that the projection service renders to a PDF via that seam.
 * This package carries no signer PII in its model: a definition describes the <i>shape</i> of data
 * a document will collect; only a transient projection data map holds an instance of it.
 *
 * <p>No {@code @ApplicationModule} annotation: this is a sub-package of {@code documents}, not a
 * module of its own, so it stays inside the {@code documents} boundary and {@code ModularityTests}
 * remains green.
 */
package in.agreementmitra.documents.template;
