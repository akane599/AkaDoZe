package com.akylas.enforcedoze.service

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ServiceHelperGrantTest {
    private val source = File("src/main/java/com/akylas/enforcedoze/ForceDozeService.java").readText()

    @Test
    fun serviceRestartHasNoUnrecordedPrivilegedHelperCommands() {
        for (command in listOf("pm grant", "whitelist +", "appops set", "allow_listener")) {
            assertFalse("Service bypasses the helper record with $command", source.contains(command))
        }
        assertTrue(source.contains("AccessManager.getInstance(this).grantHelpersAutomatically()"))
    }

    @Test
    fun automaticServiceGrantsStayOnDozeWorkerAndUnprivilegedRequestRemains() {
        assertTrue(source.contains("worker = runtime.attachService();"))
        assertTrue(source.contains("postWork(this::initializeWorker)"))
        val initialize = source.substringAfter("private void initializeWorker()").substringBefore("public IBinder onBind")
        assertTrue(initialize.contains("grantHelpersAutomatically()"))
        val start = source.substringAfter("public int onStartCommand").substringBefore("public void reloadAppsBlockList")
        assertTrue(start.indexOf("postWork(() -> {") < start.indexOf("addSelfToDozeWhitelist();"))
        val whitelist = source.substringAfter("public void addSelfToDozeWhitelist()").substringBefore("private DozeConfig config")
        assertTrue(whitelist.contains("grantHelpersAutomatically()"))
        assertTrue(whitelist.contains("new Intent(this, RequestIgnoreBatteryActivity.class)"))
        assertTrue(whitelist.contains("notificationManager.notify(8765, n)"))
    }
}
