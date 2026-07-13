package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Token parsing for the closed {@link FieldType} and {@link TemplateStatus} enums (unit; no I/O).
 */
class FieldTypeTest {

  @Test
  void everyFieldTypeTokenMaps() {
    assertThat(FieldType.from("text")).isEqualTo(FieldType.TEXT);
    assertThat(FieldType.from("longtext")).isEqualTo(FieldType.LONGTEXT);
    assertThat(FieldType.from("int")).isEqualTo(FieldType.INT);
    assertThat(FieldType.from("money")).isEqualTo(FieldType.MONEY);
    assertThat(FieldType.from("date")).isEqualTo(FieldType.DATE);
    assertThat(FieldType.from("bool")).isEqualTo(FieldType.BOOL);
    assertThat(FieldType.from("enum")).isEqualTo(FieldType.ENUM);
  }

  @Test
  void fieldTypeParsingIsCaseInsensitive() {
    assertThat(FieldType.from("MONEY")).isEqualTo(FieldType.MONEY);
    assertThat(FieldType.from(" Int ")).isEqualTo(FieldType.INT);
  }

  @Test
  void unknownFieldTypeTokenIsRejected() {
    assertThatThrownBy(() -> FieldType.from("phone")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FieldType.from(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void everyStatusTokenMaps() {
    assertThat(TemplateStatus.from("draft")).isEqualTo(TemplateStatus.DRAFT);
    assertThat(TemplateStatus.from("legal_approved")).isEqualTo(TemplateStatus.LEGAL_APPROVED);
    assertThat(TemplateStatus.from("published")).isEqualTo(TemplateStatus.PUBLISHED);
    assertThat(TemplateStatus.from("deprecated")).isEqualTo(TemplateStatus.DEPRECATED);
    assertThat(TemplateStatus.from("DRAFT")).isEqualTo(TemplateStatus.DRAFT);
  }

  @Test
  void unknownStatusTokenIsRejected() {
    assertThatThrownBy(() -> TemplateStatus.from("archived"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
