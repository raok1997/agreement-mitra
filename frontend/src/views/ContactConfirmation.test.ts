import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import ContactConfirmation from "./ContactConfirmation.vue";

const complete = [
  { id: "a", name: "Asha Owner", role: "OWNER", email: "asha@example.com", mobile: "" },
  { id: "b", name: "Tara Tenant", role: "TENANT", email: "tara@example.com", mobile: "" },
];

const missing = [
  { id: "a", name: "Asha Owner", role: "OWNER", email: "asha@example.com", mobile: "" },
  { id: "b", name: "Tara Tenant", role: "TENANT", email: "", mobile: "" },
];

describe("ContactConfirmation", () => {
  it("reads as a confirmation when nothing is missing", () => {
    const wrapper = mount(ContactConfirmation, { props: { parties: complete } });
    expect(wrapper.text()).toContain("Confirm where we send the agreement");
  });

  it("asks for what is missing when a party is unreachable", () => {
    const wrapper = mount(ContactConfirmation, { props: { parties: missing } });
    expect(wrapper.text()).toContain("How should we reach each party?");
    expect(wrapper.text()).toContain("Add a valid email address for this party");
  });

  it("blocks continuing while any party is unreachable", () => {
    const wrapper = mount(ContactConfirmation, { props: { parties: missing } });
    expect(wrapper.get("button[type='button']").attributes("disabled")).toBeDefined();
  });

  it("emits the corrected contacts on confirm", async () => {
    const wrapper = mount(ContactConfirmation, { props: { parties: complete } });
    await wrapper.get("button[type='button']").trigger("click");
    const emitted = wrapper.emitted("confirm");
    expect(emitted).toBeTruthy();
    expect(emitted![0][0]).toHaveLength(2);
  });

  it("never presents a disabled channel as a delivery route", () => {
    // No SMS or WhatsApp provider exists. Saying "we will text you" would be a promise the system
    // cannot keep, so mobile is described as future/notification use only.
    const wrapper = mount(ContactConfirmation, { props: { parties: complete } });
    const text = wrapper.text().toLowerCase();
    expect(text).not.toContain("we will sms");
    expect(text).not.toContain("whatsapp");
    expect(text).toContain("signing notifications");
  });

  it("rejects a malformed mobile without demanding one", () => {
    const wrapper = mount(ContactConfirmation, {
      props: {
        parties: [
          { id: "a", name: "Asha", role: "OWNER", email: "asha@example.com", mobile: "abc" },
        ],
      },
    });
    expect(wrapper.text()).toContain("does not look right");
  });
});
