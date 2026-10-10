#!/usr/bin/env node
//
// Browser-driven post-deploy check, run from a workstation (NOT the server). It does what a person
// did by hand after every deploy: draft an agreement, Save & continue (which renders the PDF and
// writes it to object storage), then delete the draft. Prints PASS/FAIL per check and exits
// non-zero on any failure.
//
//   npm run login [-- <base-url>]     sign in to Google once, by hand; the session lasts 24 hours
//   npm run smoke [-- <base-url>]     default https://agreementmitra.com
//   npm run smoke keep                skip the delete and leave the window open on the saved
//                                     agreement, so you can press "Finalise and pay" by hand
//   npm run smoke no-login            draft anonymously; the draft CANNOT be deleted afterwards
//                                     and stays until the 90-day purge. For local runs.
//   npm run smoke headless
//
// Options are plain words because `npm run` swallows a `--flag` unless a bare `--` precedes it.
// The `--flag` spelling is accepted too, for `node smoke-ui.mjs --keep`.
//
// Stops at save. It places no order and takes no payment itself: a paid order cannot be deleted,
// and nothing marks one as a test (ROADMAP `prod-test-order-lifecycle`). `keep` hands the saved
// draft to you for a manual payment check; a draft you do not pay is yours to delete from
// "My agreements".
//
// Drives your installed Google Chrome (`playwright-core`, no bundled browser). Google sign-in is
// never automated and no password is stored: `login` opens a window, you sign in, and the session
// cookie is saved to ~/.config/agreementmitra/ (mode 0600, outside the repository). Use an account
// WITHOUT the STAFF role -- this check needs none, so a leaked file is never a staff session.
//
// Dummy data only. Nothing entered is logged, and no cookie value is printed.

