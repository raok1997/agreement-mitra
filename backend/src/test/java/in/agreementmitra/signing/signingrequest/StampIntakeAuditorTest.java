package in.agreementmitra.signing.signingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** The audit write never fails the intake it describes - at the save or at the commit. */
class StampIntakeAuditorTest {

  private StampIntakeAuditRepository repository;
  private PlatformTransactionManager transactions;
  private StampIntakeAuditor auditor;

  @BeforeEach
  void setUp() {
    repository = mock(StampIntakeAuditRepository.class);
    transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    auditor = new StampIntakeAuditor(repository, transactions);
  }

  private void record() {
    auditor.record(UUID.randomUUID(), UUID.randomUUID(), "REF-1", "ACCEPTED");
  }

  @Test
  void theRowIsSavedInItsOwnTransaction() {
    record();

    verify(repository).save(any());
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions).getTransaction(definition.capture());
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    verify(transactions).commit(any());
  }

  @Test
  void aSaveThatFailsDoesNotEscape() {
    when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("unavailable"));

    assertThatCode(this::record).doesNotThrowAnyException();
  }

  @Test
  void aCommitThatFailsDoesNotEscape() {
    doThrow(new TransactionSystemException("commit failed")).when(transactions).commit(any());

    assertThatCode(this::record).doesNotThrowAnyException();
  }
}
