import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("public/sitemap.xml", () => {
  it.each(["/terms", "/privacy", "/refunds", "/contact"])(
    "lists the policy page %s",
    (path) => {
      const sitemap = readFileSync(
        resolve(process.cwd(), "public/sitemap.xml"),
        "utf8",
      );
      expect(sitemap).toContain(`<loc>https://agreementmitra.com${path}</loc>`);
    },
  );
});
