package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TenancyDurationTest {

  // KEEP IN SYNC with documents' DerivedFieldTest#wholeMonthsBetweenDates and the frontend
  // tenancyMonths table: the stored term (stamp duty, registration) must equal the term the
  // document states.
  @ParameterizedTest(name = "{0} to {1} is {2} months")
  @CsvSource({
    "2026-01-08, 2028-01-08, 24",
    "2026-09-01, 2027-07-31, 11",
    "2026-01-01, 2026-11-30, 11",
    "2026-01-01, 2026-12-31, 12",
    "2026-01-01, 2026-12-01, 11",
    "2026-01-01, 2027-01-01, 12",
    "2026-01-01, 2027-01-31, 13",
    "2026-01-01, 2026-12-20, 11",
    "2026-01-01, 2026-01-30, 0",
    "2026-01-01, 2026-01-31, 1",
    "2026-01-31, 2026-02-27, 0",
    "2026-01-31, 2026-02-28, 1",
    "2026-01-31, 2026-03-31, 2",
    "2028-02-29, 2029-02-28, 12",
    "2024-02-29, 2025-03-01, 12",
    "2026-06-01, 2026-06-01, 0",
  })
  void wholeMonthsInclusiveOfTheEndDate(LocalDate start, LocalDate end, int expected) {
    assertThat(TenancyDuration.months(start, end)).isEqualTo(expected);
  }
}
