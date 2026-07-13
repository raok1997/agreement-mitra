package in.agreementmitra.documents.template;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A catalog entry: system-owned template <b>metadata plus a pointer</b> -- never a template body.
 * One row of the {@code template} registry, mirroring the {@link
 * in.agreementmitra.signing.agreement} aggregate style: an app-assigned UUID from {@link #create},
 * {@link Persistable} with a transient {@code isNew} flag so an app-assigned id does not trigger a
 * phantom {@code SELECT} before {@code INSERT} on {@code save()}, id-based equals/hashCode, and an
 * id-only {@code toString()} (no metadata leak in logs).
 *
 * <p>The row holds {@code name}, {@code description}, {@code type} (category), {@code state},
 * {@code language} (reserved, English only), {@code version}, {@code status}, and {@code
 * layerSetRef} -- a <b>pointer</b> to the classpath layer set. It stores <b>no</b> definition YAML,
 * patch YAML, clause text, or HTML: bodies stay classpath resources (object storage later), the
 * resolver still loads them from there via the pointer (see {@link RegistryLayerSource}).
 *
 * <p>Package-private, co-located with the definition/resolution records so no record visibility
 * widens; the module's only public surface is {@code documents.api}.
 */
@Entity
@Table(name = "template")
class TemplateCatalogEntry implements Persistable<UUID> {

  /** Reserved language token; English only for now (the language layer is never applied). */
  static final String DEFAULT_LANGUAGE = "en";

  @Id private UUID id;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "description")
  private String description;

  @Column(name = "type", nullable = false)
  private String type;

  @Column(name = "state", nullable = false)
  private String state;

  @Column(name = "language", nullable = false)
  private String language;

  @Column(name = "version", nullable = false)
  private int version;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private TemplateStatus status;

  /** Pointer to the classpath layer set root (never a body). See {@link RegistryLayerSource}. */
  @Column(name = "layer_set_ref", nullable = false)
  private String layerSetRef;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Transient private boolean isNew = true;

  protected TemplateCatalogEntry() {
    // JPA
  }

  private TemplateCatalogEntry(
      UUID id,
      String name,
      String description,
      String type,
      String state,
      String language,
      int version,
      TemplateStatus status,
      String layerSetRef,
      Instant createdAt) {
    this.id = id;
    this.name = name;
    this.description = description;
    this.type = type;
    this.state = state;
    this.language = language;
    this.version = version;
    this.status = status;
    this.layerSetRef = layerSetRef;
    this.createdAt = createdAt;
  }

  /** Create a catalog entry with a fresh app-assigned id and creation timestamp. */
  static TemplateCatalogEntry create(
      String name,
      String description,
      String type,
      String state,
      String language,
      int version,
      TemplateStatus status,
      String layerSetRef) {
    return new TemplateCatalogEntry(
        UUID.randomUUID(),
        name,
        description,
        type,
        state,
        language,
        version,
        status,
        layerSetRef,
        Instant.now());
  }

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostPersist
  @PostLoad
  void markNotNew() {
    this.isNew = false;
  }

  String name() {
    return name;
  }

  String description() {
    return description;
  }

  String type() {
    return type;
  }

  String state() {
    return state;
  }

  String language() {
    return language;
  }

  int version() {
    return version;
  }

  TemplateStatus status() {
    return status;
  }

  String layerSetRef() {
    return layerSetRef;
  }

  Instant createdAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof TemplateCatalogEntry other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    // Id only -- no metadata (name/description) in logs.
    return "TemplateCatalogEntry{id=" + id + "}";
  }
}
