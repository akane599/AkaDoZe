package com.akylas.enforcedoze.access

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNamesTest {
    @Test
    fun acceptsOnlyBoundedAsciiPackageGrammar() {
        for (name in listOf("a.b", "com.akylas.enforcedoze", "A_1.B2_", "a.${"b".repeat(253)}")) {
            assertTrue(name, PackageNames.isValid(name))
        }
        for (name in listOf(
            "", "a", "a..b", ".a.b", "a.b.", "1a.b", "a.1b", "_a.b", "a-b.c",
            "a.b;reboot", "\$(x)", "a.b && reboot", "a.b|reboot", "a.b`id`", "a.b\nreboot",
            "a.b\r", "a.b ", " a.b", "a.b/c", "a.b'", "a.b\"", "é.a", "a.日本", "a.b\u0000",
            "a.${"b".repeat(254)}",
        )) {
            assertFalse(name, PackageNames.isValid(name))
        }
    }
}
