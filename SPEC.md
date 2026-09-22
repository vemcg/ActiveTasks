# ActiveTasks Spec

A traditional to-do list, companion to MicroTasking, sharing MicroTasking's Google Sheet as its
list/item source. Unlike a plain mirror of the sheet, an item only reaches ActiveTasks once it's been
explicitly **referred** here from MicroTasking's task queue - see "Referral bridge" below for the
full lifecycle, negotiated directly between this repo's and MicroTasking's Claude Code sessions
and confirmed by the user on both sides (2026-09-18).

## Lists

- Each Google Sheet tab (excluding a README tab) is one to-do list, named after the tab.
- A tab with zero rows still counts as a live list (`known_lists`, refreshed on every successful
  sync) - but it only gets a page in the carousel once it has at least one referred (non-done)
  item; a tab with nothing currently referred to it is skipped entirely rather than showing up
  empty (see "Screens"). `known_lists` still distinguishes "never synced" from "synced, nothing
  referred anywhere" for the carousel's empty-state message.

## Items

- **Ingestion is gated on referral.** A Sheet-sourced item only enters ActiveTasks once its hidden
  `Importance`/`Urgency` columns are populated - checked via the Apps Script Web App at sync time,
  never via the CSV/gviz export (see "Referral bridge"). A checked-but-unreferred row stays
  MicroTasking-only and never appears here at all; column A (enabled checkbox) and
  `description`/`link` (header-text matched, order-independent) are still read via the existing
  CSV/gviz path once a row does qualify.
- **A list holds only items that carry a priority** - either referred from MicroTasking, or added
  here with **Add item** (see "Screens"), which writes the row with its priority already set so it
  is never a MicroTasking pool task. There is no unprioritized "inbox" and no local-only item: a
  fresh install, or one where nothing has been prioritized yet, **comes up blank** (every list
  empty). ActiveTasks is a companion to the shared Sheet, not a standalone to-do app (standalone was
  considered and dropped 2026-09-19); a row typed into the Sheet by hand is a MicroTasking pool
  task, not an ActiveTasks item, by design. (An earlier design allowed ad-hoc `local-` items; removed at
  the user's direction 2026-09-18.)
  Stored items are versioned (`ITEMS_SCHEMA_VERSION`): anything saved by an older schema - the
  pre-referral scaffold imported every checked row - is discarded on first launch of a newer
  build, and `local-` items are never loaded. The next sync re-imports only what is genuinely
  referred.
- Re-syncing the sheet only **adds** newly-qualifying rows not already present by id. It never
  removes or overwrites an existing item because its sheet row disappeared, its priority got
  cleared elsewhere, or the description changed underneath it (a changed description is a new id,
  so it reads as a new item, not an edit to the old one) - **except** the app's own "Complete (for
  now)" action (see "Referral bridge"), which removes its own item immediately rather than waiting
  for a resync. Rationale: an item already living in this to-do list may be mid-progress - a
  spreadsheet edit made somewhere else shouldn't silently delete it.

## Priority: Eisenhower matrix

Priority is two independent continuous values, `importance` and `urgency` (each `0f..1f`), not
four fixed quadrants and not booleans. Set by the exact tap/drag position on a square matrix
widget (`MatrixWidget`) - top-left is most important+urgent ("Do First"), matching the visual
layout below, just continuous instead of four discrete cells:

|                | **Urgent**     | **Not urgent** |
|----------------|-----------------|-----------------|
| **Important**     | Do First        | Schedule        |
| **Not important** | Delegate        | Eliminate       |

Display order is a computed score, `importance * importanceWeight + urgency`, descending, ties
broken by add time (older first). **`importanceWeight` is a user Settings value, not a hardcoded
formula** - MicroTasking always writes raw, unweighted importance/urgency; the weighting that
turns those into a ranking is entirely ActiveTasks's concern, adjustable in Settings via the
"Priority & Lists" slider (default `2.0`, one stop toward "Importance" - the old fixed
`important*2 + urgent` ratio as a starting point; five stops, `0.25`-`4.0`, doubling/halving from
`1.0` at center - see "Screens"). The formula and the number are never shown to the user; the
slider only ever shows which of "Importance"/"Urgency" currently counts for more, by font size.

