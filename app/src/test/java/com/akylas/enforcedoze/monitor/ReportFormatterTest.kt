package com.akylas.enforcedoze.monitor

import com.akylas.enforcedoze.doze.DeepState
import com.akylas.enforcedoze.doze.DozeEvent
import com.akylas.enforcedoze.doze.EventType
import com.akylas.enforcedoze.access.Feature
import org.junit.Assert.*
import org.junit.Test

class ReportFormatterTest {
    @Test fun reportContainsHeaderSessionCoverageProblemsAndTimeline() {
        val events = listOf(
            JournalEvent(1, 0, 1_000, 9, Source.APP, EventType.SCREEN_OFF),
            JournalEvent(1, 5_000, 6_000, 9, Source.APP, EventType.VERIFY, deep = DeepState.IDLE),
            JournalEvent(1, 100_000, 101_000, 9, Source.APP, EventType.SCREEN_ON),
        )
        val report = ReportFormatter.format(SessionAggregator.summarize(events), events,
            "1.10.2", mapOf("Model" to "Test device", "API" to "36"))
        assertTrue(report.startsWith("EnforceDoze monitor report\nApp version: 1.10.2\nDevice:\n"))
        assertTrue(report.contains("Sessions (1)"))
        assertTrue(report.contains("Duration ms: 100000"))
        assertTrue(report.contains("DEEP_IDLE: 95.00% (95000 ms)"))
        assertTrue(report.contains("UNKNOWN: 5.00% (5000 ms)"))
        assertTrue(report.contains("First verified deep IDLE ms: 5000"))
        assertTrue(report.contains("Problems: SENSORS_UNVERIFIED"))
        assertTrue(report.contains("Timeline (3)"))
        assertTrue(report.contains("source=APP kind=SCREEN_OFF"))
    }

    @Test fun emptyReportAndZeroDurationDoNotCrashOrPrintNan() {
        val empty = ReportFormatter.format(emptyList(), emptyList(), "test", emptyMap())
        assertTrue(empty.contains("Sessions (0)"))
        assertTrue(empty.endsWith("Timeline (0)\n"))
        val events = listOf(JournalEvent(1, 0, 0, 1, Source.APP, EventType.SCREEN_OFF))
        val report = ReportFormatter.format(SessionAggregator.summarize(events), events, "test", emptyMap())
        assertFalse(report.contains("NaN"))
        assertFalse(report.contains("Infinity"))
        assertTrue(report.contains("PARTIAL_SESSION"))
        assertTrue(report.contains("UNVERIFIED"))
    }

    @Test fun packagesAreRedactedInDetailsReasonsAndDeviceMetadataButFeaturesRemain() {
        val events = listOf(
            JournalEvent(1, 0, 0, 1, Source.APP, EventType.SCREEN_OFF),
            JournalEvent(1, 1_000, 1_000, 1, Source.APP, EventType.SKIPPED,
                detail = "APP_SUSPEND target=com.example.private and org.other_app.Hidden\nInjected"),
            JournalEvent(1, 2_000, 2_000, 1, Source.OS_HISTORY, null,
                detail = "com.example.private", historyKind = com.akylas.enforcedoze.doze.parse.HistoryKind.NORMAL),
            JournalEvent(1, 3_000, 3_000, 1, Source.APP, EventType.SCREEN_ON),
        )
        val report = ReportFormatter.format(SessionAggregator.summarize(events), events,
            "1.10.2", mapOf("com.example.key" to "com.example.value"))
        assertFalse(report.contains("com.example"))
        assertFalse(report.contains("org.other_app"))
        assertTrue(report.contains("APP_SUSPEND"))
        assertTrue(report.contains("[package]"))
        assertFalse(report.contains("\nInjected"))
    }

    @Test fun engineEventConversionKeepsEvidenceAndFeatureWithoutTargetPackage() {
        val event = JournalEvent.fromDozeEvent(
            DozeEvent(EventType.VERIFY, "MOTION_SENSORS", deep = DeepState.IDLE,
                feature = Feature.MOTION_SENSORS, target = "com.example.private"),
            2, 100, 1_000, 3,
        )
        assertEquals(DeepState.IDLE, event.deep)
        assertEquals("MOTION_SENSORS", event.detail)
        assertEquals(Source.APP, event.source)
        assertEquals(2, event.bootId)
        assertNull(event.historyKind)
    }
}
