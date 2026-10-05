import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { mount, type VueWrapper } from "@vue/test-utils";
import LandingPage from "./LandingPage.vue";
import { CONTACT_EMAIL } from "../content/promises";
import { RELEASE_STATE_LABEL, RELEASE_STATUS } from "../content/releaseStatus";

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

// Page text with every node matching `selectors` removed, text nodes joined with spaces (the spec's
// "page text outside a set of nodes"). Nav, header and footer count as page.
function textOutside(wrapper: VueWrapper, selectors: string): string {
  const root = wrapper.element.cloneNode(true) as Element;
  root.querySelectorAll(selectors).forEach((el) => el.remove());
  return textOf(root);
}

function textOf(node: Node): string {
  const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT);
  const parts: string[] = [];
  while (walker.nextNode()) parts.push(walker.currentNode.textContent ?? "");
  return squash(parts.join(" "));
}

const section = (wrapper: VueWrapper, selector: string): string =>
  textOf(wrapper.get(selector).element);

// The spec's phrase matching: case-insensitive, guarded so a phrase is not matched inside a longer
// word; a `prefix` phrase keeps only the leading guard. "₹"/"%" figures use plain includes instead.
function matches(text: string, phrase: string, prefix = false): boolean {
  const escaped = phrase.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const tail = prefix ? "" : "(?![A-Za-z0-9])";
  return new RegExp(`(?<![A-Za-z0-9])${escaped}${tail}`, "i").test(text);
}

const NO_LOGIN = ["no login", "no account", "without an account", "no OTP"];
const LIVENESS = [
  "live now",
  "is live",
  "are live",
  "in integration",
  "early access",
  "coming soon",
  "starting in",
  "in your city",
];
const FORBIDDEN = [
  "100%",
  "legally guaranteed",
  "guaranteed valid",
  "legally valid agreement",
  "court-approved",
  "government-approved",
  "reviewed by counsel",
  "reviewed by a lawyer",
  "lawyer-reviewed",
];

function board(wrapper: VueWrapper): [string, string][] {
  return wrapper.findAll("#status li").map((li) => {
    const [label, badge] = li.findAll("span");
    return [squash(label.text()), squash(badge.text())];
  });
}

