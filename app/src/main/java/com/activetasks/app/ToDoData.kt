// Copyright (c) 2026 Vern McGeorge. All rights reserved.
// Updated 2026-09-25, after version v0.2.0-27 main 2026-09-25
package com.activetasks.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Priority is two independent continuous axes (Eisenhower matrix: how important, how urgent),
 * each 0f..1f. Referred items get these from MicroTasking (the exact touch point on its matrix
 * widget); ad-hoc/local items get them from ActiveTasks's own matrix widget. Values are stored raw and
 * unweighted - see [priorityScore], which applies the user's importance-weight setting at
 * read/sort time rather than baking a fixed formula into the stored data.
 */
data class ToDoItem(
    val id: String,
    val description: String,
    val list: String,
    val link: String = "",
    val importance: Float = 0f,
    val urgency: Float = 0f,
    val progress: Int = 0,
    val done: Boolean = false,
    val addedAtEpochMs: Long = System.currentTimeMillis(),
    val doneAtEpochMs: Long? = null,
    // MicroTasking's per-row surrogate key (its Sheet's hidden "Task ID" column), when the sheet
    // row carries one - see [toDoItemsFromReferredRows]. Null for rows from a sheet/script version
    // that predates it; such items keep matching by list+description text until re-synced.
    val taskId: String? = null
)

/** Default importance weight (matches the old fixed important*2 + urgent formula's ratio). */
const val DEFAULT_IMPORTANCE_WEIGHT = 2f

/**
 * Sort key, computed on read rather than stored: `importance * importanceWeight + urgency`.
 * [importanceWeight] is a user setting (see Settings screen), not a constant - this is a
 * deliberately tunable ranking, not a fixed rule.
 */
fun ToDoItem.priorityScore(importanceWeight: Float): Float = importance * importanceWeight + urgency

enum class Quadrant(val label: String) {
    DO_FIRST("Do First"),
    SCHEDULE("Schedule"),
    DELEGATE("Delegate"),
    ELIMINATE("Eliminate")
}

/** Coarse quadrant for badge display/coloring only - the real priority value stays continuous. */
fun ToDoItem.quadrant(): Quadrant = when {
    importance >= 0.5f && urgency >= 0.5f -> Quadrant.DO_FIRST
    importance >= 0.5f -> Quadrant.SCHEDULE
    urgency >= 0.5f -> Quadrant.DELEGATE
    else -> Quadrant.ELIMINATE
}

/** Open items highest-priority-first (by [importanceWeight]), ties broken by whichever was added first. */
fun sortedForDisplay(items: List<ToDoItem>, importanceWeight: Float): List<ToDoItem> =
    items.sortedWith(
        compareByDescending<ToDoItem> { it.priorityScore(importanceWeight) }.thenBy { it.addedAtEpochMs }
    )

