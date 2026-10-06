package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.documents.api.TemplateDetail;
import in.agreementmitra.rules.DutyBasis;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Agreement + template dimensions -> calculator facts (design D5). */
class DutyBasisMapperTest {

  // 2026-03-31T20:00Z is already 2026-04-01 in India.
  private static final Clock LATE_UTC_EVENING =
      Clock.fixed(Instant.parse("2026-03-31T20:00:00Z"), ZoneOffset.UTC);

  private static Agreement agreement(Map<String, String> capture) {
    Agreement agreement =
        Agreement.create(
            "12 MG Road",
            new BigDecimal("15000.00"),
            new BigDecimal("45000.00"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 1));
    agreement.replaceCaptureState(capture, List.of());
    return agreement;
  }

  private static final Supplier<DutyBasisMapper.DefaultLookup> NO_DEFAULT =
      () -> DutyBasisMapper.DefaultLookup.NONE;
  private static final Supplier<DutyBasisMapper.DefaultLookup> DEFAULT_FIVE =
      () -> DutyBasisMapper.DefaultLookup.of(5);

  private static DutyBasis quote(
      Map<String, String> capture, Supplier<DutyBasisMapper.DefaultLookup> escalationDefault) {
    return DutyBasisMapper.from(
            agreement(capture), dims("TG", "residential"), escalationDefault, LATE_UTC_EVENING)
        .orElseThrow();
  }

  private static TemplateDetail.Dimensions dims(String state, String type) {
    return new TemplateDetail.Dimensions(state, type, "en");
  }

  @Test
  void mapsTermsRentDepositAndUsage() {
    DutyBasis basis =
        DutyBasisMapper.from(
                agreement(Map.of()), dims("tg", "residential"), NO_DEFAULT, LATE_UTC_EVENING)
            .orElseThrow();

    assertThat(basis.dutyState()).isEqualTo("TG");
    assertThat(basis.instrumentKind()).isEqualTo(DutyBasis.InstrumentKind.LEASE);
    assertThat(basis.usage()).isEqualTo(DutyBasis.Usage.RESIDENTIAL);
    assertThat(basis.termMonths()).isEqualTo(11);
    assertThat(basis.monthlyRent()).isEqualByComparingTo("15000");
    assertThat(basis.refundableDeposit()).isEqualByComparingTo("45000");
    assertThat(basis.rentFreeMonths()).isZero();
    assertThat(basis.counterparts()).isEqualTo(1);
    assertThat(
            DutyBasisMapper.from(
                    agreement(Map.of()), dims("TG", "Commercial"), NO_DEFAULT, LATE_UTC_EVENING)
                .orElseThrow()
                .usage())
        .isEqualTo(DutyBasis.Usage.COMMERCIAL);
  }

  @Test
  void executionDateIsTheCapturedAgreementDate() {
    DutyBasis basis =
        DutyBasisMapper.from(
                agreement(Map.of("agreementDate", "2025-11-20")),
                dims("TG", "residential"),
                NO_DEFAULT,
                LATE_UTC_EVENING)
            .orElseThrow();

    assertThat(basis.executionDate()).isEqualTo(LocalDate.of(2025, 11, 20));
  }

  @Test
  void executionDateFallsBackToTodayInIndia() {
    for (Map<String, String> capture :
        List.of(Map.<String, String>of(), Map.of("agreementDate", "not-a-date"))) {
      assertThat(
              DutyBasisMapper.from(
                      agreement(capture), dims("TG", "residential"), NO_DEFAULT, LATE_UTC_EVENING)
                  .orElseThrow()
                  .executionDate())
          .isEqualTo(LocalDate.of(2026, 4, 1));
    }
  }

  @Test
  void capturedEscalationAppliesAnnually() {
    DutyBasis escalating =
        DutyBasisMapper.from(
                agreement(Map.of("rentEscalationPercent", "5")),
                dims("TG", "residential"),
                NO_DEFAULT,
                LATE_UTC_EVENING)
            .orElseThrow();
    DutyBasis flat =
        DutyBasisMapper.from(
                agreement(Map.of("rentEscalationPercent", "0")),
                dims("TG", "residential"),
                NO_DEFAULT,
                LATE_UTC_EVENING)
            .orElseThrow();

    assertThat(escalating.escalationPercent()).isEqualByComparingTo("5");
    assertThat(escalating.escalationEveryMonths()).isEqualTo(12);
    assertThat(flat.escalationEveryMonths()).isZero();
  }

