// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-25, after version v0.2.0-27 main 2026-09-25
package com.activetasks.app

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Outcome of one sync, with the message Settings shows for it. */
sealed class SyncResult(val message: String) {
    class Success(message: String) : SyncResult(message)
    class Failure(message: String) : SyncResult(message)
}

/**
 * The app's saved copy of reality plus the pending-changes queue, shared by the screens, the
 * cross-app [TaskEventReceiver] and the background [PendingFlushWorker] - all three live in the
 * same process and change the same state, so it lives here rather than in Compose state. See
 * SPEC.md "Synchronization".
 *
 * Every network operation (sync, flush, switching Sheets) runs one at a time under [networkMutex].
 * That ordering is what keeps a sync that read the Sheet just before a completion from bringing
 * the completed item back: the completion's flush can't finish until that sync has rebuilt, and
 * the rebuild lays the still-queued completion on top of the stale read.
 */
object TaskStore {
    private const val KEY_ITEMS = "todo_items"
    private const val KEY_ITEMS_SCHEMA = "items_schema"
    private const val KEY_LISTS = "known_lists"
    private const val KEY_PENDING = "pending_changes"
    private const val KEY_PENDING_STUCK = "pending_stuck"
    private const val KEY_RECENT_COMPLETIONS = "recent_completions"
    const val KEY_SHEET_URL = "sheet_url"
    const val KEY_APPS_SCRIPT_URL = "apps_script_url"
    const val KEY_LAST_LIST = "last_list"

    private const val FLUSH_WORK_NAME = "flush_pending_changes"
    private const val RECENT_COMPLETION_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val stateLock = Any()
    private val networkMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loaded = false

    private val _items = MutableStateFlow<List<ToDoItem>>(emptyList())
    val items: StateFlow<List<ToDoItem>> = _items

    private val _lists = MutableStateFlow<List<String>>(emptyList())
    val lists: StateFlow<List<String>> = _lists

    private val _pending = MutableStateFlow<List<PendingChange>>(emptyList())

    // Number of changes still waiting after the last flush attempt failed to send them all; 0
    // whenever the queue is empty or nothing has failed yet (a change in flight isn't "stuck").
    private val _stuckCount = MutableStateFlow(0)
    val stuckCount: StateFlow<Int> = _stuckCount

    private val _lastSyncMessage = MutableStateFlow("")
    val lastSyncMessage: StateFlow<String> = _lastSyncMessage

    // Item id -> when this device completed it, so a late or duplicate "referred" message from
    // before that completion can't bring the item back (MicroTasking applies the mirror guard).
    private var recentCompletions: Map<String, Long> = emptyMap()

    // Item id -> when a cross-app message added it, so a sync whose read started before the
    // message doesn't drop it again. In memory only: after a restart any sync is newer anyway.
    private val eventAddedAt = mutableMapOf<String, Long>()

    // Sync coalescing: every request takes a ticket; a sync covers every ticket issued before it
    // started, so requests piling up behind a running sync collapse into one follow-up run.
    private var requestedSyncs = 0L
    private var completedSyncs = 0L
    private var lastSyncResult: SyncResult = SyncResult.Success("")

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    /** Loads the saved copy once per process; cheap to call from every entry point. */
    fun ensureLoaded(context: Context) {
        synchronized(stateLock) {
            if (loaded) return
            val preferences = prefs(context)
            val storedSchema = preferences.getInt(KEY_ITEMS_SCHEMA, 1)
            val storedItems = readToDoItems(preferences.getString(KEY_ITEMS, "[]") ?: "[]")
            val loadedItems = itemsToLoad(storedSchema, storedItems)
            if (loadedItems != storedItems || storedSchema < ITEMS_SCHEMA_VERSION) {
                preferences.edit()
                    .putString(KEY_ITEMS, writeToDoItems(loadedItems))
                    .putInt(KEY_ITEMS_SCHEMA, ITEMS_SCHEMA_VERSION)
                    .apply()
            }
            _items.value = loadedItems
            _lists.value = readStringList(preferences.getString(KEY_LISTS, "[]") ?: "[]")
            _pending.value = readPendingChanges(preferences.getString(KEY_PENDING, "[]") ?: "[]")
            _stuckCount.value = if (preferences.getBoolean(KEY_PENDING_STUCK, false)) _pending.value.size else 0
            recentCompletions = readRecentCompletions(preferences.getString(KEY_RECENT_COMPLETIONS, "{}") ?: "{}")
            loaded = true
        }
    }

