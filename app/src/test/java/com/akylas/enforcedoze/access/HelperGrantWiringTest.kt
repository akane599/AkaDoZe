package com.akylas.enforcedoze.access

import com.akylas.enforcedoze.LogActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HelperGrantWiringTest {
    @Test
    fun openingMainAndTunablesUsesRecordedAutomaticGrants() {
        for (path in listOf("MainActivity.java", "DozeTunablesActivity.java")) {
            val source = source(path)
            assertTrue(path, source.contains("accessManager.grantHelpersAutomatically()"))
            assertFalse(path, source.contains("accessManager.grantHelpers()"))
        }
    }

    @Test
    fun accessCardAndSettingsUserActionsRemainExplicit() {
        assertTrue(source("ui/AccessUi.java").contains("results = access.grantHelpers();"))
        val settings = source("SettingsActivity.java")
        assertEquals(1, Regex("accessManager\\.grantHelpers\\(\\)").findAll(settings).count())
        val toggle = settings.substringAfter("turnOffDataInDoze.setOnPreferenceChangeListener").substringBefore("whitelistMusicAppNetwork.setOnPreferenceChangeListener")
        assertTrue(toggle.contains("accessManager.grantHelper(\"READ_PHONE_STATE\")"))
        assertFalse(toggle.contains("grantHelpers()"))
        assertFalse(settings.contains("grantHelpersAutomatically"))
    }

    @Test
    fun logReadsNeedNoGrantAndUnavailableOutputIsHandled() {
        val logCommand = LogActivity::class.java.getDeclaredMethod("logCommand", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        assertEquals("logcat -d", logCommand.invoke(null, true))
        assertTrue((logCommand.invoke(null, false) as String).startsWith("logcat -d -s EnforceDoze"))
        val logs = source("LogActivity.java")
        assertFalse(logs.contains("READ_LOGS"))
        assertFalse(logs.contains("pm grant"))
        assertTrue(logs.contains("manager.reads().run(logCommand(full))"))
        assertTrue(logs.contains("if (result.getOk()) saveAndShareFullLog(result.getStdout());"))
        assertTrue(logs.contains("else log(\"Unable to get full logcat\");"))
        assertTrue(logs.contains("log = result.getOk() ? new ArrayList<>(result.getStdout()) : Collections.emptyList();"))
        assertTrue(logs.contains("public void onError(Context context, Exception error)"))
        assertTrue(logs.contains("log = Collections.emptyList();"))
    }

    @Test
    fun managerSerializesAndPersistsEachAttemptBeforeMutation() {
        val manager = source("access/AccessManager.kt")
        assertTrue(manager.contains("fun grantHelpers(): Map<String, CommandResult> = grantHelpers(HelperGrantPolicy.Trigger.EXPLICIT)"))
        assertTrue(manager.contains("fun grantHelper(item: String): Map<String, CommandResult> = grantHelpers(HelperGrantPolicy.Trigger.EXPLICIT, item)"))
        assertTrue(manager.contains("fun grantHelpersAutomatically(): Map<String, CommandResult> = grantHelpers(HelperGrantPolicy.Trigger.AUTOMATIC)"))
        val batch = manager.substringAfter("private fun grantHelpers(trigger:").substringBefore("private fun readGrants")
        assertTrue(batch.contains("synchronized(helperGrantLock)"))
        assertTrue(batch.contains("requireBackgroundThread()"))
        assertTrue(batch.contains("helperPrefs.getStringSet(Prefs.APPLIED_HELPERS, emptySet()).orEmpty()"))
        assertTrue(batch.contains(".filterKeys { item == null || it == item }"))
        assertTrue(batch.contains("HelperGrantPolicy.commands("))
        assertTrue(batch.contains("HelperGrantPolicy.runAttempts(commands, applied, persist ="))
        assertTrue(batch.contains("helperPrefs.edit().putStringSet(Prefs.APPLIED_HELPERS, record).commit()"))
        assertTrue(batch.contains("if (!saved) android.util.Log.e("))
        assertFalse(batch.contains("check("))
        assertTrue(batch.contains("execute = controlRunner::run"))
        assertFalse(source("Utils.java").contains("grantPermissionsViaShizuku"))
    }

    private fun source(path: String): String = File("src/main/java/com/akylas/enforcedoze", path).readText()
}
