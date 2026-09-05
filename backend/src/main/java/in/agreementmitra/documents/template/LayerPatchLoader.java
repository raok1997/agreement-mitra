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
 * Loads a {@link LayerPatch}: parse YAML -&gt; structural (JSON-Schema) validation against {@code
 * layer-patch.schema.json} -&gt; bind to the immutable patch model. Mirrors {@link
 * TemplateDefinitionLoader} and is <b>reject-or-nothing</b>: any failure raises {@link
 * ResolutionException} citing a structural location only, and returns no patch.
 *
 * <p>Patch payloads ({@code addField}/{@code addClause}/{@code addSection} and the {@code replace*}
 * variants) are bound by the shared {@link TemplateNodeBinder}, byte-identically to a definition's
 * fields/clauses/sections -- so a composed effective template hashes consistently with a plain
 * definition. Like the definition loader, YAML is read with a plain {@link YAMLMapper} (no default
 * typing), so a patch file can only produce data nodes, never construct arbitrary Java types.
 */
final class LayerPatchLoader {

  private static final String SCHEMA_RESOURCE = "documents/template/layer-patch.schema.json";

  private final YAMLMapper yaml = new YAMLMapper();
  private final JsonSchema schema;

  LayerPatchLoader() {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("layer-patch schema not found: " + SCHEMA_RESOURCE);
      }
      this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
    } catch (IOException e) {
      throw new IllegalStateException("failed to load layer-patch schema", e);
    }
  }

  /** Load a patch from a YAML string. */
  LayerPatch load(String yamlSource) {
    JsonNode tree;
    try {
      tree = yaml.readTree(yamlSource);
    } catch (IOException e) {
      throw new ResolutionException("patch is not well-formed YAML");
    }
    return bindAndValidate(tree);
  }

  /** Load a patch from a classpath resource. */
  LayerPatch loadResource(String classpathPath) {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(classpathPath)) {
      if (in == null) {
        throw new ResolutionException("patch resource not found: " + classpathPath);
      }
      return bindAndValidate(yaml.readTree(in));
    } catch (IOException e) {
      throw new ResolutionException("patch resource is not well-formed YAML");
    }
  }

  private LayerPatch bindAndValidate(JsonNode root) {
    if (root == null || root.isMissingNode() || root.isNull()) {
      throw new ResolutionException("structural validation failed: patch is empty");
    }
    Set<ValidationMessage> violations = schema.validate(root);
    if (!violations.isEmpty()) {
      // Cite only the instance location + violated keyword -- never the offending value.
      Set<String> located = new TreeSet<>();
      for (ValidationMessage v : violations) {
        located.add(v.getInstanceLocation() + " (" + v.getType() + ")");
      }
      throw new ResolutionException("structural validation failed: " + String.join("; ", located));
    }
    return bind(root);
  }

  private LayerPatch bind(JsonNode root) {
    LayerPatch.PatchMeta meta = bindMeta(root.get("meta"));
    List<Op> ops = new ArrayList<>();
    for (JsonNode op : root.get("ops")) {
      ops.add(bindOp(op));
    }
    return new LayerPatch(meta, ops);
  }

  private LayerPatch.PatchMeta bindMeta(JsonNode meta) {
    LayerKind kind;
    try {
      kind = LayerKind.from(meta.get("kind").asText());
    } catch (IllegalArgumentException e) {
      // The schema enum already guards this; defensive only.
      throw new ResolutionException("/meta/kind: unknown layer kind");
    }
    JsonNode dims = meta.get("dimensions");
    String state = dims != null && dims.hasNonNull("state") ? dims.get("state").asText() : null;
    String type = dims != null && dims.hasNonNull("type") ? dims.get("type").asText() : null;
    return new LayerPatch.PatchMeta(kind, new Dimensions(state, type), meta.get("version").asInt());
  }

  private Op bindOp(JsonNode op) {
    String name = op.get("op").asText();
    return switch (name) {
      case "addField" -> new Op.AddField(TemplateNodeBinder.bindField(op.get("field")));
      case "overrideField" -> bindOverrideField(op);
      case "removeField" -> new Op.RemoveField(op.get("key").asText());
      case "addClause" ->
          new Op.AddClause(
              optionalText(op, "after"), TemplateNodeBinder.bindClause(op.get("clause")));
      case "replaceClause" ->
          new Op.ReplaceClause(
              op.get("id").asText(), TemplateNodeBinder.bindClause(op.get("clause")));
      case "removeClause" -> new Op.RemoveClause(op.get("id").asText());
      case "addSection" ->
          new Op.AddSection(
              optionalText(op, "after"), TemplateNodeBinder.bindSection(op.get("section")));
      case "replaceSection" ->
          new Op.ReplaceSection(
              op.get("title").asText(), TemplateNodeBinder.bindSection(op.get("section")));
      case "removeSection" -> new Op.RemoveSection(op.get("title").asText());
      case "reorderSections" -> new Op.ReorderSections(stringList(op.get("order")));
      case "reorderEntries" ->
          new Op.ReorderEntries(op.get("title").asText(), stringList(op.get("order")));
      // The schema oneOf already rejects any unknown op; defensive only.
      default -> throw new ResolutionException("unsupported operation '" + name + "'");
    };
  }

  private Op.OverrideField bindOverrideField(JsonNode op) {
    Boolean required = op.hasNonNull("required") ? op.get("required").asBoolean() : null;
    boolean defaultPresent = op.has("default");
    Object defaultValue = defaultPresent ? TemplateNodeBinder.scalar(op.get("default")) : null;
    List<String> options = op.has("options") ? stringList(op.get("options")) : null;
    FieldValidation validation =
        op.has("validation") ? TemplateNodeBinder.bindValidation(op.get("validation")) : null;
    String group = optionalText(op, "group");
    String label = optionalText(op, "label");
    return new Op.OverrideField(
        op.get("key").asText(),
        required,
        defaultPresent,
        defaultValue,
        options,
        validation,
        group,
        label);
  }

  private static String optionalText(JsonNode node, String property) {
    return node.hasNonNull(property) ? node.get(property).asText() : null;
  }

  private static List<String> stringList(JsonNode array) {
    List<String> values = new ArrayList<>();
    for (JsonNode item : array) {
      values.add(item.asText());
    }
    return values;
  }
}
