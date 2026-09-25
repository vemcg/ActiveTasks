// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.activetasks.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToDoDataTest {

    @Test
    fun parseToDoCsvRows_parsesCheckedStateAndFields() {
        val csv = """
            checked,description,link
            TRUE,Buy milk,
            FALSE,Skip this one,
            TRUE,Call the plumber,https://example.com
        """.trimIndent()
        val rows = parseToDoCsvRows(csv)
        assertEquals(3, rows.size)
        assertEquals(setOf("Buy milk", "Skip this one", "Call the plumber"), rows.map { it.description }.toSet())
        assertFalse(rows.first { it.description == "Skip this one" }.checked)
        assertEquals("https://example.com", rows.first { it.description == "Call the plumber" }.link)
    }

    @Test
    fun parseToDoCsvRows_treatsEveryRowAsChecked_whenTabHasNoCheckboxes() {
        val csv = """
            checked,description
            ,Legacy row one
            ,Legacy row two
        """.trimIndent()
        val rows = parseToDoCsvRows(csv)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.checked })
    }

    @Test
    fun parseToDoCsvRows_readsTaskIdColumnByHeaderText() {
        val csv = """
            checked,description,link,Task ID
            TRUE,Buy milk,,abc-123
            TRUE,No id yet,,
        """.trimIndent()
        val rows = parseToDoCsvRows(csv)
        assertEquals("abc-123", rows.first { it.description == "Buy milk" }.taskId)
        assertEquals(null, rows.first { it.description == "No id yet" }.taskId)
    }

    @Test
    fun parseToDoCsvRows_skipsBlankDescriptions() {
        val csv = """
            checked,description
            TRUE,
            TRUE,Real task
        """.trimIndent()
        val rows = parseToDoCsvRows(csv)
        assertEquals(1, rows.size)
        assertEquals("Real task", rows.single().description)
    }

    @Test
    fun toDoItemsFromReferredRows_importsOnlyCheckedRowsWithPrioritySet() {
        val csv = """
            checked,description,link
            TRUE,Buy milk,
            TRUE,Not referred yet,
            FALSE,Referred but unchecked,
        """.trimIndent()
        val priorities = mapOf(
            "Buy milk" to SheetPriority(importance = 0.8f, urgency = 0.2f),
            "Referred but unchecked" to SheetPriority(importance = 0.9f, urgency = 0.9f)
        )
        val items = toDoItemsFromReferredRows(csv, "Errands", priorities)
        // "Not referred yet" is checked but has no priority entry - stays MicroTasking's task.
        // "Referred but unchecked" has a priority entry but isn't checked - excluded too.
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("Buy milk", item.description)
        assertEquals("sheet-Errands-Buy milk", item.id)
        assertEquals(0.8f, item.importance)
        assertEquals(0.2f, item.urgency)
    }

    @Test
    fun toDoItemsFromReferredRows_prefersTaskIdForIdAndPriorityMatch_whenRowHasOne() {
        val csv = """
            checked,description,link,Task ID
            TRUE,Buy milk,,abc-123
        """.trimIndent()
        // Keyed by "id:<taskId>", matching how MainActivity builds this map from fetchAllPriorities.
        val priorities = mapOf("id:abc-123" to SheetPriority(importance = 0.8f, urgency = 0.2f))
        val item = toDoItemsFromReferredRows(csv, "Errands", priorities).single()
        assertEquals("sheet-abc-123", item.id)
        assertEquals("abc-123", item.taskId)
        assertEquals(0.8f, item.importance)
    }

    @Test
    fun toDoItemsFromReferredRows_fallsBackToDescriptionMatch_whenRowHasNoTaskId() {
        val csv = """
            checked,description,link,Task ID
            TRUE,Buy milk,,
        """.trimIndent()
        val priorities = mapOf("Buy milk" to SheetPriority(importance = 0.8f, urgency = 0.2f))
        val item = toDoItemsFromReferredRows(csv, "Errands", priorities).single()
        assertEquals("sheet-Errands-Buy milk", item.id)
        assertEquals(null, item.taskId)
    }

    @Test
    fun reconcileWithSheet_takesSheetFieldsButKeepsLocalProgress() {
        val existing = listOf(
            ToDoItem(id = "sheet-List-A", description = "A", list = "List", importance = 1f, urgency = 1f, progress = 60, addedAtEpochMs = 5L)
        )
        val imported = listOf(
            ToDoItem(id = "sheet-List-A", description = "A", list = "List", link = "https://x", importance = 0.2f, urgency = 0.3f),
            ToDoItem(id = "sheet-List-B", description = "B", list = "List")
        )
        val rebuilt = reconcileWithSheet(imported, existing, pending = emptyList())
        assertEquals(2, rebuilt.size)
        val a = rebuilt.first { it.id == "sheet-List-A" }
        // The Sheet is definitive for priority and link; progress and add time are local.
        assertEquals(0.2f, a.importance)
        assertEquals(0.3f, a.urgency)
        assertEquals("https://x", a.link)
        assertEquals(60, a.progress)
        assertEquals(5L, a.addedAtEpochMs)
    }

    @Test
    fun reconcileWithSheet_dropsAnItemWhoseRowIsNoLongerReferred() {
        val existing = listOf(
            ToDoItem(id = "sheet-List-A", description = "A", list = "List", progress = 50),
            ToDoItem(id = "sheet-List-B", description = "B", list = "List")
        )
        val imported = listOf(ToDoItem(id = "sheet-List-B", description = "B", list = "List"))
        assertEquals(listOf("sheet-List-B"), reconcileWithSheet(imported, existing, emptyList()).map { it.id })
    }

    @Test
    fun reconcileWithSheet_keepsAQueuedCompletionGoneEvenThoughTheSheetStillShowsIt() {
        val imported = listOf(ToDoItem(id = "sheet-List-A", description = "A", list = "List"))
        val pending = listOf(PendingChange(PendingOp.CLEAR_PRIORITY, "sheet-List-A", "List", "A", taskId = null))
        assertTrue(reconcileWithSheet(imported, existing = emptyList(), pending = pending).isEmpty())
    }

    @Test
    fun reconcileWithSheet_keepsAQueuedPriorityChangeOverTheSheetsOlderValue() {
        val existing = listOf(ToDoItem(id = "sheet-List-A", description = "A", list = "List", importance = 0.9f, urgency = 0.8f))
        val imported = listOf(ToDoItem(id = "sheet-List-A", description = "A", list = "List", importance = 0.1f, urgency = 0.1f))
        val pending = listOf(
            PendingChange(PendingOp.SET_PRIORITY, "sheet-List-A", "List", "A", taskId = null, importance = 0.9f, urgency = 0.8f)
        )
        val item = reconcileWithSheet(imported, existing, pending).single()
        assertEquals(0.9f, item.importance)
        assertEquals(0.8f, item.urgency)
    }

    @Test
    fun reconcileWithSheet_keepsAnItemAMessageAddedAfterTheReadStarted() {
        val messaged = ToDoItem(id = "sheet-t1", description = "New", list = "List", taskId = "t1")
        val rebuilt = reconcileWithSheet(imported = emptyList(), existing = listOf(messaged), pending = emptyList(), keepIds = setOf("sheet-t1"))
        assertEquals(listOf("sheet-t1"), rebuilt.map { it.id })
    }

    @Test
    fun reconcileWithSheet_migratesALegacyItemOntoItsNewTaskIdBasedId_whenItsRowGainsATaskId() {
        // An item synced before the Sheet's Task ID column was populated is stored under the legacy
        // sheet-$list-$description id; the next sync reads the same row with a Task ID, under a new
        // sheet-$taskId id. Without migration this reads as remove-old + add-new, losing progress.
        val legacy = ToDoItem(
            id = "sheet-Errands-Buy milk", description = "Buy milk", list = "Errands",
            importance = 0.5f, urgency = 0.5f, progress = 40
        )
        val freshlyImported = ToDoItem(
            id = "sheet-abc-123", description = "Buy milk", list = "Errands", taskId = "abc-123",
            importance = 0.7f, urgency = 0.3f
        )
        val item = reconcileWithSheet(listOf(freshlyImported), listOf(legacy), emptyList()).single()
        assertEquals("sheet-abc-123", item.id)
        assertEquals("abc-123", item.taskId)
        assertEquals(0.7f, item.importance)
        assertEquals(40, item.progress)
    }

    @Test
    fun reconcileWithSheet_resolvesADeviceAlreadyDuplicatedByTheTaskIdRollout() {
        // Both the legacy item (real progress) and the taskId-based duplicate an older sync added
        // (0%) are stored; the next sync collapses them to one card with the real progress.
        val legacy = ToDoItem(
            id = "sheet-Errands-Buy milk", description = "Buy milk", list = "Errands",
            importance = 0.5f, urgency = 0.5f, progress = 40
        )
        val alreadyDuplicated = ToDoItem(
            id = "sheet-abc-123", description = "Buy milk", list = "Errands", taskId = "abc-123",
            importance = 0.7f, urgency = 0.3f, progress = 0
        )
        val freshlyImported = alreadyDuplicated.copy(importance = 0.8f)
        val rebuilt = reconcileWithSheet(listOf(freshlyImported), listOf(legacy, alreadyDuplicated), emptyList())
        assertEquals(1, rebuilt.size)
        assertEquals(40, rebuilt.single().progress)
        assertEquals("sheet-abc-123", rebuilt.single().id)
    }

    @Test
    fun reconcileCompletedPending_dropsAClearPriorityChange_whenItsRowIsNoLongerReferred() {
        // The write's own HTTP response never came back as Success, but this sync's read shows the
        // row is no longer among "List"'s referred rows - the clear already landed.
        val pending = listOf(PendingChange(PendingOp.CLEAR_PRIORITY, "sheet-List-A", "List", "A", taskId = null))
        val tabs = listOf(SheetTabCsv("List", "checked,description\nTRUE,A\n"))
        val referredKeysByTab = mapOf("List" to emptySet<String>())
        assertTrue(reconcileCompletedPending(pending, tabs, referredKeysByTab).isEmpty())
    }

    @Test
    fun reconcileCompletedPending_keepsAClearPriorityChange_whenItsRowIsStillReferred() {
        val pending = listOf(PendingChange(PendingOp.CLEAR_PRIORITY, "sheet-List-A", "List", "A", taskId = null))
        val tabs = listOf(SheetTabCsv("List", "checked,description\nTRUE,A\n"))
        val referredKeysByTab = mapOf("List" to setOf("A"))
        assertEquals(pending, reconcileCompletedPending(pending, tabs, referredKeysByTab))
    }

    @Test
    fun reconcileCompletedPending_dropsADeleteRowChange_whenItsRowIsGoneFromTheTab() {
        val pending = listOf(PendingChange(PendingOp.DELETE_ROW, "sheet-List-A", "List", "A", taskId = null))
        val tabs = listOf(SheetTabCsv("List", "checked,description\nTRUE,B\n"))
        assertTrue(reconcileCompletedPending(pending, tabs, referredKeysByTab = emptyMap()).isEmpty())
    }

    @Test
    fun reconcileCompletedPending_matchesByTaskIdOverDescription() {
        // The description changed in the Sheet since this item was imported; the taskId still ties
        // the pending change to the row's current referred-rows key.
        val pending = listOf(PendingChange(PendingOp.CLEAR_PRIORITY, "sheet-abc-123", "List", "Old text", taskId = "abc-123"))
        val tabs = listOf(SheetTabCsv("List", "checked,description,Task ID\nTRUE,New text,abc-123\n"))
        val referredKeysByTab = mapOf("List" to setOf("id:abc-123"))
        assertEquals(pending, reconcileCompletedPending(pending, tabs, referredKeysByTab))
    }

    @Test
    fun reconcileCompletedPending_leavesAChangeQueued_whenItsTabWasntInThisRead() {
        // A tab missing from this sync's read isn't evidence the write landed - leave it for the
        // write's own RowNotFound handling instead of guessing.
        val pending = listOf(PendingChange(PendingOp.CLEAR_PRIORITY, "sheet-List-A", "List", "A", taskId = null))
        assertEquals(pending, reconcileCompletedPending(pending, tabs = emptyList(), referredKeysByTab = emptyMap()))
    }

    @Test
    fun reconcileCompletedPending_neverDropsASetPriorityChange() {
        val pending = listOf(
            PendingChange(PendingOp.SET_PRIORITY, "sheet-List-A", "List", "A", taskId = null, importance = 0.5f, urgency = 0.5f)
        )
        val tabs = listOf(SheetTabCsv("List", "checked,description\nTRUE,A\n"))
        val referredKeysByTab = mapOf("List" to emptySet<String>())
        assertEquals(pending, reconcileCompletedPending(pending, tabs, referredKeysByTab))
    }

    @Test
    fun reconcileWithSheet_dedupesCollidingIdsWithinOneImportBatch() {
        // Two sheet rows with identical description text and no Task ID collide on the same id -
        // LazyColumn would hard-crash on the duplicate key.
        val imported = listOf(
            ToDoItem(id = "sheet-List-Dup", description = "Dup", list = "List", importance = 0.1f),
            ToDoItem(id = "sheet-List-Dup", description = "Dup", list = "List", importance = 0.9f)
        )
        assertEquals(1, reconcileWithSheet(imported, emptyList(), emptyList()).size)
    }

    @Test
    fun enqueuePendingChange_replacesAnUnsentPriorityChangeForTheSameItem() {
        val first = PendingChange(PendingOp.SET_PRIORITY, "i1", "List", "A", null, importance = 0.1f)
        val second = PendingChange(PendingOp.SET_PRIORITY, "i1", "List", "A", null, importance = 0.9f)
        assertEquals(listOf(second), enqueuePendingChange(listOf(first), second))
    }

    @Test
    fun enqueuePendingChange_aCompletionDropsTheItemsUnsentPriorityChange_andBlocksLaterOnes() {
        val priority = PendingChange(PendingOp.SET_PRIORITY, "i1", "List", "A", null, importance = 0.5f)
        val completion = PendingChange(PendingOp.DELETE_ROW, "i1", "List", "A", null)
        val queue = enqueuePendingChange(listOf(priority), completion)
        assertEquals(listOf(completion), queue)
        assertEquals(queue, enqueuePendingChange(queue, priority))
        assertEquals(queue, enqueuePendingChange(queue, completion.copy(op = PendingOp.CLEAR_PRIORITY)))
    }

    @Test
    fun readWritePendingChanges_roundTrips() {
        val queue = listOf(
            PendingChange(PendingOp.CLEAR_PRIORITY, "sheet-t1", "List", "A", "t1", queuedAtEpochMs = 10L),
            PendingChange(PendingOp.SET_PRIORITY, "sheet-List-B", "List", "B", null, 0.25f, 0.75f, 20L)
        )
        assertEquals(queue, readPendingChanges(writePendingChanges(queue)))
        assertTrue(readPendingChanges("not json").isEmpty())
    }

    @Test
    fun applyTaskEvent_referredAddsTheItem_andRepeatingItIsHarmless() {
        val event = TaskEvent(TaskEvent.REFERRED, "t1", "Errands", "Buy milk", "https://x", 0.6f, 0.4f)
        val once = applyTaskEvent(emptyList(), event)
        val item = once.single()
        assertEquals("sheet-t1", item.id)
        assertEquals("Errands", item.list)
        assertEquals(0.6f, item.importance)
        assertEquals(once, applyTaskEvent(once, event))
    }

    @Test
    fun applyTaskEvent_matchesByTaskIdOnlyWhenBothSidesHaveOne() {
        val withId = ToDoItem(id = "sheet-t1", description = "Same", list = "List", taskId = "t1")
        val otherTaskSameText = TaskEvent(TaskEvent.COMPLETED_FOR_NOW, "t2", "List", "Same")
        assertEquals(listOf(withId), applyTaskEvent(listOf(withId), otherTaskSameText))
        val legacy = ToDoItem(id = "sheet-List-Same", description = "Same", list = "List")
        assertTrue(applyTaskEvent(listOf(legacy), otherTaskSameText).isEmpty())
    }

    @Test
    fun itemsToLoad_dedupesCollidingIdsFromStorage() {
        val stored = listOf(
            ToDoItem(id = "sheet-List-Dup", description = "Dup", list = "List", importance = 0.1f),
            ToDoItem(id = "sheet-List-Dup", description = "Dup", list = "List", importance = 0.9f)
        )
        assertEquals(1, itemsToLoad(ITEMS_SCHEMA_VERSION, stored).size)
    }

    @Test
    fun computeVisibleLists_keepsAnOrphanedListVisible_whenItsTabIsRenamedOrRemoved() {
        // "List" carried a live item in from an earlier sync, but the current sync's known_lists
        // (wholesale-replaced, not merged) no longer includes it - e.g. the tab was renamed in the
        // Sheet. The item must still be reachable from somewhere in the carousel rather than
        // silently disappearing (SPEC.md "Harden against user edits": "Silent disappearance").
        val knownLists = listOf("Renamed List")
        val items = listOf(ToDoItem(id = "sheet-1", description = "Orphaned", list = "List", importance = 0.5f))
        assertEquals(listOf("List"), computeVisibleLists(knownLists, items))
    }

    @Test
    fun computeVisibleLists_matchesPlainFilterBehavior_whenNothingIsOrphaned() {
        val knownLists = listOf("Cleaning", "Errands")
        val items = listOf(
            ToDoItem(id = "a", description = "", list = "Cleaning", importance = 0.5f),
            ToDoItem(id = "b", description = "", list = "Errands", importance = 0.5f, done = true)
        )
        // "Errands" has an item, but it's done, so it still gets no page - same as before this
        // function existed.
        assertEquals(listOf("Cleaning"), computeVisibleLists(knownLists, items))
    }

    @Test
    fun parseToDoCsvRows_handlesAQuotedCellSpanningMultipleLines() {
        // The gviz CSV export quotes a cell containing a literal newline rather than escaping it -
        // splitting on line breaks before tracking quote state would tear this row apart and read
        // the second physical line as a bogus extra row (SPEC.md "Harden against user edits").
        val csv = "checked,description,link\nTRUE,\"Line one\nline two\",\nTRUE,Second task,\n"
        val rows = parseToDoCsvRows(csv)
        assertEquals(2, rows.size)
        assertEquals("Line one\nline two", rows.first().description)
        assertEquals("Second task", rows[1].description)
    }

    @Test
    fun itemsToLoad_dropsEverythingStoredUnderAnOlderSchema() {
        // The pre-referral scaffold stored every checked row (same "sheet-" id format), which
        // can't be told apart from genuinely referred ones - so the whole old set goes.
        val stored = listOf(
            ToDoItem(id = "sheet-Cleaning-Vacuum", description = "Vacuum", list = "Cleaning"),
            ToDoItem(id = "local-1-0", description = "Ad hoc", list = "Cleaning")
        )
        assertTrue(itemsToLoad(1, stored).isEmpty())
    }

    @Test
    fun itemsToLoad_keepsOnlySheetItemsUnderTheCurrentSchema() {
        val referred = ToDoItem(id = "sheet-Cleaning-Vacuum", description = "Vacuum", list = "Cleaning", importance = 0.9f)
        val adHoc = ToDoItem(id = "local-1-0", description = "Ad hoc", list = "Cleaning")
        assertEquals(listOf(referred), itemsToLoad(ITEMS_SCHEMA_VERSION, listOf(referred, adHoc)))
    }

    @Test
    fun initialListName_prefersTheLastOpenedList() {
        val lists = listOf("Cleaning", "Paperwork", "Errands")
        val items = listOf(ToDoItem(id = "a", description = "", list = "Errands", importance = 1f, urgency = 1f))
        assertEquals("Paperwork", initialListName(lists, items, DEFAULT_IMPORTANCE_WEIGHT, lastOpenedList = "Paperwork"))
    }

    @Test
    fun initialListName_fallsBackToTheListWithTheHighestPriorityItem() {
        val lists = listOf("Cleaning", "Paperwork", "Errands")
        val items = listOf(
            ToDoItem(id = "a", description = "", list = "Cleaning", importance = 0.2f, urgency = 0.2f),
            ToDoItem(id = "b", description = "", list = "Errands", importance = 0.9f, urgency = 0.8f),
            ToDoItem(id = "c", description = "", list = "Errands", importance = 0.1f, urgency = 0.1f),
            ToDoItem(id = "d", description = "", list = "Paperwork", importance = 1f, urgency = 1f, done = true)
        )
        // No last-opened list -> Errands (its top item outranks Cleaning's; Paperwork's only item is done).
        assertEquals("Errands", initialListName(lists, items, DEFAULT_IMPORTANCE_WEIGHT, lastOpenedList = null))
        // A last-opened list that no longer exists is treated as none.
        assertEquals("Errands", initialListName(lists, items, DEFAULT_IMPORTANCE_WEIGHT, lastOpenedList = "Gone"))
    }

    @Test
    fun initialListName_isTheFirstListWhenNothingIsReferred() {
        val lists = listOf("Cleaning", "Paperwork")
        assertEquals("Cleaning", initialListName(lists, emptyList(), DEFAULT_IMPORTANCE_WEIGHT, lastOpenedList = null))
        assertEquals(null, initialListName(emptyList(), emptyList(), DEFAULT_IMPORTANCE_WEIGHT, lastOpenedList = null))
    }

    @Test
    fun quadrant_mapsContinuousValuesToLabelsAndScore() {
        assertEquals(Quadrant.DO_FIRST, ToDoItem(id = "1", description = "", list = "", importance = 1f, urgency = 1f).quadrant())
        assertEquals(Quadrant.SCHEDULE, ToDoItem(id = "2", description = "", list = "", importance = 1f, urgency = 0f).quadrant())
        assertEquals(Quadrant.DELEGATE, ToDoItem(id = "3", description = "", list = "", importance = 0f, urgency = 1f).quadrant())
        assertEquals(Quadrant.ELIMINATE, ToDoItem(id = "4", description = "", list = "", importance = 0f, urgency = 0f).quadrant())

        assertEquals(3f, ToDoItem(id = "1", description = "", list = "", importance = 1f, urgency = 1f).priorityScore(DEFAULT_IMPORTANCE_WEIGHT))
        assertEquals(0f, ToDoItem(id = "4", description = "", list = "", importance = 0f, urgency = 0f).priorityScore(DEFAULT_IMPORTANCE_WEIGHT))
        // The weight is a setting, not a constant - a higher weight favors importance further.
        assertEquals(4f, ToDoItem(id = "5", description = "", list = "", importance = 1f, urgency = 0f).priorityScore(4f))
    }

    @Test
    fun sortedForDisplay_ordersByScoreThenAddedTime() {
        val eliminate = ToDoItem(id = "e", description = "", list = "", addedAtEpochMs = 1)
        val doFirstOlder = ToDoItem(id = "d1", description = "", list = "", importance = 1f, urgency = 1f, addedAtEpochMs = 2)
        val doFirstNewer = ToDoItem(id = "d2", description = "", list = "", importance = 1f, urgency = 1f, addedAtEpochMs = 3)
        val schedule = ToDoItem(id = "s", description = "", list = "", importance = 1f, addedAtEpochMs = 4)

        val sorted = sortedForDisplay(listOf(eliminate, schedule, doFirstNewer, doFirstOlder), DEFAULT_IMPORTANCE_WEIGHT)
        assertEquals(listOf("d1", "d2", "s", "e"), sorted.map { it.id })
    }

    @Test
    fun readWriteToDoItems_roundTrips() {
        val items = listOf(
            ToDoItem(
                id = "sheet-abc-123", description = "A", list = "List", link = "https://x",
                importance = 0.75f, urgency = 0.25f, progress = 40, done = true, addedAtEpochMs = 100, doneAtEpochMs = 200,
                taskId = "abc-123"
            )
        )
        val roundTripped = readToDoItems(writeToDoItems(items))
        assertEquals(items, roundTripped)
    }

    @Test
    fun readToDoItems_survivesGarbageJson() {
        assertTrue(readToDoItems("not json").isEmpty())
        assertFalse(readToDoItems("[]").isNotEmpty())
    }
}
