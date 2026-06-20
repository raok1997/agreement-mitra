import { mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import { nextTick } from "vue";
import SignDemo from "./SignDemo.vue";
import { requestSignature, type SignSession } from "../api/client";

vi.mock("../api/client");

const mockedRequest = vi.mocked(requestSignature);

const session: SignSession = {
  providerRequestId: "req-1",
  signingUrl: "https://sandbox.example/sign/abc",
};

describe("SignDemo", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it("requests a signature for the entered agreement id", async () => {
    mockedRequest.mockResolvedValue(session);
    const wrapper = mount(SignDemo);

    await wrapper.get("input").setValue("agr-99");
    await wrapper.get("button").trigger("click");

    expect(mockedRequest).toHaveBeenCalledWith("agr-99");
  });

  it("disables the button and shows a pending label while in flight", async () => {
    // Deferred promise the test holds unresolved, so the loading frame is observable.
    let resolveIt!: (value: SignSession) => void;
    mockedRequest.mockReturnValue(
      new Promise<SignSession>((resolve) => {
        resolveIt = resolve;
      }),
    );
    const wrapper = mount(SignDemo);

    await wrapper.get("button").trigger("click");
    await nextTick();

    const button = wrapper.get("button");
    expect(button.attributes("disabled")).toBeDefined();
    expect(button.text()).toBe("Requesting...");

    resolveIt(session);
    await nextTick();
  });

  it("renders the signing URL once a session resolves", async () => {
    mockedRequest.mockResolvedValue(session);
    const wrapper = mount(SignDemo);

    await wrapper.get("button").trigger("click");
    await nextTick();
    await nextTick();

    const link = wrapper.get("a");
    expect(link.attributes("href")).toBe(session.signingUrl);
    expect(link.text()).toBe(session.signingUrl);
  });

  it("renders the error text and no signing URL when the request fails", async () => {
    mockedRequest.mockRejectedValue(new Error("Sign request failed: 500"));
    const wrapper = mount(SignDemo);

    await wrapper.get("button").trigger("click");
    await nextTick();
    await nextTick();

    expect(wrapper.text()).toContain("Sign request failed: 500");
    expect(wrapper.find("a").exists()).toBe(false);
  });
});
