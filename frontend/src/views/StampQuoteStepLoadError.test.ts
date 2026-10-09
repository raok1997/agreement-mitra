import { afterEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import StampQuoteStep from "./StampQuoteStep.vue";

// Kept apart from StampQuoteStep.test.ts, which mocks the stamp quote module: here the real API
// client meets a failing server (a stubbed fetch), which is the failure the step must survive.

vi.mock("../api/authStore", () => ({
  authHeader: () => ({}),
  reconcile: async () => {},
}));

// The stored agreement loads; only the quote fails, so the quote's failure is the case under test.
const STORED = {
  id: "agr-1",
  propertyAddress: "12 MG Road",
  monthlyRent: 25000,
  securityDeposit: 100000,
  startDate: "2026-11-01",
  endDate: "2027-10-31",
  durationMonths: 12,
  signers: [{ id: "o", name: "Ravi Kumar", role: "OWNER" }],
};

afterEach(() => vi.unstubAllGlobals());

it("reports a failed quote load without offering payment", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) =>
      String(input).endsWith("/api/agreements/agr-1")
        ? new Response(JSON.stringify(STORED), { status: 200 })
        : new Response("{}", { status: 500 }),
    ),
  );

  const wrapper = mount(StampQuoteStep, { props: { agreementId: "agr-1" } });
  await flushPromises();

  expect(wrapper.text()).toContain("Could not load the stamp duty");
  expect(wrapper.get('[data-testid="key-terms-rent"]').text()).toBe("₹25,000");
  expect(wrapper.find('[data-testid="stamp-quote-pay"]').exists()).toBe(false);
});
