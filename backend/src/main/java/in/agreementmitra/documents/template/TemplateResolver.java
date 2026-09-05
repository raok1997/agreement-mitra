package in.agreementmitra.documents.template;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Composes a {@code (state, type)} into one immutable, hash-pinned {@link EffectiveTemplate}. A
 * <b>deterministic, data-independent pure function</b> of {@code (dimensions, resolved layer
 * versions)}: it reads no user data and evaluates no {@code showWhen}. It starts from the base
 * definition, applies each patch's operations in author order with layers in fixed precedence order
 * ({@code base -> type -> state -> state_type}, last-wins), re-validates the composed result
 * reject-or-nothing, and hashes it with the shared {@link CanonicalJson}.
 *
 * <p>Re-validation runs the {@link TemplateDefinitionValidator} definition semantics (unique
 * keys/ids, slots resolve, section entries resolve, enum/options, default type-consistency) plus
 * the resolution-specific target checks performed while applying each op, plus {@link
 * ShowWhenValidator}. Any fault raises {@link ResolutionException} and yields no template.
 */
final class TemplateResolver {

  private final LayerSource layerSource;
  private final TemplateDefinitionValidator semanticValidator = new TemplateDefinitionValidator();

  TemplateResolver(LayerSource layerSource) {
    this.layerSource = layerSource;
  }

  EffectiveTemplate resolve(Dimensions dimensions) {
    LayerSource.LayerSet layers = layerSource.layersFor(dimensions.state(), dimensions.type());
    TemplateDefinition base = layers.base().definition();

    List<Field> fields = new ArrayList<>(base.fields());
    List<Clause> clauses = new ArrayList<>(base.clauses());
    List<Section> sections = new ArrayList<>(base.sections());

    for (LayerSource.LayerSet.Patch patch : layers.patches()) {
      String layerId = patch.ref().id();
      for (Op op : patch.patch().ops()) {
        apply(op, fields, clauses, sections, layerId);
      }
    }

    TemplateDefinition composed =
        new TemplateDefinition(base.meta(), fields, clauses, sections, null);
    try {
      semanticValidator.validate(composed);
    } catch (TemplateDefinitionException e) {
      throw new ResolutionException("effective template is invalid: " + e.getMessage());
    }
    ShowWhenValidator.validate(composed);

    String canonical =
        CanonicalJson.canonicalize(
            composed.meta(), composed.fields(), composed.clauses(), composed.sections());
    String contentHash = CanonicalJson.contentHash(canonical);
    TemplateDefinition materialized =
        new TemplateDefinition(
            composed.meta(),
            composed.fields(),
            composed.clauses(),
            composed.sections(),
            contentHash);

    Map<String, Integer> provenance = new LinkedHashMap<>();
    provenance.put(layers.base().ref().id(), layers.base().ref().version());
    for (LayerSource.LayerSet.Patch patch : layers.patches()) {
      provenance.put(patch.ref().id(), patch.ref().version());
    }

    return new EffectiveTemplate(materialized, dimensions, provenance, contentHash);
  }

  // --- operation application -------------------------------------------------

  private void apply(
      Op op, List<Field> fields, List<Clause> clauses, List<Section> sections, String layerId) {
    switch (op) {
      case Op.AddField add -> fields.add(add.field());
      case Op.OverrideField override -> applyOverride(override, fields, layerId);
      case Op.RemoveField remove -> {
        int idx = fieldIndex(fields, remove.key());
        requireFound(idx, layerId, "field", remove.key());
        fields.remove(idx);
      }
      case Op.AddClause add -> applyAddClause(add, clauses, layerId);
      case Op.ReplaceClause replace -> {
        int idx = inlineClauseIndex(clauses, replace.id());
        requireFound(idx, layerId, "clause", replace.id());
        clauses.set(idx, replace.clause());
      }
      case Op.RemoveClause remove -> {
        int idx = inlineClauseIndex(clauses, remove.id());
        requireFound(idx, layerId, "clause", remove.id());
        clauses.remove(idx);
      }
      case Op.AddSection add -> applyAddSection(add, sections, layerId);
      case Op.ReplaceSection replace -> {
        int idx = sectionIndex(sections, replace.title());
        requireFound(idx, layerId, "section", replace.title());
        sections.set(idx, replace.section());
      }
      case Op.RemoveSection remove -> {
        int idx = sectionIndex(sections, remove.title());
        requireFound(idx, layerId, "section", remove.title());
        sections.remove(idx);
      }
      case Op.ReorderSections reorder -> applyReorderSections(reorder, sections, layerId);
      case Op.ReorderEntries reorder -> applyReorderEntries(reorder, sections, layerId);
    }
  }

