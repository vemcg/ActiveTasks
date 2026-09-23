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

/**
 * Result of a write-back POST (setPriority/clearPriority/deleteRow). [RowNotFound] means the Web
 * App itself reported the row doesn't exist anymore (its tab is gone, or its description/taskId no
 * longer matches any row) - not a network/connectivity problem, so retrying the identical request
 * will just keep failing the same way; callers use this to offer removing the item locally instead
 * of leaving it stuck forever. See SPEC.md/PUNCH_LIST.md "Harden against user edits to the shared
 * Sheet".
 */
sealed class SheetWriteOutcome {
    data object Success : SheetWriteOutcome()
    data object RowNotFound : SheetWriteOutcome()
    data class Failure(val message: String?) : SheetWriteOutcome()
}

/**
 * The exact wordings MicroTasking's `doPost` (scripts/populate_google_sheet.js) returns when it
 * can't resolve the target row - an unknown tab, a description that no longer matches any row in
 * that tab, or a taskId no row carries anymore. Matched by substring (case-insensitive) rather than
 * equality so a script that appends extra detail still classifies correctly; wording it doesn't
 * recognize falls back to a generic [SheetWriteOutcome.Failure] - safe, since that keeps the item
 * retryable instead of misclassifying a real network/server error as "confirmed gone".
 *
 * Tech debt: this is v1-contract string-sniffing, not a real error code. MicroTasking's SPEC.md
 * "Sheet connection & API" (PUNCH_LIST.md item 3 here / its item 9) specifies structured
 * `no_such_category`/`no_such_row`/`no_such_task_id`-style codes for the same cases - once that
 * ships and this app moves onto it, replace this list with a code check instead of English text.
 */
private val ROW_NOT_FOUND_MARKERS = listOf(
    "no tab named",
    "no row matching that description",
    "no row with that task id"
)

/**
 * Pure parse of one write-back HTTP response, split out from [postAction] so it's unit-testable
 * (plain JUnit, no server) the same way the rest of this codebase's string/JSON parsing is.
 */
fun parseWriteOutcome(responseCode: Int, responseBody: String): SheetWriteOutcome {
    if (responseCode !in 200..299) {
        return SheetWriteOutcome.Failure("HTTP $responseCode: ${responseBody.take(200)}".trim())
    }
    return runCatching {
        val json = JSONObject(responseBody)
        if (json.optBoolean("ok", false)) return SheetWriteOutcome.Success
        val error = json.optString("error", "")
        if (ROW_NOT_FOUND_MARKERS.any { error.contains(it, ignoreCase = true) }) SheetWriteOutcome.RowNotFound
        else SheetWriteOutcome.Failure(error.ifBlank { "Sheet reported failure with no error message" })
    }.getOrDefault(SheetWriteOutcome.Failure("Unexpected response body: ${responseBody.take(200)}"))
}

private const val MAX_REDIRECTS = 5
private val REDIRECT_CODES = setOf(
    HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308
)

/**
 * `{webAppUrl}` (script.google.com/macros/s/.../exec) 302-redirects to a one-time
 * script.googleusercontent.com URL. Confirmed on-device (2026-09-23, HTTP 405 from that URL) that
 * this redirect target only ever accepts GET: Apps Script executes `doPost` against the *initial*
 * POST request itself (its body already reached the script before the redirect happens) and the
 * redirect is purely to hand back the already-computed JSON result from a plain content host -
 * replaying it as a second POST (an earlier version of this function did exactly that, to work
 * around a since-disproven theory that the body was being lost) gets a 405, not `doPost`'s
 * response. So: send the POST once, and if redirected, fetch the result with a GET.
 */
private fun postJson(urlString: String, bodyBytes: ByteArray): Pair<Int, String> {
    val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        instanceFollowRedirects = false
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
    }
    connection.outputStream.use { it.write(bodyBytes) }
    val responseCode = connection.responseCode
    if (responseCode in REDIRECT_CODES) {
        val location = connection.getHeaderField("Location")
        connection.disconnect()
        if (location != null) return httpGet(location)
    }
    val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader()?.readText().orEmpty()
    connection.disconnect()
    return responseCode to responseBody
}

/** Follows a GET redirect chain manually rather than trusting a given JDK/Android version's default auto-follow behavior for a cross-host https redirect. */
private fun httpGet(urlString: String, redirectsLeft: Int = MAX_REDIRECTS): Pair<Int, String> {
    val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        instanceFollowRedirects = false
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
    }
    val responseCode = connection.responseCode
    if (responseCode in REDIRECT_CODES && redirectsLeft > 0) {
        val location = connection.getHeaderField("Location")
        connection.disconnect()
        if (location != null) return httpGet(location, redirectsLeft - 1)
    }
    val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader()?.readText().orEmpty()
    connection.disconnect()
    return responseCode to responseBody
}

private fun postAction(appsScriptUrl: String, body: JSONObject): SheetWriteOutcome = runCatching {
    val (responseCode, responseBody) = postJson(appsScriptUrl.trimEnd('/'), body.toString().toByteArray(Charsets.UTF_8))
    parseWriteOutcome(responseCode, responseBody)
}.getOrElse { SheetWriteOutcome.Failure(it.message ?: it.javaClass.simpleName) }

/**
 * "Complete (for now)": clears importance/urgency so MicroTasking can queue the row again.
 * [taskId], when the item has one, is sent alongside category/description so the write is
 * rename-proof even if the sheet's description text has changed since this item was imported.
 */
fun clearSheetPriority(appsScriptUrl: String, category: String, description: String, taskId: String? = null): SheetWriteOutcome =
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
fun deleteSheetRow(appsScriptUrl: String, category: String, description: String, taskId: String? = null): SheetWriteOutcome =
    postAction(
        appsScriptUrl,
        JSONObject().apply {
            put("action", "deleteRow")
            put("category", category)
            put("description", description)
            if (taskId != null) put("taskId", taskId)
        }
    )
