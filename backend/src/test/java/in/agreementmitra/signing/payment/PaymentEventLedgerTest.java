package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import in.agreementmitra.support.LogCapture;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The ledger cannot fail its caller. No Spring context and no database: what is under test is that
 * a write which cannot happen - at the statement or at the commit - never escapes, and what the
 * line logged for it holds.
 */
class PaymentEventLedgerTest {

  private static final String ORDER_ID = "order_RZP9f3k1";
  private static final String PAYMENT_ID = "pay_RZP8b2j0";

  @RegisterExtension final LogCapture logs = LogCapture.of(PaymentEventLedger.class, Level.DEBUG);

  private JdbcClient jdbc;
  private JdbcClient.StatementSpec statement;
  private PlatformTransactionManager transactions;
  private PaymentEventLedger ledger;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcClient.class);
    statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    transactions = mock(PlatformTransactionManager.class);
    when(jdbc.sql(anyString())).thenReturn(statement);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    ledger = new PaymentEventLedger(jdbc, transactions);
  }

  private void record() {
    ledger.record(
        PaymentEventOrigin.webhook("evt_1", "payment.captured"),
        ORDER_ID,
        PAYMENT_ID,
        49_900L,
        "INR",
        Instant.parse("2026-10-11T05:00:00Z"),
        PaymentEventOutcome.RECORDING_FAILED,
        null,
        "22001");
  }

  private void assertTheOneWarnHoldsNothingOfThePayment() {
    assertThat(logs.events()).hasSize(1);
    assertThat(logs.hasLevel(Level.WARN)).isTrue();
    assertThat(logs.messages().get(0))
        .contains("WEBHOOK")
        .contains("RECORDING_FAILED")
        .doesNotContain(ORDER_ID)
        .doesNotContain(PAYMENT_ID)
        .doesNotContain("49900");
    // The failure is not handed to the logger: its message is the SQL and the row.
    assertThat(logs.throwableMessages()).isEmpty();
  }

  @Test
  void theStatementRunsOnceInItsOwnTransaction() {
    record();

    verify(statement, times(1)).update();
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions).getTransaction(definition.capture());
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    verify(transactions).commit(any());
    assertThat(logs.events()).isEmpty();
  }

  @Test
  void aStatementThatCannotRunDoesNotEscape() {
    when(jdbc.sql(anyString()))
        .thenThrow(new DataAccessResourceFailureException("INSERT ... " + PAYMENT_ID));

    assertThatCode(this::record).doesNotThrowAnyException();

    assertTheOneWarnHoldsNothingOfThePayment();
  }

  @Test
  void aCommitThatFailsDoesNotEscape() {
    doThrow(new TransactionSystemException("commit failed for " + PAYMENT_ID))
        .when(transactions)
        .commit(any());

    assertThatCode(this::record).doesNotThrowAnyException();

    assertTheOneWarnHoldsNothingOfThePayment();
  }
}
