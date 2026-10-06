import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import {
  OPERATING_ENTITY_DEFAULTS,
  resolveOperatingEntity,
} from "./operatingEntity";
import {
  assertOperatorEnv,
  LLPIN_PATTERN,
  OPERATOR_LEGAL_NAME,
} from "./operatorFacts";

/** The un-escaped value of the string constant `name` in backend OperatingEntity.java. */
function javaStringConstant(name: string): string {
  // Test-only: reads ../backend, which the web image (`build:only`) never does.
  const java = readFileSync(
    resolve(
      process.cwd(),
      "../backend/src/main/java/in/agreementmitra/OperatingEntity.java",
    ),
    "utf8",
  );
  const literal = new RegExp(`${name}\\s*=\\s*"((?:[^"\\\\]|\\\\.)*)"`).exec(
    java,
  );
  expect(literal, `${name} not found in OperatingEntity.java`).not.toBeNull();
  return literal![1].replace(/\\\\/g, "\\");
}

describe("the operator's legal name", () => {
  it("equals the backend's OperatingEntity.LEGAL_NAME", () => {
    expect(OPERATOR_LEGAL_NAME).toBe(javaStringConstant("LEGAL_NAME"));
  });
});

describe("the LLPIN pattern", () => {
  it("equals the backend's LLPIN_REGEX", () => {
    expect(javaStringConstant("LLPIN_REGEX")).toBe(LLPIN_PATTERN.source);
  });
});

describe("assertOperatorEnv", () => {
  it.each(["", "   ", undefined])("passes a blank LLPIN (%j)", (value) => {
    expect(() =>
      assertOperatorEnv({
        VITE_OPERATOR_LLPIN: value,
        VITE_OPERATOR_REGISTERED_OFFICE: value,
      }),
    ).not.toThrow();
  });

  it("passes a padded, well-formed LLPIN", () => {
    expect(() =>
      assertOperatorEnv({ VITE_OPERATOR_LLPIN: " ACA-1234 " }),
    ).not.toThrow();
  });

  it("passes a realistic address with # and /", () => {
    expect(() =>
      assertOperatorEnv({
        VITE_OPERATOR_REGISTERED_OFFICE:
          "Flat #4/2, Sri Sai Residency, Road No. 12, Banjara Hills, Hyderabad - 500034 (Telangana) & Co's",
      }),
    ).not.toThrow();
  });

  it("refuses a placeholder LLPIN, naming the variable", () => {
    expect(() => assertOperatorEnv({ VITE_OPERATOR_LLPIN: "TBD" })).toThrow(
      /VITE_OPERATOR_LLPIN/,
    );
  });

  it.each([
    "Road <1>",
    'Road "1"',
    "Road \\1",
    "Road; 1",
    "Road $1",
    "Road\t1",
    "Road\n1",
    "Road 1",
    "Rōad 1",
    "x".repeat(201),
  ])("refuses an office containing %j, naming the variable", (office) => {
    expect(() =>
      assertOperatorEnv({ VITE_OPERATOR_REGISTERED_OFFICE: office }),
    ).toThrow(/VITE_OPERATOR_REGISTERED_OFFICE/);
  });
});

describe("resolveOperatingEntity", () => {
  it("trims values and maps blank to null", () => {
    expect(
      resolveOperatingEntity({
        VITE_OPERATOR_LLPIN: " ACA-1234 ",
        VITE_OPERATOR_REGISTERED_OFFICE: "  ",
      }),
    ).toEqual({
      legalName: OPERATOR_LEGAL_NAME,
      llpin: "ACA-1234",
      registeredOffice: null,
    });
  });

  it("defaults to the name with no identifiers", () => {
    expect(OPERATING_ENTITY_DEFAULTS).toEqual({
      legalName: OPERATOR_LEGAL_NAME,
      llpin: null,
      registeredOffice: null,
    });
  });
});
