// Writes docs/TERMS-OF-SERVICE.md from the one source of the terms text
// (src/content/termsOfService.ts). Run `npm run terms:doc` from frontend/ after changing a clause.
//
// Loads the TypeScript sources through Vite's own `ssrLoadModule` rather than a second, hand-kept
// copy of the terms in JavaScript. The parity test is the real gate for whether the committed
// markdown matches the source; this script is what keeps you from satisfying it by hand.
//
// It used to run under the `vite-node` BINARY, which was never a declared dependency -- it resolved
// only as a child of vitest 3.x. vitest 4 dropped that dependency and the script broke, exactly as
// its old header warned. Do NOT reintroduce `vite-node`: the only line compatible with vite ^6 is
// the abandoned 3.2.4, and a naive `npm i -D vite-node` pulls 6.0.0 plus a nested Vite 8.
// `vite` is already a direct dependency, so driving it programmatically costs nothing.
//
// Every option below is load-bearing -- each was added because omitting it caused a real failure:
//   root            -- else module ids resolve against process.cwd(), so this works via
//                      `npm run terms:doc` but dies with MODULE_NOT_FOUND when invoked as
//                      `node frontend/scripts/render-terms.mjs`. The vite-node version was
//                      cwd-independent and losing that would be a silent regression.
//   ws: false       -- middleware mode has no httpServer, so Vite otherwise stands up its OWN
//                      WebSocket server on :24678 bound to ALL interfaces, unauthenticated. This
//                      script exists because of an unauthenticated-HMR-socket advisory; opening
//                      one here would be absurd. Binds nothing, output is byte-identical.
//   noDiscovery     -- else the dependency scanner races server.close() and prints ~80 lines of
//                      esbuild stack trace WHILE STILL EXITING 0 with correct output.
//   server.close()  -- in a finally, else the Vite server keeps the event loop alive and the
//                      process never exits. `npm run terms:doc` would appear to hang forever.
// The real vite.config.ts is loaded deliberately (no `configFile: false`), so this resolves modules
// exactly as termsOfService.test.ts does; divergence would surface as a stale legal document.

import { writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), "..");

const server = await createServer({
  root: frontendRoot,
  appType: "custom",
  server: { middlewareMode: true, ws: false },
  optimizeDeps: { noDiscovery: true },
  logLevel: "warn",
});

try {
  const { renderTermsMarkdown } = await server.ssrLoadModule("/src/content/termsMarkdown.ts");
  const { TERMS_DOC_PATH } = await server.ssrLoadModule("/src/content/termsDocPath.ts");

  const target = resolve(frontendRoot, TERMS_DOC_PATH);
  writeFileSync(target, renderTermsMarkdown(), "utf8");
  console.log(`Wrote ${target}`);
} finally {
  // Swallow teardown failures only. If ssrLoadModule threw -- the routine failure mode, a syntax
  // or import error in the terms source -- a rejecting close() here would replace that error and
  // hide the actual cause. The process still exits non-zero on the original throw.
  try {
    await server.close();
  } catch {
    // ignore
  }
}
