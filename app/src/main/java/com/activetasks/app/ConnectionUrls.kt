// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.activetasks.app

/**
 * What Settings > Google Sheet Connection makes of one URL box: nothing to say ([Blank]), a note that
 * it's the wrong kind of URL ([Invalid]), or a link to show under the box ([Valid] - just the URL's
 * hash portion as the text, the complete URL as the target). Advisory only: it never blocks saving.
 * Identical rules in MicroTasking's `ConnectionUrls.kt`; change them in step.
 */
sealed class UrlCheck {
    data object Blank : UrlCheck()
    data class Invalid(val message: String) : UrlCheck()
    data class Valid(val id: String, val target: String) : UrlCheck()
}

private val SHEET_ID = Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)")

// `/macros/s/<id>/exec`, or the Workspace-account form `/a/macros/<domain>/s/<id>/exec`.
private val WEB_APP_ID = Regex("/macros/(?:[^/?#]+/)?s/([^/?#]+)")

/** The complete URL a link should open: trimmed, with `https://` added when the scheme was left off. */
private fun linkTarget(url: String): String = if (url.startsWith("http", ignoreCase = true)) url else "https://$url"

/** A Sheet URL must contain `docs.google.com/spreadsheets` and carry a `/spreadsheets/d/<id>`. Case-insensitive on the host part. */
fun checkSheetUrl(raw: String): UrlCheck {
    val url = raw.trim()
    if (url.isEmpty()) return UrlCheck.Blank
    val id = if (url.contains("docs.google.com/spreadsheets", ignoreCase = true)) SHEET_ID.find(url)?.groupValues?.get(1) else null
    return if (id != null) UrlCheck.Valid(id, linkTarget(url))
    else UrlCheck.Invalid("Not a Google Sheet URL - it should contain docs.google.com/spreadsheets/d/...")
}

/**
 * A Web App URL must contain `script.google.com/macros` (or the Workspace form
 * `script.google.com/a/macros`, which the plain rule would wrongly reject) and carry an `/s/<id>`.
 */
fun checkWebAppUrl(raw: String): UrlCheck {
    val url = raw.trim()
    if (url.isEmpty()) return UrlCheck.Blank
    val hostOk = url.contains("script.google.com/macros", ignoreCase = true) ||
        url.contains("script.google.com/a/macros", ignoreCase = true)
    val id = if (hostOk) WEB_APP_ID.find(url)?.groupValues?.get(1) else null
    return if (id != null) UrlCheck.Valid(id, linkTarget(url))
    else UrlCheck.Invalid("Not an Apps Script Web App URL - it should contain script.google.com/macros/s/...")
}
