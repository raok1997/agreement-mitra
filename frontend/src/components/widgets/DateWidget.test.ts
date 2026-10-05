import { afterEach, describe, expect, it, vi } from "vitest";
import { mount, type VueWrapper } from "@vue/test-utils";
import { nextTick } from "vue";
import DateWidget from "./DateWidget.vue";
import type { FormField } from "../../api/templateForm";

// The first widget test in the repo. The date widget replaces a native <input type="date"> whose
// display order followed the host locale, so these pin what it now owns: the dd/mm/yyyy display,
// the mask, the model contract (ISO when valid, raw text otherwise), and the accessibility the
// native control used to provide for free.

const FIELD: FormField = {
  key: "startDate",
  label: "Start date",
  widget: "date",
  type: "date",
  required: true,
};

let wrapper: VueWrapper | null = null;
afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
});

function mountWidget(modelValue = "", error: string | null = null) {
  wrapper = mount(DateWidget, {
    props: { field: FIELD, modelValue, error },
    attachTo: document.body,
  });
  return wrapper;
}

function input(w: VueWrapper): HTMLInputElement {
  return w.get<HTMLInputElement>('[data-testid="field-startDate"]').element;
}

function lastEmit(w: VueWrapper): string | undefined {
  const emits = w.emitted<[string]>("update:modelValue");
  return emits?.[emits.length - 1][0];
}

/** Type one character the way a browser would: beforeinput (cancellable), then the edit + input. */
async function typeChar(w: VueWrapper, ch: string): Promise<void> {
  const el = input(w);
  el.setSelectionRange(el.value.length, el.value.length);
  const ev = new InputEvent("beforeinput", {
    inputType: "insertText",
    data: ch,
    cancelable: true,
    bubbles: true,
  });
  el.dispatchEvent(ev);
  if (!ev.defaultPrevented) {
    el.value += ch;
    el.dispatchEvent(new Event("input", { bubbles: true }));
  }
  await nextTick();
}

async function paste(w: VueWrapper, text: string): Promise<void> {
  const el = input(w);
  el.setSelectionRange(el.value.length, el.value.length);
  await w
    .get('[data-testid="field-startDate"]')
    .trigger("paste", { clipboardData: { getData: () => text } });
}

describe("DateWidget", () => {
  it("emits ISO for a valid entry without a blur, and redisplays canonically on blur", async () => {
    const w = mountWidget();
    const field = w.get('[data-testid="field-startDate"]');
    await field.trigger("focus");
    await field.setValue("8/1/2026");
    expect(lastEmit(w)).toBe("2026-01-08");
    expect(input(w).value).toBe("8/1/2026");
    await field.trigger("blur");
    expect(input(w).value).toBe("08/01/2026");
  });

  it("displays an ISO modelValue day-first on mount, with a visible format hint", () => {
    const w = mountWidget("2026-01-08");
    expect(input(w).value).toBe("08/01/2026");
    expect(w.text()).toContain("dd/mm/yyyy");
  });

  it("emits an impossible full-length date as raw text and shows its error while focused", async () => {
    const w = mountWidget();
    const field = w.get('[data-testid="field-startDate"]');
    await field.trigger("focus");
    await field.setValue("31/02/2026");
    expect(lastEmit(w)).toBe("31/02/2026");
    await w.setProps({ modelValue: "31/02/2026", error: "Start date is not a real date." });
    expect(w.find('[data-testid="field-error-startDate"]').exists()).toBe(true);
  });

  it("hides a partial entry's error while focused and shows it after blur", async () => {
    const w = mountWidget();
    const field = w.get('[data-testid="field-startDate"]');
    await field.trigger("focus");
    await field.setValue("08/0");
    expect(lastEmit(w)).toBe("08/0");
    await w.setProps({ modelValue: "08/0", error: "Start date is incomplete." });
    expect(w.find('[data-testid="field-error-startDate"]').exists()).toBe(false);
    expect(input(w).getAttribute("aria-invalid")).toBe("true");
    await field.trigger("blur");
    expect(w.find('[data-testid="field-error-startDate"]').exists()).toBe(true);
  });

  it("inserts the slashes as digits are typed and rejects non-digits", async () => {
    const w = mountWidget();
    for (const ch of "0801") await typeChar(w, ch);
    expect(input(w).value).toBe("08/01");
    await typeChar(w, "a");
    expect(input(w).value).toBe("08/01");
    for (const ch of "2026") await typeChar(w, ch);
    expect(input(w).value).toBe("08/01/2026");
    expect(lastEmit(w)).toBe("2026-01-08");
    await typeChar(w, "1");
    expect(input(w).value).toBe("08/01/2026");
  });

  it("inserts a paste whole: never stripped to digits, never truncated", async () => {
    const w = mountWidget();
    await paste(w, "03-02-2001");
    expect(input(w).value).toBe("03-02-2001");
    expect(lastEmit(w)).toBe("03-02-2001");

    input(w).value = "";
    await paste(w, "08/01/20261");
    expect(input(w).value).toBe("08/01/20261");
    expect(lastEmit(w)).toBe("08/01/20261");
  });

  it("accepts a pasted year-first ISO date and redisplays it day-first on blur", async () => {
    const w = mountWidget();
    await paste(w, " 2001-02-03 ");
    expect(lastEmit(w)).toBe("2001-02-03");
    await w.get('[data-testid="field-startDate"]').trigger("blur");
    expect(input(w).value).toBe("03/02/2001");
  });

  it("does not reformat the text when its own emit echoes back", async () => {
    const w = mountWidget();
    const field = w.get('[data-testid="field-startDate"]');
    await field.setValue("8/1/2026");
    await w.setProps({ modelValue: "2026-01-08" });
    expect(input(w).value).toBe("8/1/2026");
  });

  it("does not reformat after a picker selection's echo", async () => {
    const w = mountWidget("2026-01-08");
    await w.get('[data-testid="date-picker-toggle-startDate"]').trigger("click");
    await w.get('[data-iso="2026-01-09"]').trigger("click");
    expect(lastEmit(w)).toBe("2026-01-09");
    await w.setProps({ modelValue: "2026-01-09" });
    expect(input(w).value).toBe("09/01/2026");
  });

  it("replaces the text on an outside modelValue change, showing non-ISO as-is", async () => {
    const w = mountWidget("2026-01-08");
    await w.setProps({ modelValue: "2027-03-04" });
    expect(input(w).value).toBe("04/03/2027");
    await w.setProps({ modelValue: "garbage" });
    expect(input(w).value).toBe("garbage");
  });

  it("makes every button a non-submit button", async () => {
    const w = mountWidget("2026-01-08");
    await w.get('[data-testid="date-picker-toggle-startDate"]').trigger("click");
    const buttons = w.findAll("button");
    expect(buttons.length).toBeGreaterThan(28);
    for (const b of buttons) expect(b.attributes("type")).toBe("button");
  });

  it("exposes its label, format hint, error and invalid state to assistive technology", async () => {
    const w = mountWidget("31/02/2026", "Start date is not a real date.");
    const el = input(w);
    expect(el.getAttribute("inputmode")).toBe("numeric");
    expect(el.getAttribute("aria-label")).toBe("Start date");
    expect(el.getAttribute("aria-invalid")).toBe("true");
    const ids = (el.getAttribute("aria-describedby") ?? "").split(" ");
    const described = ids.map((id) => document.getElementById(id)?.textContent?.trim());
    expect(described).toEqual(["dd/mm/yyyy", "Start date is not a real date."]);
    await w.setProps({ error: null });
    expect(el.getAttribute("aria-invalid")).toBe("false");
  });
});

