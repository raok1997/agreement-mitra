<script setup lang="ts">
// Public marketing page served at "/" (see App.vue's path switch). Deliberately
// self-contained: no API calls, no auth, no network. It is the first thing cold
// search traffic sees (the Hyderabad SEO play in docs/GO-TO-MARKET-HYDERABAD.md),
// so it must render instantly and never depend on the backend being up.
//
// Copy discipline (openspec landing-page spec):
//   - each recurring message has ONE owning section: no login -> hero; stamp duty in
//     the price and free to draft -> #price;
//   - the #status board (src/content/releaseStatus.ts) is the only statement of what
//     is live -- nothing else here asserts or denies availability;
//   - every price, guarantee figure and contact detail comes from src/content/promises.ts,
//     which is pinned to the terms and the backend fee config by test;
//   - no "legally valid" or "reviewed by counsel" claim, and no raw-HTML binding.
// The FAQ is mirrored as FAQPage JSON-LD in index.html; LandingPage.test.ts holds them equal.
//
// Layout (openspec landing-page-progressive-disclosure): main holds four sections -- hero, the
// #price band, #how, and the "Before you decide" panel, whose tabs are the #guarantees, #status
// and #faq panels. Every panel stays in the DOM (`hidden`, never v-if), and a panel element carries
// no display utility class, because one would beat Tailwind's [hidden] rule and show all three.
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import wordmark from "../assets/logo-wordmark.svg";
import SiteFooter from "../components/SiteFooter.vue";
import { OPERATING_ENTITY } from "../content/operatingEntity";
import { PANEL_IDS, panelForHash, type PanelId } from "./landingPanels";
import {
  CONTACT_EMAIL,
  GUARANTEES,
  PRICE,
  SUPPORT_HOURS,
  formatRupees,
} from "../content/promises";
import { RELEASE_STATE_LABEL, RELEASE_STATUS } from "../content/releaseStatus";

const emit = defineEmits<{ (e: "start"): void }>();

const total = formatRupees(PRICE.totalRupees);
const includedDuty = formatRupees(PRICE.includedDutyRupees);
const refund = formatRupees(GUARANTEES.certificateRefundRupees);
const retryCharge = formatRupees(GUARANTEES.signerRetryChargeRupees);
const perDay = formatRupees(GUARANTEES.delayCreditPerDayRupees);
const cap = formatRupees(GUARANTEES.delayCreditCapRupees);

// Icons are Heroicons-outline-style 24px stroke paths, inlined so four glyphs add no dependency.
const steps = [
  {
    n: "1",
    title: "Answer a short form",
    body: "Parties, property, rent, deposit and dates.",
    icon: "m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L10.582 16.07a4.5 4.5 0 0 1-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 0 1 1.13-1.897l8.932-8.931Zm0 0L19.5 7.125M18 14v4.75A2.25 2.25 0 0 1 15.75 21H5.25A2.25 2.25 0 0 1 3 18.75V8.25A2.25 2.25 0 0 1 5.25 6H10",
  },
  {
    n: "2",
    title: "Watch the agreement build itself",
    body: "The actual document updates as you type, so you know exactly what you are getting.",
    icon: "M2.036 12.322a1.012 1.012 0 0 1 0-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178ZM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
  },
  {
    n: "3",
    title: "Pay, and we stamp it",
    body: "We buy the stamp certificate and attach it to your agreement.",
    icon: "M9 12.75 11.25 15 15 9.75M21 12c0 1.268-.63 2.39-1.593 3.068a3.745 3.745 0 0 1-1.043 3.296 3.745 3.745 0 0 1-3.296 1.043A3.745 3.745 0 0 1 12 21c-1.268 0-2.39-.63-3.068-1.593a3.746 3.746 0 0 1-3.296-1.043 3.745 3.745 0 0 1-1.043-3.296A3.745 3.745 0 0 1 3 12c0-1.268.63-2.39 1.593-3.068a3.745 3.745 0 0 1 1.043-3.296 3.746 3.746 0 0 1 3.296-1.043A3.746 3.746 0 0 1 12 3c1.268 0 2.39.63 3.068 1.593a3.746 3.746 0 0 1 3.296 1.043 3.746 3.746 0 0 1 1.043 3.296A3.745 3.745 0 0 1 21 12Z",
  },
  {
    n: "4",
    title: "Sign with Aadhaar OTP",
    body: "Both parties sign from their phones. The signed PDF and its audit trail come to your inbox.",
    icon: "M10.5 1.5H8.25A2.25 2.25 0 0 0 6 3.75v16.5a2.25 2.25 0 0 0 2.25 2.25h7.5A2.25 2.25 0 0 0 18 20.25V3.75a2.25 2.25 0 0 0-2.25-2.25H13.5m-3 0V3h3V1.5m-3 0h3m-3 18.75h3M9.75 11.25l1.5 1.5 3-3",
  },
];

