#!/usr/bin/env node
// Frontend dependency-vulnerability gate (CR-6, frontend-security-scanning).
//
// Runs OSV-Scanner over the WHOLE npm lockfile (dev + prod, transitives) and
// fails the process on ANY unsuppressed finding. Fail-CLOSED: a missing
// osv-scanner binary exits non-zero with an install hint, it does NOT skip.
//
// A Node shim (not a shell one-liner) so the missing-binary guard also fires on
// Windows cmd.exe — see openspec design.md Decision 3.

import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

// Run from the frontend root (parent of scripts/) so --config / --lockfile
// resolve against package-lock.json and osv-scanner.toml deterministically.
const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), "..");

const INSTALL_HINT =
  "OSV-Scanner not found on PATH. Install it to run the frontend security scan:\n" +
  "  brew install osv-scanner\n" +
  "  (or see https://google.github.io/osv-scanner/installation/)";

const result = spawnSync(
  "osv-scanner",
  ["scan", "--config=osv-scanner.toml", "--lockfile=package-lock.json"],
  { cwd: frontendRoot, stdio: "inherit" },
);

// Fail-closed: binary absent → ENOENT. Surface the actionable hint, exit non-zero.
if (result.error) {
  if (result.error.code === "ENOENT") {
    console.error(`\n${INSTALL_HINT}`);
  } else {
    console.error(`\nFailed to run osv-scanner: ${result.error.message}`);
  }
  process.exit(1);
}

// Propagate OSV-Scanner's exit code (non-zero on any unsuppressed finding).
process.exit(result.status ?? 1);
