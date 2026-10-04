package in.agreementmitra;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Captures the security-event logger's output for a test; close it in a {@code finally}. */
final class SecurityEventCapture implements AutoCloseable {

  private final Logger logger = (Logger) LoggerFactory.getLogger(SecurityEvents.LOGGER);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  SecurityEventCapture() {
    appender.start();
    logger.addAppender(appender);
  }

  List<String> lines() {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
  }
}