  @Test
  void refusesWhatCannotBeDescribed() {
    assertThat(
            DutyBasisMapper.from(
                agreement(Map.of()), dims("TG", "industrial"), NO_DEFAULT, LATE_UTC_EVENING))
        .isEmpty();
    assertThat(
            DutyBasisMapper.from(
                agreement(Map.of()), dims(" ", "residential"), NO_DEFAULT, LATE_UTC_EVENING))
        .isEmpty();
    assertThat(DutyBasisMapper.from(agreement(Map.of()), null, NO_DEFAULT, LATE_UTC_EVENING))
        .isEmpty();
  }

  @Test
  void blankEscalationTakesTheTemplateDefault() {
    for (Map<String, String> capture :
        List.of(Map.<String, String>of(), Map.of("rentEscalationPercent", "  "))) {
      DutyBasis basis = quote(capture, DEFAULT_FIVE);

      assertThat(basis.escalationPercent()).isEqualByComparingTo("5");
      assertThat(basis.escalationEveryMonths()).isEqualTo(12);
    }
  }

  @Test
  void capturedZeroBeatsTheDefault() {
    DutyBasis basis = quote(Map.of("rentEscalationPercent", "0"), DEFAULT_FIVE);

    assertThat(basis.escalationPercent()).isZero();
    assertThat(basis.escalationEveryMonths()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"+7", "07", " 7 "})
  void capturedEscalationParsesLikeTheDeedField(String captured) {
    assertThat(quote(Map.of("rentEscalationPercent", captured), DEFAULT_FIVE).escalationPercent())
        .isEqualByComparingTo("7");
  }

  @Test
  void noTemplateDefaultMeansNoEscalation() {
    DutyBasis basis = quote(Map.of(), NO_DEFAULT);

    assertThat(basis.escalationPercent()).isZero();
    assertThat(basis.escalationEveryMonths()).isZero();
  }

  @Test
  void aTemplateDefaultOutsideTheFieldRangeMeansNoEscalation() {
    DutyBasis basis = quote(Map.of(), () -> DutyBasisMapper.DefaultLookup.of(500));

    assertThat(basis.escalationPercent()).isZero();
    assertThat(basis.escalationEveryMonths()).isZero();
  }

  @Test
  void aTemplateThatCannotBeFoundMakesTheBasisUnavailable() {
    assertThat(
            DutyBasisMapper.from(
                agreement(Map.of()),
                dims("TG", "residential"),
                () -> DutyBasisMapper.DefaultLookup.NOT_FOUND,
                LATE_UTC_EVENING))
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"150", "4.5", "5.0", "-5", "abc", "1E+999999999"})
  void anUnusableCaptureFallsToTheDefault(String captured) {
    DutyBasis basis = quote(Map.of("rentEscalationPercent", captured), DEFAULT_FIVE);

    assertThat(basis.escalationPercent()).isEqualByComparingTo("5");
    assertThat(basis.escalationEveryMonths()).isEqualTo(12);
  }

  @Test
  void theDefaultIsNotLookedUpWhenTheCaptureIsUsable() {
    AtomicInteger lookups = new AtomicInteger();

    quote(
        Map.of("rentEscalationPercent", "3"),
        () -> {
          lookups.incrementAndGet();
          return DutyBasisMapper.DefaultLookup.of(5);
        });

    assertThat(lookups).hasValue(0);
  }

  @Test
  void theDraftExecutionDateIsUsedWhenNoneIsCaptured() {
    LocalDate drafted = LocalDate.of(2026, 3, 15);
    for (Map<String, String> capture :
        List.of(
            Map.<String, String>of(),
            Map.of("agreementDate", "not-a-date"),
            Map.of("agreementDate", "+99999-01-01"))) {
      Agreement agreement = agreement(capture);
      agreement.pinEffectiveTemplate("h", Map.of(), drafted);

      assertThat(DutyBasisMapper.executionDate(agreement, LATE_UTC_EVENING)).isEqualTo(drafted);
    }
  }

  @Test
  void aCapturedExecutionDateBeatsTheDraftDate() {
    Agreement agreement = agreement(Map.of("agreementDate", "2025-11-20"));
    agreement.pinEffectiveTemplate("h", Map.of(), LocalDate.of(2026, 3, 15));

    assertThat(DutyBasisMapper.executionDate(agreement, LATE_UTC_EVENING))
        .isEqualTo(LocalDate.of(2025, 11, 20));
  }
}
