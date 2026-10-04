package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PlausibleDates}: the single accepted date range. No Spring, no I/O. */
class PlausibleDatesTest {

  @Test
  void acceptsTheBoundaryYearsAndOrdinaryDates() {
    assertThat(PlausibleDates.isPlausible(LocalDate.of(1900, 1, 1))).isTrue();
    assertThat(PlausibleDates.isPlausible(LocalDate.of(2026, 10, 4))).isTrue();
    assertThat(PlausibleDates.isPlausible(LocalDate.of(2199, 12, 31))).isTrue();
  }

  @Test
  void rejectsYearsOutsideTheRange() {
    assertThat(PlausibleDates.isPlausible(LocalDate.of(1899, 12, 31))).isFalse();
    assertThat(PlausibleDates.isPlausible(LocalDate.of(2200, 1, 1))).isFalse();
    assertThat(PlausibleDates.isPlausible(LocalDate.MAX)).isFalse();
    assertThat(PlausibleDates.isPlausible(LocalDate.MIN)).isFalse();
  }

  @Test
  void isNullSafe() {
    assertThat(PlausibleDates.isPlausible(null)).isFalse();
  }

  @Test
  void reportsWhichEndOfTheRangeWasCrossed() {
    assertThat(PlausibleDates.isTooEarly(LocalDate.of(1899, 12, 31))).isTrue();
    assertThat(PlausibleDates.isTooLate(LocalDate.of(1899, 12, 31))).isFalse();
    assertThat(PlausibleDates.isTooLate(LocalDate.of(2200, 1, 1))).isTrue();
    assertThat(PlausibleDates.isTooEarly(LocalDate.of(2200, 1, 1))).isFalse();
  }
}
