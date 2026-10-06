import { describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import LandingPage from "./LandingPage.vue";
import type * as ReleaseStatusModule from "../content/releaseStatus";

// Its own file because vi.mock is hoisted file-wide. Proves a launch flip is a one-line module edit:
// flipping the eSign row changes that row on the page and nothing else.
const { FLIPPED } = vi.hoisted(() => ({ FLIPPED: "Aadhaar OTP eSign" }));

vi.mock("../content/releaseStatus", async (importOriginal) => {
  const original = await importOriginal<typeof ReleaseStatusModule>();
  return {
    ...original,
    RELEASE_STATUS: original.RELEASE_STATUS.map((row) =>
      row.label === FLIPPED ? { ...row, state: "live" as const } : row,
    ),
  };
});

describe("LandingPage status flip", () => {
  it("shows the flipped row live and leaves every other row on its module label", async () => {
    const { RELEASE_STATUS, RELEASE_STATE_LABEL } =
      await import("../content/releaseStatus");
    const board = mount(LandingPage)
      .findAll("#status li")
      .map((li) => li.findAll("span").map((s) => s.text().trim()));

    expect(board).toHaveLength(RELEASE_STATUS.length);
    for (const [label, badge] of board) {
      const row = RELEASE_STATUS.find((r) => r.label === label)!;
      expect(badge, label).toBe(
        label === FLIPPED ? "Live now" : RELEASE_STATE_LABEL[row.state],
      );
    }
    expect(RELEASE_STATUS.find((r) => r.label === FLIPPED)?.state).toBe("live");
  });
});
