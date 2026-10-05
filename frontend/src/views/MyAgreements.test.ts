import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import MyAgreements from "./MyAgreements.vue";
import * as agreements from "../api/agreements";
import type { AgreementSummary } from "../api/agreements";

// Component test (5.10): the list renders a status badge per row and offers "Edit" only when the
// backend flags the row editable (a signed one offers "View" instead). Clicking emits the row id.
vi.mock("../api/agreements", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/agreements")>();
  return {
    AgreementHttpError: actual.AgreementHttpError,
    listMyAgreements: vi.fn(),
    deleteAgreement: vi.fn(),
  };
});
const mockedList = vi.mocked(agreements.listMyAgreements);
const mockedDelete = vi.mocked(agreements.deleteAgreement);

function row(over: Partial<AgreementSummary>): AgreementSummary {
  return {
    id: "id-1",
    trackingNumber: "AM-ABC123-010126",
    propertyAddress: "12 MG Road",
    monthlyRent: 25000,
    startDate: "2026-01-01",
    endDate: "2026-12-01",
    durationMonths: 11,
    createdAt: "2026-01-01T00:00:00Z",
    lastEditedAt: "2026-01-01T00:00:00Z",
    ownerNames: ["Asha Owner"],
    tenantNames: ["Tara Tenant"],
    status: "DRAFT",
    editable: true,
    deletable: false,
    ...over,
  };
}

