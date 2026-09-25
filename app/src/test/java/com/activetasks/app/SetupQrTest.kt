// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.activetasks.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupQrTest {
    private val sheet = "https://docs.google.com/spreadsheets/d/abc123"
    private val webApp = "https://script.google.com/macros/s/AKfycbXYZ/exec"

    @Test
    fun sheetOnly_isTreatedAsSheet() {
        assertEquals(SetupQrPayload(sheet, null), parseSetupQr(sheet))
    }

    @Test
    fun webAppOnly_isTreatedAsWebApp() {
        assertEquals(SetupQrPayload(null, webApp), parseSetupQr(webApp))
    }

    @Test
    fun both_splitByContentInEitherOrder() {
        val expected = SetupQrPayload(sheet, webApp)
        assertEquals(expected, parseSetupQr("$sheet\n$webApp"))
        assertEquals(expected, parseSetupQr("$webApp\r\n$sheet"))
    }

    @Test
    fun blankLinesAndWhitespaceAreIgnored() {
        assertEquals(SetupQrPayload(sheet, webApp), parseSetupQr("\n  $sheet  \n\n $webApp \n"))
    }

    @Test
    fun emptyText_hasNeither() {
        assertEquals(SetupQrPayload(null, null), parseSetupQr("  \n "))
    }

    @Test
    fun workspaceWebAppUrl_isRecognized() {
        assertTrue(looksLikeWebAppUrl("https://script.google.com/a/macros/example.com/s/AKfycb/exec"))
    }

    @Test
    fun isDifferentSheet_comparesSpreadsheetIdsNotUrlText() {
        val bare = "https://docs.google.com/spreadsheets/d/abc123"
        val edit = "https://docs.google.com/spreadsheets/d/abc123/edit#gid=0"
        assertFalse(isDifferentSheet(bare, edit))
        assertTrue(isDifferentSheet(bare, "https://docs.google.com/spreadsheets/d/xyz789/edit"))
        assertFalse(isDifferentSheet("", bare))
        assertFalse(isDifferentSheet(bare, "not a sheet url"))
    }

    @Test
    fun sheetUrl_isNotAWebAppUrl() {
        assertFalse(looksLikeWebAppUrl(sheet))
        assertNull(parseSetupQr(sheet).webAppUrl)
    }
}
