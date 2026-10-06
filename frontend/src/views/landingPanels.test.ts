import { describe, expect, it } from "vitest";
import { PANEL_IDS, panelForHash } from "./landingPanels";

describe("landingPanels", () => {
  it("lists the panels in tab order", () => {
    expect(PANEL_IDS).toEqual(["guarantees", "status", "faq"]);
  });

  it.each(PANEL_IDS)("maps #%s to its panel", (id) => {
    expect(panelForHash(`#${id}`)).toBe(id);
  });

  it.each([
    "",
    "#",
    "#how",
    "#price",
    "#STATUS",
    "status",
    "#status?x",
    "https://x/#status",
  ])("returns null for %j", (hash) => {
    expect(panelForHash(hash)).toBeNull();
  });
});
