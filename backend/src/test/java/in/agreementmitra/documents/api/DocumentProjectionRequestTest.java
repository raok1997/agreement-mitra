package in.agreementmitra.documents.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DocumentProjectionRequest#activeSections()} normalization (task 1.1): a
 * null list collapses to an empty, unmodifiable, order-preserving list; a supplied list is copied
 * defensively (mutating the source never leaks into the record) and its order is preserved. No
 * Spring context, no I/O.
 */
class DocumentProjectionRequestTest {

  @Test
  void nullActiveSectionsCollapsesToAnEmptyUnmodifiableList() {
    DocumentProjectionRequest request = new DocumentProjectionRequest(null, Map.of(), null);

    assertThat(request.activeSections()).isEmpty();
    assertThatThrownBy(() -> request.activeSections().add("Pets"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void theTwoArgConvenienceConstructorDefaultsActiveSectionsToEmpty() {
    DocumentProjectionRequest request = new DocumentProjectionRequest(null, Map.of());

    assertThat(request.activeSections()).isEmpty();
  }

  @Test
  void aSuppliedListIsCopiedDefensivelySoMutatingTheSourceDoesNotAffectTheRecord() {
    List<String> source = new ArrayList<>(List.of("Pets", "Rent Escalation"));
    DocumentProjectionRequest request = new DocumentProjectionRequest(null, Map.of(), source);

    source.add("Injected");
    source.remove("Pets");

    assertThat(request.activeSections()).containsExactly("Pets", "Rent Escalation");
    assertThatThrownBy(() -> request.activeSections().add("Injected"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void orderIsPreserved() {
    DocumentProjectionRequest request =
        new DocumentProjectionRequest(null, Map.of(), List.of("Zebra", "Alpha", "Mango"));

    assertThat(request.activeSections()).containsExactly("Zebra", "Alpha", "Mango");
  }
}
