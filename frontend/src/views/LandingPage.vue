<script setup lang="ts">
// Public marketing page served at "/" (see App.vue's path switch). Deliberately
// self-contained: no API calls, no auth, no network. It is the first thing cold
// search traffic sees (the Hyderabad SEO play in docs/GO-TO-MARKET-HYDERABAD.md),
// so it must render instantly and never depend on the backend being up.
//
// Copy discipline: the signing vertical still runs on a STUBBED eSign provider
// (docs/ROADMAP.md "Where we are"), so nothing here promises live Aadhaar eSign
// as a bookable service. The draft builder is real and free, and that is what the
// primary CTA sells. The status section states the rest plainly.
import wordmark from "../assets/logo-wordmark.svg";

const emit = defineEmits<{ (e: "start"): void }>();

const CONTACT_EMAIL = "support@agreementmitra.com";

const steps = [
  {
    n: "1",
    title: "Answer a short form",
    body: "Parties, property, rent, deposit, dates. No account, no OTP, no app download to get started.",
  },
  {
    n: "2",
    title: "Watch the agreement build itself",
    body: "A live preview of the actual document updates as you type, so you never pay to find out what you are getting.",
  },
  {
    n: "3",
    title: "Add e-stamp duty",
    body: "The stamp value for your state and rent is computed and shown in the total before you pay. No surprise line items.",
  },
  {
    n: "4",
    title: "Sign with Aadhaar OTP",
    body: "Both parties sign remotely from their phones. The signed PDF and a tamper-evident audit trail land in your inbox.",
  },
];

const pillars = [
  {
    title: "Priced in the open",
    body: "Stamp duty is a pass-through cost that most portals bury until checkout. We compute it and show the all-in total up front, itemised. If the number changes, you see why.",
  },
  {
    title: "No login to start",
    body: "You should not have to hand over a phone number to find out what a rental agreement costs. Draft anonymously; sign in only if you want to save and come back to it.",
  },
  {
    title: "Correct for your state, in your language",
    body: "Rental law is state law. We are building a rules engine that generates jurisdiction-correct clauses instead of one national fill-in-the-blank PDF, with Indic-script rendering built in from day one.",
  },
];

// Honest status board. Keep this in sync with docs/ROADMAP.md -- it is the one
// place on the site that makes a claim about what a visitor can actually do today.
const status = [
  { label: "Guided agreement builder", state: "live" as const },
  { label: "Live document preview", state: "live" as const },
  { label: "Download a draft PDF", state: "live" as const },
  { label: "Save and resume with Google", state: "live" as const },
  { label: "E-stamp duty on the real exchange", state: "soon" as const },
  { label: "Aadhaar OTP eSign", state: "soon" as const },
  { label: "Telugu and Hindi agreements", state: "planned" as const },
];

const statusCopy: Record<string, string> = {
  live: "Live now",
  soon: "In integration",
  planned: "Planned",
};

