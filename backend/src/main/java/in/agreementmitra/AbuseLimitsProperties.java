package in.agreementmitra;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The anonymous-surface route-class limits, bound from {@code abuse.limits.*}
 * (anonymous-surface-abuse-controls D5). Every value comes from configuration; none is compiled in,
 * and binding fails when a limited class is missing.
 *
 * @param enabled the one switch for the route-class limits (recovery's own limiter is unaffected);
 *     the test profile turns it off so loopback traffic in one test cannot throttle the next
 * @param maximumSize the hard ceiling on keys the route-class limiter retains
 * @param classes each limited {@link RouteClass}'s allowance
 */
@ConfigurationProperties(prefix = "abuse.limits")
@Validated
record AbuseLimitsProperties(
    boolean enabled,
    @Positive long maximumSize,
    @NotNull @Valid Map<RouteClass, ClassLimits> classes) {

  AbuseLimitsProperties {
    Map<RouteClass, ClassLimits> configured =
        classes == null || classes.isEmpty() ? Map.of() : new EnumMap<>(classes);
    List<RouteClass> missing =
        Arrays.stream(RouteClass.values())
            .filter(RouteClass::limited)
            .filter(c -> !configured.containsKey(c))
            .toList();
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("abuse.limits.classes is missing " + missing);
    }
    classes = configured;
    if (classes.keySet().stream().anyMatch(c -> !c.limited())) {
      throw new IllegalArgumentException("abuse.limits.classes may list only limited classes");
    }
  }

  /** The longest window plus the longest lockout: how long the limiter must remember a key. */
  Duration retention() {
    return classes.values().stream()
        .map(c -> c.window().plus(c.lockout()))
        .max(Duration::compareTo)
        .orElse(Duration.ofMinutes(1));
  }

  /**
   * One class's allowance.
   *
   * @param perSource requests per window from one source
   * @param perResource requests per window naming one agreement, or null for a source-only class
   * @param window the sliding window
   * @param lockout how long a source is refused once it exceeds {@code perSource}; zero for none.
   *     The per-resource dimension never locks out: a lockout on an agreement id would let anyone
   *     holding the link deny the agreement's holder access to it.
   */
  record ClassLimits(
      @Positive int perSource,
      @Positive Integer perResource,
      @NotNull Duration window,
      @NotNull Duration lockout) {

    ClassLimits {
      // One source must exhaust its own budget before it can exhaust an agreement's: otherwise a
      // single link holder polling just under the per-source limit fills the agreement's bucket and
      // starves the other parties. Shared addresses are covered by the per-source limit itself,
      // which is set well above what a household generates.
      if (perResource != null && perResource <= perSource) {
        throw new IllegalArgumentException(
            "a class's per-resource limit must be higher than its per-source limit");
      }
      if (window != null && (window.isZero() || window.isNegative())) {
        throw new IllegalArgumentException("a class's window must be positive");
      }
      if (lockout != null && lockout.isNegative()) {
        throw new IllegalArgumentException("a class's lockout must not be negative");
      }
    }
  }
}
