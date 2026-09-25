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
- **The Sheet is definitive** (changed 2026-09-24 - see "Synchronization"). Every successful sync
  rebuilds the item set from the referred rows it read: a row deleted or un-referred in the Sheet
  makes its item vanish, and description/link/list/priority follow the Sheet. Only progress stays
  local. The app's own Complete (for now)/Fully complete remove the item immediately and queue the
  Sheet write. (Before 2026-09-24 a resync only ever *added* items and never removed one; that
  policy, and the "Remove locally" escape hatch it needed, are gone.) Duplicate-description rows
  without a `taskId` still collide - see PUNCH_LIST.md item 4.

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
connection & API") when the "Priority & progress" dialog closes with a moved marker - through the
pending-changes queue, so an offline change is retried rather than lost, and a sync in between
keeps the new local value. Progress stays local-only.

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
6. ActiveTasks syncs (every time the app comes to the foreground, and on Save Settings with
   changed connection details - see "Synchronization"; a referral message from MicroTasking adds
   the item without waiting for a sync), reading each tab's plain columns via the
   existing CSV/gviz export and each tab's importance/urgency via the Apps Script endpoint only
   (CSV/gviz export includes hidden columns' raw values regardless of Sheets-UI hidden state,
   which would defeat "invisible to the user"). Fetching happens at the app's normal sync
   boundaries (the triggers above + any future periodic sync) and the result is persisted
   locally between syncs - no new per-action/live network dependency beyond that.
