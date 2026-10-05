import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  enableAutoUnmount,
  flushPromises,
  mount,
  type VueWrapper,
} from "@vue/test-utils";
import { nextTick } from "vue";
import LandingPage from "./LandingPage.vue";
import { PANEL_IDS } from "./landingPanels";
import { CONTACT_EMAIL } from "../content/promises";
import { RELEASE_STATE_LABEL, RELEASE_STATUS } from "../content/releaseStatus";

// File-global: every mount is unmounted after its test, so no hashchange listener outlives it.
enableAutoUnmount(afterEach);

afterEach(() => {
  history.replaceState({}, "", "/");
  Reflect.deleteProperty(Element.prototype, "scrollIntoView");
  vi.unstubAllGlobals();
});

// jsdom has no scrollIntoView; the stub records which element was scrolled and how.
function mountLanding({ hash = "", attach = false } = {}): {
  wrapper: VueWrapper;
  scrollIntoView: ReturnType<typeof vi.fn>;
} {
  history.replaceState({}, "", `/${hash}`);
  const scrollIntoView = vi.fn();
  Element.prototype.scrollIntoView = scrollIntoView;
  const wrapper = mount(LandingPage, attach ? { attachTo: document.body } : {});
  return { wrapper, scrollIntoView };
}

// trigger() cannot hand back the event, so dispatch by hand and read defaultPrevented after.
function clickLink(el: Element, init: MouseEventInit = {}): MouseEvent {
  const event = new MouseEvent("click", {
    bubbles: true,
    cancelable: true,
    button: 0,
    ...init,
  });
  el.dispatchEvent(event);
  return event;
}

// jsdom navigates on an unprevented anchor click (on a timer, whatever the modifiers), which would
// push an entry and fire hashchange into a later test. This records whether the component prevented
// the click -- it runs after the page's own handler -- and then stops the navigation itself.
function recordPrevented(): { prevented: boolean[]; stop: () => void } {
  const prevented: boolean[] = [];
  const listener = (e: Event): void => {
    prevented.push(e.defaultPrevented);
    e.preventDefault();
  };
  window.addEventListener("click", listener);
  return {
    prevented,
    stop: () => window.removeEventListener("click", listener),
  };
}

function pressKey(
  el: Element,
  key: string,
  init: KeyboardEventInit = {},
): KeyboardEvent {
  const event = new KeyboardEvent("keydown", {
    ...init,
    key,
    bubbles: true,
    cancelable: true,
  });
  el.dispatchEvent(event);
  return event;
}