import { chromium } from "playwright-core";
import { chmodSync, existsSync, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const DEFAULT_BASE = "https://agreementmitra.com";
const STATE_DIR = join(homedir(), ".config", "agreementmitra");
const LOGIN_TIMEOUT_MS = 5 * 60_000;
// Save & continue creates the agreement, then renders its PDF through Chromium and stores it.
const GENERATE_TIMEOUT_MS = 90_000;
const CSRF_COOKIES = ["__Host-XSRF-TOKEN", "XSRF-TOKEN"];
// Rendered from the template's own default; the capture form never shows it.
const HIDDEN_FIELDS = new Set(["stampDuty"]);

function usage() {
  console.error(
    "usage: smoke-ui.mjs [login] [no-login] [keep] [headless] [state=TG] [type=residential] [base-url]",
  );
  process.exit(2);
}

function parseArgs(argv) {
  const opts = {
    command: "smoke",
    base: DEFAULT_BASE,
    login: true,
    headless: false,
    keep: false,
    state: "TG",
    type: "residential",
  };
  for (const raw of argv) {
    const arg = raw.replace(/^--/, "");
    if (arg === "login") opts.command = "login";
    else if (arg === "no-login") opts.login = false;
    else if (arg === "headless") opts.headless = true;
    else if (arg === "keep") opts.keep = true;
    else if (arg.startsWith("state=")) opts.state = arg.slice(6);
    else if (arg.startsWith("type=")) opts.type = arg.slice(5);
    else if (/^https?:\/\//.test(arg)) opts.base = arg.replace(/\/+$/, "");
    else usage();
  }
  return opts;
}

const opts = parseArgs(process.argv.slice(2));
const host = new URL(opts.base).host.replace(/[^a-z0-9.-]/gi, "_");
const stateFile = join(STATE_DIR, `smoke-${host}.json`);
const profileDir = join(STATE_DIR, `smoke-profile-${host}`);

let failed = false;
const pass = (message) => console.log(`PASS ${message}`);
const fail = (message) => {
  console.log(`FAIL ${message}`);
  failed = true;
};

/** A failed step: reported once as FAIL, and it ends the run (cleanup still happens). */
class StepFailed extends Error {}

const testId = (page, id) => page.getByTestId(id);
const signInButton = (page) =>
  page.getByRole("button", { name: "Sign in with Google" });

// Mirrors frontend/src/views/formModel.ts `sectionId`: the rail's test hooks are keyed by it.
function sectionId(title, index) {
  const slug = title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return slug || `section-${index}`;
}

function ddmmyyyy(date) {
  const dd = String(date.getDate()).padStart(2, "0");
  const mm = String(date.getMonth() + 1).padStart(2, "0");
  return `${dd}/${mm}/${date.getFullYear()}`;
}

/** A start date a week out and an end date eleven months after it: valid everywhere, never stale. */
function tenancyDates() {
  const start = new Date();
  start.setDate(start.getDate() + 7);
  const end = new Date(start);
  end.setMonth(end.getMonth() + 11);
  end.setDate(end.getDate() - 1);
  return { start, end };
}

/** The dummy value for one required field, chosen by widget; obviously not a real person. */
function valueFor(field, dates) {
  switch (field.widget) {
    case "date":
      if (field.key === "startDate") return ddmmyyyy(dates.start);
      if (field.key === "endDate") return ddmmyyyy(dates.end);
      return ddmmyyyy(new Date());
    case "money":
      return String(Math.max(field.validation?.min ?? 0, 15000));
    case "number":
      return String(field.validation?.min ?? 1);
    case "select":
      return field.options?.[0]?.value ?? "";
    case "textarea":
      return "SMOKE TEST, 1 Dummy Street, Hyderabad 500001";
    default:
      return `SMOKE TEST ${field.key.replace(/[^a-z]/gi, " ").trim()}`.slice(
        0,
        field.validation?.maxLength ?? 80,
      );
  }
}

async function fillField(page, field, value) {
  const input = testId(page, `field-${field.key}`);
  if (field.widget === "checkbox") await input.check();
  else if (field.widget === "select") await input.selectOption(value);
  else await input.fill(value);
}

async function getJson(page, path) {
  const res = await page.request.get(`${opts.base}${path}`);
  if (!res.ok()) throw new StepFailed(`GET ${path} answered ${res.status()}`);
  return res.json();
}

async function launch(headless) {
  try {
    return await chromium.launch({
      channel: "chrome",
      headless,
      ignoreDefaultArgs: ["--enable-automation"],
      args: ["--disable-blink-features=AutomationControlled"],
    });
  } catch (e) {
    console.error(`Could not start Google Chrome: ${e.message.split("\n")[0]}`);
    process.exit(2);
  }
}

// --- login -------------------------------------------------------------------------------------

async function login() {
  mkdirSync(STATE_DIR, { recursive: true, mode: 0o700 });
  // A persistent profile, so Google remembers the account and the next login is one click.
  const context = await chromium.launchPersistentContext(profileDir, {
    channel: "chrome",
    headless: false,
    ignoreDefaultArgs: ["--enable-automation"],
    args: ["--disable-blink-features=AutomationControlled"],
  });
  try {
    const page = context.pages()[0] ?? (await context.newPage());
    await page.goto(`${opts.base}/start`);
    const signedIn = testId(page, "nav-my-agreements");
    await signedIn.or(signInButton(page)).first().waitFor();
    if (!(await signedIn.isVisible())) {
      await signInButton(page).click();
      console.error("Sign in to Google in the browser window (5 minutes)...");
      await signedIn.waitFor({ timeout: LOGIN_TIMEOUT_MS });
    }
    const me = await getJson(page, "/api/auth/me");
    if (me?.role === "STAFF") {
      console.error(
        "WARNING: this account has the STAFF role. The saved file is a staff session; " +
          "prefer an account without it.",
      );
    }
    await context.storageState({ path: stateFile });
    chmodSync(stateFile, 0o600);
    console.log(`Signed in. Session saved to ${stateFile} (valid about 24 hours).`);
  } finally {
    await context.close();
  }
}

// --- smoke -------------------------------------------------------------------------------------

async function requireSession(page) {
  const signedIn = testId(page, "nav-my-agreements");
  await signedIn.or(signInButton(page)).first().waitFor();
  if (await signedIn.isVisible()) return pass("signed in from the saved session");
  throw new StepFailed(
    "the saved session has expired or is missing -- run `npm run login` and try again",
  );
}

async function pickTemplate(page) {
  const catalog = await getJson(page, "/api/templates");
  const template = catalog.find(
    (t) => t.state === opts.state && t.type === opts.type,
  );
  if (!template) {
    throw new StepFailed(`no ${opts.state} ${opts.type} template in the catalog`);
  }
  await testId(page, `select-${template.id}`).click();
  await testId(page, "save-continue").waitFor();
  pass(`${opts.state} ${opts.type} template opens in the capture form`);
}

async function fillRequiredSections(page) {
  const params = new URLSearchParams({ state: opts.state, type: opts.type });
  const schema = await getJson(page, `/api/templates/form?${params}`);
  const dates = tenancyDates();
  const rail = testId(page, "section-rail");
  for (const [index, section] of schema.sections.entries()) {
    const fields = section.fields.filter(
      (f) => f.required && !f.readOnly && !HIDDEN_FIELDS.has(f.key),
    );
    if (section.optional || fields.length === 0) continue;
    const id = sectionId(section.title, index);
    await rail.getByTestId(`section-${id}`).click();
    await testId(page, "section-modal").waitFor();
    for (const field of fields) {
      await fillField(page, field, valueFor(field, dates));
    }
    await testId(page, "modal-save").click();
    await testId(page, "section-modal").waitFor({ state: "hidden" });
  }

  const save = testId(page, "save-continue");
  if (await save.isDisabled()) {
    // The schema changed under this script: name what is still open instead of timing out.
    const open = await rail
      .locator('[data-testid^="section-"]', { hasText: "Needs input" })
      .evaluateAll((nodes) => nodes.map((n) => n.getAttribute("data-testid")));
    throw new StepFailed(
      `Save & continue is disabled; still needing input: ${open.join(", ") || "unknown"}`,
    );
  }
  pass("every required section is Ready");
}

/** Click Save & continue; returns the new agreement's id as soon as the create answers. */
async function saveAndGenerate(page, created) {
  const isPost = (res, test) =>
    res.request().method() === "POST" && test(new URL(res.url()).pathname);
  const createResponse = page.waitForResponse((res) =>
    isPost(res, (path) => path === "/api/agreements"),
  );
  const documentResponse = page.waitForResponse(
    (res) => isPost(res, (path) => path.endsWith("/document")),
    { timeout: GENERATE_TIMEOUT_MS },
  );
  // Observed even if the create fails and nothing awaits it.
  documentResponse.catch(() => {});
  await testId(page, "save-continue").click();

  const create = await createResponse;
  if (!create.ok()) {
    throw new StepFailed(`creating the agreement answered ${create.status()}`);
  }
  created.id = (await create.json()).id;
  pass("agreement created");

  const generated = await documentResponse;
  if (!generated.ok()) {
    throw new StepFailed(
      `Generate answered ${generated.status()} -- the PDF was not rendered or not stored`,
    );
  }
  pass("Generate rendered the PDF and stored it (object storage accepts writes)");

  await testId(page, "save-ok").waitFor();
  const reference = (await testId(page, "tracking-number").innerText()).trim();
  if (!reference) throw new StepFailed("the saved agreement shows no reference");
  pass(`the saved agreement shows its reference (${reference})`);

  if (opts.login) {
    await testId(page, "claimed-ok").waitFor();
    pass("the agreement was saved to the account");
  }
}

async function deleteThroughUi(page, id) {
  await testId(page, "nav-my-agreements").click();
  await testId(page, `delete-${id}`).click();
  await testId(page, "confirm-ok").click();
  await testId(page, `row-${id}`).waitFor({ state: "detached" });
}

/** The fallback when the screen is not where the script expects: the same calls, made directly. */
async function deleteThroughApi(page, id) {
  const cookies = await page.context().cookies(opts.base);
  const token = CSRF_COOKIES.map(
    (name) => cookies.find((c) => c.name === name)?.value,
  ).find(Boolean);
  const headers = token ? { "X-XSRF-TOKEN": token } : {};
  const url = `${opts.base}/api/agreements/${id}`;
  // Idempotent for the same owner; needed when the run failed before the automatic claim.
  await page.request.post(`${url}/claim`, { headers });
  const res = await page.request.delete(url, { headers });
  if (res.status() !== 204 && res.status() !== 404) {
    throw new Error(`DELETE answered ${res.status()}`);
  }
}

/** keep: the draft stays, and a visible window stays open on it until the user closes it. */
async function handOver(page, id) {
  console.log(`KEPT the draft ${id} was not deleted`);
  if (opts.headless || page.isClosed()) return;
  console.log(
    'The window is on the saved agreement: press "Finalise and pay" to test payment. ' +
      "Close the window when you are done.",
  );
  await page.waitForEvent("close", { timeout: 0 });
}

async function cleanUp(page, id) {
  if (opts.keep) return handOver(page, id);
  if (!opts.login) {
    console.log(
      `NOTE the draft ${id} has no owner and cannot be deleted; the 90-day purge removes it`,
    );
    return;
  }
  try {
    await deleteThroughUi(page, id);
  } catch {
    try {
      await deleteThroughApi(page, id);
    } catch (e) {
      fail(`the draft ${id} was NOT deleted (${e.message.split("\n")[0]}) -- delete it by hand`);
      return;
    }
  }
  const gone = await page.request.get(`${opts.base}/api/agreements/${id}`);
  if (gone.status() === 404) pass("the draft was deleted");
  else fail(`the draft ${id} still answers ${gone.status()} after delete -- delete it by hand`);
}

async function smoke() {
  if (!opts.login && new URL(opts.base).host.endsWith(new URL(DEFAULT_BASE).host)) {
    console.error(
      "no-login is refused on production: its draft could never be deleted. Run: npm run login",
    );
    process.exit(2);
  }
  if (opts.login && !existsSync(stateFile)) {
    console.error(`No saved session for ${host}. Run: npm run login`);
    process.exit(2);
  }
  const browser = await launch(opts.headless);
  const context = await browser.newContext(
    opts.login ? { storageState: stateFile } : {},
  );
  const page = await context.newPage();
  const created = { id: null };
  try {
    await page.goto(`${opts.base}/start`);
    if (opts.login) await requireSession(page);
    await pickTemplate(page);
    await fillRequiredSections(page);
    await saveAndGenerate(page, created);
  } catch (e) {
    // Playwright's message can run to a page of call log; the first line names the step.
    fail(e.message.split("\n")[0]);
  } finally {
    if (created.id) await cleanUp(page, created.id);
    await browser.close();
  }
  console.log(failed ? "smoke-ui: FAILED" : "smoke-ui: all checks passed");
  process.exit(failed ? 1 : 0);
}

await (opts.command === "login" ? login() : smoke());
