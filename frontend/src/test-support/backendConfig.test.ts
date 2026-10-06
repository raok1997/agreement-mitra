import { describe, expect, it } from "vitest";
import { parseYamlDefault } from "./backendConfig";

describe("parseYamlDefault", () => {
  it("reads an unquoted placeholder default", () => {
    const yaml = "fee:\n  base-minor-units: ${PAYMENT_FEE_BASE:49900}\n";
    expect(parseYamlDefault(yaml, "base-minor-units")).toBe("49900");
  });

  it("strips the quotes around a quoted placeholder", () => {
    const yaml = 'footer:\n  screen-notice: "${NOTICE:Read it first.}"\n';
    expect(parseYamlDefault(yaml, "screen-notice")).toBe("Read it first.");
  });

  it("keeps a default that itself contains ': '", () => {
    const yaml =
      'footer:\n  screen-notice: "${NOTICE:It is ours: we wrote it.}"\n';
    expect(parseYamlDefault(yaml, "screen-notice")).toBe(
      "It is ours: we wrote it.",
    );
  });

  it("throws when no line matches", () => {
    expect(() => parseYamlDefault("a:\n  b: ${X:1}\n", "c")).toThrow(/found 0/);
  });

  it("throws when two lines match", () => {
    const yaml = "a:\n  b: ${X:1}\nc:\n  b: ${Y:2}\n";
    expect(() => parseYamlDefault(yaml, "b")).toThrow(/found 2/);
  });
});