describe("DateWidget calendar picker", () => {
  it("opens from its button on the current value's month with that day focused", async () => {
    const w = mountWidget("2026-01-08");
    const toggle = w.get('[data-testid="date-picker-toggle-startDate"]');
    expect(toggle.attributes("aria-expanded")).toBe("false");
    await toggle.trigger("click");
    await nextTick();
    expect(toggle.attributes("aria-expanded")).toBe("true");
    const grid = w.get('[role="grid"]');
    expect(grid.attributes("aria-label")).toBe("Start date, January 2026");
    expect((document.activeElement as HTMLElement).dataset.iso).toBe("2026-01-08");
  });

  it("is arrow-key navigable and emits ISO on selection, returning focus to the input", async () => {
    const w = mountWidget("2026-01-31");
    await w.get('[data-testid="date-picker-toggle-startDate"]').trigger("click");
    await nextTick();
    const grid = w.get('[role="grid"]');
    await grid.trigger("keydown", { key: "ArrowRight" });
    await nextTick();
    // Crosses into February: the grid follows the active day.
    expect((document.activeElement as HTMLElement).dataset.iso).toBe("2026-02-01");
    await grid.trigger("keydown", { key: "ArrowDown" });
    await nextTick();
    const active = document.activeElement as HTMLElement;
    expect(active.dataset.iso).toBe("2026-02-08");
    active.click();
    await nextTick();
    await nextTick();
    expect(lastEmit(w)).toBe("2026-02-08");
    expect(input(w).value).toBe("08/02/2026");
    expect(w.find('[role="grid"]').exists()).toBe(false);
    expect(document.activeElement).toBe(input(w));
  });

  it("opens on today's month when the field is empty or invalid", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date(2026, 9, 4));
    try {
      const w = mountWidget("31/02/2026");
      await w.get('[data-testid="date-picker-toggle-startDate"]').trigger("click");
      expect(w.get('[role="grid"]').attributes("aria-label")).toBe(
        "Start date, October 2026",
      );
    } finally {
      vi.useRealTimers();
    }
  });

  it("selects today from its Today button and marks today in the grid", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date(2026, 9, 4));
    try {
      const w = mountWidget("2026-01-08");
      await w.get('[data-testid="date-picker-toggle-startDate"]').trigger("click");
      await w.get('[data-testid="date-picker-next"]').trigger("click");
      expect(w.find('[aria-current="date"]').exists()).toBe(false);
      await w.get('[data-testid="date-picker-today"]').trigger("click");
      expect(lastEmit(w)).toBe("2026-10-04");
      expect(input(w).value).toBe("04/10/2026");
      await w.setProps({ modelValue: "2026-10-04" });
      await w.get('[data-testid="date-picker-toggle-startDate"]').trigger("click");
      expect(w.get('[aria-current="date"]').attributes("data-iso")).toBe("2026-10-04");
    } finally {
      vi.useRealTimers();
    }
  });

  it("stays within the accepted year range", async () => {
    const w = mountWidget("1900-01-15");
    await w.get('[data-testid="date-picker-toggle-startDate"]').trigger("click");
    expect(w.get('[data-testid="date-picker-prev"]').attributes("disabled")).toBeDefined();
  });

  it("closes on Escape without letting it reach the document, and refocuses the input", async () => {
    const onDocKey = vi.fn();
    document.addEventListener("keydown", onDocKey);
    try {
      const w = mountWidget("2026-01-08");
      await w.get('[data-testid="date-picker-toggle-startDate"]').trigger("click");
      await nextTick();
      await w.get('[role="grid"]').trigger("keydown", { key: "Escape" });
      await nextTick();
      await nextTick();
      expect(w.find('[role="grid"]').exists()).toBe(false);
      expect(onDocKey).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(input(w));
    } finally {
      document.removeEventListener("keydown", onDocKey);
    }
  });
});
