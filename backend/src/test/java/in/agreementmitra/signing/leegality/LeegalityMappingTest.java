package in.agreementmitra.signing.leegality;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.SignatureStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the Leegality {@code document.status} → FSM mapping and the log-redaction helper.
 * Pure functions, no Spring, no network.
 */
class LeegalityMappingTest {

  @ParameterizedTest
  @CsvSource({
    "COMPLETED,SIGNED",
    "SIGNED,SIGNED",
    "REJECTED,FAILED",
    "FAILED,FAILED",
    "DECLINED,FAILED",
    "EXPIRED,EXPIRED",
    "completed,SIGNED", // case-insensitive
  })
  void terminalVendorStatusesMapToTerminalFsm(String vendor, SignatureStatus expected) {
    assertThat(LeegalityEsignProvider.mapStatus(vendor)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DRAFT", "SENT", "IN_PROGRESS", "anything-unknown"})
  void nonTerminalOrUnknownStatusesMapToSignRequested(String vendor) {
    assertThat(LeegalityEsignProvider.mapStatus(vendor)).isEqualTo(SignatureStatus.SIGN_REQUESTED);
  }

  @Test
  void nullStatusIsTreatedAsInFlight() {
    assertThat(LeegalityEsignProvider.mapStatus(null)).isEqualTo(SignatureStatus.SIGN_REQUESTED);
  }

  @Test
  void redactKeepsOnlyLastFourCharacters() {
    assertThat(LeegalityEsignProvider.redact("DOC-1234567890")).isEqualTo("****7890");
    assertThat(LeegalityEsignProvider.redact("ab")).isEqualTo("****");
    assertThat(LeegalityEsignProvider.redact(null)).isEqualTo("****");
  }
}
