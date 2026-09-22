// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.activetasks.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** What a scanned setup QR code carried: either value may be absent (see [parseSetupQr]). */
data class SetupQrPayload(val sheetUrl: String?, val webAppUrl: String?)

/**
 * Apps Script Web App URLs live on script.google.com (`/macros/s/<id>/exec`, or
 * `/a/macros/<domain>/s/<id>/exec` for Workspace accounts); a Google Sheet URL never does.
 * Same classification as MicroTasking's `looksLikeWebAppUrl`.
 */
fun looksLikeWebAppUrl(text: String): Boolean =
    text.startsWith("http", ignoreCase = true) && text.contains("script.google.com/", ignoreCase = true)

/**
 * Splits a scanned setup QR (one URL per line) into its Sheet URL and Web App URL by what each
 * line looks like rather than by position, so a code carrying only the Web App URL, only the Sheet
 * URL, or both (older combined codes) in either order all work. Mirrors MicroTasking's
 * `parseSetupQr`.
 */
fun parseSetupQr(scannedText: String): SetupQrPayload {
    val lines = scannedText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    return SetupQrPayload(
        sheetUrl = lines.firstOrNull { !looksLikeWebAppUrl(it) },
        webAppUrl = lines.firstOrNull { looksLikeWebAppUrl(it) }
    )
}

/**
 * Client for the Apps Script Web App that both ActiveTasks and MicroTasking use to read/write the
 * hidden, protected Importance/Urgency columns (and to delete a fully-completed row). Those two
 * columns are never read via the plain CSV/gviz export used elsewhere in this file/SheetImport.kt
 * - CSV export includes hidden columns' raw data regardless of Sheets-UI hidden state, which
 * would defeat the "invisible to the user" requirement. See SPEC.md "Referral bridge".
 *
 * Contract as deployed by MicroTasking's repo (scripts/populate_google_sheet.js there), confirmed
 * 2026-09-18, `taskId` added since (backward-compatible - optional on both sides):
 *   GET  {webAppUrl}?action=getPriorities
 *        -> {"ok":true,"rows":[{"category","description","importance","urgency","taskId"?}, ...]}
 *           - every referred row across every tab in one call, not one call per tab.
 *   POST {webAppUrl} {"action":"setPriority"|"clearPriority"|"deleteRow","category","description",
 *        "taskId"?, ["importance","urgency" for setPriority]} -> {"ok":true} or
 *        {"ok":false,"error":"..."}
 * Row identity is (category = tab name, description = column B text, exact trimmed match) unless
 * `taskId` is sent, in which case the server matches by that surrogate key instead - never a
 * row/gid index either way. `taskId` is MicroTasking's per-row surrogate key (its Sheet's hidden
 * "Task ID" column, stamped once and never recomputed); sending it makes writes survive a
 * description that's been renamed in the sheet since the row was read.
 */
data class SheetPriority(val importance: Float, val urgency: Float)

/** One referred row as returned by `getPriorities`, before grouping by tab/category. */
data class ReferredRow(val category: String, val description: String, val taskId: String?, val priority: SheetPriority)

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 15_000

/**
 * Fetches every currently-referred row (across all tabs) in one call. A row absent from the
 * result has not been referred (no importance/urgency set) and must not be imported - see
 * [toDoItemsFromReferredRows], which callers feed by grouping this list per tab/category.
 */
fun fetchAllPriorities(appsScriptUrl: String): List<ReferredRow> = runCatching {
    val url = "${appsScriptUrl.trimEnd('/')}?action=getPriorities"
    val json = JSONObject(URL(url).readText())
    if (!json.optBoolean("ok", false)) return@runCatching emptyList()
    val rows = json.optJSONArray("rows") ?: return@runCatching emptyList()
    (0 until rows.length()).mapNotNull { i ->
        val row = rows.getJSONObject(i)
        val category = row.optString("category")
        val description = row.optString("description")
        if (category.isBlank() || description.isBlank()) return@mapNotNull null
        ReferredRow(
            category = category,
            description = description,
            taskId = row.optString("taskId", "").ifBlank { null },
            priority = SheetPriority(
                importance = row.optDouble("importance", 0.0).toFloat(),
                urgency = row.optDouble("urgency", 0.0).toFloat()
            )
        )
    }
}.getOrDefault(emptyList())

private fun postAction(appsScriptUrl: String, body: JSONObject): Boolean = runCatching {
    val connection = (URL(appsScriptUrl.trimEnd('/')).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
    }
    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    val responseCode = connection.responseCode
    val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader()?.readText().orEmpty()
    connection.disconnect()
    responseCode in 200..299 && JSONObject(responseBody).optBoolean("ok", false)
}.getOrDefault(false)

/**
 * "Complete (for now)": clears importance/urgency so MicroTasking can queue the row again.
 * [taskId], when the item has one, is sent alongside category/description so the write is
 * rename-proof even if the sheet's description text has changed since this item was imported.
 */
fun clearSheetPriority(appsScriptUrl: String, category: String, description: String, taskId: String? = null): Boolean =
    postAction(
        appsScriptUrl,
        JSONObject().apply {
            put("action", "clearPriority")
            put("category", category)
            put("description", description)
            if (taskId != null) put("taskId", taskId)
        }
    )

/**
 * "Fully complete": deletes the row outright (checkbox + description + link + hidden columns).
 * [taskId] is sent the same way and for the same reason as in [clearSheetPriority].
 */
fun deleteSheetRow(appsScriptUrl: String, category: String, description: String, taskId: String? = null): Boolean =
    postAction(
        appsScriptUrl,
        JSONObject().apply {
            put("action", "deleteRow")
            put("category", category)
            put("description", description)
            if (taskId != null) put("taskId", taskId)
        }
    )
