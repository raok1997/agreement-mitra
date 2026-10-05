import { afterEach, describe, expect, it } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ConfirmDialog from "./ConfirmDialog.vue";

// Attached to the document so focus is real; jsdom does not move focus on Tab itself, which is why
// the dialog traps Tab with its own keydown handler and these tests drive that handler.
let wrapper: VueWrapper | undefined;

function open(busy = false) {
  wrapper = mount(ConfirmDialog, {
    props: { title: "Delete this draft?", confirmLabel: "Delete", busy },
    slots: { default: "<p>Body text</p>" },
    attachTo: document.body,
  });
  return wrapper;
}

afterEach(() => wrapper?.unmount());

describe("ConfirmDialog", () => {
  it("is a labelled modal dialog that moves focus to Cancel on open", async () => {
    const w = open();
    await flushPromises();

    const dialog = w.find('[role="dialog"]');
    expect(dialog.attributes("aria-modal")).toBe("true");
    const labelledBy = dialog.attributes("aria-labelledby");
    expect(w.find(`#${labelledBy}`).text()).toBe("Delete this draft?");
    expect(document.activeElement).toBe(
      w.find('[data-testid="confirm-cancel"]').element,
    );
  });

  it("wraps Tab and Shift+Tab inside the dialog", async () => {
    const w = open();
    await flushPromises();
    const cancel = w.find('[data-testid="confirm-cancel"]').element;
    const ok = w.find('[data-testid="confirm-ok"]').element;
    const dialog = w.find('[role="dialog"]');

    await dialog.trigger("keydown", { key: "Tab" });
    expect(document.activeElement).toBe(ok);
    await dialog.trigger("keydown", { key: "Tab" });
    expect(document.activeElement).toBe(cancel);
    await dialog.trigger("keydown", { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(ok);
  });

  it("cancels on Escape and on a backdrop click, but not on a click inside", async () => {
    const w = open();
    await flushPromises();

    await w.find('[role="dialog"]').trigger("keydown", { key: "Escape" });
    await w.find('[data-testid="confirm-backdrop"]').trigger("click");
    await w.find('[role="dialog"]').trigger("click");

    expect(w.emitted("cancel")).toHaveLength(2);
  });

  it("emits confirm, and disables confirm while busy", async () => {
    const w = open();
    await w.find('[data-testid="confirm-ok"]').trigger("click");
    expect(w.emitted("confirm")).toHaveLength(1);

    await w.setProps({ busy: true });
    expect(
      w.find('[data-testid="confirm-ok"]').attributes("disabled"),
    ).toBeDefined();
  });

  it("keeps Escape and the Tab trap working after a click on the dialog's text", async () => {
    const w = open();
    await flushPromises();
    const panel = w.find('[role="dialog"]');

    (panel.element as HTMLElement).focus();
    expect(document.activeElement).toBe(panel.element);
    await panel.trigger("keydown", { key: "Tab" });
    expect(document.activeElement).toBe(
      w.find('[data-testid="confirm-cancel"]').element,
    );
    await panel.trigger("keydown", { key: "Escape" });
    expect(w.emitted("cancel")).toHaveLength(1);
  });
});
