// Every policy document that owns its own text, and where its counsel-facing copy lives. Shared by
// the generator (scripts/render-legal-docs.mjs) and the staleness tests (legalDocs.test.ts), so the
// two cannot disagree about which file is the one being guarded. /refunds and /contact are absent
// by design: they render terms clauses and own no text of their own.

import type { LegalDocument } from "./legalDocument";
import { PRIVACY_POLICY } from "./privacyPolicy";
import { TERMS_OF_SERVICE } from "./termsOfService";

export interface LegalDocEntry {
  doc: LegalDocument;
  /** The generated markdown, relative to `frontend/`. */
  docPath: string;
  /** The data module, named in the generated header. */
  sourcePath: string;
  /** The staleness test, named in the generated header. */
  testPath: string;
}

const TEST_PATH = "frontend/src/content/legalDocs.test.ts";

export const LEGAL_DOCS: LegalDocEntry[] = [
  {
    doc: TERMS_OF_SERVICE,
    docPath: "../docs/TERMS-OF-SERVICE.md",
    sourcePath: "frontend/src/content/termsOfService.ts",
    testPath: TEST_PATH,
  },
  {
    doc: PRIVACY_POLICY,
    docPath: "../docs/PRIVACY-POLICY.md",
    sourcePath: "frontend/src/content/privacyPolicy.ts",
    testPath: TEST_PATH,
  },
];
