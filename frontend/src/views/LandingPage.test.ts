import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import LandingPage from "./LandingPage.vue";

// The FAQ block is duplicated on purpose: the visible copy lives in LandingPage.vue, and a
// static copy lives in index.html as schema.org FAQPage markup so crawlers can read it
// without executing the SPA. Google devalues (and can penalise) FAQ markup that does not
// match the rendered page, so these tests are the thing keeping the two honest.
function faqPageMarkup(): { name: string; text: string }[] {
  // Vitest's root is frontend/, and import.meta.url is not a file: URL under jsdom.
  const html = readFileSync(resolve(process.cwd(), "index.html"), "utf8");
  const block =
    /<script type="application\/ld\+json">([\s\S]*?)<\/script>/.exec(html);
  if (!block) throw new Error("index.html has no application/ld+json block");

  const graph = JSON.parse(block[1])["@graph"] as Record<string, unknown>[];
  const faqPage = graph.find((node) => node["@type"] === "FAQPage");
  if (!faqPage) throw new Error("index.html JSON-LD has no FAQPage node");

  return (
    faqPage.mainEntity as { name: string; acceptedAnswer: { text: string } }[]
  ).map((q) => ({ name: q.name, text: q.acceptedAnswer.text }));
}

const squash = (s: string): string => s.replace(/\s+/g, " ").trim();

describe("LandingPage", () => {
  it("renders the hero and routes every CTA into the app", async () => {
    const wrapper = mount(LandingPage);

    expect(wrapper.text()).toContain(
      "Rental agreements, with nothing hidden until checkout.",
    );

    // All three entry points (nav, hero, closing) must reach the builder, not just the hero.
    for (const id of ["nav-start", "hero-start", "cta-start"]) {
      await wrapper.find(`[data-testid="${id}"]`).trigger("click");
    }
    expect(wrapper.emitted("start")).toHaveLength(3);
  });

  it("keeps the visible FAQ identical to the FAQPage JSON-LD in index.html", () => {
    const wrapper = mount(LandingPage);
    const questions = wrapper
      .findAll('[data-testid="faq-q"]')
      .map((el) => squash(el.text()));
    const answers = wrapper
      .findAll('[data-testid="faq-a"]')
      .map((el) => squash(el.text()));

    const markup = faqPageMarkup();
    expect(questions).toEqual(markup.map((q) => squash(q.name)));
    expect(answers).toEqual(markup.map((q) => squash(q.text)));
  });

  it("does not advertise the stubbed rails as live", () => {
    // The eSign provider and the e-stamp exchange are still stubbed (docs/ROADMAP.md).
    // The status board is the page's one factual claim about what a visitor can do today,
    // so it must show those two as in-integration, never as live.
    const wrapper = mount(LandingPage);
    const board = new Map(
      wrapper.findAll("#status li").map((li) => {
        const [label, badge] = li.findAll("span");
        return [squash(label.text()), squash(badge.text())];
      }),
    );

    expect(board.get("Aadhaar OTP eSign")).toBe("In integration");
    expect(board.get("E-stamp duty on the real exchange")).toBe(
      "In integration",
    );

    // ...and the builder, which genuinely works, is what is shown as live.
    expect(board.get("Guided agreement builder")).toBe("Live now");
  });

  it("exposes a working contact address for the domain", () => {
    const wrapper = mount(LandingPage);
    const mailtos = wrapper
      .findAll('a[href^="mailto:"]')
      .map((el) => el.attributes("href"));
    expect(mailtos.length).toBeGreaterThan(0);
    expect(new Set(mailtos)).toEqual(
      new Set(["mailto:support@agreementmitra.com"]),
    );
  });
});
