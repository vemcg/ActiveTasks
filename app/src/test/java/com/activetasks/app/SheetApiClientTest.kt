// Copyright (c) 2026 Vern McGeorge. All rights reserved.
package com.activetasks.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetApiClientTest {

    @Test
    fun parseWriteOutcome_isSuccess_whenOkIsTrue() {
        assertEquals(SheetWriteOutcome.Success, parseWriteOutcome(200, """{"ok":true}"""))
    }

    @Test
    fun parseWriteOutcome_isRowNotFound_forEachKnownNotFoundWording() {
        // Exact wordings from MicroTasking's doPost (scripts/populate_google_sheet.js) for a row it
        // can't resolve - see ROW_NOT_FOUND_MARKERS' doc comment for why this is string-matched
        // rather than a structured error code, and the tech debt that implies.
        val notFoundBodies = listOf(
            """{"ok":false,"error":"No tab named \"Old List\""}""",
            """{"ok":false,"error":"No row matching that description"}""",
            """{"ok":false,"error":"No row with that task id"}"""
        )
        notFoundBodies.forEach { body ->
            assertEquals("body: $body", SheetWriteOutcome.RowNotFound, parseWriteOutcome(200, body))
        }
    }

    @Test
    fun parseWriteOutcome_isFailure_forAnUnrecognizedErrorWording() {
        val outcome = parseWriteOutcome(200, """{"ok":false,"error":"Unknown action \"frobnicate\""}""")
        assertTrue(outcome is SheetWriteOutcome.Failure)
        assertEquals("Unknown action \"frobnicate\"", (outcome as SheetWriteOutcome.Failure).message)
    }

    @Test
    fun parseWriteOutcome_isFailure_onANon2xxResponseEvenWithOkTrueBody() {
        // A proxy/App Engine error page etc. shouldn't be trusted just because its body happens to
        // parse as {"ok":true} - the HTTP status is checked first.
        val outcome = parseWriteOutcome(500, """{"ok":true}""")
        assertTrue(outcome is SheetWriteOutcome.Failure)
        // The status is surfaced (not swallowed to null) so a UI error message has something
        // concrete to show instead of a generic "check your connection" for every distinct cause.
        assertTrue((outcome as SheetWriteOutcome.Failure).message!!.contains("500"))
    }

    @Test
    fun parseWriteOutcome_isFailure_onGarbageBody() {
        val outcome = parseWriteOutcome(200, "not json")
        assertTrue(outcome is SheetWriteOutcome.Failure)
        assertTrue((outcome as SheetWriteOutcome.Failure).message!!.isNotBlank())
    }
}
