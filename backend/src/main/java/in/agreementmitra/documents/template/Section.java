package in.agreementmitra.documents.template;

import java.util.List;

/**
 * An ordered group within a definition. {@code entries} are field {@code key}s and inline-clause
 * {@code id}s, in authored order (both section order and entry order are content-bearing and
 * preserved). Every entry must resolve to a declared field or inline clause. {@code optional} marks
 * an opt-in add-on section (default {@code false} = mandatory); {@code render} selects the layout
 * kind the compiler uses (default {@code KEYVALUE}).
 */
record Section(String title, List<String> entries, boolean optional, RenderKind render) {

  Section {
    entries = List.copyOf(entries);
  }
}
