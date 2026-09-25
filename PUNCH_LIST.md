# ActiveTasks Punch List

1. **MicroTasking → ActiveTasks "Refer to ActiveTasks" hand-off** — *design negotiated with the
   MicroTasking session and confirmed by the user (2026-09-18); code written on both sides,
   compiles, not yet verified on-device end-to-end.* The bridge is a per-user **Apps Script Web
   App**, deployed from the same Sheet-bound script every user already pastes into their own copy
   (Deploy → New deployment → Web app, documented in MicroTasking's setup instructions/README).
   MicroTasking writes continuous importance/urgency values to hidden, Protected-Range `Importance`/
   `Urgency` Sheet columns on referral (`ManagedTask.referredAt` on its side, available from any
   task-queue state - referring an already-`Started` task discards its timer as a neutral outcome,
   like Defer). Those two columns are read/written via the Web App only, never via CSV export,
   since hiding a column doesn't remove it from that export. ActiveTasks ingests a row only once
   they're populated (gated ingestion, see `SPEC.md` "Items"), ranks by a user-adjustable
   importance-weight setting (not a fixed formula - MicroTasking always writes raw unweighted
   values), and clears them ("Complete (for now)") or deletes the row ("Fully complete") back
   through the same Web App - progress itself stays local-only, not a sheet column.
   - Confirmed request/response contract and ActiveTasks's client (`SheetApiClient.kt`): see `SPEC.md`
     "Referral bridge".
   - Done on ActiveTasks's side: `ToDoItem` model (continuous `importance`/`urgency`, `progress`),
     gated-ingestion sync (`toDoItemsFromReferredRows` + `fetchAllPriorities`), the continuous
     `MatrixWidget` (replaces the old 4-quadrant tap dialog, used for re-triage), the carousel
     home screen (`CarouselScreen`, replaces Lists overview + List detail entirely; opens on the
     last-swiped list, else the highest-priority one), item cards with Complete-for-now/
     Fully-complete/Priority & progress buttons underneath (no checkbox/trash), `ItemDetailDialog`
     (priority matrix + progress slider), referred-only lists (no ad-hoc add; older stored items
     purged), and Settings additions (Apps Script Web App URL field, importance-weight slider, items-per-list count).
   - **Remaining**: on-device verification of the full round trip against a real deployed Web App
     (referral in MicroTasking → shows up in ActiveTasks → Complete-for-now hands it back → MicroTasking
     re-queues it); the onboarding QR is superseded by the single connection code in item 3
     below - until it lands, the Web App URL is pasted into Settings by hand.
2. **App icon polish** — current adaptive icon (checklist rows) is a first pass, not reviewed
   on-device at all densities/shapes yet.
3. **Sheet connection & API (Phase 3 of MicroTasking's `PUNCH_LIST.md` item 9)** — *specified
   2026-09-19 in MicroTasking's `SPEC.md` "Sheet connection & API"; nothing built here yet. Do
   not start until MicroTasking's Phase 1 (the script) has passed its checkpoint - this app is
   written against that contract.* ActiveTasks stays a companion (referral-gated, blank until
   something is prioritized, no inbox).
   - Scanner: adopt `parseSetupQr` (today `QrScannerScreen`'s result is dropped straight into the
     Sheet-URL field, which mangles the two-line QR the current onboarding page makes and would
     the new one-line connection code). Store the connection code, show it with the key masked,
     never log it.
   - `SheetApiClient.kt`: ~~stop `fetchAllPriorities` swallowing every failure into an empty list~~
     (done 2026-09-24 for item 5 - it returns null on failure); build URLs so an existing
     `?key=` survives (`"$url?action=…"` concatenation breaks it); parse the `code` field; add
     `hello`, `getTasks`, `createRow`, `setPriority`; treat `no_such_*` as "changed in your
     Sheet" + resync, `unauthorized` as "rescan", `busy` as one retry.
   - Sync via `getTasks` instead of the xlsx `workbook.xml` + gviz CSV path (`SheetImport.kt`),
     keeping the gate: only rows with a priority become items; empty tabs stay as empty lists;
     an `apiVersion`/`unknown_action` check shows the "redeploy your Sheet's script" notice.
   - **Add item** screen (pick a list, type the item, touch the matrix -> `createRow` with the
     priority; item appears immediately under its `external-…` id) and **re-triage write-through**
     (`setPriority`; local copy updates even if the write fails).
   - Update `CLAUDE.md` (it still describes the gviz/xlsx read path) once the sync moves over.
4. **Harden against user edits to the shared Sheet** — *scoped 2026-09-22 at the user's request:
   "make it very hard for user input to break either app," specifically "if I rename a description
   ActiveTasks needs to handle that gracefully."* Mitigated 2026-09-22 (this session) on top of the
   surrogate-`taskId` groundwork (`6113146`, same day). Today both apps identify a Sheet row by
   `(tab name, description text)` unless a `taskId` is present (`SPEC.md` "Referral bridge" > "Row
   identity"; MicroTasking's `doPost` prefers `taskId` when sent). Confirmed by reading
   `TaskPool.mergeImportedManagedTasks`: MicroTasking's own pool already handles a description
   rename cleanly (rebuilt from the current import each sync) - the problems below were specific to
   ActiveTasks's *own* item store, which (deliberately) never rebuilds from scratch.
   - **Orphaned duplicate** (a description rename produces a new id, stranding the old card) - now
     mostly prevented for any row carrying a `taskId`: `toDoItemsFromReferredRows` builds the id
     from `taskId` when present, which a description rename doesn't change (`6113146`). Still
     possible for a row from a sheet/script predating `taskId` (falls back to the old
     `sheet-$listName-$description` id) - covered by the "orphaned item" mitigation below instead
     of being prevented outright.
   - **Silent disappearance**, if the whole tab (list) is renamed rather than just one row's
     description - `known_lists` is still replaced wholesale on every sync (not merged), so the old
     tab name still drops out of it. **Fixed**: `computeVisibleLists` (`ToDoData.kt`) now unions
     `known_lists` with every live item's own `list` value, so an item survives its tab
     disappearing from `known_lists` and stays reachable in the carousel - the item still shows
     under its *original* (possibly now-stale) list name, since `mergeImportedToDoItems` never
     rewrites an existing item's fields; only removing/re-adding it would pick up a new tab name,
     which is out of scope here (see "still open" below).
   - **Duplicate id within the same tab** (two rows with identical description text, no `taskId`
     yet) - `CarouselScreen`'s `LazyColumn` keys items by `it.id` with no de-dup guard, the same bug
     class MicroTasking already hit and fixed (`DEFECTS.md` items 2-3 there: a duplicate key
     hard-crashes `LazyColumn`). **Mitigated**: `mergeImportedToDoItems` and `itemsToLoad` now
     `distinctBy { it.id }` (crash prevention only - since the two rows are indistinguishable
     without a `taskId`, one is silently dropped; the real fix is `taskId` reaching every row), plus
     a defensive `distinctBy` right before the `LazyColumn` itself in `CarouselScreen` as the "de-dup
     right before rendering" belt-and-suspenders the plan called for.
   - **"Remove locally" for an item whose Sheet row no longer resolves.** *Superseded 2026-09-24
     by item 5: the Sheet is now definitive, so a vanished row just drops its item and the button
     is gone. `RowNotFound` survives as "dequeue the pending write".* Was done: `SheetApiClient`
     now returns a `SheetWriteOutcome` (`Success`/`RowNotFound`/`Failure`) instead of a bare
     `Boolean`, classifying MicroTasking's `doPost` error text ("No tab named …", "No row matching
     that description", "No row with that task id") as `RowNotFound` - a confirmed-gone row, not a
     network hiccup. `completeForNow`/`fullyComplete` (`MainActivity.kt`) flag the item as orphaned
     on `RowNotFound` instead of leaving a generic "try again" error forever; its card swaps
     Complete-for-now/Fully-complete for a "Remove locally" button (local-only, no further write).
     **Tech debt this leaves**: the `RowNotFound` classification is v1-contract string-sniffing
     (`ROW_NOT_FOUND_MARKERS` in `SheetApiClient.kt`), not a real error code - once item 3's
     redesign ships MicroTasking's proposed structured `no_such_*` codes, swap this for a code check
     (the doc comment on `ROW_NOT_FOUND_MARKERS` flags this same thing).
   - **Broader audit, same request**, of other user-editable-Sheet-input paths for the same
     "quietly wrong forever" failure mode:
     - Blank/whitespace-only descriptions: already excluded (`parseToDoCsvRows` trims before
       checking emptiness) - no gap found.
     - Extremely long description text: not a robustness issue - Compose's `Text` wraps normally,
       nothing crashes or truncates unsafely. Left as-is.
     - A description that looks like a formula (`=...`): not an exploit vector for this app - the
       CSV export already returns computed values (not formula source), and ActiveTasks never
       re-exports/re-opens a description as a formula anywhere. Left as-is.
     - **Fixed**: a Sheet cell spanning multiple physical lines (quoted by the gviz CSV export
       rather than escaped) used to break parsing, since rows were split on line breaks *before*
       quote-state was tracked - one bad multi-line cell silently turned into two-plus bogus rows.
       `SheetImport.kt` gained `splitCsvRecords`, a record-level (quote-aware) splitter;
       `parseToDoCsvRows` uses it instead of a plain line split. (MicroTasking's own gviz-based
       import has the same underlying gap, noted in its `PUNCH_LIST.md` item 1 follow-ups - not
       fixed there by this change, since that's a separate parser in a separate repo.)
   - **Still open / dangling tech debt** (flagged for the user, not silently deferred):
     - *Fixed 2026-09-24 by item 5 (a sync now takes `list` from the Sheet):* A `taskId`-bearing item's `list` field goes cosmetically stale after its tab is renamed - it
       keeps writing through correctly (`taskId` lookup ignores category) and stays visible (via
       `computeVisibleLists`'s union above), but the carousel page/header still shows the *old* tab
       name until the item is completed and the row re-referred fresh. Full fix would mean letting a
       resync update `list` on an existing item, which cuts against the "sheet is a source of new
       items, never a mirror to sync down to" merge policy (`SPEC.md` "Merge-on-resync policy") -
       not changed here without that policy conversation.
     - The duplicate-description collision (no `taskId`) is mitigated against the *crash*, not the
       *data loss* - one of the two same-named tasks is still silently dropped on import. Real fix
       is `taskId` reaching every row (item 3 / MicroTasking's item 9's territory), same as noted
       above.
     - `RowNotFound` detection depends on matching the *current* v1 script's exact English error
       wording (see tech debt note above) - it fails safe (falls back to generic retryable
       `Failure`) but should move to structured error codes once item 3 lands.
5. **Automatic synchronization: Sheet-definitive sync, pending-changes queue, cross-app messages**
   — *specified and built 2026-09-24 (`SPEC.md` "Synchronization"; `TaskStore.kt`,
   `TaskEvents.kt`); unit-tested, not yet verified on-device.* MicroTasking's side is built in its
   own repo by its session against the same message contract.
   - **Remaining**: on-device check with both apps' shared-key builds installed - refer in
     MicroTasking and watch it appear in ActiveTasks (including split screen); complete here and
     watch MicroTasking re-queue it; complete while offline and confirm "N changes waiting" shows
     and then clears when the network returns without reopening the app.
   - Existing ActiveTasks installs must be uninstalled once (signing key changed to MicroTasking's);
     the install page says so.