const inclusions = [
  `stamp duty up to ${includedDuty}`,
  "buying and attaching the stamp certificate",
  "Aadhaar eSign",
];

const guarantees = [
  {
    title: `We fix a wrong stamp certificate, and refund ${refund}.`,
    body: `If a certificate we buy for you is rejected or wrongly denominated because we got it wrong, we put it right at our cost and refund you ${refund}.`,
    qualifier:
      "Only when the mistake is ours. Reduced by any discount you received.",
    section: 8,
  },
  {
    title: "Signing failed? We re-send it at no charge.",
    body: "If a signing request fails or expires, we send a fresh one.",
    qualifier: `If it keeps failing at the signer's end, we may ask ${retryCharge} before restarting. Where we can't tell whose failure it was, we treat it as ours.`,
    section: 11,
  },
  {
    title: "Late through our fault? We pay you back.",
    body: `We aim to stamp your agreement within ${GUARANTEES.stampTargetWorkingDaysText} working day of payment; an order paid outside business hours counts from the next working day. If we are more than ${GUARANTEES.delayGraceWorkingDaysText} working days late through something that was ours, we refund ${perDay} for each further working day, up to ${cap}.`,
    qualifier:
      "Not while we wait on details from you or a signer we can't reach, or during a stamp-portal or eSign outage or a public holiday. Reduced by any discount you received.",
    section: 14,
  },
];

const faqs = [
  {
    q: "Why are most rental agreements in India for 11 months?",
    a: "Because the Registration Act, 1908 requires a lease for a term longer than a year to be registered with the sub-registrar, and an 11-month term stays under that national line. States can add their own rules, and some may require registration for shorter leases. Before you pay, we show whether your agreement may need registering for its state and term. Registration is a separate step from stamping and is not part of our service.",
  },
  {
    q: "Is an Aadhaar OTP signature legally valid?",
    a: "Aadhaar eSign is an electronic signature recognised under the Information Technology Act, 2000, issued through a licensed eSign Service Provider working with a Certifying Authority. The signed PDF carries a digital signature certificate and an audit trail recording who signed, when, and from where.",
  },
  {
    q: "What does it cost?",
    a: `${total} in total when the stamp duty on your agreement is ${includedDuty} or less. Where the duty is higher, you see the stamp amount and the exact total before you pay, and there is never a second bill. Stamp duty is set by your state from the rent, deposit and term, and we cannot change it.`,
  },
  {
    q: "Does a stamped agreement mean it is registered?",
    a: "No. Stamping pays the state's duty on the document. Registration is a separate act of recording the lease with the sub-registrar. A stamped, eSigned agreement is not a registered lease.",
  },
  {
    q: "Do I need an account?",
    a: "Not to build and preview an agreement. You only need to sign in if you want to save a draft, leave, and pick it up later, or keep a list of your past agreements.",
  },
  {
    q: "Which cities do you serve?",
    a: "You can draft residential rental agreements for Telangana and Karnataka. Where we can also stamp and eSign is on the status board on our home page. If your state is not listed, write to us.",
  },
  {
    q: "What happens if something goes wrong?",
    a: `The guarantees on our home page cover the main things that can go wrong on our side: a wrong stamp certificate, a failed signature and a late delivery. For anything else, write to ${CONTACT_EMAIL}. We answer ${SUPPORT_HOURS}.`,
  },
];

// One heading style for every visible section, a clear step below the h1 (design D2 type scale).
const SECTION_HEADING =
  "text-xl font-bold tracking-tight text-ink-900 md:text-2xl";

const TAB_LABELS: Record<PanelId, string> = {
  guarantees: "If something goes wrong",
  status: "What's live",
  faq: "Questions",
};

const active = ref<PanelId>("guarantees");
const decideEl = ref<HTMLElement | null>(null);
const tabEls: Partial<Record<PanelId, HTMLElement>> = {};

function setTabEl(id: PanelId, el: unknown): void {
  if (el instanceof HTMLElement) tabEls[id] = el;
  else delete tabEls[id];
}

function start(): void {
  emit("start");
}

