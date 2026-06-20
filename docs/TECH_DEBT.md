# Tech Debt

Known, deliberate shortcuts we've accepted. Each entry: what, why it's debt,
and the condition that retires it. Keep it honest — if something here is no
longer true, fix the code or delete the entry.

## TD-1 — Frontend build gate is a local stopgap, not CI

**Added:** 2026-06-20 · **Owner:** single-dev · **Retire when:** CR-7 (`ci-pipeline`) lands

`frontend/package.json` chains the security + test gates into the build:

```json
"build": "npm run security:scan && npm run test && vue-tsc -b && vite build",
"build:only": "vue-tsc -b && vite build",
```

This makes a frontend build fail-closed like the backend already is — the
backend's `securityScan` (OSV deps + SpotBugs/FindSecBugs) is wired into
`check`, and `build` dependsOn `check` (`backend/build.gradle.kts`), so only
`bootRun` skips the gates. The frontend had no such coupling before this.

**Why it's debt — three limits:**

- **No caching.** Every local build re-runs the full OSV scan + Vitest suite.
  CI would cache the dep graph and only scan on change.
- **`lint` is not in the chain.** ESLint stays manual (`npm run lint`). This
  matches the backend, where Spotless isn't in `check` either — style is left
  to the dev / format hook.
- **Still bypassable.** A dev can run `build:only` or `vite build` directly and
  skip the gates. Only CI is authoritative; a local gate is a reminder, not
  enforcement.

**Retirement:** when CR-7 stands up GitHub Actions, the gates run on every
push/PR with caching. At that point this stopgap is redundant — revert `build`
to `build:only` (or keep the chain as a fast local pre-check, but it's no longer
the safety net). See the `best-practices-hardening-roadmap` memory, CR-7.
