// Renders a policy document as the markdown counsel reads (docs/TERMS-OF-SERVICE.md,
// docs/PRIVACY-POLICY.md). The page and the file are therefore ONE text with two faces, the same
// discipline the document compiler uses for the preview and the PDF.
//
// The files are GENERATED -- `npm run legal:doc` writes them -- and legalDocs.test.ts fails when one
// is stale. The test is the gate, not the script: if the script ever stops working, regenerate the
// file by hand and the gate still tells you whether you got it right.

import type { LegalDocEntry } from "./legalDocs";
import type { ClauseStatus } from "./legalDocument";
import { OPERATING_ENTITY_DEFAULTS } from "./operatingEntity";
import { CONTACT_EMAIL, GRIEVANCE_EMAIL } from "./promises";

/** How a non-drafted clause announces itself in the markdown, mirroring the on-page gap box. */
const GAP_LABEL: Record<ClauseStatus, string | null> = {
  drafted: null,
  counsel: "GAP - FOR COUNSEL",
  product: "GAP - AWAITING PRODUCT INPUT",
};

// Each sentence once; the terms' intro (both kinds) must stay byte-identical to what it was.
const COUNSEL_GAP =
  "**FOR COUNSEL** is a section we have deliberately not\nwritten because it is not ours to write.";
const PRODUCT_GAP =
  "**AWAITING PRODUCT INPUT** is a commercial term the\ncompany has not yet decided; it is left empty rather than guessed, because a guess here would be\nreviewed as though it were intended.";

/** Explains only the gap kinds the document actually contains. */
function gapIntro(statuses: Set<ClauseStatus>): string[] {
  const counsel = statuses.has("counsel");
  const product = statuses.has("product");
  if (counsel && product) {
    return [
      `Two kinds of gap are marked below. ${COUNSEL_GAP} ${PRODUCT_GAP}`,
      "",
    ];
  }
  if (counsel) return [`Gaps are marked below. ${COUNSEL_GAP}`, ""];
  if (product) return [`Gaps are marked below. ${PRODUCT_GAP}`, ""];
  return [];
}

export function renderLegalMarkdown(entry: LegalDocEntry): string {
  const { doc } = entry;
  const out: string[] = [
    "<!-- GENERATED FILE - DO NOT EDIT.",
    `     Source: ${entry.sourcePath}. Regenerate: \`npm run legal:doc\` from`,
    `     frontend/. ${entry.testPath} fails when this file is stale. -->`,
    "",
    `# AgreementMitra — ${doc.title} (DRAFT)`,
    "",
    `**Last updated:** ${doc.lastUpdated}`,
    "",
    `> ${doc.banner}`,
    "",
    ...gapIntro(new Set(doc.clauses.map((c) => c.status))),
    "---",
    "",
  ];

  for (const clause of doc.clauses) {
    out.push(`## ${clause.heading}`, "");
    const label = GAP_LABEL[clause.status];
    if (label && clause.gap) {
      out.push(`> **${label}.** ${clause.gap}`, "");
    }
    for (const paragraph of clause.body) {
      out.push(paragraph, "");
    }
  }

  // From the committed defaults, never the build env: this document must not depend on whoever runs
  // the generator. Not part of the clause text a terms acceptance versions (D6).
  const entity = OPERATING_ENTITY_DEFAULTS;
  out.push(
    "---",
    "",
    "## Operator details",
    "",
    "These identifiers are deployment data, set from configuration when issued; they are not part of",
    "the clause text above. This document shows the committed defaults.",
    "",
    `- **Legal name:** ${entity.legalName}`,
    `- **LLPIN:** ${entity.llpin ?? "being issued"}`,
    `- **Registered office:** ${entity.registeredOffice ?? "to be confirmed"}`,
    `- **Support:** ${CONTACT_EMAIL}`,
    `- **Grievance officer:** ${entity.grievanceOfficer ?? "to be named"}, ${GRIEVANCE_EMAIL}`,
    "",
  );

  return out.join("\n").replace(/\n+$/, "\n");
}
