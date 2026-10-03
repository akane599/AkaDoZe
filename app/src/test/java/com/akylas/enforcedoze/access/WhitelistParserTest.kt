package com.akylas.enforcedoze.access

import org.junit.Assert.*
import org.junit.Test

class WhitelistParserTest {
    private fun parse(vararg lines: String, exit: Int = 0, timeout: Boolean = false) =
        WhitelistParser.parse(CommandResult(exit, lines.toList(), emptyList(), 1, timeout))

    @Test fun partialOutputKeepsValidRowsAndCountsOnlyMalformedNonblankLines() {
        val result = parse("system,com.android.phone,1001", "OEM new format", "user,com.example.app,10123",
            "user,com.example.app,10123", "", "user,not-a-package,123", "system-excidle,com.example.other,123")
        assertEquals(listOf("com.android.phone", "com.example.app"), result.packages)
        assertEquals(2, result.unparsedLineCount)
        assertEquals(WhitelistParseReason.PARTIALLY_PARSED, result.parseReason)
        assertFalse("partial output cannot verify absence during removal", result.verified)
    }

    @Test fun transportFailureAndTimeoutAreNotMisreportedAsParsingErrors() {
        val failed = parse("user,com.example.app,10123", "denied", exit = 1)
        assertEquals(WhitelistParseReason.COMMAND_FAILED, failed.parseReason)
        assertEquals(listOf("com.example.app"), failed.packages)
        assertEquals(1, failed.unparsedLineCount)
        assertFalse(failed.verified)
        val timeout = parse("user,com.example.app,10123", timeout = true)
        assertEquals(WhitelistParseReason.TIMED_OUT, timeout.parseReason)
        assertFalse(timeout.verified)
    }

    @Test fun fullyParsedAndEmptyHaveDistinctReasonsWithoutChangingReadbackSemantics() {
        val full = parse(" user , com.example.app , 10123 ")
        assertNull(full.parseReason)
        assertTrue(full.verified)
        assertEquals(0, full.unparsedLineCount)
        for (empty in listOf(parse(), parse("", "  "), parse("system-excidle,com.example.app,10123"))) {
            assertEquals(WhitelistParseReason.EMPTY, empty.parseReason)
            assertTrue("successful empty deep whitelist verifies absence", empty.verified)
            assertTrue(empty.packages.isEmpty())
        }
    }
}
