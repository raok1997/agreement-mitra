#!/usr/bin/env node
/**
 * Flow journal tool for the openspec-flow skill.
 *
 * The journal's heading format is load-bearing: resume reads the last entry's status
 * marker to decide where to re-enter. Prose instructions did not hold it — 5 of the 21
 * archived journals drifted, and 10 active changes have no journal at all. So the format
 * is generated here, never typed, and `audit` makes a missing journal visible without
 * anyone remembering anything.
 *
 *   append     --change <name>   < entry.json   append one stage entry (validated)
 *   last       --change <name>                  last entry + where resume should re-enter
 *   check      --change <name>                  validate one journal
 *   audit                                       sweep every active change  ← start here
 *   followups  [--change <name>]                follow-ups not yet in the ROADMAP register
 *
 * `followups` closes the other half of the same leak `audit` closes. A journal records
 * `Follow-up CRs:` and then archives WITH its change, so a follow-up nobody copied out by
 * hand is durable but unreadable — 70 follow-up mentions sit in the archived journals, and
 * the handful that survived say so explicitly ("pre-recorded in the roadmap memory"). This
 * compares every journal's follow-up lines against the register in docs/ROADMAP.md.
 *
 * `append` reads JSON on stdin so multi-line prose survives intact:
 *   { "stage": "apply", "status": "ok" | "warn" | "halt" (or the emoji),
 *     "outcome": "...", "decisions": "...", "halts": "...", "followUps": "...",
 *     "modifiedFiles": ["backend/src/.../Foo.java", "CLAUDE.md"] }
 * Only `stage`, `status` and `outcome` are required; `modifiedFiles` is required for
 * the apply and fix stages and must be real repo-relative paths, never prose.
 */

import fs from 'node:fs';
import path from 'node:path';

const STAGES = ['explore', 'propose', 'review', 'apply', 'validate', 'fix', 'manual-test', 'archive'];
const STAGE_SET = new Set(STAGES);
const NEEDS_FILE_LIST = new Set(['apply', 'fix']);

const OK = '✅';           // white heavy check mark
const WARN = '⚠️';   // warning sign + variation selector
const HALT = '⛔';         // no entry
const MARKERS = [OK, WARN, HALT];
const ALIASES = new Map([
  ['ok', OK], ['done', OK], ['clean', OK], ['pass', OK], [OK, OK],
  ['warn', WARN], ['warning', WARN], ['concern', WARN], [WARN, WARN], ['⚠', WARN],
  ['halt', HALT], ['halted', HALT], ['blocked', HALT], [HALT, HALT],
]);

const die = (msg) => { console.error(`flow-journal: ${msg}`); process.exit(2); };

function repoRoot(start = process.cwd()) {
  let dir = path.resolve(start);
  for (;;) {
    if (fs.existsSync(path.join(dir, 'openspec', 'changes'))) return dir;
    const up = path.dirname(dir);
    if (up === dir) die('could not locate a repo root containing openspec/changes (pass --repo)');
    dir = up;
  }
}

const journalPath = (root, change) =>
  path.join(root, 'openspec', 'changes', change, '.flow-journal.md');

/** Classify one `## ` heading. Canonical is what this tool writes; tolerated is
 *  parseable but non-canonical (a marker with trailing words); drifted has no marker
 *  where one belongs. Only headings naming a known stage are considered at all. */
function classifyHeading(line) {
  // A stage may carry a parenthetical qualifier — `## review (round 2) — ...` — which the
  // archives use for multi-round stages. Recognising it matters: dropping such a heading
  // would make `last` skip backwards to an older stage and resume in the wrong place.
  const m = /^##\s+([a-z][a-z-]*)(?:\s*\(([^)]*)\))?\s*[—-]\s*(.*)$/.exec(line);
  if (!m) return { kind: 'other', line };
  const [, stage, qualifier, rest] = m;
  if (!STAGE_SET.has(stage)) return { kind: 'other', line };

  const bracket = /\[([^\]]*)\]\s*$/.exec(rest);
  if (!bracket) return { kind: 'drifted', stage, qualifier, line, reason: 'no [marker]' };

  const inner = bracket[1].trim();
  const marker = MARKERS.find((mk) => inner.startsWith(mk) || inner.startsWith(mk.replace('️', '')));
  if (!marker) return { kind: 'drifted', stage, qualifier, line, reason: `bracket holds "${inner}", not a status marker` };

  const trailing = inner.slice(inner.startsWith(marker) ? marker.length : marker.length - 1).trim();
  const timestamp = rest.slice(0, bracket.index).trim();
  return trailing
    ? { kind: 'tolerated', stage, qualifier, marker, timestamp, line, reason: `extra text after marker: "${trailing}"` }
    : { kind: 'canonical', stage, qualifier, marker, timestamp, line };
}

