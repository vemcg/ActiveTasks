# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

ActiveTasks is an Android app (Kotlin + Jetpack Compose), a companion to
[MicroTasking](https://github.com/vemcg/MicroTasking) (sibling repo `../MicroTasking`): a
traditional to-do list that reads the *same* Google Sheet MicroTasking uses, with priority set via
an Eisenhower matrix (importance x urgency, continuous, not four fixed quadrants) instead of a
flat scale. Where MicroTasking pushes semi-random prompts, ActiveTasks is pull — no background alarms,
no notifications.

**Gated ingestion, not "every checked row":** unlike MicroTasking's own task pool, an item only
reaches ActiveTasks via an explicit "refer to ActiveTasks" action in MicroTasking's task queue — a row the
user just checks directly in the Sheet stays purely MicroTasking's task until referred. Referral
writes importance/urgency to two hidden, protected columns via a shared Apps Script Web App (owned
by MicroTasking's repo, `scripts/populate_google_sheet.js` there); ActiveTasks reads those two columns
the same way (never via the plain CSV/gviz export, which would leak hidden-column data) and only
imports rows present in that read. See `SPEC.md` "Referral bridge" for the full contract
(negotiated directly with MicroTasking's session/repo, PUNCH_LIST.md item 1 there is the mirror of
this repo's item 1).

## Commands

Requires an Android SDK; `local.properties` (gitignored) must contain `sdk.dir=<path>`.

- Build debug APK: `./gradlew assembleDebug`
- Run all unit tests: `./gradlew testDebugUnitTest`
- Run one test class: `./gradlew testDebugUnitTest --tests "com.activetasks.app.ToDoDataTest"`
- Run one test method: `./gradlew testDebugUnitTest --tests "com.activetasks.app.ToDoDataTest.toDoItemsFromReferredRows_importsOnlyCheckedRowsWithPrioritySet"`
- Fast compile check without running tests: `./gradlew compileDebugKotlin`

Manually trigger a build for a non-`main` branch (pushes to other branches do **not**
auto-trigger the release workflow, same convention as MicroTasking):
`gh workflow run "Build & release APK" --ref <branch>`.

## Build tracking (user-requested convention, 2026-09-24)

- **Last built version:** `v0.2.0-24` (branch `main`, built 2026-09-24). At the
  start of every session, note this as the current known state before doing anything else. After
  triggering a build and confirming it went live (`gh run list` / the new GitHub Release), update
  this line to the new version/branch/date — don't leave it stale once a newer build exists.
- **Copyright-comment convention, from 2026-09-24 forward:** when editing a file that already
  carries this project's own `Copyright (c) <year> Vern McGeorge` header (not the Gradle wrapper's
  or `LICENSE`'s), add or update a line directly under it reading `Updated <date>, after version
  <build version> <build branch> <build timestamp>` where `<build version>`,`<build branch>`, and `<build timestamp>` are whatever "Last built version" above says *at the time of
  the edit* (the most recent build that had already shipped, not one triggered by this edit, which
  hasn't happened yet). `<date>` is `YYYY-MM-DD`, matching this repo's existing dating convention
  in `PUNCH_LIST.md`/`SPEC.md`. Applies going forward only, to files actually touched for some
  other reason - not a retroactive pass over every file that currently carries the header.

## Architecture

Six source files under `app/src/main/java/com/activetasks/app/`:

- **`MainActivity.kt`** — the Activity plus every Compose screen: Settings (paste/QR-scan the
  Sheet URL, the Apps Script Web App URL, importance-weight and items-per-list settings - all a
  hoisted `SettingsDraft`; Save with changed connection details is what syncs, there's no Sync
  button), `CarouselScreen` (a `HorizontalPager` of per-list top-N views — this *is* the home
  screen, there's no separate "see everything" list-detail screen), the continuous Eisenhower
  matrix widget (`MatrixWidget`, tap/drag position → importance/urgency floats), `ToDoItemRow`
  (each referred item is a card: task text, then Complete-for-now / Fully-complete / Priority &
  progress / Open-link buttons underneath — no checkbox, no trash icon), `ItemDetailDialog`
  (priority matrix + progress slider only; a moved priority is queued for write-back on close), QR scanner (ML Kit barcode scanning, copied from
  MicroTasking's `QrScannerScreen`). There is deliberately **no** add-item path: a list only ever
  holds items referred from MicroTasking (`itemsToLoad` also purges anything a pre-referral build
  stored, gated by `ITEMS_SCHEMA_VERSION`). The carousel opens on the last-swiped list, else the
  highest-priority one (`initialListName`).
- **`ToDoData.kt`** — data model (`ToDoItem`, `Quadrant`), JSON read/write helpers
  (SharedPreferences-backed, no Room/DB — same convention as MicroTasking's `TaskPool.kt`), plain
  CSV row parsing (`parseToDoCsvRows`), gated-ingestion item construction
  (`toDoItemsFromReferredRows`, requires a `SheetPriority` per row from `SheetApiClient.kt`), the
  Sheet-definitive rebuild (`reconcileWithSheet`), the pending-changes queue model
  (`PendingChange`, `enqueuePendingChange`) and applying a cross-app message (`applyTaskEvent`).
- **`TaskStore.kt`** — process-wide owner of the saved copy (items, known lists, pending-changes
  queue) shared by the screens, `TaskEventReceiver` and `PendingFlushWorker`; runs sync, flush and
  switch-Sheet one at a time under one mutex. Also `PendingFlushWorker`, the one-shot WorkManager
  job that flushes the queue when the network returns.
- **`TaskEvents.kt`** — the cross-app message contract with MicroTasking (explicit broadcast,
  signature-level permission shared by both apps; must match MicroTasking's copy exactly - see
  `SPEC.md` "Synchronization" > "Message contract, v1"), plus `TaskEventReceiver`.
- **`SheetImport.kt`** — generic Google Sheet tab discovery + per-tab CSV fetch
  (`fetchSheetTabs`, columns A-C only; null on any failure - never a partial read), adapted from MicroTasking's `MainActivity.kt` Sheet-import
  functions but kept free of any `ToDoItem`-specific mapping so it's just "give me every tab's raw
  CSV."
- **`SheetApiClient.kt`** — client for the Apps Script Web App that reads/writes the hidden,
  protected importance/urgency columns (`fetchTabPriorities`) and clears/deletes a row
  (`clearSheetPriority`, `deleteSheetRow`, `setSheetPriority`); `fetchAllPriorities` returns null on
  failure, never an empty list. The endpoint is owned and implemented by MicroTasking's
  repo (`scripts/populate_google_sheet.js` there); this file's request/response shape is
  provisional until that side's contract is confirmed — see PUNCH_LIST.md.

**Priority model**: not a flat field, and not fixed-weight either — `ToDoItem.importance`/`urgency`
are independent floats (0f..1f), continuous rather than four fixed quadrants, set by the exact
tap/drag position on `MatrixWidget`. `ToDoItem.priorityScore(importanceWeight)`
(`importance * importanceWeight + urgency`) is the sort key, computed on read, not stored — the
weight itself is a user Settings value (`DEFAULT_IMPORTANCE_WEIGHT = 2f`), not a hardcoded
constant, since MicroTasking always writes raw unweighted values. `Quadrant`/`quadrant()` still
exist as a coarse 0.5-threshold bucketing for badge display/coloring only.

**Sync policy: the Sheet is definitive** (changed 2026-09-24 from an add-only merge). Every
successful sync rebuilds the item set from the Sheet's referred rows (`reconcileWithSheet`); only
progress is local. A sync is all-or-nothing - any failed read changes nothing, which is why
`fetchSheetTabs`/`fetchAllPriorities` must never turn a failure into an empty result. Local
actions apply immediately and reach the Sheet through the persisted pending-changes queue, which
a sync lays over its read so an unsent change is never reverted. See `SPEC.md` "Synchronization".

**Google Sheet import**: same mechanics as MicroTasking for columns A-C (tab names via the `.xlsx`
export's zipped `workbook.xml`, each tab's rows via the `gviz` CSV export, column A as the enabled
checkbox, `description`/`link` matched by header text) — but no Apps Script *provisioning* tooling
here (creating/deploying the script), since that script is owned and deployed from MicroTasking's
repo; this repo only calls it (`SheetApiClient.kt`). Don't add sheet-provisioning scripts here.

**Testing**: plain JUnit, no Robolectric — everything in `ToDoData.kt`/`SheetImport.kt` operates
on strings/plain objects rather than a real `Context`, so there's nothing that needs a simulated
Android framework (unlike MicroTasking's `TaskDeliveryTest`, which needs real
`SharedPreferences`).

**Docs convention**, same as MicroTasking — read before starting non-trivial work: `SPEC.md`
(intended behavior, including "Not implemented" section), `PUNCH_LIST.md` (scoped-but-not-started
feature work), `DEFECTS.md` (numbered bug write-ups, empty so far).

**Release pipeline** (`.github/workflows/release-apk.yml`, `scripts/generate_install_page.py`):
same shape as MicroTasking's (debug-signed APK via a checked-in debug keystore — since 2026-09-24 the
same key as MicroTasking's (a copy of its `keystore/debug.keystore`), required by the shared
signature-level permission the two apps' cross-app messages use — GitHub Release tagged `vBASE-N`, install
page with QR code(s) deployed to GitHub Pages), but trimmed: no `--template-url` step, since the
install page tells the user to reuse the Sheet they already set up for MicroTasking rather than
create a new one.
