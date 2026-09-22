<script setup lang="ts">
// The "not a law firm / not legal advice" notice, on the screens where the customer is about to
// commit: the capture form (where the document is being made), the contact step, and the payment
// confirmation.
//
// WHY IT IS A COMPONENT. Before this existed, the ONLY disclaimer on the service was a line at the
// foot of the FAQ on the marketing page, disclaiming the FAQ answers -- the marketing was disclaimed
// and the product was not (docs/LEGAL-POSTURE.md item 2). One component means the wording cannot
// drift between screens, and adding it to a new screen is one import.
//
// IT POINTS AT THE TERMS. A disclaimer that ends the conversation is worth less than one that hands
// the reader the document -- so the notice links to /terms rather than restating it.
//
// print:hidden. It is on-screen guidance about the product, not part of any document the customer
// prints; the payment confirmation prints as a receipt and this is not on the receipt. The same
// reasoning keeps the notice out of the executed deed (counsel brief Q6(d)).

withDefaults(
  defineProps<{
    /** "inline" sits inside a screen's flow; "bar" spans a full-width edge of the capture shell. */
    variant?: "inline" | "bar";
  }>(),
  { variant: "inline" },
);
</script>

<template>
  <p
    class="text-xs leading-relaxed text-slate-500 print:hidden"
    :class="
      variant === 'bar'
        ? 'border-t border-slate-200 bg-slate-50 px-4 py-2'
        : 'mt-6'
    "
    data-testid="legal-disclaimer"
  >
    AgreementMitra is not a law firm and this is not legal advice. Your
    agreement is generated from a template; no lawyer reviews it for your
    circumstances. Read it before you sign, and see the
    <a
      href="/terms"
      class="underline hover:text-slate-700"
      data-testid="legal-disclaimer-terms-link"
      >terms of service</a
    >.
  </p>
</template>
