import { afterEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import AuthCallback from "./AuthCallback.vue";
import * as authStore from "../api/authStore";

// The callback view reads the single-use handoff from the URL fragment and exchanges it for a
// session, then signals the app (emit "done"). Only the store is mocked.
vi.mock("../api/authStore", () => ({ completeLogin: vi.fn() }));
const mockedComplete = vi.mocked(authStore.completeLogin);

function setHash(hash: string) {
  window.location.hash = hash;
}

describe("AuthCallback", () => {
  afterEach(() => {
    setHash("");
    mockedComplete.mockReset();
  });

  it("exchanges the handoff from the fragment and emits done", async () => {
    setHash("#handoff=one-time-abc");
    mockedComplete.mockResolvedValue();

    const wrapper = mount(AuthCallback);
    await flushPromises();

    expect(mockedComplete).toHaveBeenCalledWith("one-time-abc");
    expect(wrapper.emitted("done")).toHaveLength(1);
  });

  it("shows an error and does not emit done when the fragment has no handoff", async () => {
    setHash("#");
    const wrapper = mount(AuthCallback);
    await flushPromises();

    expect(mockedComplete).not.toHaveBeenCalled();
    expect(wrapper.emitted("done")).toBeUndefined();
    expect(wrapper.text()).toContain("couldn't complete sign-in");
  });

  it("shows an error and does not emit done when the exchange fails", async () => {
    setHash("#handoff=bad");
    mockedComplete.mockRejectedValue(new Error("nope"));

    const wrapper = mount(AuthCallback);
    await flushPromises();

    expect(mockedComplete).toHaveBeenCalledWith("bad");
    expect(wrapper.emitted("done")).toBeUndefined();
    expect(wrapper.text()).toContain("couldn't complete sign-in");
  });
});
