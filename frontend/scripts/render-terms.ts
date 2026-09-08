// Writes docs/TERMS-OF-SERVICE.md from the one source of the terms text
// (src/content/termsOfService.ts). Run `npm run terms:doc` from frontend/ after changing a clause.
//
// Run through vite-node so it can import the TypeScript source directly rather than a second,
// hand-kept copy of the terms existing in JavaScript. The parity test is the real gate; this script
// is the convenience that keeps you from having to satisfy it by hand.
//
// NOTE: vite-node is a TRANSITIVE binary (vitest depends on it); it is not a declared dependency of
// this package. It resolves today and nothing ships from here -- the generated markdown is
// committed, so no build step needs this script. If a future install stops hoisting the binary,
// `npm run terms:doc` fails loudly with "command not found": either declare vite-node as a
// devDependency or regenerate the file by hand. Either way the parity test still tells you whether
// the result is right.

import { writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { renderTermsMarkdown } from "../src/content/termsMarkdown";
import { TERMS_DOC_PATH } from "../src/content/termsDocPath";

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const target = join(frontendRoot, TERMS_DOC_PATH);
writeFileSync(target, renderTermsMarkdown(), "utf8");
console.log(`Wrote ${target}`);
