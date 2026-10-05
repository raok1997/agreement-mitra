<script setup lang="ts">
// The service's one disclaimer, on the screens where the customer is about to commit: the capture
// form (where the document is being made), the contact step, and the payment confirmation.
//
// WHAT IT SAYS, IN ORDER (openspec landing-page spec): the wording is ours; the facts and choices
// are the customer's to check; then the short "not a law firm / no lawyer reviews it / not legal
// advice" line. The on-preview screen notice (documents.footer.screen-notice in the backend's
// application.yml) carries the same wording as plain text -- LegalDisclaimer.test.ts holds the two
// equal, so the service keeps one disclaimer wording (docs/LEGAL-POSTURE.md item 2).
//
// RELEASE CONDITION. "We stand behind it" is a quality statement. It does not ship to real
// customers until counsel has reviewed it against ToS section 16 (docs/ROADMAP.md release Counsel
// line; docs/COUNSEL-BRIEF.md Q6(d)). Do not add "reviewed by counsel" here until
// template-counsel-signoff-gate records that review.
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
    The wording of this agreement is ours: we wrote the template and we stand
    behind it. The facts you enter and the choices you make are yours, so check
    them before you sign. We are not a law firm, no lawyer reviews your
    agreement for your circumstances, and this is not legal advice; see the
    <a
      href="/terms"
      class="underline hover:text-slate-700"
      data-testid="legal-disclaimer-terms-link"
      >terms of service</a
    >.
  </p>
</template>
