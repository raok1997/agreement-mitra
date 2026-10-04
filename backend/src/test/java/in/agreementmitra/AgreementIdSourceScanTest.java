package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Build-time guard: no log call or exception construction in {@code src/main/java} carries an
 * agreement id that is not wrapped in {@link AgreementIds} (agreement-id-debug-logging 6.1, design
 * D6.2).
 *
 * <p>Purely syntactic. Comments and string/text-block contents are blanked first (so javadoc
 * examples and message text do not count), then each {@code log.<level>(} call and {@code new
 * ...Exception(} / {@code new ...Error(} construction is cut to its paren-matched close, the
 * arguments of every {@code AgreementIds.redact(...)} / {@code AgreementIds.redactIn(...)} are
 * removed, and what remains must not mention an agreement id. Only the {@code AgreementIds}
 * qualifier is accepted: the codebase's other {@code redact} helpers are last-4 provider-id
 * redactors.
 *
 * <p>Known blind spots, covered elsewhere: a local holding an id-bearing blob key (MinIO's {@code
 * key}; {@code MinioBlobStoreTest} and the lifecycle integration test cover it), an id held in a
 * variable with an unrelated name, and logging an object whose {@code toString()} is not one of the
 * redacted named types (records never logged, such as {@code
 * SigningRequestPersistence.AwaitingStamp}, are exempt).
 */
class AgreementIdSourceScanTest {

  private static final Path SOURCES = Path.of("src/main/java");

  private static final Pattern LOG_CALL =
      Pattern.compile("\\b(?:log|LOG|logger)\\.(?:trace|debug|info|warn|error)\\(");
  private static final Pattern CONSTRUCTION =
      Pattern.compile("\\bnew\\s+\\w*(?:Exception|Error)\\(");
  private static final Pattern WRAPPER = Pattern.compile("\\bAgreementIds\\.redact(?:In)?\\(");
  private static final Pattern AGREEMENT_ID =
      Pattern.compile(
          "\\bagreementId\\b|\\.agreementId\\(\\)|\\bagreement\\.id\\(\\)|\\bagreement\\.getId\\(\\)");

  @Test
  void noLogCallOrExceptionMessageCarriesARawAgreementId() throws IOException {
    List<String> findings = new ArrayList<>();
    int[] logCalls = {0};
    try (Stream<Path> files = Files.walk(SOURCES)) {
      files
          .filter(p -> p.toString().endsWith(".java"))
          .sorted()
          .forEach(
              file -> {
                String source = read(file);
                logCalls[0] += count(LOG_CALL, blank(source));
                scan(source).forEach(line -> findings.add(SOURCES.relativize(file) + ":" + line));
              });
    }

    // A renamed logger field would otherwise silently disable the scan.
    assertThat(logCalls[0]).as("log calls found").isGreaterThan(50);
    assertThat(findings)
        .as("raw agreement id in a log call or exception message; wrap it in AgreementIds.redact")
        .isEmpty();
  }

  // --- matcher self-test -------------------------------------------------------

  @Test
  void flagsARawIdInASingleLineLogCall() {
    assertThat(scan("log.debug(\"Draft stored for agreement {}\", agreementId);"))
        .containsExactly(1);
  }

  @Test
  void flagsARawIdInAMultiLineLogCallAtTheCallsLine() {
    String source =
        """
        class A {
          void f() {
            log.info(
                "Agreement {} closed",
                view.agreementId());
          }
        }
        """;
    assertThat(scan(source)).containsExactly(3);
  }

  @Test
  void flagsAccessorFormsInExceptionConstructions() {
    assertThat(scan("throw new IllegalStateException(\"gone: \" + agreement.getId());"))
        .containsExactly(1);
    assertThat(scan("throw new ResourceNotFoundException(\"x\" + agreement.id());"))
        .containsExactly(1);
    assertThat(scan("throw new AssertionError(agreementId);")).containsExactly(1);
  }

  @Test
  void acceptsTheAgreementIdsWrappers() {
    assertThat(scan("log.debug(\"Draft stored for {}\", AgreementIds.redact(agreementId));"))
        .isEmpty();
    assertThat(scan("log.debug(\"Stored {}\", AgreementIds.redactIn(\"drafts/\" + agreementId));"))
        .isEmpty();
    assertThat(
            scan(
                "throw new ResourceNotFoundException(\"Agreement not found: \""
                    + " + AgreementIds.redact(agreementId));"))
        .isEmpty();
  }

  @Test
  void rejectsAnUnqualifiedOrForeignRedactHelper() {
    assertThat(scan("log.debug(\"cert {}\", CertificateNumbers.redact(agreementId));"))
        .containsExactly(1);
    assertThat(scan("log.debug(\"doc {}\", redact(agreementId));")).containsExactly(1);
  }

  @Test
  void ignoresCommentsJavadocAndStringContents() {
    String source =
        """
        /** Example: {@code log.info("x {}", agreementId)}. */
        // log.debug("y", agreementId);
        class A {
          void f() {
            log.debug("agreementId is not logged here");
            String s = \"""
                log.info(agreementId)
                \""";
          }
        }
        """;
    assertThat(scan(source)).isEmpty();
  }

  @Test
  void anIdOutsideTheCallIsNotItsConcern() {
    assertThat(scan("UUID agreementId = x; log.debug(\"Order placed\");")).isEmpty();
  }

  // --- scanner -----------------------------------------------------------------

  /** One-based line of every log call or exception construction carrying a raw agreement id. */
  static List<Integer> scan(String source) {
    String code = blank(source);
    List<Integer> lines = new ArrayList<>();
    for (Pattern call : List.of(LOG_CALL, CONSTRUCTION)) {
      Matcher m = call.matcher(code);
      while (m.find()) {
        int open = m.end() - 1;
        int close = matchingParen(code, open);
        String args = withoutWrappedArguments(code.substring(open + 1, close));
        if (AGREEMENT_ID.matcher(args).find()) {
          lines.add(lineOf(code, m.start()));
        }
      }
    }
    return lines.stream().sorted().toList();
  }

  private static String withoutWrappedArguments(String args) {
    StringBuilder out = new StringBuilder(args);
    Matcher m = WRAPPER.matcher(args);
    while (m.find()) {
      int open = m.end() - 1;
      int close = matchingParen(args, open);
      for (int i = open + 1; i < close; i++) {
        out.setCharAt(i, ' ');
      }
    }
    return out.toString();
  }

  private static int matchingParen(String code, int open) {
    int depth = 0;
    for (int i = open; i < code.length(); i++) {
      char c = code.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return i;
      }
    }
    return code.length();
  }

  /**
   * {@code source} with comments and the contents of string, char and text-block literals replaced
   * by spaces. Newlines and offsets are preserved, so line numbers and paren matching still hold.
   */
  static String blank(String source) {
    StringBuilder out = new StringBuilder(source);
    int i = 0;
    int n = source.length();
    while (i < n) {
      char c = source.charAt(i);
      if (source.startsWith("//", i)) {
        while (i < n && source.charAt(i) != '\n') {
          out.setCharAt(i++, ' ');
        }
      } else if (source.startsWith("/*", i)) {
        int end = source.indexOf("*/", i + 2);
        end = end < 0 ? n : end + 2;
        blankRange(out, i, end);
        i = end;
      } else if (source.startsWith("\"\"\"", i)) {
        int end = i + 3;
        while (end < n && !source.startsWith("\"\"\"", end)) {
          end += source.charAt(end) == '\\' ? 2 : 1;
        }
        blankRange(out, i + 3, Math.min(end, n));
        i = Math.min(end + 3, n);
      } else if (c == '"' || c == '\'') {
        int end = i + 1;
        while (end < n && source.charAt(end) != c && source.charAt(end) != '\n') {
          end += source.charAt(end) == '\\' ? 2 : 1;
        }
        blankRange(out, i + 1, Math.min(end, n));
        i = Math.min(end + 1, n);
      } else {
        i++;
      }
    }
    return out.toString();
  }

  private static void blankRange(StringBuilder out, int from, int to) {
    for (int k = from; k < to; k++) {
      if (out.charAt(k) != '\n') {
        out.setCharAt(k, ' ');
      }
    }
  }

  private static int lineOf(String code, int offset) {
    int line = 1;
    for (int i = 0; i < offset; i++) {
      if (code.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  private static int count(Pattern pattern, String code) {
    return (int) pattern.matcher(code).results().count();
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
