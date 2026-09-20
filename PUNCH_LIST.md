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
