// Renders the terms of service (src/content/termsOfService.ts) as the markdown document counsel
// reads: docs/TERMS-OF-SERVICE.md. The page at /terms and that file are therefore ONE text with two
// faces, the same discipline the document compiler uses for the preview and the PDF.
//
// The file is GENERATED -- `npm run terms:doc` writes it -- and termsOfService.test.ts fails when it
// is stale. The test is the gate, not the script: if the script ever stops working, regenerate the
// file by hand and the gate still tells you whether you got it right.

import {
  TERMS_CLAUSES,
  TERMS_LAST_UPDATED,
  TERMS_STATUS_BANNER,
  type Clause,
} from "./termsOfService";

/** How a non-drafted clause announces itself in the markdown, mirroring the on-page gap box. */
const GAP_LABEL: Record<Clause["status"], string | null> = {
  drafted: null,
  counsel: "GAP - FOR COUNSEL",
  product: "GAP - AWAITING PRODUCT INPUT",
};

export function renderTermsMarkdown(): string {
  const out: string[] = [
    "<!-- GENERATED FILE - DO NOT EDIT.",
    "     Source: frontend/src/content/termsOfService.ts. Regenerate: `npm run terms:doc` from",
    "     frontend/. frontend/src/content/termsOfService.test.ts fails when this file is stale. -->",
    "",
    "# AgreementMitra — Terms of Service (DRAFT)",
    "",
    `**Last updated:** ${TERMS_LAST_UPDATED}`,
    "",
    `> ${TERMS_STATUS_BANNER}`,
    "",
    "Two kinds of gap are marked below. **FOR COUNSEL** is a section we have deliberately not",
    "written because it is not ours to write. **AWAITING PRODUCT INPUT** is a commercial term the",
    "company has not yet decided; it is left empty rather than guessed, because a guess here would be",
    "reviewed as though it were intended.",
    "",
    "---",
    "",
  ];

  for (const clause of TERMS_CLAUSES) {
    out.push(`## ${clause.heading}`, "");
    const label = GAP_LABEL[clause.status];
    if (label && clause.gap) {
      out.push(`> **${label}.** ${clause.gap}`, "");
    }
    for (const paragraph of clause.body) {
      out.push(paragraph, "");
    }
  }

  return out.join("\n").replace(/\n+$/, "\n");
}
