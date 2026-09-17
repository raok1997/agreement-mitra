package in.agreementmitra.rules.duty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.core.io.Resource;

/**
 * Typed reads over a YAML tree for the rule and catalog loaders. Every read failure becomes a
 * {@link RuleSetDefinitionException} naming the subject, source and field.
 *
 * <p>Money and rates must be <b>quoted strings</b>: a YAML float is binary, so {@code 0.1} would
 * already be wrong when parsed.
 */
final class YamlFields {

  private static final YAMLMapper YAML = new YAMLMapper();

  private final String subject;
  private final String source;

  YamlFields(String subject, String source) {
    this.subject = subject;
    this.source = source;
  }

  static JsonNode read(Resource resource) {
    String source = describe(resource);
    try (InputStream in = resource.getInputStream()) {
      JsonNode root = YAML.readTree(in);
      if (root == null || !root.isObject()) {
        throw new RuleSetDefinitionException("file", source, "top level must be a mapping");
      }
      return root;
    } catch (IOException e) {
      throw new RuleSetDefinitionException("file", source, "unreadable YAML: " + e.getMessage());
    }
  }

  static String describe(Resource resource) {
    return resource.getFilename() != null ? resource.getFilename() : resource.getDescription();
  }

  YamlFields as(String newSubject) {
    return new YamlFields(newSubject, source);
  }

  RuleSetDefinitionException defect(String message) {
    return new RuleSetDefinitionException(subject, source, message);
  }

  void allowOnly(JsonNode node, String where, Set<String> allowed) {
    Set<String> unknown = new TreeSet<>();
    node.fieldNames().forEachRemaining(name -> unknown.add(name));
    unknown.removeAll(allowed);
    if (!unknown.isEmpty()) {
      throw defect("unknown key(s) in " + where + ": " + unknown);
    }
  }

  String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw defect(field + " must be a string");
    }
    return value.asText();
  }

  String requiredText(JsonNode node, String field) {
    String value = text(node, field);
    if (value == null || value.isBlank()) {
      throw defect(field + " is required");
    }
    return value.trim();
  }

  BigDecimal decimal(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw defect(
          field + " must be a quoted decimal string, e.g. \"0.5\" (YAML numbers are binary)");
    }
    try {
      return new BigDecimal(value.asText().trim());
    } catch (NumberFormatException e) {
      throw defect(field + " is not a decimal: " + value.asText());
    }
  }

  BigDecimal nonNegativeDecimal(JsonNode node, String field) {
    BigDecimal value = decimal(node, field);
    if (value != null && value.signum() < 0) {
      throw defect(field + " must not be negative");
    }
    return value;
  }

  Integer integer(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.canConvertToInt() || !value.isIntegralNumber()) {
      throw defect(field + " must be an integer");
    }
    return value.intValue();
  }

  Long longValue(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber()) {
      throw defect(field + " must be an integer");
    }
    return value.longValue();
  }

  LocalDate date(JsonNode node, String field) {
    String value = text(node, field);
    if (value == null) {
      return null;
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw defect(field + " must be an ISO date (yyyy-mm-dd): " + value);
    }
  }

  <E extends Enum<E>> E enumValue(JsonNode node, String field, Class<E> type) {
    String value = text(node, field);
    if (value == null) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw defect(field + " has unknown value: " + value);
    }
  }
}
