import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import LegalClause from "./LegalClause.vue";

describe("LegalClause", () => {
  it("renders markup in a clause as literal text", () => {
    const wrapper = mount(LegalClause, {
      props: {
        clause: {
          id: "x",
          heading: "1. Test",
          status: "drafted",
          body: ["<b>x</b>"],
        },
      },
    });
    expect(wrapper.text()).toContain("<b>x</b>");
    expect(wrapper.find("b").exists()).toBe(false);
  });

  it("labels a counsel gap and a product gap differently", () => {
    const counsel = mount(LegalClause, {
      props: {
        clause: {
          id: "a",
          heading: "A",
          status: "counsel",
          gap: "g",
          body: [],
        },
      },
    });
    const product = mount(LegalClause, {
      props: {
        clause: {
          id: "b",
          heading: "B",
          status: "product",
          gap: "g",
          body: [],
        },
      },
    });
    expect(counsel.get('[data-testid="terms-gap"]').text()).toContain(
      "Gap - with our lawyers",
    );
    expect(product.get('[data-testid="terms-gap"]').text()).toContain(
      "Gap - not yet decided",
    );
  });

  it.each(["LegalClause", "DraftBanner", "OperatorDetails"])(
    "%s never renders HTML",
    (name) => {
      const source = readFileSync(
        resolve(process.cwd(), `src/components/${name}.vue`),
        "utf8",
      );
      expect(source).not.toContain("v-html");
    },
  );
});
