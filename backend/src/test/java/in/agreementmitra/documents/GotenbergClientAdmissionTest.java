package in.agreementmitra.documents;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import in.agreementmitra.RenderCapacityException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Render admission control against a blocking upstream (anonymous-surface-abuse-controls task
 * 10.8). The stub Gotenberg answers after a fixed delay, so a render in flight holds its slot for
 * that long; slot counts are small so saturation takes two or three renders.
 */
class GotenbergClientAdmissionTest {

  private static final String ROUTE = "/forms/chromium/convert/html";
  private static final int RENDER_MILLIS = 1500;
  private static final Duration ADMISSION_WAIT = Duration.ofMillis(300);

  private static final WireMockServer GOTENBERG = new WireMockServer(options().dynamicPort());

  private final ExecutorService pool = Executors.newCachedThreadPool();
  private final List<CompletableFuture<byte[]>> inFlight = new ArrayList<>();
  private GotenbergClient client;

  @BeforeAll
  static void start() {
    GOTENBERG.start();
  }

  @AfterAll
  static void stop() {
    GOTENBERG.stop();
  }

  @BeforeEach
  void setUp() {
    GOTENBERG.resetAll();
    GOTENBERG.stubFor(
        post(urlEqualTo(ROUTE))
            .willReturn(
                aResponse()
                    .withFixedDelay(RENDER_MILLIS)
                    .withBody("%PDF-1.4 stub".getBytes(StandardCharsets.ISO_8859_1))));
    // 2 slots: 1 general + 1 reserved for fulfilment; a waiting room of 1.
    GotenbergProperties properties =
        new GotenbergProperties(
            GOTENBERG.baseUrl(), 2, Duration.ofSeconds(10), ADMISSION_WAIT, 1, 1);
    client =
        new GotenbergClient(
            RestClient.builder()
                .baseUrl(GOTENBERG.baseUrl())
                // HTTP/1.1: the JDK client's default h2c upgrade is reset by WireMock.
                .requestFactory(
                    new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
                .build(),
            properties,
            new DocumentFooterProperties("", ""));
  }

  @AfterEach
  void tearDown() {
    inFlight.forEach(f -> f.join());
    pool.shutdownNow();
  }

  private CompletableFuture<byte[]> renderInBackground(RenderPriority priority) {
    CompletableFuture<byte[]> future =
        CompletableFuture.supplyAsync(() -> client.renderHtml("<p>x</p>", "REF", priority), pool);
    inFlight.add(future.exceptionally(e -> null));
    return future;
  }

  /** Wait until the upstream has seen {@code count} renders, so their slots are really held. */
  private static void awaitUpstreamCalls(int count) {
    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> GOTENBERG.findAll(postRequestedFor(urlEqualTo(ROUTE))).size() >= count);
  }

  @Test
  void aRenderWithASlotFreeProceedsAsBefore() {
    assertThat(client.renderHtml("<p>x</p>", "REF", RenderPriority.STANDARD))
        .startsWith("%PDF-".getBytes(StandardCharsets.ISO_8859_1));
  }

  @Test
  void aStandardRenderIsRefusedAtOnceWhenTheGeneralSlotIsHeldAndTheWaitingRoomIsFull() {
    renderInBackground(RenderPriority.STANDARD);
    awaitUpstreamCalls(1);
    renderInBackground(RenderPriority.STANDARD); // the one waiter
    Awaitility.await().pollDelay(Duration.ofMillis(150)).until(() -> true);

    long started = System.nanoTime();
    assertThatThrownBy(() -> client.renderHtml("<p>x</p>", "REF", RenderPriority.STANDARD))
        .isInstanceOf(RenderCapacityException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(ADMISSION_WAIT);
  }

  @Test
  void aStandardRenderThatWaitsPastTheBoundIsRefusedWhenItElapses() {
    renderInBackground(RenderPriority.STANDARD);
    awaitUpstreamCalls(1);

    long started = System.nanoTime();
    assertThatThrownBy(() -> client.renderHtml("<p>x</p>", "REF", RenderPriority.STANDARD))
        .isInstanceOf(RenderCapacityException.class);
    Duration waited = Duration.ofNanos(System.nanoTime() - started);
    assertThat(waited).isGreaterThanOrEqualTo(ADMISSION_WAIT);
    assertThat(waited).isLessThan(Duration.ofMillis(RENDER_MILLIS));
  }

  @Test
  void aFulfilmentRenderIsAdmittedToTheReservedSlotWhileTheGeneralOneIsHeld() {
    renderInBackground(RenderPriority.STANDARD);
    awaitUpstreamCalls(1);

    assertThat(client.renderHtml("<p>x</p>", "REF", RenderPriority.FULFILMENT))
        .startsWith("%PDF-".getBytes(StandardCharsets.ISO_8859_1));
  }

  @Test
  void aFulfilmentRenderIsRefusedWhenTheReservedSlotIsAlsoHeld() {
    renderInBackground(RenderPriority.STANDARD);
    renderInBackground(RenderPriority.FULFILMENT);
    awaitUpstreamCalls(2);

    assertThatThrownBy(() -> client.renderHtml("<p>x</p>", "REF", RenderPriority.FULFILMENT))
        .isInstanceOf(RenderCapacityException.class);
  }

  @Test
  void aFullWaitingRoomDoesNotFastFailAFulfilmentRender() {
    // Anonymous renders hold the general slot and fill the waiting room; a fulfilment render holds
    // the reserved slot. A second fulfilment render must still get its bounded wait for a slot,
    // not the immediate refusal the anonymous waiting room gives a STANDARD render.
    renderInBackground(RenderPriority.STANDARD);
    renderInBackground(RenderPriority.FULFILMENT);
    awaitUpstreamCalls(2);
    renderInBackground(RenderPriority.STANDARD); // the one waiter
    Awaitility.await().pollDelay(Duration.ofMillis(150)).until(() -> true);

    long started = System.nanoTime();
    assertThatThrownBy(() -> client.renderHtml("<p>x</p>", "REF", RenderPriority.FULFILMENT))
        .isInstanceOf(RenderCapacityException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isGreaterThanOrEqualTo(ADMISSION_WAIT);
  }
}