function faqs(wrapper: VueWrapper): { q: string; a: string }[] {
  const qs = wrapper
    .findAll('[data-testid="faq-q"]')
    .map((el) => squash(el.text()));
  const as = wrapper
    .findAll('[data-testid="faq-a"]')
    .map((el) => squash(el.text()));
  return qs.map((q, i) => ({ q, a: as[i] }));
}

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

  it("renders the status board from the release-status module", () => {
    // ToS §2 points readers at "the status board on our home page", so the anchor is load-bearing.
    const wrapper = mount(LandingPage);
    expect(wrapper.find('nav a[href="#status"]').exists()).toBe(true);
    expect(wrapper.find("section#status").exists()).toBe(true);

    expect(board(wrapper)).toEqual(
      RELEASE_STATUS.map((row) => [row.label, RELEASE_STATE_LABEL[row.state]]),
    );
    const stamping = RELEASE_STATUS.map((row) => row.stampingState);
    expect(stamping).toContain("TG");
    expect(stamping).toContain("KA");
  });

  it("uses the one support address everywhere", () => {
    const wrapper = mount(LandingPage);
    const mailtos = wrapper
      .findAll('a[href^="mailto:"]')
      .map((el) => el.attributes("href"));
    expect(mailtos.length).toBeGreaterThan(0);
    expect(new Set(mailtos)).toEqual(new Set([`mailto:${CONTACT_EMAIL}`]));

    const wrong = faqs(wrapper).find(
      (f) => f.q === "What happens if something goes wrong?",
    );
    expect(wrong?.a).toContain(CONTACT_EMAIL);
  });

  it("holds exactly seven sections in the decided order", () => {
    const wrapper = mount(LandingPage);
    const main = wrapper.get("main").element;
    const sections = Array.from(main.children).filter(
      (el) => el.tagName === "SECTION",
    );
    expect(sections).toHaveLength(7);
    const selectors = [
      '[data-testid="hero"]',
      "#how",
      "#price",
      "#guarantees",
      "#status",
      "#faq",
      '[data-testid="closing-cta"]',
    ];
    selectors.forEach((sel, i) =>
      expect(sections[i].matches(sel), sel).toBe(true),
    );
  });

  describe("each recurring message has one owner", () => {
    it("states no-login in the hero only", () => {
      const wrapper = mount(LandingPage);
      const outside = textOutside(wrapper, '#faq, [data-testid="hero"]');
      const hero = section(wrapper, '[data-testid="hero"]');
      for (const phrase of NO_LOGIN)
        expect(matches(outside, phrase), phrase).toBe(false);
      expect(NO_LOGIN.some((phrase) => matches(hero, phrase))).toBe(true);
    });

    it("states free-to-draft in the price card only", () => {
      const wrapper = mount(LandingPage);
      expect(matches(textOutside(wrapper, "#faq, #price"), "free")).toBe(false);
      expect(matches(section(wrapper, "#price"), "free")).toBe(true);
    });

    it("states stamp duty in the price in the price card only", () => {
      const wrapper = mount(LandingPage);
      expect(
        matches(textOutside(wrapper, "#faq, #price"), "includ", true),
      ).toBe(false);
      const price = section(wrapper, "#price");
      expect(matches(price, "stamp duty")).toBe(true);
      expect(matches(price, "includ", true)).toBe(true);
    });

    it("never says all-in", () => {
      expect(matches(textOf(mount(LandingPage).element), "all-in")).toBe(false);
    });
  });

  it("makes no liveness claim outside the status board", () => {
    const outside = textOutside(mount(LandingPage), "#status");
    for (const phrase of LIVENESS)
      expect(matches(outside, phrase), phrase).toBe(false);
  });

  it("shows the price, the overflow rule and its scope", () => {
    const wrapper = mount(LandingPage);
    const price = section(wrapper, "#price");
    expect(price).toContain("₹499");
    expect(price).toContain("₹100");
    expect(price).toContain("exact total before you pay");
    expect(matches(price, "free")).toBe(true);
    expect(wrapper.find('#price a[href="#status"]').exists()).toBe(true);
  });

  it("shows three guarantees, each linked to the terms and carrying its qualifiers", () => {
    const wrapper = mount(LandingPage);
    const cards = wrapper.findAll('#guarantees [data-testid="guarantee"]');
    expect(cards).toHaveLength(3);
    for (const card of cards) {
      expect(card.find('a[href="/terms"]').exists()).toBe(true);
    }
    const [certificate, signing, delay] = cards.map((c) => squash(c.text()));

    expect(certificate).toContain("₹400");
    expect(certificate).toContain("Only when the mistake is ours");
    expect(certificate).toContain("Reduced by any discount");

    expect(signing).toContain("at no charge");
    expect(signing).toContain("₹100 before restarting");

    for (const qualifier of [
      "within one working day of payment",
      "more than two working days late",
      "₹100 for each further working day, up to ₹400",
      "counts from the next working day",
      "details from you",
      "a signer we can't reach",
      "outage",
      "public holiday",
      "Reduced by any discount",
    ]) {
      expect(delay, qualifier).toContain(qualifier);
    }

    const page = textOf(wrapper.element);
    expect(page).not.toContain("Why bother");
    expect(page).not.toContain("in your language");
  });

  describe("FAQ", () => {
    it("ties the 11-month answer to no single state's threshold", () => {
      const all = faqs(mount(LandingPage));
      const elevenMonths = all.find(
        (f) => f.q === "Why are most rental agreements in India for 11 months?",
      );
      for (const literal of [
        "term longer than a year",
        "may require registration for shorter leases",
        "Before you pay, we show whether your agreement may need registering",
      ]) {
        expect(elevenMonths?.a).toContain(literal);
      }
      const faqText = all
        .map((f) => `${f.q} ${f.a}`)
        .join(" ")
        .toLowerCase();
      for (const forbidden of [
        "does not need to be registered",
        "simply does not need",
        "twelve-month rule",
        "below that threshold",
      ]) {
        expect(faqText).not.toContain(forbidden);
      }
    });

    it("asks what it costs and what happens if something goes wrong", () => {
      const questions = faqs(mount(LandingPage)).map((f) => f.q);
      expect(questions).toContain("What does it cost?");
      expect(questions).toContain("What happens if something goes wrong?");
      expect(questions).not.toContain("What does stamp duty cost?");
    });

    it("does not answer the e-signature question with an unconditional yes", () => {
      const esign = faqs(mount(LandingPage)).find(
        (f) => f.q === "Is an Aadhaar OTP signature legally valid?",
      );
      expect(esign?.a).toBeDefined();
      expect(esign!.a.startsWith("Yes")).toBe(false);
    });
  });

  it("makes no unearned legal claim and renders no raw HTML", () => {
    const page = textOf(mount(LandingPage).element).toLowerCase();
    for (const phrase of FORBIDDEN) expect(page, phrase).not.toContain(phrase);
    const source = readFileSync(
      resolve(process.cwd(), "src/views/LandingPage.vue"),
      "utf8",
    );
    expect(source).not.toContain("v-html");
  });
});