// replaceState, never a push: App.vue routes on popstate, and a tab switch is not a page.
function setHash(id: PanelId): void {
  history.replaceState(history.state, "", `#${id}`);
}

function selectTab(id: PanelId): void {
  active.value = id;
  setHash(id);
}

function onTabKeydown(event: KeyboardEvent): void {
  // Alt+Arrow is browser Back/Forward on Windows and Linux; a modified key is not ours.
  if (event.altKey || event.ctrlKey || event.metaKey) return;
  const last = PANEL_IDS.length - 1;
  const current = PANEL_IDS.indexOf(active.value);
  let next: number;
  switch (event.key) {
    case "ArrowRight":
      next = current === last ? 0 : current + 1;
      break;
    case "ArrowLeft":
      next = current === 0 ? last : current - 1;
      break;
    case "Home":
      next = 0;
      break;
    case "End":
      next = last;
      break;
    default:
      return;
  }
  event.preventDefault();
  const id = PANEL_IDS[next];
  selectTab(id);
  tabEls[id]?.focus();
}

async function openPanel(id: PanelId): Promise<void> {
  active.value = id;
  await nextTick();
  const reduceMotion = window.matchMedia?.(
    "(prefers-reduced-motion: reduce)",
  ).matches;
  // Optional-called: jsdom has no scrollIntoView. It returns void, so there is nothing to catch.
  decideEl.value?.scrollIntoView?.({
    block: "start",
    behavior: reduceMotion ? "auto" : "smooth",
  });
}

// The browser would scroll to a still-hidden panel before Vue un-hides it, so a plain primary click
// on a panel link is taken over. Anything a visitor means for a new tab is left alone.
function onPageClick(event: MouseEvent): void {
  if (
    event.defaultPrevented ||
    event.button !== 0 ||
    event.metaKey ||
    event.ctrlKey ||
    event.shiftKey ||
    event.altKey
  )
    return;
  const anchor =
    event.target instanceof Element ? event.target.closest("a") : null;
  if (!anchor || anchor.hasAttribute("download")) return;
  const target = anchor.getAttribute("target");
  if (target && target !== "_self") return;
  const id = panelForHash(anchor.getAttribute("href") ?? "");
  if (!id) return;
  event.preventDefault();
  setHash(id);
  void openPanel(id);
}

function onHashChange(): void {
  const id = panelForHash(location.hash);
  if (id) void openPanel(id);
}

onMounted(() => {
  onHashChange();
  window.addEventListener("hashchange", onHashChange);
});

onBeforeUnmount(() => {
  window.removeEventListener("hashchange", onHashChange);
});
</script>

