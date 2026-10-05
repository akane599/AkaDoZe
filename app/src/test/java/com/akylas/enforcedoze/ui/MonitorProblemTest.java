package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.Grants;
import com.akylas.enforcedoze.access.Reason;
import com.akylas.enforcedoze.doze.DeepState;
import com.akylas.enforcedoze.doze.EventType;
import com.akylas.enforcedoze.doze.SensorMode;
import com.akylas.enforcedoze.monitor.JournalEvent;
import com.akylas.enforcedoze.monitor.Source;
import com.akylas.enforcedoze.service.SelfTestKind;
import com.akylas.enforcedoze.service.SelfTestOutcome;
import com.akylas.enforcedoze.service.SelfTestResult;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** SQ-42 NIT-1: a failed readback is a problem row even when it carries the Doze or sensor state it read. */
public class MonitorProblemTest {
    private static JournalEvent verify(String detail, DeepState deep, SensorMode sensor) {
        return new JournalEvent(1, 0L, 0L, 1L, Source.APP, EventType.VERIFY, deep, null, sensor, null, null, detail);
    }

    @Test
    public void unverifiedForceDozeWithDeepStateIsAProblem() {
        assertTrue(MonitorFormat.isProblem(verify("FORCE_DOZE: UNVERIFIED", DeepState.ACTIVE, null)));
    }

    @Test
    public void unverifiedSensorRestrictionWithSensorModeIsAProblem() {
        assertTrue(MonitorFormat.isProblem(verify("MOTION_SENSORS: UNVERIFIED", null, SensorMode.NORMAL)));
    }

    // --- SQ-118 (1C): self-test reasons follow the test's kind and the live grants ---

    private static final int API = 34;

    private static AccessState app(boolean dump) {
        return new AccessState(AccessLevel.APP, null, new Grants(dump, false), null);
    }

    private static SelfTestResult unavailable(SelfTestKind kind, Reason reason) {
        return new SelfTestResult(kind, SelfTestOutcome.UNAVAILABLE, reason);
    }

    @Test
    public void sensorsTestRefusedWithoutDumpNamesTheDumpGrant() {
        // N5: the engine refuses SENSORS at APP without DUMP with a generic NO_ACCESS.
        assertEquals(Reason.NEEDS_DUMP, MonitorFormat.testUnavailableReason(
                unavailable(SelfTestKind.SENSORS, Reason.NO_ACCESS), app(false), false, API));
        assertEquals(Reason.NEEDS_DUMP, MonitorFormat.testUnavailableReason(
                unavailable(SelfTestKind.SENSORS, null), app(false), false, API));
    }

    @Test
    public void dozeTestRefusedBelowShellKeepsTheSessionReason() {
        // Null means "needs Shizuku or root": DUMP never makes the DOZE test available.
        assertNull(MonitorFormat.testUnavailableReason(unavailable(SelfTestKind.DOZE, Reason.NO_ACCESS), app(true), false, API));
        assertNull(MonitorFormat.testUnavailableReason(unavailable(SelfTestKind.DOZE, null), app(false), false, API));
    }

    @Test
    public void aPreciseEngineReasonIsKept() {
        assertEquals(Reason.API_TOO_OLD, MonitorFormat.testUnavailableReason(
                unavailable(SelfTestKind.SENSORS, Reason.API_TOO_OLD), app(true), false, API));
        assertEquals(Reason.UNVERIFIED, MonitorFormat.testUnavailableReason(
                unavailable(SelfTestKind.SENSORS, Reason.UNVERIFIED), app(true), false, API));
    }

    private static String source(String path) throws IOException {
        File file = new File(path).isFile() ? new File(path) : new File("app/" + path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        assertTrue("missing " + start, from >= 0);
        int to = text.indexOf(end, from);
        assertTrue("missing " + end, to >= 0);
        return text.substring(from, to);
    }

    @Test
    public void monitorGatesEachSelfTestOnItsOwnKind() throws IOException {
        // N4: a sessionsAvailable gate made the APP+DUMP SENSORS test unreachable.
        String monitor = source("src/main/java/com/akylas/enforcedoze/ui/DozeMonitorActivity.java");
        assertFalse(monitor.contains("sessionsAvailable"));
        String start = between(monitor, "private void startTest(SelfTestKind kind)", "runningTest = kind;");
        assertTrue(start.contains("AccessUi.selfTestOffered(kind, accessState(), shizukuMode(), Build.VERSION.SDK_INT)"));
        String format = source("src/main/java/com/akylas/enforcedoze/ui/MonitorFormat.java");
        assertFalse(format.contains("sessionsAvailable"));
    }

    @Test
    public void summaryNotificationRendersTheAggregatedSensorVerdict() throws IOException {
        String notice = source("src/main/java/com/akylas/enforcedoze/ui/NoticeSink.java");
        String post = between(notice, "private void postSummary(", "private boolean summaryEnabled()");
        assertTrue(post.contains("SessionAggregator.summarize(segment)"));
        assertTrue(post.contains("MonitorFormat.summaryLine(app, summary)"));
        String format = source("src/main/java/com/akylas/enforcedoze/ui/MonitorFormat.java");
        String line = between(format, "static String summaryLine(", "// --- Self-tests ---");
        assertTrue(line.contains("switch (summary.getSensorsRestricted())"));
        assertTrue(line.contains("case YES: parts.add(context.getString(R.string.notice_summary_sensors_yes))"));
        assertTrue(line.contains("case NO: parts.add(context.getString(R.string.notice_summary_sensors_no))"));
        assertTrue(line.contains("default: parts.add(context.getString(R.string.notice_summary_sensors_unverified))"));
    }

    @Test
    public void verifiedReadbacksAreNotProblems() {
        assertFalse(MonitorFormat.isProblem(verify("FORCE_DOZE", DeepState.IDLE, null)));
        assertFalse(MonitorFormat.isProblem(verify("MOTION_SENSORS", null, SensorMode.RESTRICTED)));
        assertFalse(MonitorFormat.isProblem(verify("WIFI", null, null)));
    }
}
