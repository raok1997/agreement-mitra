package in.agreementmitra.documents;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Startup checks on render admission (anonymous-surface-abuse-controls tasks 5.1, 10.7). */
class GotenbergPropertiesTest {

  private static GotenbergProperties properties(int slots, int waiters, int reserved) {
    return new GotenbergProperties(
        "http://localhost:3000",
        slots,
        Duration.ofSeconds(30),
        Duration.ofSeconds(2),
        waiters,
        reserved);
  }

  @Test
  void reservedRendersMustBeFewerThanTheSlots() {
    assertThatThrownBy(() -> properties(4, 2, 4)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties(4, 2, 5)).isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> properties(4, 2, 3)).doesNotThrowAnyException();
  }

  @Test
  void slotsPlusWaitersMustLeaveConnectionsInThePool() {
    assertThatCode(() -> DocumentsConfig.checkConnectionHeadroom(properties(4, 2, 1), 10))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> DocumentsConfig.checkConnectionHeadroom(properties(4, 6, 1), 10))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> DocumentsConfig.checkConnectionHeadroom(properties(4, 2, 1), 6))
        .isInstanceOf(IllegalStateException.class);
  }
}
