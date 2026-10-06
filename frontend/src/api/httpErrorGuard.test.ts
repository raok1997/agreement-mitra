import { readdirSync, readFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

// Two rules that keep refusals readable (agreement-error-problem-type-plumbing D2, D4):
// - an API HTTP error is built only through its static `from(res)`, which always reads the problem
//   type -- a hand-built `new XHttpError(res.status)` is how 9 of 11 sites once dropped it;
// - a CustomerFacingError, whose message a view shows as is, is constructed only where its text is
//   written for customers.

const API_DIR = dirname(fileURLToPath(import.meta.url));
const DIRECT_HTTP_ERROR = /new\s+(Agreement|Payment|StaffQueue)HttpError\s*\(/;
const CUSTOMER_FACING = /new\s+CustomerFacingError\s*\(/;
const CUSTOMER_FACING_ALLOWED = new Set([
  "http.ts",
  "client.ts",
  "payments.ts",
]);

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return entry.name.endsWith(".ts") && !entry.name.endsWith(".test.ts")
      ? [path]
      : [];
  });
}

function offenders(matcher: RegExp, allowed: Set<string> = new Set()) {
  return sourceFiles(API_DIR)
    .map((path) => relative(API_DIR, path))
    .filter((file) => !allowed.has(file))
    .filter((file) => matcher.test(readFileSync(join(API_DIR, file), "utf8")));
}

describe("src/api HTTP error guard", () => {
  it("no module constructs an API HTTP error except through from(res)", () => {
    expect(offenders(DIRECT_HTTP_ERROR)).toEqual([]);
  });

  it("CustomerFacingError is constructed only in http.ts, client.ts and payments.ts", () => {
    expect(offenders(CUSTOMER_FACING, CUSTOMER_FACING_ALLOWED)).toEqual([]);
  });

  it("the matchers catch a hand-built error and pass from(res)", () => {
    expect(
      DIRECT_HTTP_ERROR.test("throw new AgreementHttpError(res.status)"),
    ).toBe(true);
    expect(
      DIRECT_HTTP_ERROR.test("throw new PaymentHttpError(res.status)"),
    ).toBe(true);
    expect(
      DIRECT_HTTP_ERROR.test("throw new StaffQueueHttpError(res.status, t)"),
    ).toBe(true);
    expect(
      DIRECT_HTTP_ERROR.test("throw await AgreementHttpError.from(res)"),
    ).toBe(false);
    expect(DIRECT_HTTP_ERROR.test("return new this(res.status, type)")).toBe(
      false,
    );
    expect(CUSTOMER_FACING.test('reject(new CustomerFacingError("x"))')).toBe(
      true,
    );
    expect(CUSTOMER_FACING.test("class X extends CustomerFacingError {")).toBe(
      false,
    );
  });
});
