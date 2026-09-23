// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.activetasks.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

data class SheetTabCsv(val tabName: String, val csv: String)

fun extractGoogleSheetId(url: String): String? =
    Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)").find(url)?.groupValues?.getOrNull(1)

private fun unescapeXmlEntities(text: String): String = text
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")

/**
 * Lists the spreadsheet's tab names in order by downloading the full workbook as .xlsx and
 * reading the sheet names straight out of the zip's xl/workbook.xml entry - no need to parse
 * actual cell data out of the xlsx, since tab data is still fetched per-name via the gviz CSV
 * export below.
 */
private fun fetchSheetTabNamesViaXlsx(spreadsheetId: String): List<String> = runCatching {
    val url = URL("https://docs.google.com/spreadsheets/d/$spreadsheetId/export?format=xlsx")
    java.util.zip.ZipInputStream(url.openStream()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == "xl/workbook.xml") {
                val xml = zip.readBytes().toString(Charsets.UTF_8)
                return@runCatching Regex("<sheet[^>]*\\sname=\"([^\"]+)\"").findAll(xml)
                    .map { unescapeXmlEntities(it.groupValues[1]) }
                    .toList()
            }
            entry = zip.nextEntry
        }
        emptyList()
    }
}.getOrDefault(emptyList())

/** Lists the spreadsheet's tab names via the legacy public worksheet feed - a fallback since Google has deprecated this GData API for many accounts. */
private fun fetchSheetTabNames(spreadsheetId: String): List<String> = runCatching {
    val feedUrl = "https://spreadsheets.google.com/feeds/worksheets/$spreadsheetId/public/basic?alt=json"
    val feed = JSONObject(URL(feedUrl).readText()).optJSONObject("feed") ?: return@runCatching emptyList()
    val entries = when (val entry = feed.opt("entry")) {
        is JSONArray -> entry
        is JSONObject -> JSONArray().put(entry)
        else -> JSONArray()
    }
    List(entries.length()) { index -> entries.getJSONObject(index).getJSONObject("title").getString("\$t") }
}.getOrDefault(emptyList())

/** Fetches one tab's rows as CSV, addressed by tab name rather than gid. */
private fun fetchSheetTabCsv(spreadsheetId: String, tabName: String): String = runCatching {
    val encodedName = URLEncoder.encode(tabName, "UTF-8")
    val url = "https://docs.google.com/spreadsheets/d/$spreadsheetId/gviz/tq?tqx=out:csv&sheet=$encodedName"
    URL(url).readText()
}.getOrDefault("")

/**
 * Splits full CSV text into logical records, each possibly spanning multiple physical lines when a
 * quoted field embeds a literal newline - the gviz CSV export quotes such a cell rather than
 * escaping the newline inside it. Splitting on line breaks before tracking quote state (the
 * previous approach: one [splitCsvLine] call per physical line) truncated any row with a
 * multi-line cell and misread the rest of that cell's lines as bogus extra rows (SPEC.md/
 * PUNCH_LIST.md "Harden against user edits").
 */
fun splitCsvRecords(csvText: String): List<String> {
    val records = mutableListOf<String>()
    val record = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < csvText.length) {
        val c = csvText[i]
        when {
            c == '"' && inQuotes && i + 1 < csvText.length && csvText[i + 1] == '"' -> {
                record.append("\"\"")
                i++
            }
            c == '"' -> {
                inQuotes = !inQuotes
                record.append(c)
            }
            (c == '\n' || c == '\r') && !inQuotes -> {
                if (c == '\r' && i + 1 < csvText.length && csvText[i + 1] == '\n') i++
                records.add(record.toString())
                record.setLength(0)
            }
            else -> record.append(c)
        }
        i++
    }
    if (record.isNotEmpty()) records.add(record.toString())
    return records
}

/**
 * Splits one CSV record into fields, honoring `"`-quoted fields: a comma inside quotes is literal
 * and `""` is an escaped quote. Only commas/quotes are special here - a record from
 * [splitCsvRecords] that spans multiple physical lines rides through with its embedded newline(s)
 * intact as ordinary field content, same as any other character.
 */
fun splitCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                field.append('"')
                i++
            }
            c == '"' -> inQuotes = !inQuotes
            c == ',' && !inQuotes -> {
                fields.add(field.toString())
                field.setLength(0)
            }
            else -> field.append(c)
        }
        i++
    }
    fields.add(field.toString())
    return fields.map { it.trim() }
}

/**
 * Fetches every tab of the spreadsheet at [url] as raw CSV text, one entry per tab, in tab order.
 * The README tab (a MicroTasking-sheet convention, present on the shared sheet this points at) is
 * skipped. Returns an empty list if the sheet's tabs can't be enumerated at all (e.g. sharing
 * settings block it) - there's no single-tab fallback here because unlike MicroTasking's task
 * pool, a to-do list without a list name to file it under isn't useful.
 */
fun fetchSheetTabs(url: String): List<SheetTabCsv> {
    val spreadsheetId = extractGoogleSheetId(url) ?: return emptyList()
    val tabNames = fetchSheetTabNamesViaXlsx(spreadsheetId)
        .ifEmpty { fetchSheetTabNames(spreadsheetId) }
        .filter { !it.equals("README", ignoreCase = true) }
    return tabNames.map { tabName -> SheetTabCsv(tabName, fetchSheetTabCsv(spreadsheetId, tabName)) }
}
