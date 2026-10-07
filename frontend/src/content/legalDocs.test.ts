import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it, vi } from "vitest";
import { LEGAL_DOCS } from "./legalDocs";
import { renderLegalMarkdown } from "./legalMarkdown";

// Each policy document has two audiences -- the customer on the page and counsel reading the file
// under docs/ -- and two hand-kept copies of a legal text is exactly the defect
// docs/LEGAL-POSTURE.md exists to prevent. So there is one source and the markdown is generated
// from it. This is the gate that makes the generation non-optional.
describe.each(LEGAL_DOCS.map((entry) => [entry.docPath, entry] as const))(
  "%s",
  (docPath, entry) => {
    it("is in step with its source", () => {
      // docPath is relative to frontend/, which is where both vitest and the generator run.
      const onDisk = readFileSync(resolve(process.cwd(), docPath), "utf8");
      expect(
        onDisk,
        `${docPath} is stale. Run \`npm run legal:doc\` from frontend/.`,
      ).toBe(renderLegalMarkdown(entry));
    });

    it("labels each gap with who owes it", () => {
      const markdown = renderLegalMarkdown(entry);
      for (const clause of entry.doc.clauses) {
        if (clause.status === "drafted") continue;
        const label =
          clause.status === "counsel"
            ? "GAP - FOR COUNSEL"
            : "GAP - AWAITING PRODUCT INPUT";
        expect(markdown).toContain(`> **${label}.** ${clause.gap}`);
      }
    });

    it("renders from the committed defaults, never the build env", async () => {
      const unstubbed = renderLegalMarkdown(entry);
      vi.stubEnv("VITE_OPERATOR_LLPIN", "ACA-1234");
      try {
        vi.resetModules();
        const { LEGAL_DOCS: reloaded } = await import("./legalDocs");
        const { renderLegalMarkdown: rerender } =
          await import("./legalMarkdown");
        const stubbed = rerender(reloaded.find((e) => e.docPath === docPath)!);
        expect(stubbed).toBe(unstubbed);
        expect(stubbed).not.toContain("ACA-1234");
      } finally {
        vi.unstubAllEnvs();
        vi.resetModules();
      }
    });
  },
);

describe("the privacy document", () => {
  it("explains only the kind of gap it contains", () => {
    const privacy = LEGAL_DOCS.find((e) =>
      e.docPath.endsWith("PRIVACY-POLICY.md"),
    )!;
    const markdown = renderLegalMarkdown(privacy);
    expect(markdown).toContain("# AgreementMitra — Privacy Policy (DRAFT)");
    // Every privacy clause is drafted, so the document explains no gap at all.
    expect(markdown).not.toContain("FOR COUNSEL");
    expect(markdown).not.toContain("AWAITING PRODUCT INPUT");
  });
});
