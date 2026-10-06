// Reads a Spring placeholder default out of the backend's application.yml, so a frontend test can
// pin a frontend constant to the backend value it restates. Test-only: it reads ../backend, which
// the web image (deploy/Dockerfile.web, `build:only`) never does.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/**
 * The default of the one `<leafKey>: ${ENV:default}` line in `yamlText`. Fails closed: zero or
 * several matching lines, or a value that is not a placeholder, throws.
 */
export function parseYamlDefault(yamlText: string, leafKey: string): string {
  const escaped = leafKey.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const pattern = new RegExp(`^\\s+${escaped}:\\s+(.*)$`);
  const lines = yamlText.split(/\r?\n/).filter((line) => pattern.test(line));
  if (lines.length !== 1) {
    throw new Error(
      `expected exactly one "${leafKey}:" line, found ${lines.length}`,
    );
  }

  let value = pattern.exec(lines[0])![1].trim();
  const quoted = /^(["'])([\s\S]*)\1$/.exec(value);
  if (quoted) value = quoted[2];

  if (!value.startsWith("${") || !value.endsWith("}")) {
    throw new Error(`"${leafKey}" is not a \${ENV:default} placeholder`);
  }
  const inner = value.slice(2, -1);
  const colon = inner.indexOf(":");
  if (colon < 0) throw new Error(`"${leafKey}" placeholder has no default`);
  return inner.slice(colon + 1);
}

export function readYamlDefault(leafKey: string): string {
  // Vitest's root is frontend/, and import.meta.url is not a file: URL under jsdom.
  const yaml = readFileSync(
    resolve(process.cwd(), "../backend/src/main/resources/application.yml"),
    "utf8",
  );
  return parseYamlDefault(yaml, leafKey);
}