    // --- state writes (callers hold stateLock) ---

    private fun setItems(context: Context, newItems: List<ToDoItem>) {
        _items.value = newItems
        prefs(context).edit().putString(KEY_ITEMS, writeToDoItems(newItems)).apply()
    }

    private fun setPending(context: Context, newPending: List<PendingChange>, stuck: Boolean) {
        _pending.value = newPending
        val isStuck = stuck && newPending.isNotEmpty()
        _stuckCount.value = if (isStuck) newPending.size else 0
        prefs(context).edit()
            .putString(KEY_PENDING, writePendingChanges(newPending))
            .putBoolean(KEY_PENDING_STUCK, isStuck)
            .apply()
    }

    private fun recordCompletion(context: Context, itemId: String) {
        val now = System.currentTimeMillis()
        recentCompletions = (recentCompletions + (itemId to now)).filterValues { now - it < RECENT_COMPLETION_TTL_MS }
        prefs(context).edit().putString(KEY_RECENT_COMPLETIONS, writeRecentCompletions(recentCompletions)).apply()
    }

    // --- user actions: take effect locally at once, then reach the Sheet via the queue ---

    /** Progress or an in-progress matrix drag: local only, nothing queued. */
    fun updateItemLocally(context: Context, itemId: String, transform: (ToDoItem) -> ToDoItem) {
        ensureLoaded(context)
        synchronized(stateLock) {
            setItems(context, _items.value.map { if (it.id == itemId) transform(it) else it })
        }
    }

    /** Complete (for now) / Fully complete: the item leaves the list now; the Sheet write is queued. */
    fun completeItem(context: Context, item: ToDoItem, fully: Boolean) {
        ensureLoaded(context)
        synchronized(stateLock) {
            setItems(context, _items.value.filterNot { it.id == item.id })
            recordCompletion(context, item.id)
            val change = PendingChange(
                op = if (fully) PendingOp.DELETE_ROW else PendingOp.CLEAR_PRIORITY,
                itemId = item.id, category = item.list, description = item.description, taskId = item.taskId
            )
            setPending(context, enqueuePendingChange(_pending.value, change), stuck = _stuckCount.value > 0)
        }
        requestFlush(context)
    }

    /** Re-triage finished (dialog closed with a new priority): queue the write-back of the item's current priority. */
    fun commitPriority(context: Context, itemId: String) {
        ensureLoaded(context)
        synchronized(stateLock) {
            val item = _items.value.find { it.id == itemId } ?: return
            val change = PendingChange(
                op = PendingOp.SET_PRIORITY, itemId = item.id, category = item.list, description = item.description,
                taskId = item.taskId, importance = item.importance, urgency = item.urgency
            )
            setPending(context, enqueuePendingChange(_pending.value, change), stuck = _stuckCount.value > 0)
        }
        requestFlush(context)
    }

    /** A message from MicroTasking - apply the single change, no network. */
    fun applyIncomingEvent(context: Context, event: TaskEvent) {
        ensureLoaded(context)
        synchronized(stateLock) {
            if (event.event == TaskEvent.REFERRED) {
                val id = toDoItemId(event.taskId, event.category, event.description)
                val completedAt = recentCompletions[id]
                if (completedAt != null && event.eventAtEpochMs <= completedAt) return
                eventAddedAt[id] = System.currentTimeMillis()
            }
            setItems(context, applyTaskEvent(_items.value, event))
        }
    }

    // --- network: flush, sync, switch Sheet ---

    /**
     * Starts a flush in the background; returns immediately. Also schedules the reliable
     * WorkManager fallback right now, unconditionally - not only after the optimistic attempt
     * below fails, which is too late once the app is backgrounded: Android can freeze a background
     * process's threads (no CPU time at all, network included) before this fire-and-forget
     * coroutine ever gets to run, let alone reach the point where a failed attempt would schedule
     * the fallback. Scheduling it up front means a completion or priority change queued right
     * before the user switches away still has a guaranteed OS-backed retry, instead of depending on
     * this coroutine surviving long enough to notice it needs one. [flush] cancels the scheduled
     * work again once the queue is actually empty, so the common case (foregrounded, good network)
     * costs nothing beyond one redundant enqueue/cancel.
     */
    fun requestFlush(context: Context) {
        val appContext = context.applicationContext
        scheduleFlushWork(WorkManager.getInstance(appContext))
        scope.launch { flush(appContext) }
    }