function readJournal(root, change) {
  const file = journalPath(root, change);
  if (!fs.existsSync(file)) return { file, exists: false, headings: [] };
  const lines = fs.readFileSync(file, 'utf8').split('\n');
  const headings = lines.filter((l) => l.startsWith('## ')).map(classifyHeading);
  return { file, exists: true, headings };
}

/** Stage entries only — `other` headings are prose sections, not stage records. */
const stageEntries = (headings) => headings.filter((h) => h.kind !== 'other');

function resumeFrom(entry) {
  if (entry.marker === HALT) return entry.stage;          // halted partway → re-enter same stage
  const i = STAGES.indexOf(entry.stage);
  return i >= 0 && i < STAGES.length - 1 ? STAGES[i + 1] : 'done';
}

// ---------------------------------------------------------------- commands

function cmdAppend(root, change) {
  if (!change) die('append requires --change <name>');
  const dir = path.dirname(journalPath(root, change));
  if (!fs.existsSync(dir)) die(`no change directory at openspec/changes/${change}`);

  const raw = fs.readFileSync(0, 'utf8').trim();
  if (!raw) die('append expects a JSON entry on stdin');
  let e;
  try { e = JSON.parse(raw); } catch (err) { die(`stdin is not valid JSON: ${err.message}`); }

  if (!STAGE_SET.has(e.stage)) die(`stage must be one of: ${STAGES.join(' | ')}`);
  const marker = ALIASES.get(String(e.status ?? '').trim().toLowerCase())
              ?? ALIASES.get(String(e.status ?? '').trim());
  if (!marker) die(`status must be one of: ok | warn | halt (or ${MARKERS.join(' ')})`);
  const outcome = String(e.outcome ?? '').trim();
  if (!outcome) die('outcome is required — 1-3 plain-English sentences');

  const files = e.modifiedFiles ?? [];
  if (!Array.isArray(files)) die('modifiedFiles must be an array of repo-relative paths');
  if (NEEDS_FILE_LIST.has(e.stage) && files.length === 0) {
    die(`the ${e.stage} stage must record modifiedFiles — actual paths, never prose or "n/a"`);
  }
  for (const f of files) {
    const p = String(f).trim();
    if (!p || /\s/.test(p) || !(p.includes('/') || /\.[a-z0-9]+$/i.test(p))) {
      die(`modifiedFiles entry is not a repo-relative path: "${f}"`);
    }
  }
  const missing = files.filter((f) => !fs.existsSync(path.join(root, String(f).trim())));

  const qualifier = String(e.qualifier ?? '').trim();
  if (qualifier && /[()]/.test(qualifier)) die('qualifier must not contain parentheses');
  const heading = qualifier ? `${e.stage} (${qualifier})` : e.stage;

  const field = (v) => { const s = String(v ?? '').trim(); return s || 'none'; };
  const block = [
    `## ${heading} — ${new Date().toISOString()}  [${marker}]`,
    `Outcome: ${outcome}`,
    `Decisions: ${field(e.decisions)}`,
    `Halts: ${field(e.halts)}`,
    `Follow-up CRs: ${field(e.followUps)}`,
    ...(files.length ? ['Modified files:', ...files.map((f) => String(f).trim())] : []),
    '',
  ].join('\n');

  const file = journalPath(root, change);
  if (!fs.existsSync(file)) fs.writeFileSync(file, `# Flow journal — ${change}\n\n`);
  else if (!fs.readFileSync(file, 'utf8').endsWith('\n\n')) fs.appendFileSync(file, '\n');
  fs.appendFileSync(file, block);

  console.log(`appended ${e.stage} [${marker}] to ${path.relative(root, file)}`);
  if (missing.length) {
    console.log(`  note: ${missing.length} path(s) not on disk (deleted files are fine): ${missing.join(', ')}`);
  }
  const prior = stageEntries(readJournal(root, change).headings).slice(0, -1);
  const bad = prior.filter((h) => h.kind === 'drifted');
  if (bad.length) console.log(`  warning: ${bad.length} earlier entr(ies) have unparseable headings — run \`check\``);
}