const selectedTab = (wrapper: VueWrapper): string | undefined =>
  wrapper.find('[role="tab"][aria-selected="true"]').attributes("id");

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

    // Both entry points (the sticky nav and the hero) must reach the builder, not just the hero.
    for (const id of ["nav-start", "hero-start"]) {
      await wrapper.find(`[data-testid="${id}"]`).trigger("click");
    }
    expect(wrapper.emitted("start")).toHaveLength(2);
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
    expect(wrapper.find('#status[role="tabpanel"]').exists()).toBe(true);

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
    expect(wrapper.find('footer a[href^="mailto:"]').exists()).toBe(true);

    const wrong = faqs(wrapper).find(
      (f) => f.q === "What happens if something goes wrong?",
    );
    expect(wrong?.a).toContain(CONTACT_EMAIL);
  });

  it("holds exactly four sections in the decided order", () => {
    const wrapper = mount(LandingPage);
    const main = wrapper.get("main").element;
    const sections = Array.from(main.children).filter(
      (el) => el.tagName === "SECTION",
    );
    expect(sections).toHaveLength(4);
    const selectors = [
      '[data-testid="hero"]',
      "#price",
      "#how",
      '[data-testid="decide"]',
    ];
    selectors.forEach((sel, i) =>
      expect(sections[i].matches(sel), sel).toBe(true),
    );
    expect(wrapper.find('[data-testid="closing-cta"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="cta-start"]').exists()).toBe(false);
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

  it("shows three guarantees, each expandable, linked to the terms and carrying its qualifiers", () => {
    const wrapper = mount(LandingPage);
    const cards = wrapper.findAll(
      '#guarantees details[data-testid="guarantee"]',
    );
    expect(cards).toHaveLength(3);
    for (const card of cards) {
      expect(squash(card.get("summary").text())).not.toBe("");
      expect(card.find('a[href="/terms"]').exists()).toBe(true);
      expect(card.attributes("open"), "collapsed by default").toBeUndefined();
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

    // v1 is residential only; commercial returns with the picker's isOffered rule.
    it("offers residential agreements only in the cities answer", () => {
      const all = faqs(mount(LandingPage));
      const cities = all.find((f) => f.q === "Which cities do you serve?");
      expect(cities?.a).toBeDefined();
      for (const word of ["Telangana", "Karnataka", "residential"])
        expect(cities!.a).toContain(word);
      const faqText = all.map((f) => `${f.q} ${f.a}`).join(" ").toLowerCase();
      expect(faqText).not.toContain("commercial");
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

  describe("first screen", () => {
    it("holds the headline, the sub-line and one control, with no image", () => {
      const hero = mount(LandingPage).get('[data-testid="hero"]');

      const h1s = hero.findAll("h1");
      expect(h1s).toHaveLength(1);
      expect(squash(h1s[0].text())).toBe(
        "Rental agreements, with nothing hidden until checkout.",
      );
      expect(hero.text()).toContain("No login to begin");

      const controls = hero.findAll("button, a");
      expect(controls).toHaveLength(1);
      expect(controls[0].attributes("data-testid")).toBe("hero-start");
      expect(hero.find("img, picture, video").exists()).toBe(false);
    });

    it("has no animation in the source", () => {
      const text = readFileSync(
        resolve(process.cwd(), "src/views/LandingPage.vue"),
        "utf8",
      );
      for (const token of ["animate-", "<video", "@keyframes", "<Transition"])
        expect(text, token).not.toContain(token);
    });

    it("puts the price band and then the steps directly after the hero", () => {
      const main = mount(LandingPage).get("main").element;
      const sections = Array.from(main.children).filter(
        (el) => el.tagName === "SECTION",
      );
      expect(sections[0].matches('[data-testid="hero"]')).toBe(true);
      expect(sections[1].id).toBe("price");
      expect(sections[2].id).toBe("how");
    });

    it("gives every visible section heading one style, a step below the h1", () => {
      const wrapper = mount(LandingPage);
      const headings = wrapper
        .findAll("h2")
        .filter((h) => !h.classes().includes("sr-only"));
      expect(headings.length).toBeGreaterThanOrEqual(2);
      expect(new Set(headings.map((h) => h.attributes("class"))).size).toBe(1);

      const h1Sizes = wrapper
        .get("h1")
        .classes()
        .filter((c) => /(^|:)text-(\[|xs|sm|base|lg|\d?xl)/.test(c));
      expect(h1Sizes.length).toBeGreaterThan(0);
      for (const size of h1Sizes)
        expect(headings[0].classes(), size).not.toContain(size);
    });
  });

  it("shows How it works as four steps with icons and unchanged copy", () => {
    const steps = mount(LandingPage).findAll("#how ol > li");
    expect(steps.map((li) => squash(li.get("h3").text()))).toEqual([
      "Answer a short form",
      "Watch the agreement build itself",
      "Pay, and we stamp it",
      "Sign with Aadhaar OTP",
    ]);
    for (const li of steps)
      expect(li.find('svg[aria-hidden="true"]').exists()).toBe(true);
  });

  describe("Before you decide panel", () => {
    // Tailwind's preflight hides [hidden] with one attribute selector, which a display utility on
    // the same element beats -- so a tab panel must carry none, under any variant.
    const DISPLAY =
      /^(block|flex|grid|contents|flow-root|list-item|hidden|inline(-\S+)?|table(-\S+)?|\[display:.*\])$/;
    const utility = (token: string): string =>
      token.replace(/^(?:[a-z0-9-]+(?:\[[^\]]*\])?:)*/, "").replace(/^!/, "");

    it("strips variants before checking a class for a display utility", () => {
      for (const token of ["md:grid", "!flex", "lg:!hidden", "[display:block]"])
        expect(DISPLAY.test(utility(token)), token).toBe(true);
      for (const token of ["scroll-mt-36", "pt-6", "focus-visible:ring-2"])
        expect(DISPLAY.test(utility(token)), token).toBe(false);
    });

    it("opens the guarantees tab by default and keeps the others rendered but hidden", () => {
      const wrapper = mount(LandingPage);
      expect(
        wrapper
          .findAll('[role="tab"]')
          .map((t) => [squash(t.text()), t.attributes("aria-selected")]),
      ).toEqual([
        ["If something goes wrong", "true"],
        ["What's live", "false"],
        ["Questions", "false"],
      ]);
      expect(wrapper.get("#guarantees").attributes("hidden")).toBeUndefined();
      expect(wrapper.get("#status").attributes("hidden")).toBeDefined();
      expect(wrapper.get("#faq").attributes("hidden")).toBeDefined();
      expect(wrapper.findAll("#status li")).toHaveLength(RELEASE_STATUS.length);
      expect(
        wrapper.findAll('#faq [data-testid="faq-q"]').length,
      ).toBeGreaterThan(0);
    });

    it("puts no display utility on a tab panel element", () => {
      const panels = mount(LandingPage).findAll('[role="tabpanel"]');
      expect(panels).toHaveLength(3);
      for (const panel of panels) {
        const tokens = (panel.attributes("class") ?? "")
          .split(/\s+/)
          .filter(Boolean);
        for (const token of tokens)
          expect(
            DISPLAY.test(utility(token)),
            `${panel.attributes("id")}: ${token}`,
          ).toBe(false);
      }
    });

    it("wires every tab to the panel it controls", () => {
      const wrapper = mount(LandingPage);
      const tablist = wrapper.get('[role="tablist"]');
      expect(tablist.attributes("aria-labelledby")).toBe("decide-heading");
      expect(squash(wrapper.get("#decide-heading").text())).toBe(
        "Before you decide",
      );
      const tabs = tablist.findAll('[role="tab"]');
      expect(tabs.map((t) => t.attributes("aria-controls"))).toEqual([
        ...PANEL_IDS,
      ]);
      for (const tab of tabs) {
        const panel = wrapper.get(`#${tab.attributes("aria-controls")}`);
        expect(panel.attributes("role")).toBe("tabpanel");
        expect(panel.attributes("aria-labelledby")).toBe(tab.attributes("id"));
      }
      expect(wrapper.get("#status").attributes("tabindex")).toBe("0");
      expect(wrapper.get("#guarantees").attributes("tabindex")).toBeUndefined();
      expect(wrapper.get("#faq").attributes("tabindex")).toBeUndefined();
    });

    it.each(PANEL_IDS)("opens the %s tab from the hash on load", async (id) => {
      const { wrapper, scrollIntoView } = mountLanding({ hash: `#${id}` });
      await flushPromises();
      expect(selectedTab(wrapper)).toBe(`tab-${id}`);
      expect(wrapper.get(`#${id}`).attributes("hidden")).toBeUndefined();
      expect(scrollIntoView).toHaveBeenCalledTimes(1);
      expect(scrollIntoView.mock.contexts[0]).toBe(
        wrapper.get('[data-testid="decide"]').element,
      );
    });

    it("scrolls instantly under reduced motion and smoothly otherwise", async () => {
      vi.stubGlobal(
        "matchMedia",
        vi.fn(() => ({ matches: true })),
      );
      const reduced = mountLanding({ hash: "#status" });
      await flushPromises();
      expect(reduced.scrollIntoView.mock.calls[0][0]).toMatchObject({
        behavior: "auto",
      });
      reduced.wrapper.unmount();
      vi.unstubAllGlobals();

      const smooth = mountLanding({ hash: "#status" });
      await flushPromises();
      expect(smooth.scrollIntoView.mock.calls[0][0]).toMatchObject({
        behavior: "smooth",
      });
    });

    it("opens a tab from an in-page link without adding a history entry", async () => {
      const { wrapper, scrollIntoView } = mountLanding({ attach: true });
      const decide = wrapper.get('[data-testid="decide"]').element;
      const recorder = recordPrevented();
      const length = history.length;
      try {
        const toStatus = clickLink(
          wrapper.get('#price a[href="#status"]').element,
        );
        await flushPromises();
        expect(toStatus.defaultPrevented).toBe(true);
        expect(selectedTab(wrapper)).toBe("tab-status");
        expect(location.hash).toBe("#status");
        expect(scrollIntoView.mock.contexts.at(-1)).toBe(decide);

        const toFaq = clickLink(wrapper.get('nav a[href="#faq"]').element);
        await flushPromises();
        expect(toFaq.defaultPrevented).toBe(true);
        expect(selectedTab(wrapper)).toBe("tab-faq");
        expect(location.hash).toBe("#faq");
        expect(scrollIntoView).toHaveBeenCalledTimes(2);
        expect(scrollIntoView.mock.contexts.at(-1)).toBe(decide);

        expect(history.length).toBe(length);
      } finally {
        recorder.stop();
      }
    });

    it("leaves a modified click to the browser", async () => {
      const { wrapper } = mountLanding({ attach: true });
      const recorder = recordPrevented();
      try {
        clickLink(wrapper.get('nav a[href="#faq"]').element, { metaKey: true });
        await flushPromises();
        expect(recorder.prevented).toEqual([false]);
        expect(selectedTab(wrapper)).toBe("tab-guarantees");
      } finally {
        recorder.stop();
      }
    });

    it("moves between tabs from the keyboard", async () => {
      const { wrapper } = mountLanding({ attach: true });
      const length = history.length;
      (wrapper.get("#tab-guarantees").element as HTMLElement).focus();

      const expected: [string, string][] = [
        ["ArrowRight", "status"],
        ["ArrowRight", "faq"],
        ["ArrowRight", "guarantees"],
        ["End", "faq"],
        ["Home", "guarantees"],
        ["ArrowLeft", "faq"],
      ];
      for (const [key, id] of expected) {
        const event = pressKey(document.activeElement as Element, key);
        await nextTick();
        expect(event.defaultPrevented, key).toBe(true);
        expect(selectedTab(wrapper), key).toBe(`tab-${id}`);
        expect(document.activeElement?.id, key).toBe(`tab-${id}`);
        expect(
          wrapper.findAll('[role="tab"]').map((t) => t.attributes("tabindex")),
          key,
        ).toEqual(PANEL_IDS.map((p) => (p === id ? "0" : "-1")));
        expect(location.hash, key).toBe(`#${id}`);
      }
      expect(history.length).toBe(length);
    });

    it("leaves a modified arrow key to the browser", async () => {
      const { wrapper } = mountLanding({ attach: true });
      const tab = wrapper.get("#tab-guarantees").element as HTMLElement;
      tab.focus();
      for (const mod of ["altKey", "ctrlKey", "metaKey"] as const) {
        const event = pressKey(tab, "ArrowLeft", { [mod]: true });
        await nextTick();
        expect(event.defaultPrevented, mod).toBe(false);
        expect(selectedTab(wrapper), mod).toBe("tab-guarantees");
      }
    });
  });
});
