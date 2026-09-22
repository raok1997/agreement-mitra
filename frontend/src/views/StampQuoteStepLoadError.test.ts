import { afterEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import StampQuoteStep from "./StampQuoteStep.vue";

// Kept apart from StampQuoteStep.test.ts, which mocks the stamp quote module: here the real API
// client meets a failing server (a stubbed fetch), which is the failure the step must survive.

vi.mock("../api/authStore", () => ({ authHeader: () => ({}) }));

afterEach(() => vi.unstubAllGlobals());

it("reports a failed quote load without offering payment", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response("{}", { status: 500 })),
  );

  const wrapper = mount(StampQuoteStep, { props: { agreementId: "agr-1" } });
  await flushPromises();

  expect(wrapper.text()).toContain("Could not load the stamp duty");
  expect(wrapper.find('[data-testid="stamp-quote-pay"]').exists()).toBe(false);
});