function cmdLast(root, change) {
  if (!change) die('last requires --change <name>');
  const { file, exists, headings } = readJournal(root, change);
  const entries = stageEntries(headings);
  const out = { change, journal: path.relative(root, file), exists };

  if (!exists || entries.length === 0) {
    out.resolvable = false;
    out.reason = exists ? 'journal has no stage entries' : 'no journal';
    out.fallback = 'infer from artifacts: openspec status --change <name> --json + tasks.md checkboxes';
  } else {
    const last = entries[entries.length - 1];
    if (last.kind === 'drifted') {
      // Do NOT skip back to an older parseable entry — an older stage would resume
      // in the wrong place, which is worse than admitting we cannot tell.
      out.resolvable = false;
      out.reason = `last entry's heading is unparseable (${last.reason}): ${last.line.trim()}`;
      out.fallback = 'infer from artifacts: openspec status --change <name> --json + tasks.md checkboxes';
    } else {
      out.resolvable = true;
      out.stage = last.stage;
      out.status = last.marker;
      out.timestamp = last.timestamp;
      out.headingKind = last.kind;
      out.resumeAt = resumeFrom(last);
    }
  }
  console.log(JSON.stringify(out, null, 2));
  process.exit(out.resolvable ? 0 : 1);
}

function summarize(root, change) {
  const { file, exists, headings } = readJournal(root, change);
  const entries = stageEntries(headings);
  const counts = { canonical: 0, tolerated: 0, drifted: 0 };
  for (const h of entries) counts[h.kind]++;
  const notAJournal = exists && entries.length === 0;
  return { change, file: path.relative(root, file), exists, entries, counts, notAJournal };
}

function cmdCheck(root, change) {
  if (!change) die('check requires --change <name>');
  const s = summarize(root, change);
  // Absence is NOT a check failure. `check` runs at every stage boundary, including the
  // first — before any entry exists — and on the ten in-flight changes that predate the
  // journal. Exiting non-zero there would trip the flow's hard-error halt on every run.
  // `audit` is where a missing journal is the finding.
  if (!s.exists) { console.log(`${change}: no journal yet at ${s.file} — nothing to validate`); process.exit(0); }
  if (s.notAJournal) {
    console.log(`✗ ${change}: ${s.file} has no stage headings — this is not a flow journal`);
    process.exit(1);
  }
  console.log(`${change}: ${s.entries.length} entr(ies) — ${s.counts.canonical} canonical, ${s.counts.tolerated} tolerated, ${s.counts.drifted} drifted`);
  for (const h of s.entries) {
    if (h.kind !== 'canonical') console.log(`  ${h.kind === 'drifted' ? '✗' : '~'} ${h.line.trim()}\n      ${h.reason}`);
  }
  const last = s.entries[s.entries.length - 1];
  if (last.kind !== 'drifted') console.log(`  last: ${last.stage} [${last.marker}] → resume at ${resumeFrom(last)}`);
  process.exit(s.counts.drifted > 0 ? 1 : 0);
}

