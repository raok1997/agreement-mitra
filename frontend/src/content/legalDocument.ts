// The shared model for every policy document (terms of service, privacy policy).
//
// STATUS IS PART OF THE CONTENT, NOT A FOOTNOTE. Every clause carries a `status`:
//   "drafted"  -- we wrote it and we mean it, subject to counsel's review of the whole
//   "counsel"  -- a deliberate GAP. We have not written it because it is not ours to write.
//   "product"  -- a commercial term nobody has decided yet. NOT a guess, and never to be filled in
//                 by inference: a wrong number here is a number counsel then reviews as intended.
// A "counsel" or "product" clause renders as a visible gap on the page. That is the honest thing to
// publish during founding-team beta -- a marked hole beats a confident invention, and it beats
// having no policy at all (docs/LEGAL-POSTURE.md item 2).
//
// IDS, NOT NUMBERS. Code and tests name a clause by `id`, never by its number or heading, so a
// renumbered clause cannot silently detach a page or a guarantee from the text it restates.

export type ClauseStatus = "drafted" | "counsel" | "product";

export interface Clause {
  /** Stable kebab-case identifier, unique within its document. */
  id: string;
  /** Section heading, rendered as an <h2> on the page and "## " in the markdown. */
  heading: string;
  /** Body paragraphs. Plain text: no markup, no interpolation -- it is a legal text, not a template. */
  body: string[];
  status: ClauseStatus;
  /** For a non-"drafted" clause: what is missing and who owes it. Rendered as the visible gap note. */
  gap?: string;
}

export interface LegalDocument {
  /** The page <h1>, and the markdown H1 as "AgreementMitra — <title> (DRAFT)". */
  title: string;
  /** The date the draft last changed. Bumped by hand when a clause changes. */
  lastUpdated: string;
  /** Shown at the head of both faces. The page must never look like a settled document. */
  banner: string;
  clauses: Clause[];
}

/** The bold lead-in every draft banner starts with, on every policy page. */
export const DRAFT_BANNER_LEAD = "Draft, pending legal review.";

export function clauseById(doc: LegalDocument, id: string): Clause {
  const found = doc.clauses.find((c) => c.id === id);
  if (!found) {
    throw new Error(`${doc.title} has no clause with id "${id}"`);
  }
  return found;
}