    /**
     * Sends every queued change it can. Returns how many are left. Outside the worker, a leftover
     * (re)confirms the one-shot network-return job [requestFlush] already scheduled up front (a
     * harmless no-op, [scheduleFlushWork] keeps whichever is already queued); an empty queue cancels
     * the now-unneeded job instead.
     */
    suspend fun flush(context: Context, fromWorker: Boolean = false): Int {
        ensureLoaded(context)
        val remaining = networkMutex.withLock { flushLocked(context) }
        if (!fromWorker) {
            val workManager = WorkManager.getInstance(context)
            if (remaining > 0) scheduleFlushWork(workManager) else workManager.cancelUniqueWork(FLUSH_WORK_NAME)
        }
        return remaining
    }

    private fun flushLocked(context: Context): Int {
        val webAppUrl = prefs(context).getString(KEY_APPS_SCRIPT_URL, "").orEmpty()
        val toSend = _pending.value
        if (toSend.isEmpty()) return 0
        if (webAppUrl.isBlank()) {
            synchronized(stateLock) { setPending(context, _pending.value, stuck = true) }
            return _pending.value.size
        }
        var anyFailed = false
        for (change in toSend) {
            // Later entries still get attempted after a failure: they target different rows.
            when (sendPendingChange(webAppUrl, change)) {
                SheetWriteOutcome.Success -> {
                    synchronized(stateLock) { setPending(context, _pending.value - change, stuck = anyFailed) }
                    taskEventFor(change)?.let { sendTaskEvent(context, it) }
                }
                // The row is already gone - what a completion wanted anyway; a priority change for
                // it is moot and the next sync drops the item. No message: nothing changed.
                SheetWriteOutcome.RowNotFound ->
                    synchronized(stateLock) { setPending(context, _pending.value - change, stuck = anyFailed) }
                is SheetWriteOutcome.Failure -> anyFailed = true
            }
        }
        synchronized(stateLock) { setPending(context, _pending.value, stuck = anyFailed) }
        return _pending.value.size
    }

