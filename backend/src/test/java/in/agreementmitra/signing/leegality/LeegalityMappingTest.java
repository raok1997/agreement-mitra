package in.agreementmitra.signing.leegality;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.InviteeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the Leegality per-invitee status mapping and the log-redaction helper. Pure
 * functions, no Spring, no network.
 */
class LeegalityMappingTest {

  @ParameterizedTest
  @CsvSource({
    "COMPLETED,SIGNED",
    "SIGNED,SIGNED",
    "REJECTED,REJECTED",
    "FAILED,REJECTED",
    "DECLINED,REJECTED",
    "EXPIRED,EXPIRED",
    "completed,SIGNED", // case-insensitive
  })
  void terminalVendorStatusesMapToInviteeStatus(String vendor, InviteeStatus expected) {
    assertThat(LeegalityEsignProvider.mapInviteeStatus(vendor)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DRAFT", "SENT", "IN_PROGRESS", "anything-unknown"})
  void nonTerminalOrUnknownStatusesMapToPending(String vendor) {
    assertThat(LeegalityEsignProvider.mapInviteeStatus(vendor)).isEqualTo(InviteeStatus.PENDING);
  }

  @Test
  void nullStatusIsTreatedAsPending() {
    assertThat(LeegalityEsignProvider.mapInviteeStatus(null)).isEqualTo(InviteeStatus.PENDING);
  }

  @Test
  void redactKeepsOnlyLastFourCharacters() {
    assertThat(LeegalityEsignProvider.redact("DOC-1234567890")).isEqualTo("****7890");
    assertThat(LeegalityEsignProvider.redact("ab")).isEqualTo("****");
    assertThat(LeegalityEsignProvider.redact(null)).isEqualTo("****");
  }
}
