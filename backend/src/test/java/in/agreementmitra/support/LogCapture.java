package in.agreementmitra.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

/**
 * Captures log events for one test (agreement-id-debug-logging D7). Register it as a field with
 * {@code @RegisterExtension}: it attaches a {@link ListAppender} to the capture logger, optionally
 * sets a level on a named logger, and restores both after each test.
 *
 * <p>Pin the level whenever a test asserts on DEBUG lines: the shipped default is INFO, and an
 * absence assertion over an INFO-filtered capture passes over nothing.
 */
public final class LogCapture implements BeforeEachCallback, AfterEachCallback {

  private final String captureLogger;
  private final String levelLogger;
  private final Level level;
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Level previousLevel;

  private LogCapture(String captureLogger, String levelLogger, Level level) {
    this.captureLogger = captureLogger;
    this.levelLogger = levelLogger;
    this.level = level;
  }

  /** Capture {@code type}'s logger, pinned to {@code level}. */
  public static LogCapture of(Class<?> type, Level level) {
    return new LogCapture(type.getName(), type.getName(), level);
  }

  /**
   * Capture every event reaching the root logger, with {@code levelLogger} pinned to {@code level}.
   */
  public static LogCapture root(String levelLogger, Level level) {
    return new LogCapture(Logger.ROOT_LOGGER_NAME, levelLogger, level);
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    Logger pinned = logger(levelLogger);
    previousLevel = pinned.getLevel();
    pinned.setLevel(level);
    appender.list.clear();
    appender.start();
    logger(captureLogger).addAppender(appender);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    logger(captureLogger).detachAppender(appender);
    appender.stop();
    logger(levelLogger).setLevel(previousLevel);
  }

  public List<ILoggingEvent> events() {
    return List.copyOf(appender.list);
  }

  public List<String> messages() {
    return events().stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  public boolean hasLevel(Level wanted) {
    return events().stream().anyMatch(e -> e.getLevel() == wanted);
  }

  /** The message and class name of every throwable in every captured event's cause chain. */
  public List<String> throwableMessages() {
    return throwableMessages(events());
  }

  public static List<String> throwableMessages(List<ILoggingEvent> events) {
    List<String> out = new ArrayList<>();
    for (ILoggingEvent event : events) {
      for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
        out.add(t.getClassName() + ": " + t.getMessage());
        Stream.of(t.getSuppressed())
            .forEach(s -> out.add(s.getClassName() + ": " + s.getMessage()));
      }
    }
    return out;
  }

  private static Logger logger(String name) {
    return (Logger) LoggerFactory.getLogger(name);
  }
}
