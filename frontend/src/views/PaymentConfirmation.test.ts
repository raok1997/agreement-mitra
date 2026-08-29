import { afterEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import PaymentConfirmation from "./PaymentConfirmation.vue";

// This screen is the customer's last, and the only place the reference and the recovery link are
// put in front of them. What it claims therefore has to be true: promising an email that was never
// sent leaves someone waiting for a message that will never arrive.
describe("PaymentConfirmation", () => {
  const base = {
    reference: "AM3G3VXSAKD",
    amountLabel: "INR 499.00",
    linkSent: true,
  };

  it("shows the reference prominently", () => {
    const wrapper = mount(PaymentConfirmation, { props: base });
    expect(wrapper.get('[data-testid="confirmation-reference"]').text()).toBe(
      "AM3G3VXSAKD",
    );
  });

  it("shows the amount the server confirmed", () => {
    const wrapper = mount(PaymentConfirmation, { props: base });
    expect(wrapper.text()).toContain("INR 499.00");
  });

  it("omits the amount rather than inventing one when it could not be read back", () => {
    const wrapper = mount(PaymentConfirmation, {
      props: { ...base, amountLabel: null },
    });
    expect(wrapper.text()).not.toContain("Amount paid");
  });

  it("tells the customer a link was emailed, and how that access ends", () => {
    const wrapper = mount(PaymentConfirmation, { props: base });
    expect(wrapper.text()).toContain("emailed you a link");
    // The link is permanent and claiming is the only revocation, so it has to be disclosed.
    expect(wrapper.text().toLowerCase()).toContain("sign in");
  });

  it("does not promise an email when there was no address to send one to", () => {
    const wrapper = mount(PaymentConfirmation, {
      props: { ...base, linkSent: false },
    });
    expect(wrapper.text()).not.toContain("emailed you a link");
    expect(wrapper.text()).toContain("Write down your reference");
  });

  // A customer with no account has the reference and nothing else. Taking it off the screen - onto
  // paper or into a PDF - has to be one click away, or it is only ever transcribed by hand.
  describe("keeping a copy", () => {
    afterEach(() => {
      vi.unstubAllGlobals();
    });

    it("opens the browser's print dialog, which is also its save-as-PDF", async () => {
      const print = vi.fn();
      vi.stubGlobal("print", print);
      const wrapper = mount(PaymentConfirmation, { props: base });

      await wrapper.get('[data-testid="print-receipt"]').trigger("click");

      expect(print).toHaveBeenCalledTimes(1);
    });

    it("keeps the payment facts on the printed sheet and the buttons off it", () => {
      const wrapper = mount(PaymentConfirmation, { props: base });
      // jsdom applies no stylesheet, so what is asserted is the opt-out itself: the controls are
      // marked print:hidden, and nothing above the reference is.
      const printedAway = (el: Element | null): boolean =>
        el != null && (el.classList.contains("print:hidden") || printedAway(el.parentElement));
      expect(printedAway(wrapper.get('[data-testid="print-receipt"]').element)).toBe(true);
      expect(printedAway(wrapper.get('[data-testid="confirmation-reference"]').element)).toBe(false);
    });
  });
});
