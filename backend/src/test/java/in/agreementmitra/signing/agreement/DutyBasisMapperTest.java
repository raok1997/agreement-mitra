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
import org.junit.jupiter.api.Test;

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

  private static TemplateDetail.Dimensions dims(String state, String type) {
    return new TemplateDetail.Dimensions(state, type, "en");
  }

  @Test
  void mapsTermsRentDepositAndUsage() {
    DutyBasis basis =
        DutyBasisMapper.from(agreement(Map.of()), dims("tg", "residential"), LATE_UTC_EVENING)
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
            DutyBasisMapper.from(agreement(Map.of()), dims("TG", "Commercial"), LATE_UTC_EVENING)
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
                LATE_UTC_EVENING)
            .orElseThrow();

    assertThat(basis.executionDate()).isEqualTo(LocalDate.of(2025, 11, 20));
  }

  @Test
  void executionDateFallsBackToTodayInIndia() {
    for (Map<String, String> capture :
        List.of(Map.<String, String>of(), Map.of("agreementDate", "not-a-date"))) {
      assertThat(
              DutyBasisMapper.from(agreement(capture), dims("TG", "residential"), LATE_UTC_EVENING)
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
                LATE_UTC_EVENING)
            .orElseThrow();
    DutyBasis flat =
        DutyBasisMapper.from(
                agreement(Map.of("rentEscalationPercent", "0")),
                dims("TG", "residential"),
                LATE_UTC_EVENING)
            .orElseThrow();

    assertThat(escalating.escalationPercent()).isEqualByComparingTo("5");
    assertThat(escalating.escalationEveryMonths()).isEqualTo(12);
    assertThat(flat.escalationEveryMonths()).isZero();
  }

  @Test
  void refusesWhatCannotBeDescribed() {
    assertThat(
            DutyBasisMapper.from(agreement(Map.of()), dims("TG", "industrial"), LATE_UTC_EVENING))
        .isEmpty();
    assertThat(
            DutyBasisMapper.from(agreement(Map.of()), dims(" ", "residential"), LATE_UTC_EVENING))
        .isEmpty();
    assertThat(DutyBasisMapper.from(agreement(Map.of()), null, LATE_UTC_EVENING)).isEmpty();
  }
}