function cmdAudit(root) {
  const dir = path.join(root, 'openspec', 'changes');
  const changes = fs.readdirSync(dir, { withFileTypes: true })
    .filter((d) => d.isDirectory() && d.name !== 'archive')
    .map((d) => d.name).sort();

  let missing = 0, drifted = 0, open = 0;
  console.log(`Flow journals — ${changes.length} active change(s)\n`);
  for (const c of changes) {
    const s = summarize(root, c);
    if (!s.exists) { console.log(`  ✗ ${c.padEnd(38)} no journal`); missing++; continue; }
    if (s.notAJournal) { console.log(`  ✗ ${c.padEnd(38)} file present but holds no stage entries`); missing++; continue; }
    const last = s.entries[s.entries.length - 1];
    if (last.kind === 'drifted') {
      console.log(`  ✗ ${c.padEnd(38)} ${s.entries.length} entries, last heading unparseable`);
      drifted++; continue;
    }
    if (last.marker === HALT) open++;
    const flag = s.counts.drifted ? '✗' : s.counts.tolerated ? '~' : '✓';
    if (s.counts.drifted) drifted++;
    console.log(`  ${flag} ${c.padEnd(38)} ${String(s.entries.length).padStart(2)} entries · last ${last.stage} [${last.marker}] → ${resumeFrom(last)}`);
  }
  console.log(`\n${changes.length - missing - drifted} clean · ${missing} missing · ${drifted} with drifted headings · ${open} halted awaiting a decision`);
  if (missing) console.log('A change with no journal has no audit trail. Backfill it, or start journalling from the next stage.');
  process.exit(missing || drifted ? 1 : 0);
}

// ---------------------------------------------------------------- follow-ups

const REGISTER_FILE = path.join('docs', 'ROADMAP.md');
const REGISTER_HEADING = /^##\s+Follow-up register/i;
const FIELD_LABELS = /^(Outcome|Decisions|Halts|Follow-up CRs|Modified files):/;

/** A match is textual, so a paraphrase does not count. Says so wherever the check fails —
 *  the first run of this command flagged a follow-up that WAS in the register, because the
 *  journal said "jurisdiction problem-type plumbing" and the register said
 *  `agreement-error-problem-type-plumbing`. Write the slug, not a description of it. */
const SLUG_HINT =
  'A follow-up counts as promoted only if its text contains the register slug verbatim.\n' +
  'Write the slug in the journal (`some-slug`), not a prose paraphrase of it.';

/**
 * Slugs already in the register, read from backticked tokens under its heading.
 *
 * A missing file or heading is fatal, never an empty set: an empty set would silently
 * reclassify every follow-up in the repo as unpromoted, which reads as a catastrophic leak
 * when the real fault is a moved path.
 */
function registerSlugs(root) {
  const file = path.join(root, REGISTER_FILE);
  if (!fs.existsSync(file)) die(`no follow-up register at ${REGISTER_FILE} — expected a "## Follow-up register" section there`);
  const lines = fs.readFileSync(file, 'utf8').split('\n');
  const start = lines.findIndex((l) => REGISTER_HEADING.test(l));
  if (start < 0) die(`${REGISTER_FILE} has no "## Follow-up register" heading — the register moved or was renamed`);
  const slugs = new Set();
  for (let i = start + 1; i < lines.length && !lines[i].startsWith('## '); i++) {
    for (const m of lines[i].matchAll(/`([a-z0-9]+(?:-[a-z0-9]+)+)`/g)) slugs.add(m[1]);
  }
  return slugs;
}

/** Every `Follow-up CRs:` block in one journal, with the stage heading it sits under. */
function followUpBlocks(root, change) {
  const file = journalPath(root, change);
  if (!fs.existsSync(file)) return [];
  const lines = fs.readFileSync(file, 'utf8').split('\n');
  const blocks = [];
  let stage = '(before any stage)';
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].startsWith('## ')) {
      const h = classifyHeading(lines[i]);
      stage = h.kind === 'other' ? lines[i].replace(/^##\s*/, '').trim() : h.stage;
      continue;
    }
    if (!lines[i].startsWith('Follow-up CRs:')) continue;
    // The field is written from free prose and may wrap, so take following lines until the
    // next labelled field or heading rather than assuming one line.
    const parts = [lines[i].slice('Follow-up CRs:'.length).trim()];
    for (let j = i + 1; j < lines.length; j++) {
      const nxt = lines[j];
      if (!nxt.trim() || nxt.startsWith('## ') || FIELD_LABELS.test(nxt)) break;
      parts.push(nxt.trim());
    }
    const text = parts.join(' ').trim();
    if (text && !/^none\b/i.test(text)) blocks.push({ stage, text });
  }
  return blocks;
}