    private fun scheduleFlushWork(workManager: WorkManager) {
        val request = OneTimeWorkRequestBuilder<PendingFlushWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        // KEEP: one already waiting for the network (or backing off) covers this change too.
        workManager.enqueueUniqueWork(FLUSH_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Foreground-entry sync: fire and forget; its outcome shows up in [lastSyncMessage]. */
    fun requestSync(context: Context) {
        if (prefs(context).getString(KEY_SHEET_URL, "").isNullOrBlank()) return
        val appContext = context.applicationContext
        scope.launch { sync(appContext) }
    }

    /**
     * Runs a sync to completion even if the caller's screen goes away, and returns its result -
     * for Save Settings, which waits on it.
     */
    suspend fun syncAndWait(context: Context): SyncResult {
        val appContext = context.applicationContext
        return scope.async { sync(appContext) }.await()
    }

    private suspend fun sync(context: Context): SyncResult {
        ensureLoaded(context)
        val ticket = synchronized(stateLock) { ++requestedSyncs }
        return networkMutex.withLock {
            if (completedSyncs >= ticket) return@withLock lastSyncResult
            val coveredUpTo = synchronized(stateLock) { requestedSyncs }
            val result = syncLocked(context)
            completedSyncs = coveredUpTo
            lastSyncResult = result
            _lastSyncMessage.value = result.message
            result
        }
    }

    private fun syncLocked(context: Context): SyncResult {
        val preferences = prefs(context)
        val sheetUrl = preferences.getString(KEY_SHEET_URL, "").orEmpty()
        val webAppUrl = preferences.getString(KEY_APPS_SCRIPT_URL, "").orEmpty()
        if (sheetUrl.isBlank()) return SyncResult.Failure("Set your Google Sheet URL, then Save Settings.")
        if (webAppUrl.isBlank()) return SyncResult.Failure("Set the Apps Script Web App URL, then Save Settings.")

        flushLocked(context)
        val readStartedAt = System.currentTimeMillis()
        val tabs = fetchSheetTabs(sheetUrl)
            ?: return SyncResult.Failure(
                "Couldn't read your Sheet. Check the URL, that sharing is \"Anyone with the link can view\", " +
                    "and your connection."
            )
        val referred = fetchAllPriorities(webAppUrl)
            ?: return SyncResult.Failure("Couldn't reach your Sheet's Web App. Check its URL and your connection.")
        val prioritiesByTab = referred
            .groupBy { it.category }
            .mapValues { (_, rows) ->
                // Keyed by taskId (preferred) when the row has one, else by description text - see
                // toDoItemsFromReferredRows, which looks up the same way.
                rows.associate { (it.taskId?.let { id -> "id:$id" } ?: it.description) to it.priority }
            }
        val imported = tabs.flatMap { tab ->
            toDoItemsFromReferredRows(tab.csv, tab.tabName, prioritiesByTab[tab.tabName].orEmpty())
        }
        synchronized(stateLock) {
            val keepIds = eventAddedAt.filterValues { it >= readStartedAt }.keys
            eventAddedAt.keys.retainAll(keepIds)
            setItems(context, reconcileWithSheet(imported, _items.value, _pending.value, keepIds))
            // A completion whose write's own HTTP response never came back as a confirmed Success
            // (dropped connection, timeout) but that this read shows already landed on the Sheet:
            // stop retrying it instead of leaving it "stuck" forever - see reconcileCompletedPending.
            val confirmedPending = reconcileCompletedPending(_pending.value, tabs, prioritiesByTab.mapValues { it.value.keys })
            if (confirmedPending.size != _pending.value.size) {
                setPending(context, confirmedPending, stuck = _stuckCount.value > 0)
            }
            _lists.value = tabs.map { it.tabName }
            preferences.edit().putString(KEY_LISTS, writeStringList(_lists.value)).apply()
        }
        // A tab the Web App says has referred rows but that produced zero matched items is a real
        // anomaly (a row edited since referral, a missing Description header, an unchecked column
        // A) - say so instead of the tab silently never getting a carousel page.
        val mismatchedTabs = tabs.map { it.tabName }
            .filter { tabName -> prioritiesByTab[tabName].orEmpty().isNotEmpty() && imported.none { it.list == tabName } }
        return SyncResult.Success(
            if (mismatchedTabs.isEmpty()) "Synced ${tabs.size} list(s)."
            else "Synced ${tabs.size} list(s). Referred rows didn't match any Sheet row in: ${mismatchedTabs.joinToString(", ")}."
        )
    }

    /**
     * A different Sheet was saved: everything tied to the old one goes - items, lists, last-opened
     * list, pending changes (they target the old Sheet). The user doesn't switch back and forth.
     */
    suspend fun switchSheet(context: Context) {
        ensureLoaded(context)
        networkMutex.withLock {
            synchronized(stateLock) {
                setItems(context, emptyList())
                setPending(context, emptyList(), stuck = false)
                _lists.value = emptyList()
                recentCompletions = emptyMap()
                eventAddedAt.clear()
                _lastSyncMessage.value = ""
                prefs(context).edit()
                    .putString(KEY_LISTS, "[]")
                    .putString(KEY_RECENT_COMPLETIONS, "{}")
                    .remove(KEY_LAST_LIST)
                    .apply()
            }
            WorkManager.getInstance(context).cancelUniqueWork(FLUSH_WORK_NAME)
        }
    }

    private fun readRecentCompletions(json: String): Map<String, Long> = runCatching {
        val obj = org.json.JSONObject(json)
        obj.keys().asSequence().associateWith { obj.getLong(it) }
    }.getOrDefault(emptyMap())

    private fun writeRecentCompletions(values: Map<String, Long>): String =
        org.json.JSONObject().apply { values.forEach { (id, at) -> put(id, at) } }.toString()
}

/**
 * The one piece of background work: a one-shot job, run when the network is available, that sends
 * whatever the pending-changes queue still holds - so an offline completion reaches the Sheet (and
 * MicroTasking) without reopening the app. Retries with backoff while entries remain; not periodic.
 */
class PendingFlushWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val preferences = applicationContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        // No Web App URL: nothing can be sent until Settings is fixed, and saving it flushes anyway.
        if (preferences.getString(TaskStore.KEY_APPS_SCRIPT_URL, "").isNullOrBlank()) return Result.success()
        return if (TaskStore.flush(applicationContext, fromWorker = true) > 0) Result.retry() else Result.success()
    }
}
