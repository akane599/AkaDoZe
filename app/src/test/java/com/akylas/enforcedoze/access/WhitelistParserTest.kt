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

    @Test fun platformAndroidRowDoesNotMakeValidWhitelistUnverified() {
        val result = parse("system,android,1000", "system,com.android.phone,1001",
            "user,com.example.app,10123", "system-excidle,com.example.other,10124")
        assertTrue("the platform android row is valid readback", result.verified)
        assertNull(result.parseReason)
        assertEquals(0, result.unparsedLineCount)
        assertEquals(listOf("android", "com.android.phone", "com.example.app"), result.packages)
    }

    @Test fun bothReadersAcceptTheSameRowsAndExcludeExceptIdleMembership() {
        val target = "com.example.app"
        for ((row, member) in listOf(
            " system , $target , 0 " to true,
            "user,$target,2147483647" to true,
            "system-excidle,$target,10123" to false,
        )) {
            val result = parse("", row, "  ")
            assertTrue(row, result.verified)
            assertEquals(row, 0, result.unparsedLineCount)
            assertEquals(row, member, target in result.packages)
            assertEquals(row, member, ExternalControlPolicy.whitelistMembership(listOf("", row, "  "), target))
        }
        val exceptIdle = parse("system-excidle,android,1000")
        assertEquals(WhitelistParseReason.EMPTY, exceptIdle.parseReason)
        assertTrue(exceptIdle.verified)
        assertTrue(exceptIdle.packages.isEmpty())
    }

    @Test fun bothReadersRejectMalformedRowsEvenAlongsideValidMembership() {
        val target = "com.example.app"
        val valid = "user,$target,10123"
        for (bad in listOf(
            "OEM unknown", "other,$target,10123", "user,not-a-package,123", "user,Android,1000",
            "user,$target,-1", "user,$target,2147483648", "user,$target,1.5",
            "user,$target,", "user,$target", "user,$target,10123,extra",
        )) {
            val result = parse(valid, bad)
            assertEquals(bad, listOf(target), result.packages)
            assertEquals(bad, 1, result.unparsedLineCount)
            assertEquals(bad, WhitelistParseReason.PARTIALLY_PARSED, result.parseReason)
            assertFalse(bad, result.verified)
            assertNull(bad, ExternalControlPolicy.whitelistMembership(listOf(valid, bad), target))
        }
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
