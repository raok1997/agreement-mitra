import { afterEach, describe, expect, it } from "vitest";
import { readCookie } from "./cookies";

function clearCookies(): void {
  for (const part of document.cookie.split(";")) {
    const name = part.split("=")[0].trim();
    if (name)
      document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  }
}

describe("readCookie", () => {
  afterEach(clearCookies);

  it("reads an exact name and treats blank or missing as null", () => {
    document.cookie = "XSRF-TOKEN=abc; path=/";
    document.cookie = "OTHER-XSRF-TOKEN=zzz; path=/";
    expect(readCookie("XSRF-TOKEN")).toBe("abc");
    expect(readCookie("MISSING")).toBeNull();
  });

  it("returns the raw value for a malformed escape instead of throwing", () => {
    document.cookie = "XSRF-TOKEN=%E0%A4; path=/";
    expect(() => readCookie("XSRF-TOKEN")).not.toThrow();
    expect(readCookie("XSRF-TOKEN")).toBe("%E0%A4");
  });
});
