import { describe, expect, it } from "vitest";
import { clauseById } from "./legalDocument";
import { LEGAL_DOCS } from "./legalDocs";

const DOCS = LEGAL_DOCS.map((entry) => [entry.doc.title, entry.doc] as const);

describe.each(DOCS)("%s, as a clause list", (_title, doc) => {
  it("gives every clause a unique id", () => {
    const ids = doc.clauses.map((c) => c.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("requires a gap note on every clause we have not written", () => {
    // A clause marked incomplete but with nothing said about why is worse than no marking at all.
    for (const clause of doc.clauses) {
      if (clause.status === "drafted") continue;
      expect(
        clause.gap,
        `${clause.heading} is marked ${clause.status} with no gap note`,
      ).toBeTruthy();
    }
  });

  it("keeps every operator identifier out of the clause text", () => {
    // operating-entity-disclosure D6: the accepted wording must not change when an LLPIN is issued.
    for (const clause of doc.clauses) {
      for (const text of [...clause.body, clause.gap ?? ""]) {
        expect(text).not.toContain("LLPIN");
        expect(text).not.toContain("GSTIN");
      }
    }
  });
});

describe("clauseById", () => {
  it("throws, naming the id, when the document has no such clause", () => {
    expect(() => clauseById(LEGAL_DOCS[0].doc, "no-such-clause")).toThrow(
      /no-such-clause/,
    );
  });
});