const faqs = [
  {
    q: "Why are most rental agreements in India for 11 months?",
    a: "Under the Registration Act, 1908, a lease of twelve months or more must be registered with the sub-registrar. An eleven-month term stays below that threshold, which is why it became the default for residential tenancies. It is a registration question, not a validity question: an eleven-month agreement is still a binding contract, it simply does not need to be registered.",
  },
  {
    q: "Is an Aadhaar OTP signature legally valid?",
    a: "Yes. Aadhaar eSign is an electronic signature recognised under the Information Technology Act, 2000, and is issued through a licensed eSign Service Provider working with a Certifying Authority. The signed PDF carries a digital signature certificate plus an audit trail recording who signed, when, and from where.",
  },
  {
    q: "What does stamp duty cost?",
    a: "It depends on your state and on the rent and deposit in your agreement, because stamp duty is levied by the state government and the slabs differ. We compute it for your specific agreement and show it as its own line in the total before you pay, rather than folding it into a single opaque price.",
  },
  {
    q: "Does a stamped agreement mean it is registered?",
    a: "No, and the difference matters. Stamping pays the state duty on the document. Registration is a separate act of recording the lease with the sub-registrar, and it is what the twelve-month rule is about. A stamped, eSigned eleven-month agreement is not a registered lease.",
  },
  {
    q: "Do I need an account?",
    a: "Not to build and preview an agreement. You only need to sign in if you want to save a draft, leave, and pick it up later, or keep a list of your past agreements.",
  },
  {
    q: "Which cities do you serve?",
    a: "We are starting with Hyderabad and the rest of Telangana, then widening state by state as the rules engine covers each jurisdiction. If you are elsewhere in India, write to us and we will tell you where you sit in the queue.",
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
          <a class="hover:text-brand-700" href="#why">Why us</a>
          <a class="hover:text-brand-700" href="#status">What is live</a>
          <a class="hover:text-brand-700" href="#faq">FAQ</a>
        </div>
        <button
          type="button"
          class="rounded-lg bg-brand-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700"
          data-testid="nav-start"
          @click="start"
        >
          Start free
        </button>
      </nav>
    </header>

    <main>
      <!-- Hero -->
      <section
        class="relative overflow-hidden border-b border-ink-200 bg-ink-50"
      >
        <div
          class="pointer-events-none absolute -right-24 -top-24 h-80 w-80 rounded-full bg-accent-200/40 blur-3xl"
          aria-hidden="true"
        />
        <div class="relative mx-auto max-w-6xl px-4 py-20 md:py-28">
          <div class="max-w-2xl">
            <p
              class="inline-flex items-center gap-2 rounded-full border border-accent-300 bg-accent-50 px-3 py-1 text-xs font-semibold uppercase tracking-wide text-accent-700"
            >
              <span
                class="h-1.5 w-1.5 rounded-full bg-accent-500"
                aria-hidden="true"
              />
              Early access &middot; Hyderabad
            </p>
            <h1
              class="mt-5 text-4xl font-bold leading-tight tracking-tight text-ink-900 md:text-5xl"
            >
              Rental agreements, with nothing hidden until checkout.
            </h1>
            <p class="mt-5 text-lg leading-relaxed text-ink-600">
              Build a proper Indian rental agreement in a few minutes. See the
              document as it is written, see the stamp duty as it is calculated,
              and sign remotely with Aadhaar OTP. Free to draft. No login to
              begin.
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
            <p class="mt-4 text-sm text-ink-500">
              Free to draft and preview. You are not asked to pay to see the
              document.
            </p>
          </div>
        </div>
      </section>

      <!-- Trust strip -->
      <section class="border-b border-ink-200 bg-white">
        <ul
          class="mx-auto grid max-w-6xl grid-cols-2 gap-px bg-ink-200 px-4 py-0 md:grid-cols-4"
        >
          <li
            v-for="item in [
              'Aadhaar OTP eSign',
              'State-wise stamp duty',
              'Tamper-evident audit trail',
              'No account needed to draft',
            ]"
            :key="item"
            class="bg-white px-4 py-6 text-center text-sm font-medium text-ink-600"
          >
            {{ item }}
          </li>
        </ul>
      </section>

      <!-- How it works -->
      <section id="how" class="mx-auto max-w-6xl scroll-mt-20 px-4 py-20">
        <h2 class="text-3xl font-bold tracking-tight text-ink-900">
          How it works
        </h2>
        <p class="mt-3 max-w-2xl text-ink-600">
          Four steps, and you can see the document at every one of them.
        </p>
        <ol class="mt-10 grid gap-6 md:grid-cols-2 lg:grid-cols-4">
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

      <!-- Why us -->
      <section id="why" class="scroll-mt-20 border-y border-ink-200 bg-ink-50">
        <div class="mx-auto max-w-6xl px-4 py-20">
          <h2 class="text-3xl font-bold tracking-tight text-ink-900">
            Why bother building another one of these
          </h2>
          <p class="mt-3 max-w-2xl text-ink-600">
            The form-to-PDF part of this market is solved and cheap. These are
            the three things we think are still broken.
          </p>
          <div class="mt-10 grid gap-6 md:grid-cols-3">
            <article
              v-for="pillar in pillars"
              :key="pillar.title"
              class="rounded-xl border border-ink-200 bg-white p-7 shadow-sm"
            >
              <h3 class="text-lg font-semibold text-ink-900">
                {{ pillar.title }}
              </h3>
              <p class="mt-3 text-sm leading-relaxed text-ink-600">
                {{ pillar.body }}
              </p>
            </article>
          </div>
        </div>
      </section>

      <!-- Honest status board -->
      <section id="status" class="mx-auto max-w-3xl scroll-mt-20 px-4 py-20">
        <h2 class="text-3xl font-bold tracking-tight text-ink-900">
          What is live today
        </h2>
        <p class="mt-3 text-ink-600">
          We are in early access, so here is the unvarnished state of things.
          The builder works right now. The paid rails are still being wired to
          their providers, and we would rather say so here than after you have
          filled in a form.
        </p>
        <ul
          class="mt-8 divide-y divide-ink-200 rounded-xl border border-ink-200 bg-white"
        >
          <li
            v-for="row in status"
            :key="row.label"
            class="flex items-center justify-between gap-4 px-5 py-4"
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
              {{ statusCopy[row.state] }}
            </span>
          </li>
        </ul>
      </section>

      <!-- FAQ -->
      <section id="faq" class="scroll-mt-20 border-t border-ink-200 bg-ink-50">
        <div class="mx-auto max-w-3xl px-4 py-20">
          <h2 class="text-3xl font-bold tracking-tight text-ink-900">
            Questions people actually ask
          </h2>
          <div class="mt-10 space-y-4">
            <details
              v-for="faq in faqs"
              :key="faq.q"
              class="group rounded-xl border border-ink-200 bg-white px-6 py-5 [&[open]]:shadow-sm"
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
          <p class="mt-8 text-xs leading-relaxed text-ink-500">
            The answers above are general information about how rental
            agreements and stamping work in India. They are not legal advice,
            and they are not a substitute for a lawyer on your specific tenancy.
          </p>
        </div>
      </section>

      <!-- Closing CTA -->
      <section class="border-t border-ink-200 bg-brand-800">
        <div class="mx-auto max-w-3xl px-4 py-20 text-center">
          <h2 class="text-3xl font-bold tracking-tight text-white">
            Draft one and see for yourself.
          </h2>
          <p class="mx-auto mt-4 max-w-xl text-brand-100">
            It is free, it takes a few minutes, and you will have a preview of
            the real document before anyone asks you for a rupee.
          </p>
          <button
            type="button"
            class="mt-8 rounded-lg bg-accent-500 px-7 py-3 text-base font-semibold text-white shadow-sm transition hover:bg-accent-600"
            data-testid="cta-start"
            @click="start"
          >
            Build my agreement
          </button>
          <p class="mt-6 text-sm text-brand-200">
            Questions, or want us in your city next?
            <a
              class="font-semibold text-white underline"
              :href="`mailto:${CONTACT_EMAIL}`"
            >
              {{ CONTACT_EMAIL }}
            </a>
          </p>
        </div>
      </section>
    </main>

    <footer class="border-t border-ink-200 bg-white">
      <div
        class="mx-auto flex max-w-6xl flex-col gap-4 px-4 py-10 text-sm text-ink-500 md:flex-row md:items-center md:justify-between"
      >
        <p>
          &copy; 2026 AgreementMitra. Online rental agreements for India,
          starting in Hyderabad.
        </p>
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
