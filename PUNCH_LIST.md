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
   - `SheetApiClient.kt`: stop `fetchAllPriorities` swallowing every failure into an empty list
     (a wrong/rotated key must not look like "nothing referred"); build URLs so an existing
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
4. **Harden against user edits to the shared Sheet** — *not started, scoped 2026-09-22 at the
   user's request: "make it very hard for user input to break either app," specifically "if I
   rename a description ActiveTasks needs to handle that gracefully."* Today both apps identify a
   Sheet row by `(tab name, description text)` (`SPEC.md` "Referral bridge" > "Row identity"), and
   ActiveTasks's own item id is `sheet-$listName-${row.description}` (`ToDoData.kt`). Confirmed by
   reading `TaskPool.mergeImportedManagedTasks`: MicroTasking's own pool already handles a
   description rename cleanly (rebuilt from the current import each sync, so the old id is simply
   dropped) - the problem below is specific to ActiveTasks.
   - **Orphaned duplicate.** `mergeImportedToDoItems` only ever *adds* items by id (deliberately -
     see `SPEC.md` "Merge-on-resync policy": ActiveTasks's sheet is a source of new items, never a
     mirror to sync down to). A rename produces a new id, so the next sync adds a second card for
     what the user considers the same task, and the old card is now permanently stuck - its
     Complete-for-now/Fully-complete calls still target the *old* description, which no longer
     resolves to any row, so the write fails and the item can never be dismissed from the app.
   - **Silent disappearance, if the whole tab (list) is renamed rather than just one row's
     description.** `known_lists` is replaced wholesale by the current tab names on every sync
     (not merged), so once the old tab name drops out of it, the carousel's "no page for a list
     with nothing referred" behavior (`visibleLists`, `CarouselScreen`, added 2026-09-22) means the
     orphaned item's old list can never appear again - the item still exists in the `todo_items`
     pref, but there's no way to reach it anywhere in the UI.
   - **Related risk, not yet hit but structurally possible:** two rows in the *same* tab with
     identical description text collide on the same item id. `CarouselScreen`'s `LazyColumn` keys
     items by `it.id` with no de-dup guard - the same bug class MicroTasking already hit and fixed
     (`DEFECTS.md` items 2-3 there: a duplicate key hard-crashes `LazyColumn`).
   - **Real fix belongs with the Sheet-connection API redesign** (item 3 above / MicroTasking's
     `PUNCH_LIST.md` item 9): a server-assigned, stable row id instead of raw description text
     would remove the root cause on both sides. Until then, minimum mitigation for this repo:
     de-dup by id before rendering (cheap insurance against the crash case), and a way to detect
     and let the user clear an item whose Sheet row no longer resolves - a failed
     Complete-for-now/Fully-complete already surfaces an error; extend it to offer "remove
     locally" once the row is confirmed gone, instead of leaving the item stuck forever.
   - **Broader ask, same request:** audit other user-editable-Sheet-input paths for the same
     "quietly wrong forever" failure mode, not just description rename - e.g. blank/whitespace-only
     descriptions, extremely long text, a description that itself looks like a formula (`=...`),
     and the already-known gap that a Sheet cell spanning multiple lines breaks CSV parsing (noted
     in MicroTasking's `PUNCH_LIST.md` item 1 follow-ups).