  private void applyOverride(Op.OverrideField override, List<Field> fields, String layerId) {
    int idx = fieldIndex(fields, override.key());
    requireFound(idx, layerId, "field", override.key());
    Field current = fields.get(idx);
    Field updated =
        new Field(
            current.key(),
            override.label() != null ? override.label() : current.label(),
            current.type(),
            override.required() != null ? override.required() : current.required(),
            override.defaultPresent() ? override.defaultValue() : current.defaultValue(),
            override.options() != null ? override.options() : current.options(),
            override.validation() != null ? override.validation() : current.validation(),
            override.group() != null ? override.group() : current.group());
    fields.set(idx, updated);
  }

  private void applyAddClause(Op.AddClause add, List<Clause> clauses, String layerId) {
    if (add.after() == null) {
      clauses.add(add.clause());
      return;
    }
    int idx = inlineClauseIndex(clauses, add.after());
    requireFound(idx, layerId, "clause", add.after());
    clauses.add(idx + 1, add.clause());
  }

  private void applyAddSection(Op.AddSection add, List<Section> sections, String layerId) {
    if (add.after() == null) {
      sections.add(add.section());
      return;
    }
    int idx = sectionIndex(sections, add.after());
    requireFound(idx, layerId, "section", add.after());
    sections.add(idx + 1, add.section());
  }

  private void applyReorderSections(
      Op.ReorderSections reorder, List<Section> sections, String layerId) {
    if (reorder.order().size() != sections.size()) {
      throw new ResolutionException(
          "layer '" + layerId + "': reorderSections order is not a permutation of the sections");
    }
    List<Section> reordered = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String title : reorder.order()) {
      int idx = sectionIndex(sections, title);
      requireFound(idx, layerId, "section", title);
      if (!seen.add(title)) {
        throw new ResolutionException(
            "layer '" + layerId + "': reorderSections lists section '" + title + "' twice");
      }
      reordered.add(sections.get(idx));
    }
    sections.clear();
    sections.addAll(reordered);
  }

  private void applyReorderEntries(
      Op.ReorderEntries reorder, List<Section> sections, String layerId) {
    int idx = sectionIndex(sections, reorder.title());
    requireFound(idx, layerId, "section", reorder.title());
    Section section = sections.get(idx);
    if (reorder.order().size() != section.entries().size()) {
      throw new ResolutionException(
          "layer '"
              + layerId
              + "': reorderEntries order is not a permutation of section '"
              + reorder.title()
              + "'");
    }
    Set<String> current = new HashSet<>(section.entries());
    Set<String> seen = new HashSet<>();
    for (String entry : reorder.order()) {
      if (!current.contains(entry)) {
        throw new ResolutionException(
            "layer '"
                + layerId
                + "': reorderEntries references entry '"
                + entry
                + "' absent from section '"
                + reorder.title()
                + "'");
      }
      if (!seen.add(entry)) {
        throw new ResolutionException(
            "layer '" + layerId + "': reorderEntries lists entry '" + entry + "' twice");
      }
    }
    sections.set(
        idx, new Section(section.title(), reorder.order(), section.optional(), section.render()));
  }

  // --- lookup helpers --------------------------------------------------------

  private static int fieldIndex(List<Field> fields, String key) {
    for (int i = 0; i < fields.size(); i++) {
      if (fields.get(i).key().equals(key)) {
        return i;
      }
    }
    return -1;
  }

  private static int inlineClauseIndex(List<Clause> clauses, String id) {
    for (int i = 0; i < clauses.size(); i++) {
      if (clauses.get(i) instanceof Clause.Inline inline && inline.id().equals(id)) {
        return i;
      }
    }
    return -1;
  }

  private static int sectionIndex(List<Section> sections, String title) {
    for (int i = 0; i < sections.size(); i++) {
      if (sections.get(i).title().equals(title)) {
        return i;
      }
    }
    return -1;
  }

  private static void requireFound(int idx, String layerId, String kind, String target) {
    if (idx < 0) {
      throw new ResolutionException(
          "layer '"
              + layerId
              + "': "
              + kind
              + " '"
              + target
              + "' does not exist in the composed-so-far template");
    }
  }
}
