package in.agreementmitra.signing.staffalert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import in.agreementmitra.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A failing sweep stays inside the job, and what it logs cannot carry the webhook URL. */
class StaffAlertDispatchJobTest {

  private static final String URL = "https://hooks.example.test/api/webhooks/1/tok-9f3aSECRETc41d";

  @RegisterExtension final LogCapture logs = LogCapture.root("in.agreementmitra", Level.DEBUG);

  @Test
  void aFailingSweepDoesNotEscapeAndTheNextRunProceeds() {
    StaffAlertDispatcher dispatcher = mock(StaffAlertDispatcher.class);
    doThrow(new IllegalStateException("I/O error on POST request for \"" + URL + "\""))
        .doNothing()
        .when(dispatcher)
        .dispatchDue();
    StaffAlertDispatchJob job = new StaffAlertDispatchJob(dispatcher);

    assertThatCode(job::sweep).doesNotThrowAnyException();
    assertThatCode(job::sweep).doesNotThrowAnyException();

    verify(dispatcher, times(2)).dispatchDue();
    assertThat(logs.messages()).anyMatch(m -> m.contains("IllegalStateException"));
    assertThat(logs.messages()).noneMatch(m -> m.contains(URL) || m.contains("tok-"));
    assertThat(logs.throwableMessages()).isEmpty();
  }
}