<template>
  <div class="min-h-screen bg-white text-ink-800" @click="onPageClick">
    <!-- Nav -->
    <header
      class="sticky top-0 z-20 border-b border-ink-200 bg-white/90 backdrop-blur"
    >
      <nav
        class="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3"
        aria-label="Primary"
      >
        <a href="/" class="flex items-center">
          <img
            :src="wordmark"
            alt="AgreementMitra"
            class="h-8"
            width="360"
            height="72"
          />
        </a>
        <div class="hidden items-center gap-7 text-sm text-ink-600 md:flex">
          <a class="hover:text-brand-700" href="#how">How it works</a>
          <a class="hover:text-brand-700" href="#price">Price</a>
          <a class="hover:text-brand-700" href="#guarantees">Guarantees</a>
          <a class="hover:text-brand-700" href="#status">Status</a>
          <a class="hover:text-brand-700" href="#faq">FAQ</a>
        </div>
        <button
          type="button"
          class="rounded-lg bg-brand-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700"
          data-testid="nav-start"
          @click="start"
        >
          Build my agreement
        </button>
      </nav>
    </header>

    <main>
      <!-- Hero -->
      <section class="relative overflow-hidden bg-white" data-testid="hero">
        <div
          class="pointer-events-none absolute -right-24 -top-24 h-80 w-80 rounded-full bg-accent-200/40 blur-3xl"
          aria-hidden="true"
        />
        <div class="relative mx-auto max-w-6xl px-4 py-10 md:py-12">
          <div class="max-w-3xl">
            <h1
              class="text-3xl font-bold leading-[1.15] tracking-tight text-ink-900 md:text-[2.75rem]"
            >
              Rental agreements, with nothing hidden until checkout.
            </h1>
            <p
              class="mt-4 max-w-2xl text-base leading-relaxed text-ink-600 md:text-lg"
            >
              Build a proper Indian rental agreement in a few minutes and read
              the real document as it is written. No login to begin.
            </p>
            <button
              type="button"
              class="mt-6 rounded-lg bg-accent-500 px-6 py-3 text-base font-semibold text-white shadow-sm transition hover:bg-accent-600"
              data-testid="hero-start"
              @click="start"
            >
              Build my agreement
            </button>
          </div>
        </div>
      </section>

      <!-- Price -->
      <section
        id="price"
        class="scroll-mt-20 border-y border-ink-200 bg-ink-50"
      >
        <h2 class="sr-only">What it costs</h2>
        <div
          class="mx-auto grid max-w-6xl gap-3 px-4 py-5 md:grid-cols-2 md:items-center md:gap-10"
        >
          <div>
            <p class="text-lg font-bold text-ink-900 md:text-xl">
              {{ total }} when your stamp duty is {{ includedDuty }} or less
            </p>
            <div class="mt-1.5 text-sm text-ink-600">
              <span class="mr-1.5 font-semibold text-ink-800">Included:</span>
              <ul class="inline">
                <li
                  v-for="item in inclusions"
                  :key="item"
                  class="inline before:mx-1.5 before:text-ink-400 before:content-['·'] first:before:content-none"
                >
                  {{ item }}
                </li>
              </ul>
            </div>
          </div>
          <div class="space-y-1 text-sm leading-relaxed text-ink-600">
            <p>
              Where the duty is higher, you see the stamp amount and the exact
              total before you pay. There is never a second bill.
            </p>
            <p>Drafting, previewing and downloading a draft are free.</p>
            <p class="text-ink-500">
              Available where we stamp and eSign. See
              <a class="font-medium text-brand-700 underline" href="#status"
                >Status</a
              >.
            </p>
          </div>
        </div>
      </section>

      <!-- How it works -->
      <section
        id="how"
        class="mx-auto max-w-6xl scroll-mt-20 px-4 py-8 md:py-10"
      >
        <h2 :class="SECTION_HEADING">How it works</h2>
        <p class="mt-1 text-sm text-ink-600 md:text-base">
          Four steps, and you can read the document at every one of them.
        </p>
        <ol class="mt-6 grid gap-6 sm:grid-cols-2 md:grid-cols-4">
          <li v-for="step in steps" :key="step.n">
            <!-- Fixed row height, so a title that wraps does not push its body out of line. -->
            <div class="flex items-center gap-3 md:min-h-12">
              <div
                class="relative flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-brand-50 text-brand-700"
              >
                <svg
                  aria-hidden="true"
                  class="h-5 w-5"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  stroke-width="1.5"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    :d="step.icon"
                  />
                </svg>
                <span
                  class="absolute -right-1 -top-1 flex h-4 w-4 items-center justify-center rounded-full bg-brand-600 text-[10px] font-bold text-white"
                  aria-hidden="true"
                >
                  {{ step.n }}
                </span>
              </div>
              <h3
                class="text-sm font-semibold leading-snug text-ink-900 md:text-base"
              >
                {{ step.title }}
              </h3>
            </div>
            <p class="mt-2 text-sm leading-relaxed text-ink-600">
              {{ step.body }}
            </p>
          </li>
        </ol>
      </section>

      <!-- Before you decide: guarantees, the status board and the FAQ as tabs -->
      <section
        id="decide"
        ref="decideEl"
        class="scroll-mt-20 border-t border-ink-200 bg-ink-50"
        data-testid="decide"
      >
        <div class="mx-auto max-w-6xl px-4 py-8 md:py-10">
          <h2 id="decide-heading" :class="SECTION_HEADING">
            Before you decide
          </h2>
          <div
            role="tablist"
            aria-labelledby="decide-heading"
            class="mt-4 flex gap-1 overflow-x-auto overflow-y-hidden shadow-[inset_0_-1px_0_rgb(var(--am-ink-200))]"
            @keydown="onTabKeydown"
          >
            <button
              v-for="id in PANEL_IDS"
              :id="`tab-${id}`"
              :key="id"
              :ref="(el) => setTabEl(id, el)"
              type="button"
              role="tab"
              :aria-controls="id"
              :aria-selected="active === id"
              :tabindex="active === id ? 0 : -1"
              class="whitespace-nowrap border-b-2 px-3 py-2 text-sm font-semibold transition md:px-4"
              :class="
                active === id
                  ? 'border-brand-600 text-brand-700'
                  : 'border-transparent text-ink-600 hover:text-ink-900'
              "
              @click="selectTab(id)"
            >
              {{ TAB_LABELS[id] }}
            </button>
          </div>

          <!-- Panel elements carry no display utility: see the header comment. -->
          <div
            id="guarantees"
            role="tabpanel"
            aria-labelledby="tab-guarantees"
            :hidden="active !== 'guarantees'"
            class="scroll-mt-36 pt-4"
          >
            <p class="max-w-2xl text-sm text-ink-600">
              These come from our terms of service, which are still a draft.
            </p>
            <div class="mt-3 space-y-2">
              <details
                v-for="g in guarantees"
                :key="g.section"
                class="group rounded-xl border border-ink-200 bg-white px-4 py-3 [&[open]]:shadow-sm"
                data-testid="guarantee"
              >
                <summary
                  class="flex cursor-pointer list-none items-center justify-between gap-3 font-semibold text-ink-900 marker:content-none [&::-webkit-details-marker]:hidden"
                >
                  {{ g.title }}
                  <svg
                    aria-hidden="true"
                    class="h-5 w-5 shrink-0 text-ink-400 transition-transform group-open:rotate-180 motion-reduce:transition-none"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                    stroke-width="2"
                  >
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      d="m19.5 8.25-7.5 7.5-7.5-7.5"
                    />
                  </svg>
                </summary>
                <p class="mt-3 text-sm leading-relaxed text-ink-600">
                  {{ g.body }}
                </p>
                <p class="mt-2 text-xs leading-relaxed text-ink-500">
                  {{ g.qualifier }}
                </p>
                <a
                  class="mt-3 inline-block text-sm font-medium text-brand-700 underline"
                  href="/terms"
                >
                  Terms, section {{ g.section }}
                </a>
              </details>
            </div>
          </div>

          <!-- Status board: the only statement of what is live (src/content/releaseStatus.ts) -->
          <div
            id="status"
            role="tabpanel"
            aria-labelledby="tab-status"
            :hidden="active !== 'status'"
            tabindex="0"
            class="scroll-mt-36 pt-4 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
          >
            <p class="text-sm text-ink-600">
              We update this board the day anything changes.
            </p>
            <ul class="mt-3 grid gap-2 md:grid-cols-2">
              <li
                v-for="row in RELEASE_STATUS"
                :key="row.label"
                class="flex items-center justify-between gap-3 rounded-lg border border-ink-200 bg-white px-4 py-2"
              >
                <span class="text-sm font-medium text-ink-800">{{
                  row.label
                }}</span>
                <span
                  class="shrink-0 rounded-full px-2.5 py-1 text-xs font-semibold"
                  :class="{
                    'bg-success-50 text-success-700': row.state === 'live',
                    'bg-accent-50 text-accent-700': row.state === 'soon',
                    'bg-ink-100 text-ink-600': row.state === 'planned',
                  }"
                >
                  {{ RELEASE_STATE_LABEL[row.state] }}
                </span>
              </li>
            </ul>
          </div>

          <div
            id="faq"
            role="tabpanel"
            aria-labelledby="tab-faq"
            :hidden="active !== 'faq'"
            class="scroll-mt-36 pt-4"
          >
            <div class="grid items-start gap-2 lg:grid-cols-2">
              <details
                v-for="faq in faqs"
                :key="faq.q"
                class="group rounded-xl border border-ink-200 bg-white px-4 py-3 [&[open]]:shadow-sm"
              >
                <summary
                  class="flex cursor-pointer list-none items-start justify-between gap-3 font-semibold text-ink-900 marker:content-none [&::-webkit-details-marker]:hidden"
                  data-testid="faq-q"
                >
                  {{ faq.q }}
                  <svg
                    aria-hidden="true"
                    class="mt-0.5 h-5 w-5 shrink-0 text-ink-400 transition-transform group-open:rotate-180 motion-reduce:transition-none"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                    stroke-width="2"
                  >
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      d="m19.5 8.25-7.5 7.5-7.5-7.5"
                    />
                  </svg>
                </summary>
                <p
                  class="mt-3 text-sm leading-relaxed text-ink-600"
                  data-testid="faq-a"
                >
                  {{ faq.a }}
                </p>
              </details>
            </div>
            <p class="mt-4 text-xs leading-relaxed text-ink-500">
              General information, not legal advice.
            </p>
          </div>
        </div>
      </section>
    </main>

    <SiteFooter :entity="OPERATING_ENTITY" />
  </div>
</template>
