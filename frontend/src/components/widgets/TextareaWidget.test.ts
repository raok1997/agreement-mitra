import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import TextareaWidget from "./TextareaWidget.vue";
import type { FormField } from "../../api/templateForm";

// The textarea grows with its content so a one-item-per-line inventory stays readable, within a
// floor (a short address still gets room) and a ceiling (a long list scrolls instead of pushing the
// dialog's Save button off screen).

const FIELD: FormField = {
  key: "fixturesInventory",
  label: "Fixtures and inventory schedule",
  widget: "textarea",
  type: "longtext",
  required: false,
};

function rowsFor(modelValue: string): number {
  const wrapper = mount(TextareaWidget, { props: { field: FIELD, modelValue } });
  return wrapper.get<HTMLTextAreaElement>("textarea").element.rows;
}

describe("TextareaWidget", () => {
  it("never drops below three rows", () => {
    expect(rowsFor("")).toBe(3);
    expect(rowsFor("12 MG Road")).toBe(3);
  });

  it("gives every line its own row plus one to type into", () => {
    expect(rowsFor(["a", "b", "c", "d", "e", "f", "g"].join("\n"))).toBe(8);
  });

  it("caps at fourteen rows", () => {
    expect(rowsFor(Array.from({ length: 40 }, (_, i) => `item ${i}`).join("\n"))).toBe(14);
  });

  it("emits the typed value", async () => {
    const wrapper = mount(TextareaWidget, { props: { field: FIELD, modelValue: "" } });
    await wrapper.get("textarea").setValue("Ceiling fans\nKeys");
    expect(wrapper.emitted<[string]>("update:modelValue")?.at(-1)?.[0]).toBe(
      "Ceiling fans\nKeys",
    );
  });
});
