package in.agreementmitra.signing.payment;

import in.agreementmitra.signing.PaymentGateMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Payment configuration, bound from {@code payment.*}. Non-secret throughout: the gateway
 * credentials live in {@link RazorpayProperties} ({@code payment.razorpay.*}) and come from
 * environment variables only, so the system still starts normally with no payment configuration at
 * all.
 *
 * @param mode how strictly the gate is enforced; <b>defaults to {@link PaymentGateMode#REQUIRED}
 *     when unconfigured</b>. This default is deliberately <b>fail-closed</b>: the gate stands
 *     between an unpaid order and a staff member spending a real stamp certificate, so absent
 *     configuration must refuse rather than wave the order through. Relaxing it to {@code OPTIONAL}
 *     is an explicit configuration change ({@code PAYMENT_MODE=OPTIONAL}), never something that
 *     happens by omission.
 * @param fee the published pricing rule's two figures (state-stamp-duty-quoting, design D6).
 * @param order order-lifecycle tuning: how long an outstanding order stays reusable.
 * @param reconciliation the fallback scan that recovers payments whose webhook never arrived.
 */
@ConfigurationProperties(prefix = "payment")
record PaymentProperties(
    PaymentGateMode mode, Fee fee, Order order, Reconciliation reconciliation) {

  PaymentProperties {
    // Fail CLOSED: an unconfigured money gate must refuse, not permit.
    mode = mode == null ? PaymentGateMode.REQUIRED : mode;
    fee = fee == null ? new Fee(null, null, null) : fee;
    order = order == null ? new Order(null) : order;
    reconciliation = reconciliation == null ? new Reconciliation(null, null, null) : reconciliation;
  }

  /**
   * The published pricing rule (TERMS-OF-SERVICE section 7): <b>total = base fee + max(0, chosen
   * stamp value - included stamp value)</b>. All figures are <b>integer minor units</b> (paise)
   * plus an explicit currency -- never a float: the provider requires paise and floating-point
   * money produces off-by-one-paise errors that fail amount-equality checks.
   *
   * @param baseMinorUnits the total charged when the stamp value is within the included amount;
   *     defaults to INR 499.00
   * @param includedStampValueMinorUnits the stamp value the base fee already covers; defaults to
   *     INR 100.00. Zero is allowed (every rupee of stamp value is then added).
   * @param currency ISO-4217 code; defaults to {@code INR}, the only currency transacted today.
   */
  record Fee(Long baseMinorUnits, Long includedStampValueMinorUnits, String currency) {

    private static final long DEFAULT_BASE_MINOR_UNITS = 49_900L;
    private static final long DEFAULT_INCLUDED_STAMP_VALUE_MINOR_UNITS = 10_000L;

    Fee {
      baseMinorUnits =
          baseMinorUnits == null || baseMinorUnits <= 0 ? DEFAULT_BASE_MINOR_UNITS : baseMinorUnits;
      includedStampValueMinorUnits =
          includedStampValueMinorUnits == null || includedStampValueMinorUnits < 0
              ? DEFAULT_INCLUDED_STAMP_VALUE_MINOR_UNITS
              : includedStampValueMinorUnits;
      currency =
          currency == null || currency.isBlank()
              ? "INR"
              : currency.trim().toUpperCase(java.util.Locale.ROOT);
    }
  }

  /**
   * @param ttl how long an outstanding (unpaid) order stays reusable before a fresh one may be
   *     created. Reload during checkout reuses the outstanding order (design D5); only once it is
   *     this old is it treated as abandoned.
   */
  record Order(Duration ttl) {

    private static final Duration DEFAULT_TTL = Duration.ofHours(2);

    Order {
      ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_TTL : ttl;
    }
  }

  /**
   * @param enabled whether the scheduled scan runs; disabled in the test profile, where the job is
   *     driven directly.
   * @param ageThreshold how long an order must have been outstanding before it is re-read from the
   *     provider. A tuning value: it affects job cadence only.
   * @param batchSize the most orders one run will re-read, so a backlog cannot become a stampede.
   */
  record Reconciliation(Boolean enabled, Duration ageThreshold, Integer batchSize) {

    private static final Duration DEFAULT_AGE_THRESHOLD = Duration.ofMinutes(10);
    private static final int DEFAULT_BATCH_SIZE = 50;

    Reconciliation {
      enabled = enabled == null || enabled;
      ageThreshold =
          ageThreshold == null || ageThreshold.isNegative() ? DEFAULT_AGE_THRESHOLD : ageThreshold;
      batchSize = batchSize == null || batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
    }
  }
}
