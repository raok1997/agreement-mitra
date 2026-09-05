import { describe, expect, it, vi, beforeEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import RecoverAgreement from "./RecoverAgreement.vue";
import * as recovery from "../api/recovery";

vi.mock("../api/recovery", () => ({ requestRecovery: vi.fn() }));
const mockedRequest = vi.mocked(recovery.requestRecovery);

// THE PROPERTY UNDER TEST IS THAT THIS SCREEN TELLS YOU NOTHING. The server answers identically for
// every outcome, and this view must not reintroduce the distinction the server refuses to make -- a
// "no agreement found" branch here would hand back the enumeration oracle the whole design avoids.
describe("RecoverAgreement", () => {
  beforeEach(() => mockedRequest.mockReset());

  const VALID = "AM3G3VXSAKD";

  it("will not submit a reference that fails its own check character", async () => {
    const wrapper = mount(RecoverAgreement);
    await wrapper.get('[data-testid="recovery-reference"]').setValue("AM3G3VXSAKE");

    expect(wrapper.get('[data-testid="recovery-submit"]').attributes("disabled")).toBeDefined();
    expect(wrapper.text()).toContain("does not look right");
    expect(mockedRequest).not.toHaveBeenCalled();
  });

  it("submits a well-formed reference", async () => {
    mockedRequest.mockResolvedValue(undefined);
    const wrapper = mount(RecoverAgreement);
    await wrapper.get('[data-testid="recovery-reference"]').setValue(VALID);
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(mockedRequest).toHaveBeenCalledWith(VALID);
  });

  it("shows one outcome, which asserts nothing about the reference", async () => {
    mockedRequest.mockResolvedValue(undefined);
    const wrapper = mount(RecoverAgreement);
    await wrapper.get('[data-testid="recovery-reference"]').setValue(VALID);
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    const text = wrapper.get('[data-testid="recovery-sent"]').text();
    expect(text).toContain("Check your email");
    // Conditional by construction: it must not claim the reference matched anything.
    expect(text).toContain("If that reference matches a paid agreement");
    expect(text).not.toContain("not found");
  });

  it("reports a transport failure without implying anything about the reference", async () => {
    mockedRequest.mockImplementationOnce(() => Promise.reject(new Error("offline")));
    const wrapper = mount(RecoverAgreement);
    await wrapper.get('[data-testid="recovery-reference"]').setValue(VALID);
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(wrapper.text()).toContain("could not send that request");
    expect(wrapper.find('[data-testid="recovery-sent"]').exists()).toBe(false);
  });
});
