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
import wordmark from "../assets/logo-wordmark.svg";
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

const steps = [
  {
    n: "1",
    title: "Answer a short form",
    body: "Parties, property, rent, deposit and dates.",
  },
  {
    n: "2",
    title: "Watch the agreement build itself",
    body: "The actual document updates as you type, so you know exactly what you are getting.",
  },
  {
    n: "3",
    title: "Pay, and we stamp it",
    body: "We buy the stamp certificate and attach it to your agreement.",
  },
  {
    n: "4",
    title: "Sign with Aadhaar OTP",
    body: "Both parties sign from their phones. The signed PDF and its audit trail come to your inbox.",
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
    a: "You can draft agreements for Telangana and Karnataka, residential or commercial. Where we can also stamp and eSign is on the status board on our home page. If your state is not listed, write to us.",
  },
  {
    q: "What happens if something goes wrong?",
    a: `The guarantees on our home page cover the main things that can go wrong on our side: a wrong stamp certificate, a failed signature and a late delivery. For anything else, write to ${CONTACT_EMAIL}. We answer ${SUPPORT_HOURS}.`,
  },
];

function start(): void {
  emit("start");
}
</script>

<template>
  <div class="min-h-screen bg-white text-ink-800">
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
      <section
        class="relative overflow-hidden border-b border-ink-200 bg-ink-50"
        data-testid="hero"
      >
        <div
          class="pointer-events-none absolute -right-24 -top-24 h-80 w-80 rounded-full bg-accent-200/40 blur-3xl"
          aria-hidden="true"
        />
        <div class="relative mx-auto max-w-6xl px-4 py-14 md:py-20">
          <div class="max-w-2xl">
            <h1
              class="text-4xl font-bold leading-tight tracking-tight text-ink-900 md:text-5xl"
            >
              Rental agreements, with nothing hidden until checkout.
            </h1>
            <p class="mt-5 text-lg leading-relaxed text-ink-600">
              Build a proper Indian rental agreement in a few minutes and read
              the real document as it is written. No login to begin.
            </p>
            <div class="mt-8 flex flex-col gap-3 sm:flex-row sm:items-center">
              <button
                type="button"
                class="rounded-lg bg-accent-500 px-6 py-3 text-base font-semibold text-white shadow-sm transition hover:bg-accent-600"
                data-testid="hero-start"
                @click="start"
              >
                Build my agreement
              </button>
              <a
                class="rounded-lg border border-ink-300 bg-white px-6 py-3 text-center text-base font-semibold text-ink-700 transition hover:bg-ink-50"
                href="#how"
              >
                See how it works
              </a>
            </div>
          </div>
        </div>
      </section>

      <!-- How it works -->
      <section
        id="how"
        class="mx-auto max-w-6xl scroll-mt-20 px-4 py-12 md:py-16"
      >
        <h2 class="text-2xl font-bold tracking-tight text-ink-900 md:text-3xl">
          How it works
        </h2>
        <p class="mt-3 max-w-2xl text-ink-600">
          Four steps, and you can read the document at every one of them.
        </p>
        <ol class="mt-8 grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          <li
            v-for="step in steps"
            :key="step.n"
            class="rounded-xl border border-ink-200 bg-white p-6 shadow-sm"
          >
            <span
              class="flex h-9 w-9 items-center justify-center rounded-lg bg-brand-50 text-sm font-bold text-brand-700"
              aria-hidden="true"
            >
              {{ step.n }}
            </span>
            <h3 class="mt-4 font-semibold text-ink-900">{{ step.title }}</h3>
            <p class="mt-2 text-sm leading-relaxed text-ink-600">
              {{ step.body }}
            </p>
          </li>
        </ol>
      </section>

      <!-- Price -->
      <section
        id="price"
        class="scroll-mt-20 border-y border-ink-200 bg-ink-50"
      >
        <div class="mx-auto max-w-6xl px-4 py-12 md:py-16">
          <h2
            class="text-2xl font-bold tracking-tight text-ink-900 md:text-3xl"
          >
            What it costs
          </h2>
          <div
            class="mt-6 grid gap-6 rounded-xl border border-ink-200 bg-white p-6 shadow-sm md:grid-cols-2 md:gap-10"
          >
            <div>
              <p class="text-2xl font-bold text-ink-900">
                {{ total }} when your stamp duty is {{ includedDuty }} or less
              </p>
              <p class="mt-4 text-sm font-semibold text-ink-800">Included:</p>
              <ul class="mt-2 list-disc space-y-1 pl-5 text-sm text-ink-600">
                <li v-for="item in inclusions" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div class="space-y-3 text-sm leading-relaxed text-ink-600">
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
        </div>
      </section>

      <!-- Guarantees -->
      <section
        id="guarantees"
        class="mx-auto max-w-6xl scroll-mt-20 px-4 py-12 md:py-16"
      >
        <h2 class="text-2xl font-bold tracking-tight text-ink-900 md:text-3xl">
          If something goes wrong
        </h2>
        <p class="mt-3 max-w-2xl text-ink-600">
          These come from our terms of service, which are still a draft.
        </p>
        <div class="mt-8 grid gap-4 md:grid-cols-3">
          <article
            v-for="g in guarantees"
            :key="g.section"
            class="flex flex-col rounded-xl border border-ink-200 bg-white p-6 shadow-sm"
            data-testid="guarantee"
          >
            <h3 class="text-lg font-semibold text-ink-900">{{ g.title }}</h3>
            <p class="mt-3 text-sm leading-relaxed text-ink-600">
              {{ g.body }}
            </p>
            <p class="mt-3 text-xs leading-relaxed text-ink-500">
              {{ g.qualifier }}
            </p>
            <a
              class="mt-4 text-sm font-medium text-brand-700 underline"
              href="/terms"
            >
              Terms, section {{ g.section }}
            </a>
          </article>
        </div>
      </section>

      <!-- Status board: the only statement of what is live (src/content/releaseStatus.ts) -->
      <section
        id="status"
        class="scroll-mt-20 border-y border-ink-200 bg-ink-50"
      >
        <div class="mx-auto max-w-6xl px-4 py-12 md:py-16">
          <h2
            class="text-2xl font-bold tracking-tight text-ink-900 md:text-3xl"
          >
            What is live today
          </h2>
          <p class="mt-2 text-sm text-ink-600">
            We update this board the day anything changes.
          </p>
          <ul class="mt-6 grid gap-2 md:grid-cols-2">
            <li
              v-for="row in RELEASE_STATUS"
              :key="row.label"
              class="flex items-center justify-between gap-3 rounded-lg border border-ink-200 bg-white px-4 py-2.5"
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
      </section>

      <!-- FAQ -->
      <section id="faq" class="scroll-mt-20">
        <div class="mx-auto max-w-6xl px-4 py-12 md:py-16">
          <h2
            class="text-2xl font-bold tracking-tight text-ink-900 md:text-3xl"
          >
            Questions people actually ask
          </h2>
          <div class="mt-6 grid items-start gap-3 lg:grid-cols-2">
            <details
              v-for="faq in faqs"
              :key="faq.q"
              class="group rounded-xl border border-ink-200 bg-white px-5 py-4 [&[open]]:shadow-sm"
            >
              <summary
                class="cursor-pointer list-none font-semibold text-ink-900 marker:content-none"
                data-testid="faq-q"
              >
                {{ faq.q }}
              </summary>
              <p
                class="mt-3 text-sm leading-relaxed text-ink-600"
                data-testid="faq-a"
              >
                {{ faq.a }}
              </p>
            </details>
          </div>
          <p class="mt-6 text-xs leading-relaxed text-ink-500">
            General information, not legal advice.
          </p>
        </div>
      </section>

      <!-- Closing CTA -->
      <section
        class="border-t border-ink-200 bg-brand-800"
        data-testid="closing-cta"
      >
        <div
          class="mx-auto flex max-w-6xl flex-col gap-5 px-4 py-10 md:flex-row md:items-center md:justify-between"
        >
          <div>
            <h2 class="text-2xl font-bold tracking-tight text-white">
              Draft one and see for yourself.
            </h2>
            <p class="mt-1 text-brand-100">
              It takes a few minutes, and you read the real document before you
              decide anything.
            </p>
          </div>
          <button
            type="button"
            class="shrink-0 rounded-lg bg-accent-500 px-7 py-3 text-base font-semibold text-white shadow-sm transition hover:bg-accent-600"
            data-testid="cta-start"
            @click="start"
          >
            Build my agreement
          </button>
        </div>
      </section>
    </main>

    <footer class="border-t border-ink-200 bg-white">
      <div
        class="mx-auto flex max-w-6xl flex-col gap-4 px-4 py-6 text-sm text-ink-500 md:flex-row md:items-center md:justify-between"
      >
        <p>&copy; 2026 AgreementMitra. Online rental agreements for India.</p>
        <div class="flex items-center gap-4">
          <a
            class="font-medium text-ink-600 hover:text-brand-700"
            href="/terms"
          >
            Terms of service
          </a>
          <a
            class="font-medium text-ink-600 hover:text-brand-700"
            :href="`mailto:${CONTACT_EMAIL}`"
          >
            {{ CONTACT_EMAIL }}
          </a>
        </div>
      </div>
    </footer>
  </div>
</template>
