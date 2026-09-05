import { describe, it, expect, vi } from "vitest";
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
});
