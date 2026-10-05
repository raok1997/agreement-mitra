import { describe, expect, it } from "vitest";
import { hasProblemType, PROBLEM, problemTypeOf } from "./problems";

// Fixtures use the server's literal URNs, never PROBLEM.*, so a client-side typo cannot pass by
// agreeing with itself.

function body(json: () => Promise<unknown>): Response {
  return { json } as unknown as Response;
}

describe("problemTypeOf", () => {
  it("returns the type of a problem body", async () => {
    const res = body(() =>
      Promise.resolve({
        type: "urn:agreementmitra:problem:jurisdiction-unsupported",
      }),
    );
    expect(await problemTypeOf(res)).toBe(
      "urn:agreementmitra:problem:jurisdiction-unsupported",
    );
  });

  it("returns null when the body is not JSON", async () => {
    expect(
      await problemTypeOf(body(() => Promise.reject(new SyntaxError("x")))),
    ).toBeNull();
  });

  it("returns null for JSON without a string type", async () => {
    expect(
      await problemTypeOf(body(() => Promise.resolve({ type: 7 }))),
    ).toBeNull();
    expect(await problemTypeOf(body(() => Promise.resolve(null)))).toBeNull();
  });
});

describe("hasProblemType", () => {
  it("matches the exact URN only", () => {
    expect(
      hasProblemType(
        { problemType: "urn:agreementmitra:problem:draft-frozen" },
        PROBLEM.draftFrozen,
      ),
    ).toBe(true);
    expect(
      hasProblemType({ problemType: "draft-frozen" }, PROBLEM.draftFrozen),
    ).toBe(false);
    expect(
      hasProblemType(
        { problemType: "urn:other:draft-frozen" },
        PROBLEM.draftFrozen,
      ),
    ).toBe(false);
  });

  it.each([
    null,
    undefined,
    "urn:agreementmitra:problem:draft-frozen",
    42,
    { problemType: 42 },
    {},
  ])("is false for %j", (value) => {
    expect(hasProblemType(value, PROBLEM.draftFrozen)).toBe(false);
  });
});

describe("PROBLEM", () => {
  it("pins every type to the server's literal URN", () => {
    expect(PROBLEM).toEqual({
      jurisdictionUnsupported:
        "urn:agreementmitra:problem:jurisdiction-unsupported",
      draftFrozen: "urn:agreementmitra:problem:draft-frozen",
      draftNotDeletable: "urn:agreementmitra:problem:draft-not-deletable",
      contactsFrozen: "urn:agreementmitra:problem:contacts-frozen",
      paymentRequired: "urn:agreementmitra:problem:payment-required",
      csrf: "urn:agreementmitra:problem:csrf",
      renderBusy: "urn:agreementmitra:problem:render-busy",
      notFound: "urn:agreementmitra:problem:resource-not-found",
    });
  });
});
