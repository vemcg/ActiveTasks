# ActiveTasks Defects

Field-testing defects found in shipped/branch builds. Feature-scope work lives in
[PUNCH_LIST.md](PUNCH_LIST.md); this file is for "it's built but it misbehaves" bugs.

## 1. "N changes waiting to reach your Sheet" stayed stuck after the write had already landed

**Reported** 2026-09-25. The app showed 2 items as still queued ("changes waiting to reach your
Sheet"), but the Sheet itself already showed Importance/Urgency cleared for both - the
`clearPriority` write had actually succeeded, the app just never found out.

**Root cause**: an Apps Script Web App POST mutates the Sheet as part of executing `doPost`, then
returns its result via a redirect the client fetches separately (see
[`SheetApiClient.kt`](app/src/main/java/com/activetasks/app/SheetApiClient.kt)'s `postJson`
comment). Anything that drops the connection or times out between the POST and finishing that
follow-up read - plausibly Apps Script itself running long, per MicroTasking's parallel
investigation, since `doPost` only responds once it's fully finished, mutation included - gets
classified as `SheetWriteOutcome.Failure` even though the mutation had already gone through. The
pending-changes queue (by design) never drops a change on `Failure`, so it retried forever and kept
showing "waiting" even though there was nothing left to send.

**Fix**: `reconcileCompletedPending` (`ToDoData.kt`), run after every successful sync
(`TaskStore.syncLocked`). A queued `CLEAR_PRIORITY`/`DELETE_ROW` change is dropped once that sync's
own Sheet read independently confirms the target state was already reached (the row's no longer
referred, or no longer there at all) - self-healing regardless of why the write's own confirmation
was lost. Deliberately does not touch `SET_PRIORITY` changes, since a Sheet value merely matching
what we queued isn't proof our write is what put it there.

Confirmed via `microtasking-ba` (2026-09-25) that MicroTasking's own write path
(`WebAppClient.post`/`PendingChanges.sheetSender`) and its `doPost` (`clearPriority` just blanks
D/E and returns `{ok:true}`) show no equivalent bug - no MicroTasking-side change needed.

## 2. Cross-app hand-off could take minutes, occasionally seemed to hang

**Reported** 2026-09-25. Activating a task and completing it for now both work, and do show up in
the other app - but not quickly. Usually minutes; sometimes much faster (a handful of seconds,
reported happening overnight); occasionally seemed to just hang.

**Root cause**: `TaskStore.requestFlush` fired the network send as a bare fire-and-forget
coroutine, and only fell back to the reliable, OS-scheduled `PendingFlushWorker` *after* that
coroutine's own attempt finished and found leftovers (`TaskStore.flush`'s
`if (remaining > 0) scheduleFlushWork(...)`). Android can freeze a backgrounded app's process -
no CPU time at all, network included - before a plain coroutine like this one ever gets to run,
let alone reach the point where a failed attempt would schedule the real fallback. Complete-for-now
(or Fully complete, or a re-triaged priority) queued right before switching to the other app landed
in exactly that window: the optimistic coroutine never got to run, and nothing else was scheduled to
retry it until something else happened to wake the process (reopening the app, an unrelated wakeup,
eventually the OS thawing it on its own). Overnight was faster because the app tends to stay
foregrounded a beat longer before the screen locks, giving the coroutine enough of a head start to
actually finish before any freeze.

**Fix**: `requestFlush` (`TaskStore.kt`) now schedules the `PendingFlushWorker` fallback
immediately and unconditionally, before launching the optimistic coroutine - so a queued change
always has a guaranteed, OS-backed retry path that doesn't depend on that coroutine surviving long
enough to notice it needs one. The existing post-attempt logic still cancels that scheduled work
again once the optimistic attempt actually empties the queue, so the common case (foregrounded,
good network) costs nothing beyond one redundant enqueue/cancel.

No MicroTasking-side change needed - the flush path is entirely on this app's side of the
hand-off (MicroTasking's own receipt of the resulting broadcast is already immediate on arrival).