private fun JSONObject.optNullableLong(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun itemToJson(item: ToDoItem): JSONObject = JSONObject().apply {
    put("id", item.id)
    put("description", item.description)
    put("list", item.list)
    put("link", item.link)
    put("importance", item.importance.toDouble())
    put("urgency", item.urgency.toDouble())
    put("progress", item.progress)
    put("done", item.done)
    put("addedAtEpochMs", item.addedAtEpochMs)
    put("doneAtEpochMs", item.doneAtEpochMs ?: JSONObject.NULL)
    put("taskId", item.taskId ?: JSONObject.NULL)
}

private fun itemFromJson(json: JSONObject): ToDoItem = ToDoItem(
    id = json.getString("id"),
    description = json.getString("description"),
    list = json.getString("list"),
    link = json.optString("link", ""),
    importance = json.optDouble("importance", 0.0).toFloat(),
    urgency = json.optDouble("urgency", 0.0).toFloat(),
    progress = json.optInt("progress", 0),
    done = json.optBoolean("done", false),
    addedAtEpochMs = json.optLong("addedAtEpochMs", System.currentTimeMillis()),
    doneAtEpochMs = json.optNullableLong("doneAtEpochMs"),
    taskId = json.optNullableString("taskId")
)

fun readToDoItems(json: String): List<ToDoItem> = runCatching {
    val values = JSONArray(json)
    List(values.length()) { index -> itemFromJson(values.getJSONObject(index)) }
}.getOrDefault(emptyList())

fun writeToDoItems(items: List<ToDoItem>): String = JSONArray().apply {
    items.forEach { put(itemToJson(it)) }
}.toString()

/**
 * One sheet row's description/link/task-id, as read from the plain CSV export - importance/urgency
 * never appear there, see [SheetPriority]/[fetchAllPriorities]. The CSV export carries every
 * column regardless of Sheets-UI hidden state, so the hidden "Task ID" column (MicroTasking's
 * per-row surrogate key) rides along here the same as the visible description/link columns.
 */
data class SheetRow(val description: String, val link: String, val checked: Boolean, val taskId: String? = null)

/**
 * Parses one sheet tab's CSV rows. Column A is the enabled checkbox (a Google Sheets checkbox
 * exports "TRUE"/"FALSE"; a tab with no checkboxes at all treats every row as checked).
 * Description/link/Task ID columns are matched by header text so column order/extra columns don't
 * break parsing.
 */
fun parseToDoCsvRows(csvText: String): List<SheetRow> {
    if (csvText.isBlank()) return emptyList()

    // splitCsvRecords (not a plain line split) so a quoted cell containing a literal newline -
    // e.g. a multi-line description - stays one record instead of being torn into bogus extra
    // rows (SPEC.md/PUNCH_LIST.md "Harden against user edits").
    val rows = splitCsvRecords(csvText)
        .filter { it.isNotBlank() }
        .map { splitCsvLine(it) }
    if (rows.isEmpty()) return emptyList()

    val header = rows.first().map { it.lowercase() }
    val descriptionIndex = header.indexOfFirst { it.contains("description") }
    val linkIndex = header.indexOfFirst { it.contains("link") || it.contains("url") }
    val taskIdIndex = header.indexOfFirst { it.contains("task id") }
    if (descriptionIndex == -1) return emptyList()

    val dataRows = rows.drop(1)
    val columnA = dataRows.map { it.firstOrNull()?.lowercase().orEmpty() }
    val tabUsesCheckboxes = columnA.any { it == "true" || it == "false" }

    return dataRows.mapIndexedNotNull { index, row ->
        if (row.size <= descriptionIndex) return@mapIndexedNotNull null
        val description = row[descriptionIndex].trim()
        if (description.isEmpty()) return@mapIndexedNotNull null
        val checked = if (tabUsesCheckboxes) columnA[index] == "true" else true
        val taskId = row.getOrNull(taskIdIndex)?.trim()?.ifEmpty { null }
        SheetRow(description = description, link = row.getOrNull(linkIndex).orEmpty().trim(), checked = checked, taskId = taskId)
    }
}

/**
 * Gated ingestion: builds to-do items for [listName] from this tab's plain CSV rows plus the
 * importance/urgency values already fetched for that tab via the Apps Script endpoint (see
 * [fetchAllPriorities]). Only checked rows that MicroTasking has actually referred (present in
 * [priorities]) become items - a row just checked in the sheet directly, never referred, stays
 * MicroTasking's task and never shows up here. See SPEC.md "Items".
 *
 * [priorities] is keyed by `"id:<taskId>"` for rows carrying a surrogate key and by plain
 * description text otherwise, matching how callers build it from [fetchAllPriorities]'s
 * [ReferredRow]s - a row is looked up by taskId first, falling back to description only when the
 * row (or the referral) predates the surrogate key.
 */
fun toDoItemsFromReferredRows(
    csvText: String,
    listName: String,
    priorities: Map<String, SheetPriority>
): List<ToDoItem> = parseToDoCsvRows(csvText)
    .filter { it.checked }
    .mapNotNull { row ->
        val priority = row.taskId?.let { priorities["id:$it"] } ?: priorities[row.description] ?: return@mapNotNull null
        ToDoItem(
            id = toDoItemId(row.taskId, listName, row.description),
            description = row.description,
            list = listName,
            link = row.link,
            taskId = row.taskId,
            importance = priority.importance,
            urgency = priority.urgency
        )
    }

/**
 * An item's id, deterministic so a re-sync recognizes the same row instead of duplicating it.
 * Preferring taskId (when the row has one) makes a description rename in the sheet survive as the
 * same item, same convention MicroTasking's task-pool import uses; without one, editing the
 * description reads as a new item.
 */
fun toDoItemId(taskId: String?, listName: String, description: String): String =
    taskId?.let { "sheet-$it" } ?: "sheet-$listName-$description"

/** A Sheet write waiting to be sent - see SPEC.md "Synchronization" > "Pending-changes queue". */
enum class PendingOp(val wireName: String) {
    CLEAR_PRIORITY("clearPriority"),
    DELETE_ROW("deleteRow"),
    SET_PRIORITY("setPriority")
}

data class PendingChange(
    val op: PendingOp,
    val itemId: String,
    val category: String,
    val description: String,
    val taskId: String?,
    val importance: Float = 0f,
    val urgency: Float = 0f,
    val queuedAtEpochMs: Long = System.currentTimeMillis()
) {
    val isCompletion: Boolean get() = op != PendingOp.SET_PRIORITY
}

/**
 * Adds [change] to [queue]. A newer priority change for an item replaces an older unsent one; a
 * completion drops any unsent priority change for its item (the row is going away regardless); a
 * priority change or second completion for an item that already has a completion queued is
 * dropped, since that item has already left the list.
 */
fun enqueuePendingChange(queue: List<PendingChange>, change: PendingChange): List<PendingChange> {
    if (queue.any { it.itemId == change.itemId && it.isCompletion }) return queue
    return queue.filterNot { it.itemId == change.itemId && it.op == PendingOp.SET_PRIORITY } + change
}

private fun pendingChangeToJson(change: PendingChange): JSONObject = JSONObject().apply {
    put("op", change.op.name)
    put("itemId", change.itemId)
    put("category", change.category)
    put("description", change.description)
    put("taskId", change.taskId ?: JSONObject.NULL)
    put("importance", change.importance.toDouble())
    put("urgency", change.urgency.toDouble())
    put("queuedAtEpochMs", change.queuedAtEpochMs)
}

fun readPendingChanges(json: String): List<PendingChange> = runCatching {
    val values = JSONArray(json)
    List(values.length()) { index ->
        val entry = values.getJSONObject(index)
        PendingChange(
            op = PendingOp.valueOf(entry.getString("op")),
            itemId = entry.getString("itemId"),
            category = entry.getString("category"),
            description = entry.getString("description"),
            taskId = entry.optNullableString("taskId"),
            importance = entry.optDouble("importance", 0.0).toFloat(),
            urgency = entry.optDouble("urgency", 0.0).toFloat(),
            queuedAtEpochMs = entry.optLong("queuedAtEpochMs", 0L)
        )
    }
}.getOrDefault(emptyList())

fun writePendingChanges(changes: List<PendingChange>): String = JSONArray().apply {
    changes.forEach { put(pendingChangeToJson(it)) }
}.toString()

/**
 * Rebuilds the item set from a complete, successful Sheet read - the Sheet is definitive (SPEC.md
 * "Synchronization"). Every referred row in [imported] becomes an item; a local item with no
 * referred row is dropped. An existing item keeps only its local fields (progress, addedAt) and
 * takes everything else, priority included, from the Sheet. [pending] is laid on top: an item
 * with a queued completion stays gone even though the Sheet still shows it, and one with a queued
 * priority change keeps its new local priority until that write lands. [keepIds] are existing
 * items to keep even if the read doesn't have them - ones a cross-app message added after the read
 * started, which the read can't have seen yet.
 */
fun reconcileWithSheet(
    imported: List<ToDoItem>,
    existing: List<ToDoItem>,
    pending: List<PendingChange>,
    keepIds: Set<String> = emptySet()
): List<ToDoItem> {
    // An item stored before its Sheet row had a Task ID keeps its legacy `sheet-$list-$description`
    // id; once the row gains a Task ID, `imported` carries the same task under `sheet-$taskId`.
    // Resolve such legacy ids onto the new one so the local progress carries over instead of the
    // task reading as removed-plus-new. First existing item per resolved id wins (the older one,
    // which is the one carrying real progress on a device the taskId rollout already duplicated).
    val importedByListDescription = imported.filter { it.taskId != null }.associateBy { it.list to it.description }
    fun resolvedId(item: ToDoItem): String =
        if (item.taskId != null) item.id
        else importedByListDescription[item.list to item.description]?.id ?: item.id
    val existingByResolvedId = LinkedHashMap<String, ToDoItem>()
    existing.forEach { existingByResolvedId.putIfAbsent(resolvedId(it), it) }
    val legacyToResolved = existing.associate { it.id to resolvedId(it) }
    fun resolvedPendingId(change: PendingChange): String = legacyToResolved[change.itemId] ?: change.itemId

    val pendingCompletionIds = pending.filter { it.isCompletion }.mapTo(mutableSetOf()) { resolvedPendingId(it) }
    val pendingPriorities = pending.filter { it.op == PendingOp.SET_PRIORITY }.associateBy { resolvedPendingId(it) }

    // distinctBy guards against two sheet rows colliding on the same id - identical description
    // text in the same tab when neither row has a Task ID yet. LazyColumn hard-crashes on a
    // duplicate key; since the two rows can't be told apart, one is dropped until both get ids.
    val rebuilt = imported.distinctBy { it.id }
        .filter { it.id !in pendingCompletionIds }
        .map { sheetItem ->
            val local = existingByResolvedId[sheetItem.id]
            val merged = if (local == null) sheetItem
            else sheetItem.copy(progress = local.progress, addedAtEpochMs = local.addedAtEpochMs)
            pendingPriorities[sheetItem.id]?.let { merged.copy(importance = it.importance, urgency = it.urgency) } ?: merged
        }
    val rebuiltIds = rebuilt.mapTo(mutableSetOf()) { it.id }
    val kept = existing.filter { it.id in keepIds && it.id !in rebuiltIds && it.id !in pendingCompletionIds }
    return (rebuilt + kept).distinctBy { it.id }
}

/** A pending change's identity key, matching how [toDoItemsFromReferredRows] looks priorities up. */
private fun PendingChange.rowKey(): String = taskId?.let { "id:$it" } ?: description
private fun SheetRow.rowKey(): String = taskId?.let { "id:$it" } ?: description

/**
 * Drops queued completions that a fresh, successful sync's Sheet read shows have already taken
 * effect - Sheet truth (SPEC.md "Synchronization"), independent of whether the write's own HTTP
 * response was ever classified as [SheetWriteOutcome.Success]. A dropped connection or timeout
 * after Apps Script's `doPost` has already run - it mutates the row and only *then* produces the
 * redirect response the client reads back - leaves a completion looking permanently "stuck" even
 * though the Sheet already reflects it; this lets the next sync notice and self-heal instead of
 * retrying (and showing "N changes waiting") forever.
 *
 * A queued [PendingOp.CLEAR_PRIORITY] is confirmed once its tab was read this sync and its row is
 * no longer among that tab's referred rows; a queued [PendingOp.DELETE_ROW] is confirmed once its
 * tab was read this sync and its row no longer appears in that tab's Sheet rows at all. Only fires
 * when the change's own tab was actually present in this read - a tab missing from a [tabs]/
 * [referredKeysByTab] entry (renamed, or this specific tab failed) isn't evidence of anything, and
 * is left for the write's own [SheetWriteOutcome.RowNotFound] handling instead.
 *
 * Never touches [PendingOp.SET_PRIORITY]: a Sheet read matching the queued importance/urgency isn't
 * proof our write landed (someone else, or MicroTasking, could have written the same-looking
 * values), so misreading that as "done" could silently drop a real unsent change.
 */
fun reconcileCompletedPending(
    pending: List<PendingChange>,
    tabs: List<SheetTabCsv>,
    referredKeysByTab: Map<String, Set<String>>
): List<PendingChange> {
    val rowsByTab = tabs.associate { it.tabName to parseToDoCsvRows(it.csv) }
    return pending.filterNot { change ->
        when (change.op) {
            PendingOp.CLEAR_PRIORITY ->
                referredKeysByTab[change.category]?.let { change.rowKey() !in it } ?: false
            PendingOp.DELETE_ROW ->
                rowsByTab[change.category]?.let { rows -> rows.none { it.rowKey() == change.rowKey() } } ?: false
            PendingOp.SET_PRIORITY -> false
        }
    }
}

/**
 * Applies one cross-app message to the item set (SPEC.md "Synchronization" > "Messages between
 * the two apps"). The message is only ever sent after the Sheet write succeeded, so it carries
 * Sheet truth: a referral adds the item (or refreshes it if a sync already brought it in), a
 * completion removes it. Receiving the same message twice is harmless. Matching is by taskId when
 * both sides have one (different taskIds are different tasks, no fallback), else by list +
 * description - same rule as MicroTasking's receiver. [TaskStore.applyIncomingEvent] separately
 * drops a referral older than this device's own completion of the same item.
 */
fun applyTaskEvent(items: List<ToDoItem>, event: TaskEvent): List<ToDoItem> {
    fun matches(item: ToDoItem): Boolean =
        if (event.taskId != null && item.taskId != null) item.taskId == event.taskId
        else item.list == event.category && item.description == event.description
    return when (event.event) {
        TaskEvent.REFERRED -> {
            val existing = items.find(::matches)
            if (existing != null) {
                items.map {
                    if (it.id == existing.id) it.copy(
                        description = event.description, list = event.category, link = event.link,
                        importance = event.importance, urgency = event.urgency
                    ) else it
                }
            } else {
                items + ToDoItem(
                    id = toDoItemId(event.taskId, event.category, event.description),
                    description = event.description,
                    list = event.category,
                    link = event.link,
                    taskId = event.taskId,
                    importance = event.importance,
                    urgency = event.urgency
                )
            }
        }
        TaskEvent.COMPLETED_FOR_NOW, TaskEvent.FULLY_COMPLETED -> items.filterNot(::matches)
        else -> items
    }
}

/**
 * Bumped whenever previously-stored items can no longer be trusted as "referred by MicroTasking".
 * Version 2: v1 stored every checked sheet row (the pre-referral scaffold) plus ad-hoc `local-`
 * items, none of which were referred - and the sync of that era never removed anything, so
 * they'd otherwise linger in the lists forever.
 */
const val ITEMS_SCHEMA_VERSION = 2

/**
 * Items to load from storage. Anything stored under an older [ITEMS_SCHEMA_VERSION] is dropped
 * wholesale (the next sync re-imports whatever is genuinely referred), and ad-hoc `local-` items
 * are never loaded - a list only ever holds items referred from MicroTasking.
 */
fun itemsToLoad(storedSchemaVersion: Int, stored: List<ToDoItem>): List<ToDoItem> =
    if (storedSchemaVersion < ITEMS_SCHEMA_VERSION) emptyList()
    // distinctBy is defensive: a duplicate id shouldn't be persisted anymore (reconcileWithSheet
    // now dedupes on the way in), but this stops one already saved to a device before that fix from
    // crashing the carousel's LazyColumn forever.
    else stored.filter { it.id.startsWith("sheet-") }.distinctBy { it.id }

/**
 * Which list the carousel opens on: the last one the user viewed, else the list whose top open
 * item has the highest priority score (earlier list wins ties; a list with no open items ranks
 * last, so with nothing referred anywhere this is just the first list). Null only if [lists] is
 * empty.
 */
fun initialListName(
    lists: List<String>,
    items: List<ToDoItem>,
    importanceWeight: Float,
    lastOpenedList: String?
): String? {
    if (lastOpenedList != null && lastOpenedList in lists) return lastOpenedList
    return lists.maxByOrNull { listName ->
        items.filter { it.list == listName && !it.done }
            .maxOfOrNull { it.priorityScore(importanceWeight) }
            ?: Float.NEGATIVE_INFINITY
    }
}

/**
 * Which lists get a carousel page: every list with at least one live (non-done) item, whether or
 * not it's still among [knownLists] (the current Sheet tabs as of the last sync). A tab renamed or
 * removed since an item was imported from it drops out of [knownLists] on the very next sync
 * (`known_lists` is replaced wholesale, not merged) - without this, the item would still exist in
 * storage but have no page anywhere in the UI to reach it from (SPEC.md/PUNCH_LIST.md "Harden
 * against user edits": "Silent disappearance"). [knownLists] still matters for empty/never-synced
 * tabs, which stay hidden here either way - only the union with items' own list names can ever
 * surface a page, and only for a list that already has something referred to it.
 */
fun computeVisibleLists(knownLists: List<String>, items: List<ToDoItem>): List<String> {
    val liveLists = items.filter { !it.done }.mapTo(mutableSetOf()) { it.list }
    val orphanedLists = items.filter { !it.done && it.list !in knownLists }.map { it.list }.distinct()
    return (knownLists + orphanedLists).filter { it in liveLists }
}

fun readStringList(json: String): List<String> = runCatching {
    val values = JSONArray(json)
    List(values.length()) { values.getString(it) }
}.getOrDefault(emptyList())

fun writeStringList(values: List<String>): String = JSONArray().apply {
    values.forEach { put(it) }
}.toString()
