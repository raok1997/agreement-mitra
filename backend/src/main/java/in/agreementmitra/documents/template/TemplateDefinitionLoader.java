package in.agreementmitra.documents.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Loads a template definition: parse YAML -&gt; structural (JSON-Schema) validation -&gt; bind to
 * the immutable model -&gt; semantic (cross-reference) validation -&gt; canonical JSON + SHA-256
 * content hash. Two-stage and <b>reject-or-nothing</b>: any failure raises {@link
 * TemplateDefinitionException} and returns no model, never a partial one.
 *
 * <p>YAML is read with a plain {@link YAMLMapper}, which does no polymorphic/arbitrary-type
 * instantiation (no default typing is enabled), so a definition file can only produce data nodes --
 * never construct arbitrary Java types. The only inputs are system-owned definition files, but the
 * parse is hardened regardless.
 */
final class TemplateDefinitionLoader {

  private static final String SCHEMA_RESOURCE =
      "documents/template/template-definition.schema.json";

  private final YAMLMapper yaml = new YAMLMapper();
  private final JsonSchema schema;
  private final TemplateDefinitionValidator semanticValidator = new TemplateDefinitionValidator();

  TemplateDefinitionLoader() {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("template-definition schema not found: " + SCHEMA_RESOURCE);
      }
      this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
    } catch (IOException e) {
      throw new IllegalStateException("failed to load template-definition schema", e);
    }
  }

  /** Load a definition from a YAML string. */
  TemplateDefinition load(String yamlSource) {
    JsonNode tree;
    try {
      tree = yaml.readTree(yamlSource);
    } catch (IOException e) {
      throw new TemplateDefinitionException("definition is not well-formed YAML");
    }
    return bindAndValidate(tree);
  }

  /** Load a definition from a classpath resource (e.g. the reference fixture). */
  TemplateDefinition loadResource(String classpathPath) {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(classpathPath)) {
      if (in == null) {
        throw new TemplateDefinitionException("definition resource not found: " + classpathPath);
      }
      return bindAndValidate(yaml.readTree(in));
    } catch (IOException e) {
      throw new TemplateDefinitionException("definition resource is not well-formed YAML");
    }
  }

  private TemplateDefinition bindAndValidate(JsonNode root) {
    if (root == null || root.isMissingNode() || root.isNull()) {
      throw new TemplateDefinitionException("structural validation failed: definition is empty");
    }
    // 1. Structural: validate the raw tree against the checked-in JSON Schema.
    Set<ValidationMessage> violations = schema.validate(root);
    if (!violations.isEmpty()) {
      // Cite only the instance location + violated keyword -- never the offending value.
      Set<String> located = new TreeSet<>();
      for (ValidationMessage v : violations) {
        located.add(v.getInstanceLocation() + " (" + v.getType() + ")");
      }
      throw new TemplateDefinitionException(
          "structural validation failed: " + String.join("; ", located));
    }
    // 2. Bind to the immutable model.
    TemplateDefinition bound = bind(root);
    // 3. Semantic: cross-reference checks the schema cannot express.
    semanticValidator.validate(bound);
    // 4. Canonical form + content hash -> final identity-carrying model.
    String canonical =
        CanonicalJson.canonicalize(bound.meta(), bound.fields(), bound.clauses(), bound.sections());
    String contentHash = CanonicalJson.contentHash(canonical);
    return new TemplateDefinition(
        bound.meta(), bound.fields(), bound.clauses(), bound.sections(), contentHash);
  }

  private TemplateDefinition bind(JsonNode root) {
    Meta meta = bindMeta(root.get("meta"));

    List<Field> fields = new ArrayList<>();
    for (JsonNode field : root.get("fields")) {
      fields.add(TemplateNodeBinder.bindField(field));
    }

    List<Clause> clauses = new ArrayList<>();
    JsonNode clauseNodes = root.get("clauses");
    if (clauseNodes != null) {
      for (JsonNode clause : clauseNodes) {
        clauses.add(TemplateNodeBinder.bindClause(clause));
      }
    }

    List<Section> sections = new ArrayList<>();
    for (JsonNode section : root.get("sections")) {
      sections.add(TemplateNodeBinder.bindSection(section));
    }

    return new TemplateDefinition(meta, fields, clauses, sections, null);
  }

  private Meta bindMeta(JsonNode meta) {
    JsonNode dimensions = meta.get("dimensions");
    Dimensions dims =
        new Dimensions(
            TemplateNodeBinder.text(dimensions, "state"),
            TemplateNodeBinder.text(dimensions, "type"));
    TemplateStatus status;
    try {
      status = TemplateStatus.from(meta.get("status").asText());
    } catch (IllegalArgumentException e) {
      // The schema enum already guards this; defensive only.
      throw new TemplateDefinitionException("/meta/status: unknown status");
    }
    return new Meta(
        TemplateNodeBinder.text(meta, "id"),
        dims,
        meta.get("version").asInt(),
        status,
        TemplateNodeBinder.bindDocumentMeta(meta));
  }
}
