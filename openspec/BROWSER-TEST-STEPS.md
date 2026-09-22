# Browser test steps -- do these in the app, no setup needed

App: **http://localhost:5174** (already running). No passwords, no accounts, no terminal.

You are checking three things. Each has a **PASS looks like** line. If something doesn't match, stop
and note what you saw -- that's the useful outcome, not a failure on your part.

Roughly 20-30 minutes total.

---

## Test 1 -- does the live preview keep up? (~5 min)

1. Open the app and start a new agreement.
2. Fill in a few normal fields (names, address, rent).
3. Add an **optional section** (the "add optional" list in the left rail).
4. Type something into a field inside that new section.

**PASS looks like:** the preview on the right updates as you type, and the optional section you added
shows up in it with your text.

**Note anything like:** preview goes blank, preview ignores the new section, preview lags and never
catches up, or an error appears.

---

## Test 2 -- does the PDF match the preview? (~5 min)

Straight after test 1, without changing anything:

1. Click **Download PDF**.
2. Open the PDF next to the on-screen preview.

**PASS looks like:** they show the same thing. The optional section and the field you typed appear in
**both**.

**This is the important one.** If the preview and the PDF disagree, that's a real bug -- customers
sign the PDF, but they decide based on the preview. Note exactly what differs.

---

## Test 3 -- does it work on a phone? (~10 min)

Same page. Either open it on your phone, or narrow the browser window until it goes one-column.

1. Tap a section to edit it -- an editor should slide up and fill the screen.
2. While it's open, press **Tab** a few times.
3. Press **Esc**.

**PASS looks like:**
- the editor opens full-screen (not a cramped box)
- Tab stays *inside* the editor -- it should not jump to things behind it
- Esc closes it

**Note anything like:** editor opens tiny or cut off, Tab escapes to the background page, Esc does
nothing.

---

## Test 4 -- staff screen (~5 min, only if you can sign in as staff)

Skip this if you don't already know how to get a STAFF login -- tell me and I'll find out.

1. Sign in as STAFF and open **/staff**.
2. Look at a real row in the queue.

**PASS looks like:** the row shows the template name, the state, and **both people's names including
their father's names**. The stamp upload still works from that row.

---

## When you're done

Tell me which tests passed and paste anything odd you saw. I'll tick the matching boxes and note the
evidence -- you don't need to touch the OpenSpec files.

**One thing I can't ask you to do yet:** anything needing **Sign in with Google**. That's pointed at a
placeholder credential right now, so it will fail for reasons that have nothing to do with these
tests. Test 1's "save and reopen it" half is parked for the same reason -- do the rest, skip that.