`Quadrant`/`quadrant()` still exist as a coarse 0.5-threshold bucketing of the continuous values -
the real ranking always uses the continuous score above. Not shown as a badge on an item's card
(removed 2026-09-22: the "Do First" text and its orange color, read by more than one user as the
item's status, were just this label/color, and told the user nothing progress didn't already);
`quadrantColor` still tints `MatrixWidget`'s four reference-quadrant backgrounds.

ActiveTasks keeps its **own** matrix widget too (same continuous behavior, not the old 4-quadrant tap),
used for **re-triaging** an item after it arrives (open "Priority & progress", drag the marker).
Re-triage **writes through** to the Sheet (`setPriority`, see MicroTasking's `SPEC.md` "Sheet
connection & API"), so MicroTasking and any second device see the new priority. The local copy
updates immediately either way; if the write fails the item keeps its new local priority and the
error is shown (same failure handling as the other write-backs). Progress stays local-only.

Since ingestion is gated on referral, an item never arrives untriaged - referral itself requires
a matrix touch on MicroTasking's side.

## Referral bridge (MicroTasking → Sheet → ActiveTasks)

1. User adds a task to a Sheet tab (existing MicroTasking behavior).
2. MicroTasking selects it into its task queue (existing behavior).
3. From the queue, **at any time and in any task state** (not gated to pre-Start - this was
   negotiated between both apps' sessions and confirmed directly by the user after an initial
   disagreement between the two sessions' interpretations), the user hits a "Refer to ActiveTasks"
   action. Referring an already-`Started` task discards its in-progress timer as a neutral outcome
   - like Defer, it counts as neither Complete nor Abandoned.
4. Referral shows MicroTasking's continuous Eisenhower-matrix widget; the exact touch point
   becomes two raw floats (`importance`, `urgency`), written via the shared Apps Script Web App
   (below) to two columns to the right of the sheet's normal columns (A-C: checkbox, description,
   link). Those two columns are **hidden and Protected-Range-restricted** in the Sheets UI -
   columns A-C stay normal and user-editable as always.
5. Any row with importance/urgency set is excluded from MicroTasking's own queue selection going
   forward - checked against the Apps Script-sourced state, not just a local flag, so it's correct
   even from a second device or fresh install.
6. ActiveTasks syncs (manual "Sync Lists", every time the app comes to the foreground, and right
   after its own Complete (for now) / Fully complete; see MicroTasking's `SPEC.md` "Sync cadence"
   for the in-flight-sync handling), reading each tab's plain columns via the
   existing CSV/gviz export and each tab's importance/urgency via the Apps Script endpoint only
   (CSV/gviz export includes hidden columns' raw values regardless of Sheets-UI hidden state,
   which would defeat "invisible to the user"). Fetching happens at the app's normal sync
   boundaries (the triggers above + any future periodic sync) and the result is persisted
   locally between syncs - no new per-action/live network dependency beyond that.
7. From an item's card, the user can set a 0-100% progress value (via "Priority & progress";
   ActiveTasks-local only, never written to the Sheet - a `Progress` column was considered and
   dropped, since linked removal below already prevents any staleness a synced column would have
   solved) and either:
   - **Complete (for now)**: clears the row's importance/urgency via the Web App's clear-priority
     endpoint (freeing it back up for MicroTasking's queue) and removes ActiveTasks's own local copy of
     the item immediately - linked removal, not waiting for a resync.
   - **Fully complete**: deletes the row entirely via the Web App's delete-row endpoint (same
     shift-up-rows convention MicroTasking's own `onEdit` description-clear already uses) and
     removes ActiveTasks's local copy.

**Bridge mechanism**: a single Apps Script Web App, owned and deployed by MicroTasking's repo
(extends the already-bound `scripts/populate_google_sheet.js`, `doGet`/`doPost` endpoints). Both
apps call it over plain HTTPS - no OAuth, no Google Cloud project change, in either app.

**Row identity** for every Apps Script call is `(tab name, description text)`, resolved
server-side by the script - never a cached row index, since MicroTasking's existing row-delete
logic shifts rows up and would make a cached index unsafe.

**Superseded 2026-09-19:** the full request/response contract - connection code with a secret key,
`hello`/`getTasks`/`createRow`/`createTab`/`setPriority`/`clearPriority`/`deleteRow`, error codes,
hand-edit tolerance - now lives in MicroTasking's `SPEC.md` "Sheet connection & API" and is not
restated here. What follows is the original v1 contract, which is still what a script that hasn't
been redeployed speaks.

**Confirmed contract, v1** (2026-09-18, as deployed in MicroTasking's `scripts/populate_google_sheet.js`):
- `GET {webAppUrl}?action=getPriorities` → `{"ok":true,"rows":[{"category","description",
  "importance","urgency"}, ...]}` - every currently-referred row across every tab in **one** call,
  not one call per tab. ActiveTasks groups the result by `category` (tab name) itself.
- `POST {webAppUrl}` with a JSON body `{"action":"setPriority"|"clearPriority"|"deleteRow",
  "category","description",["importance","urgency" for setPriority]}` → `{"ok":true}` or
  `{"ok":false,"error":"..."}`.
- The Web App is deployed **per-user**, from the same Sheet-bound Apps Script editor each user
  already has (Deploy → New deployment → Web app, Execute as Me, Anyone with the link) - a
  one-time manual step, documented in MicroTasking's README/setup instructions. ActiveTasks's Settings
  screen has a manual paste field for the resulting URL (see "Screens"). The setup QR that carries
  it is now specified as a single **connection code** (Web App URL plus secret key) shared with
  MicroTasking - see MicroTasking's `SPEC.md`; not yet built on this side.

ActiveTasks's client (`SheetApiClient.kt`: `fetchAllPriorities`, `clearSheetPriority`, `deleteSheetRow`)
implements this contract.

## Screens

1. **Settings** - app bar title is "Settings" (with a back icon once `canGoBack`, i.e. once at
   least one Sheet sync has ever completed). Below it, three collapsible accordion cards (at most
   one open at a time), same accordion pattern and section-header styling as MicroTasking's
   Settings screen (`sectionHeader`), in this order:
   - **"Priority & Lists"** - a 5-stop, no-numbers slider: "Importance" and "Urgency" labels sit at
     opposite ends, and the label whose side you slide toward grows (the other shrinks) - centered
     is equal, deliberately with no visible formula or weight number (`priorityTiltFontSize`,
     `PRIORITY_TILT_WEIGHTS` in `MainActivity.kt`). Each stop just doubles/halves
     `ToDoItem.importanceWeight` from its neighbor (`4.0, 2.0, 1.0, 0.5, 0.25`; unchanged
     `DEFAULT_IMPORTANCE_WEIGHT = 2.0`, one stop left of center - importance favored slightly out of
     the box, same ratio as before this UI existed). A persisted weight that isn't exactly one of
     the five stops (from an older build, or a value some other client wrote) snaps to the nearest
     one when Settings opens. Below it, items-per-list count (`1`-`10`, default `5`, a single
     global setting).
   - **"Google Sheet Connection"** - open by default only when the Sheet URL isn't set yet (a
     not-yet-connected setup); once connected, nothing pre-opens, since it's no longer the section
     most visits need. Identical in layout and wording to MicroTasking's section of the same name
     (only "list" vs "task category" and what the Web App is for differ): why-a-Sheet-URL text,
     *Google Sheet URL* box, **Scan Sheet QR Code**, why-a-Web-App text, *Apps Script Web App URL*
     box (to become the **connection code**: Web App URL plus secret key, shown masked and never
     logged in full), **Scan Web App QR Code**, then the one action button, **Sync Lists**
     (disabled while syncing or with a blank Sheet URL), and its status message. Both scan buttons
     open the same scanner and route the result by content (`parseSetupQr`), never by which button
     was pressed. A bare Sheet URL is no longer enough: ActiveTasks can't work without priorities,
     so it needs the connection code.
   - **"About"** - version (`BuildConfig.VERSION_BASE`-`BUILD_NUMBER`) and build metadata
     (timestamp, git short SHA, git branch), same content and layout as MicroTasking's "About"
     section.

   Every field is a local draft: nothing reaches persisted settings until **Save Settings**
   (bottom action bar, alongside **Cancel** - disabled until `canGoBack`, same gate as the back
   icon), except **Sync Lists**, which always commits the two URL fields it just used (syncing
   without saving the URL that produced the result would silently stop working the next time the
   app opens) and **Scan Sheet/Web App QR Code**, which save immediately as before (scanning exits
   to a separate screen; this screen remounts with the new values as its starting draft on return).
2. **Carousel** (home screen, and the *only* list screen - there is no overview of lists with
   counts, and no separate "see everything" list-detail view) - the app bar title is always
   "ActiveTasks" (MicroTasking-style, a fixed app-name title rather than changing per page); the
   current list's name (e.g. "Must Do") is a headlineMedium sub-header underneath it. A horizontal,
   swipeable page **per list that currently has at least one referred item** - a list with nothing
   referred to it gets no page and never appears, so the page-indicator dots only ever count lists
   with something in them. Each page shows that list's top N open items by priority score.
   - **No lists have anything referred yet** (whether or not the Sheet has been synced): instead of
     the carousel, a single centered message - "No lists yet. Open Settings and sync your Google
     Sheet to get started." before the first sync, or "No tasks referred yet." after a sync that
     found no referred rows anywhere.
   - **Which page it opens on** (`initialListName`): the list the user last swiped to (persisted
     as `last_list`); if they never have, the list whose top open item has the highest priority
     score (earlier list wins ties; with nothing referred anywhere, the first list). Only a page
     the user swiped to is recorded as "last opened" - the page it merely opened on is not, so the
     highest-priority fallback keeps applying until they navigate.
   - **Each item is a card**, MicroTasking-style: the task text, then **Open link** (full-width,
     only when the row has one), then its bottom two rows - "Progress: N%" beside its **Priority &
     progress** button (opens the dialog below), then **Complete (for now)** beside **Fully
     complete** underneath (no quadrant badge - see "Priority model" above). No checkbox and no
     trash/delete icon. A failed Sheet write leaves the item in place and shows the error above the
     list.
3. **Priority & progress** (dialog, from an item's card) - the continuous matrix widget
   (re-triage in place, written through to the Sheet) and a progress slider (local-only).
4. **Add item** (dialog, from an **Add item** button on the carousel) - pick one of the existing
   lists, type the item (optional link), touch the matrix to set its priority; on confirm it is
   written with `createRow` (priority included) and appears in the list immediately. Lists are
   never created here - new tabs are made in the Sheet (or by MicroTasking's add). Exact layout
   TBD at build time.
5. **QR scanner** - scans the setup QR with the same `parseSetupQr` classification MicroTasking
   uses (a line containing `script.google.com/` is the connection code; anything else is a legacy
   Sheet URL and is ignored here). It replaces today's behavior of dropping the raw scanned text
   into the Sheet-URL field.

## Not implemented / explicitly out of scope for now

- **On-device verification of the referral round-trip.** Both apps' code is written and compiles;
  neither side has been exercised end-to-end against a real deployed Web App yet. See
  PUNCH_LIST.md item 1.
- **Connection-code QR** (one code for both apps), **Add item**, re-triage write-through, and
  reading via `getTasks` - all specified in MicroTasking's `SPEC.md` "Sheet connection & API", none
  built here yet. Until then the Sheet is still read through the public export. (The scanner half
  is built: `parseSetupQr` classifies each scanned line as Web App URL or Sheet URL, so the
  onboarding page's separate Sheet and Web App QRs each set only their own field.)
- Background reminders/notifications - this is a pull list, not a nudger; no alarms, no
  permissions beyond internet/camera.
- Bulk-editing a list's sheet-backed items, reordering lists, or a *user-visible* priority column
  in the Sheet itself (the hidden/protected importance/urgency columns are internal plumbing, not
  a user-facing feature).
- Per-list top-N override (currently one global setting only).

## Open questions (for later)

- Failure-state UX when a write-back call (complete-for-now, fully-complete, or the referral write
  itself on MicroTasking's side) fails (offline, Web App misconfigured/undeployed, etc.) - current
  behavior is "show an error message, leave local state unchanged, let the user retry"; whether
  that's sufficient or needs queue-and-retry is undecided.
- Exact importance-weight default/range and items-per-list default/range above are a first
  proposal, not user-validated in practice.
