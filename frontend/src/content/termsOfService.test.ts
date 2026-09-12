import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { renderTermsMarkdown } from "./termsMarkdown";
import { TERMS_DOC_PATH } from "./termsDocPath";
import { TERMS_CLAUSES } from "./termsOfService";

// The terms have two audiences -- the customer at /terms and counsel reading
// docs/TERMS-OF-SERVICE.md -- and two hand-kept copies of a legal text is exactly the defect
// docs/LEGAL-POSTURE.md exists to prevent. So there is one source and the markdown is generated
// from it. This is the gate that makes the generation non-optional.
describe("terms of service, as a document", () => {
  it("keeps docs/TERMS-OF-SERVICE.md in step with the published page", () => {
    // TERMS_DOC_PATH is relative to frontend/, which is where both vitest and the generator run.
    const onDisk = readFileSync(resolve(process.cwd(), TERMS_DOC_PATH), "utf8");

    expect(
      onDisk,
      "docs/TERMS-OF-SERVICE.md is stale. Run `npm run terms:doc` from frontend/.",
    ).toBe(renderTermsMarkdown());
  });

  it("labels each gap in the document with who owes it", () => {
    const markdown = renderTermsMarkdown();
    for (const clause of TERMS_CLAUSES) {
      if (clause.status === "drafted") continue;
      const label =
        clause.status === "counsel"
          ? "GAP - FOR COUNSEL"
          : "GAP - AWAITING PRODUCT INPUT";
      expect(markdown).toContain(`> **${label}.** ${clause.gap}`);
    }
  });

  it("requires a gap note on every clause we have not written", () => {
    // A clause marked incomplete but with nothing said about why is worse than no marking at all.
    for (const clause of TERMS_CLAUSES) {
      if (clause.status === "drafted") continue;
      expect(
        clause.gap,
        `${clause.heading} is marked ${clause.status} with no gap note`,
      ).toBeTruthy();
    }
  });
});
