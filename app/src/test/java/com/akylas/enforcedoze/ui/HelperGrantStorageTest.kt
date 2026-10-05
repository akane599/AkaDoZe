package com.akylas.enforcedoze.ui

import com.akylas.enforcedoze.access.Prefs
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class HelperGrantStorageTest {
    @Test
    fun helperRecordAndResetUseTheSameDeviceLocalFile() {
        assertEquals("helper_grants", Prefs.HELPER_GRANTS)
        val manager = source("access/AccessManager.kt")
        assertTrue(manager.contains("app.getSharedPreferences(Prefs.HELPER_GRANTS, Context.MODE_PRIVATE)"))
        assertFalse(manager.contains("prefs.getStringSet(Prefs.APPLIED_HELPERS"))
        assertFalse(manager.contains("prefs.edit().putStringSet(Prefs.APPLIED_HELPERS"))
        val settings = source("SettingsActivity.java").substringAfter("public void resetForceDoze()")
        assertTrue(settings.contains("context.getSharedPreferences(Prefs.HELPER_GRANTS, Context.MODE_PRIVATE), ResetReport.TRACKER"))
        val report = source("ui/ResetReport.java")
        assertTrue(report.contains("clearPreferences(prefs, helperPrefs, result)"))
        assertTrue(report.contains("return editor.commit() && helperPrefs.edit().clear().commit();"))
    }

    @Test
    fun helperRecordIsExcludedFromBackupAndBothExtractionSections() {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val backup = builder.parse(File("src/main/res/xml/backup_rules.xml"))
        assertExcluded(backup.documentElement)
        val extraction = builder.parse(File("src/main/res/xml/data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = extraction.getElementsByTagName(section)
            assertEquals(section, 1, nodes.length)
            assertExcluded(nodes.item(0) as Element)
        }
    }

    private fun assertExcluded(section: Element) {
        val exclusions = section.getElementsByTagName("exclude")
        val found = (0 until exclusions.length).map { exclusions.item(it) as Element }.any {
            it.getAttribute("domain") == "sharedpref" && it.getAttribute("path") == "${Prefs.HELPER_GRANTS}.xml"
        }
        assertTrue("${section.tagName} must exclude device-local helper attempts", found)
    }

    private fun source(path: String) = File("src/main/java/com/akylas/enforcedoze", path).readText()
}