7. From an item's card, the user can set a 0-100% progress value (via "Priority & progress";
   ActiveTasks-local only, never written to the Sheet - a `Progress` column was considered and
   dropped, since linked removal below already prevents any staleness a synced column would have
   solved) and either:
   - **Complete (for now)**: removes the item immediately and queues the Web App's clear-priority
     call (freeing the row back up for MicroTasking's queue); once it lands, MicroTasking is told
     directly (`completedForNow`).
   - **Fully complete**: removes the item immediately and queues the delete-row call (same
     shift-up-rows convention MicroTasking's own `onEdit` description-clear already uses); once it
     lands, MicroTasking is told (`fullyCompleted`).
   - A queued write that comes back confirming the row no longer exists
     (`SheetWriteOutcome.RowNotFound`) is simply dropped - the row being gone is what a completion
     wanted. See "Synchronization" > "Pending-changes queue".

**Bridge mechanism**: a single Apps Script Web App, owned and deployed by MicroTasking's repo
(extends the already-bound `scripts/populate_google_sheet.js`, `doGet`/`doPost` endpoints). Both
apps call it over plain HTTPS - no OAuth, no Google Cloud project change, in either app.

**Row identity** for every Apps Script call is `(tab name, description text)`, resolved
server-side by the script - never a cached row index, since MicroTasking's existing row-delete
logic shifts rows up and would make a cached index unsafe. A row that carries MicroTasking's
per-row surrogate `taskId` (`ToDoItem.taskId`, added `6113146`) is looked up by that id instead,
server-side (`findRowByTaskId_`) - immune to a description rename *and* a tab rename/move, since
the lookup ignores `category`/`description` entirely once a `taskId` is sent. A row from a
sheet/script that predates `taskId` still resolves the old way and is still vulnerable to a rename
producing a "no such row" response - see the `RowNotFound` handling above and PUNCH_LIST.md
"Harden against user edits to the shared Sheet".

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
     most visits need. Whether it's open persists across a QR scan round trip and across
     navigating away and back to Settings within the same app session (the accordion state is
     hoisted above the screen, not local to it) - scanning the Sheet QR code, then the Web App QR
     code, then pressing Save Settings all happen without the section auto-collapsing in between; the
     user closes it manually, or by opening a different section, same as any other accordion
     interaction. Identical in layout and wording to MicroTasking's section of the same name
     (only "list" vs "task category" and what the Web App is for differ): why-a-Sheet-URL text,
     *Google Sheet URL* box, **Scan Sheet QR Code**, why-a-Web-App text, *Apps Script Web App URL*
     box (to become the **connection code**: Web App URL plus secret key, shown masked and never
     logged in full), **Scan Web App QR Code**, then the last sync's status message. There is no
     Sync button (removed 2026-09-24): saving changed connection details is what syncs. Both scan
     buttons open the same scanner and route the result by content (`parseSetupQr`), never by
     which button was pressed; a scan only fills the draft. A bare Sheet URL is no longer enough: ActiveTasks can't work without priorities,
     so it needs the connection code.
   - **"About"** - version (`BuildConfig.VERSION_BASE`-`BUILD_NUMBER`) and build metadata
     (timestamp, git short SHA, git branch), same content and layout as MicroTasking's "About"
     section.

   Every field is a draft (hoisted above the screen, so it survives the QR scanner round trip):
   nothing - scans included - reaches persisted settings until **Save Settings** (bottom action
   bar, alongside **Cancel** - disabled until `canGoBack`, same gate as the back icon). Saving with
   a changed Sheet URL or Web App URL shows **Syncing…** and returns to the carousel when the sync
   succeeds, or stays on Settings with the error (settings stay saved). A changed Sheet URL first
   discards all local state for the old Sheet. Saving with the connection unchanged just returns.
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
     trash/delete icon. Both complete buttons act immediately; the Sheet write is queued. While
     queued writes have failed to send, a quiet "N changes waiting to reach your Sheet" line shows
     under the list name (or under the empty-state message).
3. **Priority & progress** (dialog, from an item's card) - the continuous matrix widget
   (re-triage in place, written through to the Sheet when the dialog closes) and a progress slider
   (local-only).
4. **Add item** (dialog, from an **Add item** button on the carousel) - pick one of the existing
   lists, type the item (optional link), touch the matrix to set its priority; on confirm it is
   written with `createRow` (priority included) and appears in the list immediately. Lists are
   never created here - new tabs are made in the Sheet (or by MicroTasking's add). Exact layout
   TBD at build time.
5. **QR scanner** - scans the setup QR with the same `parseSetupQr` classification MicroTasking
   uses (a line containing `script.google.com/` is the connection code; anything else is a legacy
   Sheet URL and is ignored here). It replaces today's behavior of dropping the raw scanned text
   into the Sheet-URL field.

## Synchronization

*Agreed in conversation with the user 2026-09-24, including the answers under "Decisions" at the
end of this section; built the same day on branch `synchronization-improvements`
(`TaskStore.kt`, `TaskEvents.kt`). The sections listed under "What this replaced" below have been
updated to match. Not yet verified on-device against MicroTasking's side of the message contract.*

**Goal**: sync is invisible and automatic. **The Sheet is the definitive holder of reality**; each
app's saved copy is a cache that is shown instantly and then corrected from the Sheet. The two apps
also message each other directly on the device so an activation or completion in one shows up in
the other within seconds, without waiting for a Sheet read.

### What the Sheet owns vs. what stays local

- **Sheet-owned** (always overwritten from the Sheet on sync): whether an item exists at all (a
  referred row = an item; no referred row = no item), its description, link, list (tab name),
  `taskId`, and its importance/urgency. A row deleted or un-referred in the Sheet - by hand or by
  either app - simply vanishes from ActiveTasks at the next sync. No "Remove locally", no orphaned
  state.
- **Re-triage writes back**: changing an item's priority in "Priority & progress" updates the local
  copy at once and queues a `setPriority` write (pending-changes queue below), so the Sheet stays
  definitive for importance/urgency too. No cross-app message for it - MicroTasking only writes
  priority at referral time and never displays it.
- **Local, transient to one activation**: progress (plus `addedAt`, kept so display order stays
  stable across syncs). Discarded when the item leaves (completion, or its row disappearing); a
  re-referral later is a new activation and starts at 0%.

### When a sync runs

1. **Every time the app becomes visible** (`ON_START` - a cold launch, returning from another app,
   unlocking the screen, entering split screen). The saved copy is on screen immediately and
   corrected when the read returns.
2. **Right after each local action** (Complete for now / Fully complete / re-triage), as part of
   flushing the pending-changes queue below.
3. **Settings > Save Settings when the Sheet URL or the Web App URL actually changed** - a full
   resync against the new connection. Saving with the URLs unchanged does not sync. **The Sync
   Lists button is removed**; this is the only manual path.
4. **Not** on a timer. The only background work is the one-shot network-return flush of the
   pending-changes queue (below) - no periodic job, no notifications, so ActiveTasks stays a pull
   app. An incoming message from MicroTasking applies its single change without a Sheet read.

**Switching to a different Sheet** (a different spreadsheet id saved - compared by id, not URL text, so the same Sheet pasted in another URL form doesn't count; a blank URL never counts; `isDifferentSheet`): everything local tied to the old
Sheet is thrown away before the resync - items, `known_lists`, `last_list` and the pending-changes
queue. The user doesn't move back and forth between Sheets, so nothing is carried over. (A Web App
URL change alone, same Sheet, is a redeploy of the same script and keeps local state.)

Only one sync runs at a time; a trigger that fires mid-sync schedules exactly one follow-up run.

### What a sync does

1. **Flush** the pending-changes queue (below), oldest first.
2. **Read** the Sheet: tab list, each tab's rows, and the referred rows via the Web App.
3. **Rebuild** the item set from what was read: every referred row becomes an item; an existing
   local item with the same id keeps its local fields (importance/urgency/progress/addedAt) and
   takes the Sheet's description/link/list; a local item with no referred row is removed. Anything
   still in the pending queue is then laid on top (an item with a queued completion stays removed
   even though the Sheet still shows it referred; an item with a queued `setPriority` keeps its
   new local priority). The existing legacy-id → `taskId` migration in
   `mergeImportedToDoItems` is kept.
4. **All or nothing**: the rebuild happens only if the whole read succeeded. Any failure - network,
   Web App error, one tab's fetch failing - changes nothing locally. **Prerequisite**:
   `fetchAllPriorities` currently turns every failure into an empty list, which under this design
   would read as "nothing is referred" and wipe every item. It must report failure distinctly
   (done: `fetchAllPriorities` and `fetchSheetTabs` return null on any failure).

### Pending-changes queue

- Every Sheet write goes through a queue persisted in SharedPreferences (survives the app being
  killed). Entries: the operation (`clearPriority` for Complete for now, `deleteRow` for Fully
  complete, `setPriority` for re-triage - and `createRow` once Add item exists), `taskId`,
  category, description, importance/urgency for `setPriority`, and when it was queued. A newer
  `setPriority` for the same item replaces an older unsent one; a completion for an item drops any
  unsent `setPriority` for it.
- The action takes effect locally at once, is queued, and a flush starts immediately. Flushes also
  run at the start of every sync.
- Outcome of sending one entry:
  - **Success** → removed from the queue; the cross-app message for it, if any, is sent now - never
    before the Sheet has the change.
  - **Row not found** → removed from the queue. For a completion the row is already gone, which is
    what it wanted; for a `setPriority` the item is gone from the Sheet and the next sync drops it.
    Nothing to show the user.
  - **Network/server failure** → stays queued. Later entries still get attempted (entries target
    different rows, so one stuck entry doesn't block others).
- **Retry when the network returns**: whenever a flush leaves entries behind, a one-shot WorkManager
  job with a "network connected" constraint is scheduled (replacing any already scheduled). It
  flushes the queue even if ActiveTasks isn't open, sends the messages for whatever succeeds, and
  reschedules itself only if entries remain. Not periodic, no notification.
- The queue is what keeps an offline change from being "corrected" back by the next sync.
- **Stuck changes are visible**: while the queue is non-empty after a failed flush, the carousel
  shows a quiet line under the list name - "N changes waiting to reach your Sheet". It disappears
  as soon as the queue empties. Otherwise sync shows nothing (no spinner on the foreground sync).

### Messages between the two apps

- **Events** (set-style, carrying a timestamp, so receiving one twice or late is harmless):
  - MicroTasking → ActiveTasks: **referred** (`taskId`, category, description, link, importance,
    urgency) - ActiveTasks adds the item.
  - ActiveTasks → MicroTasking: **completedForNow** (`taskId`, category, description) - MicroTasking
    clears `referredAt` so the task is back in its pool; **fullyCompleted** - MicroTasking drops
    the task.
  - Later: **created**, from ActiveTasks's Add item.
- **Receiving**: write the single change into the saved copy; if the app is on screen, update that
  one card in place. No network call in the receiver. Works whether the receiver is on screen, in
  the background, or not running at all (Android starts the receiving app's process briefly).
- **Sent after the Sheet write succeeds**, not at the moment of the action: the other app then
  never shows a change its own next sync would contradict. Costs the Web App round trip (typically
  1-3 s); an offline change reaches the other app when the queue flushes.
- **Transport**: an explicit broadcast addressed to the other app's package
  (`com.microtasking.app` ↔ `com.activetasks.app`). Each app declares the other in `<queries>`;
  the receiver is `exported="true"` and requires a signature-level permission, so only an app
  signed with the same key can send to it.
- **Message contract, v1** (identical in both apps' code; change only in step):
  - Permission, declared by **both** apps (`<permission android:protectionLevel="signature">` plus
    `<uses-permission>`, so install order doesn't matter):
    `com.vernmcgeorge.tasks.permission.TASK_EVENTS`.
  - Receivers, manifest-declared, `exported="true"`, `android:permission=` the above:
    `com.activetasks.app.TaskEventReceiver` and `com.microtasking.app.TaskEventReceiver`.
  - Sent with an explicit component (`Intent.setComponent(ComponentName(<other package>, <other
    receiver class>))`), action `com.vernmcgeorge.tasks.action.TASK_EVENT`, via
    `sendBroadcast(intent, <permission>)`. Each app's manifest has `<queries><package
    android:name="<other package>"/></queries>`. Sending never throws out to the UI; an
    uninstalled or unreachable receiver is simply skipped.
  - Extras: `schemaVersion` (Int, `1`; a receiver ignores a higher version it doesn't know),
    `event` (String: `referred` | `completedForNow` | `fullyCompleted`), `eventAtEpochMs` (Long),
    `taskId` (String, may be absent for a pre-`taskId` row), `category` (String, tab name),
    `description` (String); for `referred` also `link` (String, may be empty), `importance` and
    `urgency` (Double, raw 0..1).
  - Matching on receipt: by `taskId` when present, else by `category` + `description`, same as the
    Web App's row identity.
- **Shared signing key**: ActiveTasks switches to MicroTasking's checked-in debug keystore (copied
  into this repo, replacing ActiveTasks's own). Android refuses to update an app across a key
  change, so every existing ActiveTasks install must be uninstalled once before installing the
  first shared-key build; only local progress is lost (items and priorities come back from the
  Sheet). The release notes / install page for that build must say so.
- **Fast path only**: a message can be missed (app force-stopped, reinstalled, aggressive OEM
  battery management). Nothing depends on it for correctness - the sync each time the app becomes
  visible catches whatever was missed.

- **Agreed with MicroTasking's session (2026-09-24)**: `eventAtEpochMs` is the time of the user's
  action (the tap, i.e. when the change was queued), not of the eventual Sheet write. Each
  receiver ignores a message older than its own newer state: MicroTasking drops a completion older
  than the task's latest referral; ActiveTasks drops a referral at or before its own completion of
  that item (`recent_completions`, kept 7 days). Matching is by `taskId` only when both sides have
  one; otherwise category + description.

### MicroTasking side

Specified and built in MicroTasking's own repo, per the message above: foreground sync, its own
persisted queue for referral writes, a receiver for completedForNow/fullyCompleted, a sender for
referred, and the matching `<queries>`/permission declarations. MicroTasking keeps its own
Settings flow ("Update Tasks" button, scans saved directly) at the user's direction in that
session - decisions 6, 8 and 9 below are ActiveTasks-only.

### What this replaced

- "Items": re-sync "only adds, never removes or overwrites" → the Sheet is definitive.
- "Priority": re-triage write-through stays, but goes through the pending-changes queue instead of
  "show the error, keep the local value".
- "Referral bridge" step 7 and "Screens" item card: the orphaned state and **Remove locally** → gone;
  a missing row just drops the item.
- "Screens" > Settings: **Sync Lists** button → removed; Save Settings with changed connection
  details syncs instead.
- "Not implemented": "no alarms, no permissions beyond internet/camera" → adds the one-shot
  network-return flush job (still no alarms or notifications).
- "Open questions": failure UX for write-backs → decided: the pending-changes queue.
- PUNCH_LIST.md item 4's stale-list-name debt → fixed, since list names now update from the Sheet.
- `CLAUDE.md`'s "Merge-on-resync policy" paragraph and its signing-key note.
- `ActiveTasksApp`'s in-Compose item state → `TaskStore` (process-wide), since the receiver and
  the background flush job change the same state.

### Decisions (user, 2026-09-24)

1. Re-triage in ActiveTasks writes importance/urgency back to the Sheet (queued).
2. Cross-app messages are sent after the Sheet write succeeds.
3. ActiveTasks adopts MicroTasking's signing key.
4. Offline changes are flushed by a one-shot job when the network returns.
5. Stuck changes show "N changes waiting to reach your Sheet".
6. Sync Lists is removed; a manual resync happens only on Save Settings with changed Google
   connection details.
7. Switching to a different Sheet discards all local state, pending queue included.
8. Scanning a QR code only fills in the Settings fields (the draft); nothing is saved or synced
   until Save Settings, so Cancel still backs out of a scan.
9. Save Settings with changed connection details saves, shows "Syncing…" on the Settings screen,
   and returns to the carousel when the sync succeeds; if it fails, Settings stays open with the
   error (the settings stay saved, so the next foreground sync retries).

## Not implemented / explicitly out of scope for now

- **On-device verification of the referral round-trip.** Both apps' code is written and compiles;
  neither side has been exercised end-to-end against a real deployed Web App yet. See
  PUNCH_LIST.md item 1.
- **Connection-code QR** (one code for both apps), **Add item**, re-triage write-through, and
  reading via `getTasks` - all specified in MicroTasking's `SPEC.md` "Sheet connection & API", not
  built here yet (re-triage write-through is now built - see "Synchronization"). Until then the Sheet is still read through the public export. (The scanner half
  is built: `parseSetupQr` classifies each scanned line as Web App URL or Sheet URL, so the
  onboarding page's separate Sheet and Web App QRs each set only their own field.)
- Background reminders/notifications - this is a pull list, not a nudger; no alarms, no
  notifications. The only background work is the one-shot "network is back" flush of queued Sheet
  writes (see "Synchronization").
- Bulk-editing a list's sheet-backed items, reordering lists, or a *user-visible* priority column
  in the Sheet itself (the hidden/protected importance/urgency columns are internal plumbing, not
  a user-facing feature).
- Per-list top-N override (currently one global setting only).

## Open questions (for later)

- Exact importance-weight default/range and items-per-list default/range above are a first
  proposal, not user-validated in practice.