function listChanges(root, { archived }) {
  const dir = path.join(root, 'openspec', 'changes');
  const active = fs.readdirSync(dir, { withFileTypes: true })
    .filter((d) => d.isDirectory() && d.name !== 'archive').map((d) => d.name).sort();
  if (!archived) return active;
  const archiveDir = path.join(dir, 'archive');
  const old = fs.existsSync(archiveDir)
    ? fs.readdirSync(archiveDir, { withFileTypes: true })
        .filter((d) => d.isDirectory()).map((d) => `archive/${d.name}`).sort()
    : [];
  return { active, archived: old };
}

/** Unpromoted = the follow-up text names no slug the register already holds. Deliberately a
 *  coverage check, not a slug extractor: these lines are free prose ("ci-pipeline (CR-7) —
 *  promote securityScan into CI"), and a tokenizer that guesses wrong is worse than a check
 *  that reports the line and lets a human judge. */
const unpromoted = (blocks, slugs) =>
  blocks.filter((b) => ![...slugs].some((s) => b.text.includes(s)));

function reportChange(change, blocks, slugs) {
  const bad = unpromoted(blocks, slugs);
  for (const b of bad) console.log(`  ✗ ${change}  [${b.stage}]\n      ${b.text}`);
  return bad.length;
}

function cmdFollowups(root, change) {
  const slugs = registerSlugs(root);

  if (change) {
    const blocks = followUpBlocks(root, change);
    if (!blocks.length) { console.log(`${change}: no follow-ups recorded — nothing to promote`); process.exit(0); }
    console.log(`${change}: ${blocks.length} follow-up entr(ies), register holds ${slugs.size} slug(s)\n`);
    const n = reportChange(change, blocks, slugs);
    if (n) {
      console.log(`\n${n} follow-up(s) not in ${REGISTER_FILE}. Promote them BEFORE archiving —`);
      console.log('the journal moves into openspec/changes/archive/ with the change.');
      console.log(SLUG_HINT);
    } else {
      console.log('  ✓ every recorded follow-up names a slug already in the register');
    }
    process.exit(n ? 1 : 0);
  }

  const { active, archived } = listChanges(root, { archived: true });
  console.log(`Follow-ups vs ${REGISTER_FILE} — register holds ${slugs.size} slug(s)\n`);

  console.log(`Active changes (${active.length}):`);
  let openLeak = 0;
  for (const c of active) openLeak += reportChange(c, followUpBlocks(root, c), slugs);
  if (!openLeak) console.log('  ✓ none unpromoted');

  // Historical entries are reported but never fail the command: they are already archived,
  // so they can only be mined, not fixed in place. A permanently-red sweep is a gate people
  // learn to ignore.
  let past = 0;
  const rows = [];
  for (const c of archived) {
    const bad = unpromoted(followUpBlocks(root, c), slugs);
    past += bad.length;
    for (const b of bad) rows.push(`  ~ ${c}  [${b.stage}]\n      ${b.text}`);
  }
  console.log(`\nArchived changes (${archived.length}) — historical, informational only:`);
  console.log(rows.length ? rows.join('\n') : '  ✓ none unpromoted');

  console.log(`\n${openLeak} unpromoted on active changes · ${past} unpromoted in the archive`);
  if (openLeak) console.log(`Promote the active ones into ${REGISTER_FILE} before those changes archive.\n${SLUG_HINT}`);
  process.exit(openLeak ? 1 : 0);
}

// ---------------------------------------------------------------- entry

const argv = process.argv.slice(2);
const cmd = argv[0];
const flag = (name) => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : undefined; };
const root = repoRoot(flag('--repo'));
const change = flag('--change');

switch (cmd) {
  case 'append': cmdAppend(root, change); break;
  case 'last':   cmdLast(root, change); break;
  case 'check':  cmdCheck(root, change); break;
  case 'audit':  cmdAudit(root); break;
  case 'followups': cmdFollowups(root, change); break;
  default:
    console.log('usage: flow-journal.mjs <audit | append | last | check | followups> [--change <name>] [--repo <path>]');
    process.exit(cmd ? 2 : 0);
}
