// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.activetasks.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionUrlsTest {

    @Test
    fun blankInputSaysNothing() {
        assertEquals(UrlCheck.Blank, checkSheetUrl(""))
        assertEquals(UrlCheck.Blank, checkWebAppUrl("   "))
    }

    @Test
    fun sheetUrl_validShowsJustTheIdAndLinksTheCompleteUrl() {
        val url = "https://docs.google.com/spreadsheets/d/abc-123_X/edit?usp=sharing#gid=0"
        assertEquals(UrlCheck.Valid("abc-123_X", url), checkSheetUrl("  $url  "))
    }

    @Test
    fun sheetUrl_wrongKindOfUrlIsInvalid() {
        assertTrue(checkSheetUrl("https://example.com/spreadsheets/d/abc123") is UrlCheck.Invalid)
        assertTrue(checkSheetUrl("https://script.google.com/macros/s/abc/exec") is UrlCheck.Invalid)
        // Right host but no id to show.
        assertTrue(checkSheetUrl("https://docs.google.com/spreadsheets") is UrlCheck.Invalid)
    }

    @Test
    fun sheetUrl_hostMatchIsCaseInsensitive() {
        assertEquals(
            UrlCheck.Valid("abc123", "https://DOCS.google.com/spreadsheets/d/abc123"),
            checkSheetUrl("https://DOCS.google.com/spreadsheets/d/abc123")
        )
    }

    @Test
    fun webAppUrl_validShowsJustTheDeploymentId() {
        val url = "https://script.google.com/macros/s/AKfycb123/exec"
        assertEquals(UrlCheck.Valid("AKfycb123", url), checkWebAppUrl(url))
    }

    @Test
    fun webAppUrl_acceptsTheWorkspaceAccountForm() {
        val url = "https://script.google.com/a/macros/example.com/s/AKfycb123/exec"
        assertEquals(UrlCheck.Valid("AKfycb123", url), checkWebAppUrl(url))
    }

    @Test
    fun webAppUrl_wrongKindOfUrlIsInvalid() {
        assertTrue(checkWebAppUrl("https://docs.google.com/spreadsheets/d/abc123") is UrlCheck.Invalid)
        // The Apps Script editor's address, the commonest mix-up.
        assertTrue(checkWebAppUrl("https://script.google.com/home/projects/abc/edit") is UrlCheck.Invalid)
        assertTrue(checkWebAppUrl("https://script.google.com/macros") is UrlCheck.Invalid)
    }

    @Test
    fun linkTarget_addsHttpsOnlyWhenTheSchemeIsMissing() {
        val check = checkWebAppUrl("script.google.com/macros/s/x/exec") as UrlCheck.Valid
        assertEquals("https://script.google.com/macros/s/x/exec", check.target)
    }
}
