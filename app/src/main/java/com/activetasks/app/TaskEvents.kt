// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.activetasks.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Direct messages between ActiveTasks and MicroTasking on the same device, so an activation or
 * completion in one app shows up in the other within seconds instead of at its next Sheet read.
 * Contract v1 is spelled out in SPEC.md "Synchronization" > "Message contract, v1" and must match
 * MicroTasking's copy of these constants exactly. A fast path only: a missed message is caught by
 * the next foreground sync.
 */
const val TASK_EVENTS_PERMISSION = "com.vernmcgeorge.tasks.permission.TASK_EVENTS"
const val TASK_EVENT_ACTION = "com.vernmcgeorge.tasks.action.TASK_EVENT"
const val TASK_EVENT_SCHEMA_VERSION = 1

private const val MICROTASKING_PACKAGE = "com.microtasking.app"
private const val MICROTASKING_RECEIVER = "com.microtasking.app.TaskEventReceiver"

private const val EXTRA_SCHEMA_VERSION = "schemaVersion"
private const val EXTRA_EVENT = "event"
private const val EXTRA_EVENT_AT = "eventAtEpochMs"
private const val EXTRA_TASK_ID = "taskId"
private const val EXTRA_CATEGORY = "category"
private const val EXTRA_DESCRIPTION = "description"
private const val EXTRA_LINK = "link"
private const val EXTRA_IMPORTANCE = "importance"
private const val EXTRA_URGENCY = "urgency"

/** One message, set-style (states the new state rather than a toggle), so repeats are harmless. */
data class TaskEvent(
    val event: String,
    val taskId: String?,
    val category: String,
    val description: String,
    val link: String = "",
    val importance: Float = 0f,
    val urgency: Float = 0f,
    val eventAtEpochMs: Long = System.currentTimeMillis()
) {
    companion object {
        const val REFERRED = "referred"
        const val COMPLETED_FOR_NOW = "completedForNow"
        const val FULLY_COMPLETED = "fullyCompleted"
    }
}

/**
 * Tells MicroTasking about a change that has already reached the Sheet. Never throws: if
 * MicroTasking isn't installed, or is installed from a build without the receiver, the message is
 * simply dropped.
 */
fun sendTaskEvent(context: Context, event: TaskEvent) {
    runCatching {
        val intent = Intent(TASK_EVENT_ACTION)
            .setComponent(ComponentName(MICROTASKING_PACKAGE, MICROTASKING_RECEIVER))
            .putExtra(EXTRA_SCHEMA_VERSION, TASK_EVENT_SCHEMA_VERSION)
            .putExtra(EXTRA_EVENT, event.event)
            .putExtra(EXTRA_EVENT_AT, event.eventAtEpochMs)
            .putExtra(EXTRA_CATEGORY, event.category)
            .putExtra(EXTRA_DESCRIPTION, event.description)
        if (event.taskId != null) intent.putExtra(EXTRA_TASK_ID, event.taskId)
        if (event.event == TaskEvent.REFERRED) {
            intent.putExtra(EXTRA_LINK, event.link)
                .putExtra(EXTRA_IMPORTANCE, event.importance.toDouble())
                .putExtra(EXTRA_URGENCY, event.urgency.toDouble())
        }
        context.sendBroadcast(intent, TASK_EVENTS_PERMISSION)
    }
}

/**
 * The message a completed [PendingChange] announces, or null for one MicroTasking doesn't need
 * (priority changes). Stamped with the time of the tap, not of the flush, as agreed with
 * MicroTasking: its receiver ignores a completion older than the task's latest referral, so an
 * offline completion flushed after a re-referral can't undo the newer referral.
 */
fun taskEventFor(change: PendingChange): TaskEvent? = when (change.op) {
    PendingOp.CLEAR_PRIORITY ->
        TaskEvent(TaskEvent.COMPLETED_FOR_NOW, change.taskId, change.category, change.description, eventAtEpochMs = change.queuedAtEpochMs)
    PendingOp.DELETE_ROW ->
        TaskEvent(TaskEvent.FULLY_COMPLETED, change.taskId, change.category, change.description, eventAtEpochMs = change.queuedAtEpochMs)
    PendingOp.SET_PRIORITY -> null
}

/** Parses an incoming message; null if it isn't one, is from a newer contract version, or is missing its row identity. */
private fun Intent.toTaskEvent(): TaskEvent? {
    if (action != TASK_EVENT_ACTION) return null
    if (getIntExtra(EXTRA_SCHEMA_VERSION, 0) !in 1..TASK_EVENT_SCHEMA_VERSION) return null
    val event = getStringExtra(EXTRA_EVENT) ?: return null
    val category = getStringExtra(EXTRA_CATEGORY)?.takeIf { it.isNotBlank() } ?: return null
    val description = getStringExtra(EXTRA_DESCRIPTION)?.takeIf { it.isNotBlank() } ?: return null
    return TaskEvent(
        event = event,
        taskId = getStringExtra(EXTRA_TASK_ID)?.ifBlank { null },
        category = category,
        description = description,
        link = getStringExtra(EXTRA_LINK).orEmpty(),
        importance = getDoubleExtra(EXTRA_IMPORTANCE, 0.0).toFloat(),
        urgency = getDoubleExtra(EXTRA_URGENCY, 0.0).toFloat(),
        eventAtEpochMs = getLongExtra(EXTRA_EVENT_AT, System.currentTimeMillis())
    )
}

/**
 * Receives MicroTasking's messages. Manifest-declared and protected by [TASK_EVENTS_PERMISSION]
 * (signature level - only an app signed with the same key can send), so it runs whether or not
 * ActiveTasks is open: it writes the single change into the saved copy, and an open carousel picks
 * it up through [TaskStore]'s state. No network work here.
 */
class TaskEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = intent.toTaskEvent() ?: return
        TaskStore.applyIncomingEvent(context, event)
    }
}
