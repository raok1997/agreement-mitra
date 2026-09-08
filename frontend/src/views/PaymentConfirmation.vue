<script setup lang="ts">
// What the customer sees once the SERVER has confirmed payment.
//
// WHY THIS IS A SCREEN AND NOT A BANNER. This is the moment the customer is most likely to leave -
// they have paid, and as far as they are concerned they are done. Everything they need in order to
// come back has to be in front of them here, or it is nowhere. The old inline notice said "Nothing
// more to do", which was true of the payment and misleading about the agreement.
//
// IT NEVER CLAIMS PAYMENT ON ITS OWN. The parent renders this only for a server-confirmed payment,
// and the amount shown is the amount the SERVER reported. The checkout handler firing in this
// browser proves nothing - the tab can close before it runs, the callback can be lost - so nothing
// here is derived from it.
//
// IT HAS TO SURVIVE THE TAB CLOSING. The reference is the only way back for a customer with no
// account, so this screen can print / save itself as a PDF. The browser's own print dialog is also
// every browser's "save as PDF", so one control covers both asks. What comes out is a receipt and
// not a screenshot of the app: the app chrome and the on-screen buttons carry print:hidden.
//
// WHAT IT PROMISES MUST BE TRUE. It says a link has been emailed only when there was an address to
// email; with no contact on file it says so plainly rather than implying a message that will never
// arrive.

import LegalDisclaimer from "../components/LegalDisclaimer.vue";

const props = defineProps<{
  /** The agreement's tracking reference. */
  reference: string;
  /** Amount as the SERVER confirmed it, already formatted. Null when it could not be read back. */
  amountLabel: string | null;
  /** Whether any party had a contact we could send the link to. */
  linkSent: boolean;
}>();

const emit = defineEmits<{ (e: "continue"): void }>();

// Deliberately window.print() rather than a generated PDF download: the receipt IS this screen, so
// there is no second rendering of the payment facts that could drift out of step with it, and "Save
// as PDF" is a destination inside the same dialog on every desktop and mobile browser.
function printReceipt(): void {
  window.print();
}
</script>

<template>
  <section
    class="mx-auto max-w-2xl p-6 print:max-w-none print:p-0"
    aria-labelledby="payment-confirmation-heading"
    data-testid="payment-confirmation"
  >
    <!-- Print only: on screen the app header says whose page this is, on paper nothing would. -->
    <p class="hidden text-sm font-semibold text-slate-900 print:block">
      AgreementMitra - payment receipt
    </p>
    <p class="text-sm font-medium text-emerald-700">Payment received</p>
    <h2 id="payment-confirmation-heading" class="mt-1 text-xl font-semibold text-slate-900">
      Your agreement is paid for
    </h2>

    <dl class="mt-6 divide-y divide-slate-200 rounded-lg border border-slate-200">
      <div class="flex items-baseline justify-between gap-4 px-4 py-3">
        <dt class="text-sm text-slate-600">Reference</dt>
        <dd
          class="font-mono text-lg font-semibold tracking-wide text-slate-900"
          data-testid="confirmation-reference"
        >
          {{ props.reference }}
        </dd>
      </div>
      <div v-if="props.amountLabel" class="flex items-baseline justify-between gap-4 px-4 py-3">
        <dt class="text-sm text-slate-600">Amount paid</dt>
        <dd class="text-sm font-medium text-slate-900">{{ props.amountLabel }}</dd>
      </div>
    </dl>

    <h3 class="mt-6 text-sm font-semibold text-slate-900">What happens next</h3>
    <ol class="mt-2 list-decimal space-y-1 pl-5 text-sm text-slate-600">
      <li>We attach the e-stamp to your agreement.</li>
      <li>Each party signs it with Aadhaar OTP.</li>
      <li>The signed agreement is emailed to everyone on it.</li>
    </ol>

    <div class="mt-6 rounded-lg bg-slate-50 p-4 text-sm text-slate-700">
      <template v-if="props.linkSent">
        <p class="font-medium text-slate-900">We have emailed you a link to this agreement.</p>
        <p class="mt-1">
          Use it to come back to this agreement at any time. Keep the reference above as well - you
          can ask for the link again with it.
        </p>
        <p class="mt-1">
          The link keeps working until you sign in and save the agreement to an account. After that,
          open it by signing in instead.
        </p>
      </template>
      <template v-else>
        <!-- No address was on file, so no link was sent. Say so rather than implying otherwise. -->
        <p class="font-medium text-slate-900">Write down your reference.</p>
        <p class="mt-1">
          We do not have an email address for this agreement, so we could not send you a link. The
          reference above is how you get back to it - keep it somewhere safe.
        </p>
      </template>
    </div>

    <!-- print:hidden - the buttons are the one thing here that means nothing on paper. -->
    <div class="mt-6 flex flex-wrap gap-3 print:hidden">
      <button
        type="button"
        class="rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white"
        @click="emit('continue')"
      >
        Back to my agreement
      </button>
      <button
        type="button"
        class="rounded border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
        data-testid="print-receipt"
        @click="printReceipt"
      >
        Print or save as PDF
      </button>
    </div>
    <p class="mt-2 text-xs text-slate-500 print:hidden">
      Pick "Save as PDF" in the print dialog to keep a copy on your device.
    </p>

    <!-- print:hidden inside the component: this is guidance about the service, and the thing being
         printed here is a payment receipt. -->
    <LegalDisclaimer />
  </section>
</template>
