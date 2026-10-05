package com.akylas.enforcedoze.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android adapters stay thin; the ordering and parsing behavior are tested with pure fakes. */
class HonestyApiWiringTest {
    private fun source(path: String): String {
        val file = listOf(File(path), File("app/$path")).first { it.isFile }
        return file.readText()
    }

    @Test fun systemResetHasASerializedWorkerEntryPoint() {
        val runtime = source("src/main/java/com/akylas/enforcedoze/service/DozeRuntime.kt")
        assertTrue("reset must have an owned doze-worker callback instead of concurrent AsyncTask commands",
            runtime.contains("fun resetSystemState(callback: SystemResetCallback)"))
        val reset = runtime.substringAfter("fun resetSystemState(callback: SystemResetCallback)")
            .substringBefore("fun requestSafetyCheck()")
        assertTrue("generation is invalidated on caller before work is queued",
            reset.indexOf("bumpGeneration()") < reset.indexOf("worker().post"))
        assertTrue("runtime restores through controller, checks safety, and inspects damaged intent",
            reset.contains("controller.reconcile(") && reset.contains("checkSafety()") &&
                reset.contains("SystemReset.restoreOutcome(exit.complete, remaining, store.loadFailed, store.corruptLines)"))
        assertTrue("runtime injects own-app permission readback, not transport-only revocation success",
            reset.contains("permissionGranted = { permission ->") &&
                reset.contains("app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED"))
        assertTrue("result delivery is inside queued worker job", reset.contains("callback.onComplete(result)"))
        assertTrue("caller alone owns preference clearing", !reset.contains(".clear()"))
    }

    @Test fun noticesAreExcludedFromEveryBackupAndTransferSection() {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        for ((path, sections) in listOf(
            "src/main/res/xml/backup_rules.xml" to listOf("full-backup-content"),
            "src/main/res/xml/data_extraction_rules.xml" to listOf("cloud-backup", "device-transfer"),
        )) {
            val xml = factory.newDocumentBuilder().parse(source(path).byteInputStream())
            for (section in sections) {
                val excludes = (xml.getElementsByTagName(section).item(0) as org.w3c.dom.Element)
                    .getElementsByTagName("exclude")
                assertTrue("$section excludes device-specific debt notices", (0 until excludes.length).any {
                    val element = excludes.item(it) as org.w3c.dom.Element
                    element.getAttribute("domain") == "sharedpref" && element.getAttribute("path") == "notices.xml"
                })
            }
        }
    }

    @Test fun whitelistParsingRetainsTypedPartialDiagnostics() {
        val activity = source("src/main/java/com/akylas/enforcedoze/WhitelistAppsActivity.java")
        assertTrue("partial rows must use the pure parser and expose its diagnostics",
            activity.contains("WhitelistParser.parse(result)") && activity.contains("unparsedLineCount"))
    }
}