describe("MyAgreements", () => {
  it("renders a status badge and gates Edit on editable", async () => {
    mockedList.mockResolvedValue([
      row({ id: "draft-1", status: "DRAFT", editable: true }),
      row({ id: "signed-1", status: "SIGNED", editable: false }),
    ]);

    const wrapper = mount(MyAgreements);
    await flushPromises();

    // Draft row: editable -> Edit button, badge shows the draft label.
    expect(wrapper.find('[data-testid="status-draft-1"]').text()).toBe("Draft");
    expect(wrapper.find('[data-testid="edit-draft-1"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="view-draft-1"]').exists()).toBe(false);

    // Signed row: not editable -> View button, badge shows Signed.
    expect(wrapper.find('[data-testid="status-signed-1"]').text()).toBe(
      "Signed",
    );
    expect(wrapper.find('[data-testid="view-signed-1"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="edit-signed-1"]').exists()).toBe(false);
  });

  it("emits edit with the row id when Edit is clicked", async () => {
    mockedList.mockResolvedValue([row({ id: "draft-1", editable: true })]);
    const wrapper = mount(MyAgreements);
    await flushPromises();

    await wrapper.find('[data-testid="edit-draft-1"]').trigger("click");

    expect(wrapper.emitted("edit")?.[0]).toEqual(["draft-1"]);
  });

  it("shows an empty state when there are no agreements", async () => {
    mockedList.mockResolvedValue([]);
    const wrapper = mount(MyAgreements);
    await flushPromises();

    expect(wrapper.find('[data-testid="list-empty"]').exists()).toBe(true);
  });

  describe("rich rows", () => {
    afterEach(() => {
      vi.useRealTimers();
      vi.restoreAllMocks();
    });

    it("shows the first owner and tenant with a count of the rest", async () => {
      mockedList.mockResolvedValue([
        row({
          id: "multi",
          ownerNames: ["Ramesh Kumar Reddy", "Lakshmi Devi Reddy"],
          tenantNames: [
            "Rohan Deshpande",
            "Aditya Kulkarni",
            "Mohammed Faizan",
          ],
        }),
      ]);
      const wrapper = mount(MyAgreements);
      await flushPromises();

      expect(wrapper.find('[data-testid="owner-multi"]').text()).toBe(
        "Ramesh Kumar Reddy",
      );
      expect(wrapper.find('[data-testid="owner-more-multi"]').text()).toBe(
        "+1",
      );
      expect(wrapper.find('[data-testid="tenant-multi"]').text()).toBe(
        "Rohan Deshpande",
      );
      expect(wrapper.find('[data-testid="tenant-more-multi"]').text()).toBe(
        "+2",
      );
    });

    it("expands to every party in order with the full address and reference, then collapses", async () => {
      const address =
        "Flat 3C, Sai Krupa Residency, 27th Main Road, Sector 2, opposite BDA Complex, HSR Layout";
      mockedList.mockResolvedValue([
        row({
          id: "x",
          trackingNumber: "AMC2D9A8QZ",
          propertyAddress: address,
          ownerNames: ["Owner One", "Owner Two"],
          tenantNames: ["Tenant One"],
        }),
      ]);
      const wrapper = mount(MyAgreements);
      await flushPromises();
      const toggle = wrapper.find('[data-testid="toggle-x"]');
      expect(toggle.attributes("aria-expanded")).toBe("false");

      await toggle.trigger("click");

      expect(toggle.attributes("aria-expanded")).toBe("true");
      expect(
        wrapper
          .findAll('[data-testid="detail-party"]')
          .map((p) => p.findAll("span").map((span) => span.text())),
      ).toEqual([
        ["Owner", "Owner One"],
        ["Owner", "Owner Two"],
        ["Tenant", "Tenant One"],
      ]);
      expect(wrapper.find('[data-testid="detail-address"]').text()).toBe(
        address,
      );
      expect(wrapper.find('[data-testid="detail-reference"]').text()).toBe(
        "AMC2D9A8QZ",
      );

      await toggle.trigger("click");
      expect(wrapper.find('[data-testid="detail-x"]').exists()).toBe(false);
    });

    it("toggles a row with Enter and keeps one row open at a time", async () => {
      mockedList.mockResolvedValue([row({ id: "r1" }), row({ id: "r2" })]);
      const wrapper = mount(MyAgreements);
      await flushPromises();

      await wrapper
        .find('[data-testid="toggle-r1"]')
        .trigger("keydown", { key: "Enter" });
      expect(wrapper.find('[data-testid="detail-r1"]').exists()).toBe(true);

      await wrapper
        .find('[data-testid="toggle-r2"]')
        .trigger("keydown", { key: " " });
      expect(wrapper.find('[data-testid="detail-r1"]').exists()).toBe(false);
      expect(wrapper.find('[data-testid="detail-r2"]').exists()).toBe(true);
    });

    it("emits edit without expanding the row", async () => {
      mockedList.mockResolvedValue([row({ id: "e1", editable: true })]);
      const wrapper = mount(MyAgreements);
      await flushPromises();

      await wrapper.find('[data-testid="edit-e1"]').trigger("click");

      expect(wrapper.emitted("edit")?.[0]).toEqual(["e1"]);
      expect(wrapper.find('[data-testid="detail-e1"]').exists()).toBe(false);
    });

    it("shows the edit time as relative time", async () => {
      vi.useFakeTimers({ toFake: ["Date"] });
      vi.setSystemTime(new Date(2026, 9, 4, 14, 0));
      mockedList.mockResolvedValue([
        row({
          id: "t",
          lastEditedAt: new Date(2026, 9, 4, 12, 0).toISOString(),
        }),
      ]);
      const wrapper = mount(MyAgreements);
      await flushPromises();

      expect(wrapper.find('[data-testid="edited-t"]').text()).toBe(
        "Edited 2h ago",
      );
    });

    it("renders markup in a name as text", async () => {
      const markup = "<img src=x onerror=alert(1)>";
      mockedList.mockResolvedValue([row({ id: "m", ownerNames: [markup] })]);
      const wrapper = mount(MyAgreements);
      await flushPromises();
      await wrapper.find('[data-testid="toggle-m"]').trigger("click");

      expect(wrapper.find('[data-testid="owner-m"]').text()).toBe(markup);
      expect(wrapper.find("img").exists()).toBe(false);
    });

    it("filters to a match on a party who is not listed first", async () => {
      mockedList.mockResolvedValue([
        row({ id: "hit", tenantNames: ["Rohan Deshpande", "Mohammed Faizan"] }),
        row({ id: "miss", tenantNames: ["Neha Agarwal"] }),
      ]);
      const wrapper = mount(MyAgreements);
      await flushPromises();

      await wrapper.find('[data-testid="list-search"]').setValue("faizan");

      expect(wrapper.find('[data-testid="row-hit"]').exists()).toBe(true);
      expect(wrapper.find('[data-testid="row-miss"]').exists()).toBe(false);
    });

    it("says nothing matches and writes the query nowhere", async () => {
      const pushState = vi.spyOn(history, "pushState");
      const replaceState = vi.spyOn(history, "replaceState");
      const setItem = vi.spyOn(Storage.prototype, "setItem");
      mockedList.mockResolvedValue([row({ id: "only" })]);
      const wrapper = mount(MyAgreements);
      await flushPromises();

      await wrapper.find('[data-testid="list-search"]').setValue("koramangala");

      const message = wrapper.find('[data-testid="list-no-match"]');
      expect(message.text()).toContain('No agreements match "koramangala"');
      expect(message.text()).toContain(
        "Try a name, the address or the reference",
      );
      expect(wrapper.find('[data-testid="row-only"]').exists()).toBe(false);
      expect(pushState).not.toHaveBeenCalled();
      expect(replaceState).not.toHaveBeenCalled();
      expect(setItem).not.toHaveBeenCalled();
    });

    it("shows the empty state and no search box when there are no agreements", async () => {
      mockedList.mockResolvedValue([]);
      const wrapper = mount(MyAgreements);
      await flushPromises();

      expect(wrapper.find('[data-testid="list-empty"]').exists()).toBe(true);
      expect(wrapper.find('[data-testid="list-search"]').exists()).toBe(false);
    });
  });

  describe("delete", () => {
    let wrapper: ReturnType<typeof mount> | undefined;

    beforeEach(() => {
      mockedDelete.mockReset();
      mockedList.mockReset();
    });
    afterEach(() => wrapper?.unmount());

    async function mountWith(rows: AgreementSummary[]) {
      mockedList.mockResolvedValue(rows);
      wrapper = mount(MyAgreements, { attachTo: document.body });
      await flushPromises();
      return wrapper;
    }

    const deletableDraft = (over: Partial<AgreementSummary> = {}) =>
      row({ id: "d1", status: "DRAFT", deletable: true, ...over });

    async function openDialog(w: ReturnType<typeof mount>, id = "d1") {
      await w.find(`[data-testid="delete-${id}"]`).trigger("click");
      await flushPromises();
    }

    it("offers Delete only on deletable rows", async () => {
      const w = await mountWith([
        deletableDraft(),
        row({ id: "d2", status: "DRAFT", deletable: false }),
        row({ id: "p1", status: "IN_PROGRESS", editable: false }),
        row({ id: "s1", status: "SIGNED", editable: false }),
      ]);

      expect(w.find('[data-testid="delete-d1"]').exists()).toBe(true);
      for (const id of ["d2", "p1", "s1"]) {
        expect(w.find(`[data-testid="delete-${id}"]`).exists()).toBe(false);
      }
    });

    it("opens a dialog naming the agreement, as text, without toggling the row", async () => {
      const w = await mountWith([
        deletableDraft({
          ownerNames: ["<b>Asha</b>"],
          tenantNames: [],
          trackingNumber: "AM-XYZ",
        }),
      ]);

      await openDialog(w);

      expect(w.find('[data-testid="detail-d1"]').exists()).toBe(false);
      const dialog = w.find('[data-testid="confirm-dialog"]');
      expect(dialog.find('[data-testid="confirm-agreement"]').text()).toBe(
        "<b>Asha</b> / — · AM-XYZ",
      );
      expect(dialog.find("b").exists()).toBe(false);
      expect(dialog.text()).toContain("cannot be restored");
      expect(dialog.text()).toContain(
        "Copies already emailed to the parties cannot be recalled",
      );
    });

    it.each(["button", "escape"])(
      "cancelling (%s) sends nothing, keeps the row and refocuses Delete",
      async (how) => {
        const w = await mountWith([deletableDraft()]);
        await openDialog(w);

        if (how === "button") {
          await w.find('[data-testid="confirm-cancel"]').trigger("click");
        } else {
          await w
            .find('[role="dialog"]')
            .trigger("keydown", { key: "Escape" });
        }
        await flushPromises();

        expect(mockedDelete).not.toHaveBeenCalled();
        expect(w.find('[data-testid="confirm-dialog"]').exists()).toBe(false);
        expect(w.find('[data-testid="row-d1"]').exists()).toBe(true);
        expect(document.activeElement).toBe(
          w.find('[data-testid="delete-d1"]').element,
        );
      },
    );

    it("confirming deletes once, disables confirm while pending, then removes the row", async () => {
      let resolve!: () => void;
      mockedDelete.mockReturnValue(
        new Promise<void>((r) => {
          resolve = r;
        }),
      );
      const w = await mountWith([deletableDraft(), row({ id: "other" })]);
      await openDialog(w);

      await w.find('[data-testid="confirm-ok"]').trigger("click");
      expect(
        w.find('[data-testid="confirm-ok"]').attributes("disabled"),
      ).toBeDefined();
      await w.find('[data-testid="confirm-ok"]').trigger("click");
      resolve();
      await flushPromises();

      expect(mockedDelete).toHaveBeenCalledTimes(1);
      expect(mockedDelete).toHaveBeenCalledWith("d1");
      expect(w.find('[data-testid="row-d1"]').exists()).toBe(false);
      expect(w.find('[data-testid="row-other"]').exists()).toBe(true);
      expect(document.activeElement).toBe(
        w.find('[data-testid="list-search"]').element,
      );
    });

    it("deleting the last agreement shows the empty state", async () => {
      mockedDelete.mockResolvedValue();
      const w = await mountWith([deletableDraft()]);
      await openDialog(w);

      await w.find('[data-testid="confirm-ok"]').trigger("click");
      await flushPromises();

      expect(w.find('[data-testid="list-empty"]').exists()).toBe(true);
    });

    it("a 409 says it is no longer a draft and reloads, and the notice survives the reload", async () => {
      mockedDelete.mockRejectedValue(
        new agreements.AgreementHttpError(
          409,
          "urn:agreementmitra:problem:draft-not-deletable",
        ),
      );
      const w = await mountWith([deletableDraft()]);
      mockedList.mockResolvedValue([
        deletableDraft({ status: "IN_PROGRESS", deletable: false }),
      ]);
      await openDialog(w);

      await w.find('[data-testid="confirm-ok"]').trigger("click");
      await flushPromises();

      expect(mockedList).toHaveBeenCalledTimes(2);
      expect(w.find('[data-testid="list-notice"]').text()).toContain(
        "no longer a draft",
      );
      expect(w.find('[data-testid="delete-d1"]').exists()).toBe(false);
      expect(document.activeElement).toBe(
        w.find('[data-testid="list-notice"]').element,
      );
    });

    it("a 404 says it no longer exists and removes the row", async () => {
      mockedDelete.mockRejectedValue(
        new agreements.AgreementHttpError(
          404,
          "urn:agreementmitra:problem:resource-not-found",
        ),
      );
      const w = await mountWith([deletableDraft(), row({ id: "other" })]);
      await openDialog(w);

      await w.find('[data-testid="confirm-ok"]').trigger("click");
      await flushPromises();

      expect(w.find('[data-testid="list-notice"]').text()).toContain(
        "no longer exists",
      );
      expect(w.find('[data-testid="row-d1"]').exists()).toBe(false);
    });

    it("an unexpected failure says so and keeps the row", async () => {
      mockedDelete.mockRejectedValue(new agreements.AgreementHttpError(500));
      const w = await mountWith([deletableDraft()]);
      await openDialog(w);

      await w.find('[data-testid="confirm-ok"]').trigger("click");
      await flushPromises();

      expect(w.find('[data-testid="list-notice"]').text()).toContain(
        "Could not delete",
      );
      expect(w.find('[data-testid="row-d1"]').exists()).toBe(true);
    });
  });
});
