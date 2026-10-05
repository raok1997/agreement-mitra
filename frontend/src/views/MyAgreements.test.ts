import { afterEach, describe, it, expect, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import MyAgreements from "./MyAgreements.vue";
import * as agreements from "../api/agreements";
import type { AgreementSummary } from "../api/agreements";

// Component test (5.10): the list renders a status badge per row and offers "Edit" only when the
// backend flags the row editable (a signed one offers "View" instead). Clicking emits the row id.
vi.mock("../api/agreements", () => ({ listMyAgreements: vi.fn() }));
const mockedList = vi.mocked(agreements.listMyAgreements);

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
});
