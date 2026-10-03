package com.akylas.enforcedoze.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.preference.PreferenceManager;

import com.akylas.enforcedoze.MyApplication;
import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.CommandCatalog;
import com.akylas.enforcedoze.access.CommandResult;
import com.akylas.enforcedoze.access.Feature;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.access.Reason;
import com.akylas.enforcedoze.doze.CorruptLedgerLine;
import com.akylas.enforcedoze.doze.EventType;
import com.akylas.enforcedoze.doze.RestoreLedger;
import com.akylas.enforcedoze.doze.parse.DozeStateParser;
import com.akylas.enforcedoze.doze.parse.DozeStateReading;
import com.akylas.enforcedoze.doze.parse.SensorModeParser;
import com.akylas.enforcedoze.doze.parse.SensorModeReading;
import com.akylas.enforcedoze.monitor.JournalEvent;
import com.akylas.enforcedoze.monitor.SessionAggregator;
import com.akylas.enforcedoze.monitor.SessionSummary;
import com.akylas.enforcedoze.service.DozeRuntime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Blocking monitor reads. Call only from a background thread; nothing here mutates the system. */
final class MonitorData {
    private static final long JOURNAL_TIMEOUT_S = 5;

    private MonitorData() {}

    /** Identifies one aggregated session: a sessionId can hold several screen-off segments. */
    static final class SessionKey {
        final int bootId;
        final long sessionId;
        final long startElapsed;

        SessionKey(int bootId, long sessionId, long startElapsed) {
            this.bootId = bootId;
            this.sessionId = sessionId;
            this.startElapsed = startElapsed;
        }

        static SessionKey of(SessionSummary summary) {
            return new SessionKey(summary.getBootId(), summary.getSessionId(), summary.getStartElapsed());
        }

        boolean matches(SessionSummary summary) {
            return summary.getBootId() == bootId && summary.getSessionId() == sessionId
                    && summary.getStartElapsed() == startElapsed;
        }
    }

    static final class Live {
        AccessState access;
        Reason readUnavailable;
        DozeStateReading idle;
        SensorModeReading sensor;
        boolean watchdog;
        boolean debt;
        List<String> dismissible = Collections.emptyList();
        long readAt;
    }

    static final class Timeline {
        SessionSummary summary;
        List<JournalEvent> events = Collections.emptyList();
    }

    /** Reads lane only: the full deviceidle dump and the sensorservice dump, both parsed defensively. */
    static Live readLive(Context app) {
        DozeRuntime runtime = MyApplication.getDozeRuntime(app);
        AccessManager access = runtime.getAccess();
        Live live = new Live();
        live.access = access.getState();
        live.readUnavailable = AccessUi.unavailableReason(Feature.DOZE_STATE_READ, live.access, AccessUi.isShizukuMode(app));
        if (live.readUnavailable == null) {
            int api = Build.VERSION.SDK_INT;
            live.idle = DozeStateParser.parse(read(access, CommandCatalog.originalValueRead(Feature.FORCE_DOZE, api, null)));
            live.sensor = SensorModeParser.parse(read(access, CommandCatalog.readback(Feature.MOTION_SENSORS, api, null)));
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        live.watchdog = prefs.getBoolean(Prefs.KEEP_DOZE_ENFORCED, Prefs.DEFAULT_KEEP_DOZE_ENFORCED);
        DebtCheck debt = checkDebt(runtime);
        live.debt = debt.debt;
        live.dismissible = debt.dismissible;
        live.readAt = System.currentTimeMillis();
        return live;
    }

    private static List<String> read(AccessManager access, String command) {
        if (command == null) return Collections.emptyList();
        try {
            CommandResult result = access.reads().run(command, 8_000);
            return result.getOk() ? result.getStdout() : Collections.emptyList();
        } catch (RuntimeException failed) {
            return Collections.emptyList();
        }
    }

    /**
     * Shared by the access card and the monitor (DebtRules.isDebt; unreadable fails closed). Blocking: the
     * controller lock waits out an exit/reconcile walk that is already running, so its saves are seen.
     * Also lists the damaged records the user may dismiss: none during a session or while unreadable.
     */
    static DebtCheck checkDebt(DozeRuntime runtime) {
        synchronized (runtime.getController()) {
            boolean sessionActive = runtime.getSessionActive();
            try {
                RestoreLedger ledger = runtime.getStore().load();
                List<CorruptLedgerLine> corrupt = runtime.getStore().getCorruptLines();
                return new DebtCheck(DebtRules.isDebt(ledger.getEntries(), !corrupt.isEmpty(), sessionActive),
                        sessionActive ? Collections.emptyList() : DebtRules.dismissibleDamage(corrupt));
            } catch (Exception unreadable) {
                return new DebtCheck(DebtRules.isDebt(Collections.emptyList(), true, sessionActive),
                        Collections.emptyList());
            }
        }
    }

    static final class DebtCheck {
        final boolean debt;
        /** Feature tokens (null = unnamed) of damaged records that can't be restored automatically. */
        final List<String> dismissible;

        DebtCheck(boolean debt, List<String> dismissible) {
            this.debt = debt;
            this.dismissible = dismissible;
        }
    }

    /** Newest first. */
    static List<SessionSummary> loadSessions(Context app) throws Exception {
        List<JournalEvent> events = MyApplication.getDozeRuntime(app).getJournal().queryRecent()
                .get(JOURNAL_TIMEOUT_S, TimeUnit.SECONDS);
        List<SessionSummary> sessions = new ArrayList<>(SessionAggregator.summarize(events));
        Collections.sort(sessions, (a, b) -> Long.compare(b.getStartWallTime(), a.getStartWallTime()));
        return sessions;
    }

    /** Null summary when the session is no longer in the journal (pruned or never written). */
    static Timeline loadTimeline(Context app, SessionKey key) throws Exception {
        List<JournalEvent> all = MyApplication.getDozeRuntime(app).getJournal().querySession(key.sessionId, key.bootId)
                .get(JOURNAL_TIMEOUT_S, TimeUnit.SECONDS);
        Timeline timeline = new Timeline();
        List<JournalEvent> segment = segment(all, key.startElapsed);
        if (segment.isEmpty()) return timeline;
        for (SessionSummary summary : SessionAggregator.summarize(segment)) {
            if (key.matches(summary)) timeline.summary = summary;
        }
        if (timeline.summary != null) timeline.events = segment;
        return timeline;
    }

    /**
     * The aggregator's own split: a second SCREEN_OFF in one sessionId starts a new session.
     * Returns the segment that starts at startElapsed (its SCREEN_OFF, or its first event).
     */
    static List<JournalEvent> segment(List<JournalEvent> sessionEvents, long startElapsed) {
        List<JournalEvent> sorted = new ArrayList<>(sessionEvents);
        Collections.sort(sorted, (a, b) -> Long.compare(a.getElapsedRealtime(), b.getElapsedRealtime()));
        List<JournalEvent> segment = new ArrayList<>();
        for (JournalEvent event : sorted) {
            if (event.getType() == EventType.SCREEN_OFF && hasScreenOff(segment)) {
                if (startOf(segment) == startElapsed) return segment;
                segment = new ArrayList<>();
            }
            segment.add(event);
        }
        return !segment.isEmpty() && startOf(segment) == startElapsed ? segment : Collections.emptyList();
    }

    /** The newest segment of a session, for the screen-on summary. */
    static List<JournalEvent> lastSegment(List<JournalEvent> sessionEvents) {
        List<JournalEvent> sorted = new ArrayList<>(sessionEvents);
        Collections.sort(sorted, (a, b) -> Long.compare(a.getElapsedRealtime(), b.getElapsedRealtime()));
        List<JournalEvent> segment = new ArrayList<>();
        for (JournalEvent event : sorted) {
            if (event.getType() == EventType.SCREEN_OFF && hasScreenOff(segment)) segment = new ArrayList<>();
            segment.add(event);
        }
        return segment;
    }

    /**
     * True once an enforcement step was carried out: an ENTER_STEP answered by its VERIFY. ENTER_STEP alone
     * is emitted before the capability check, so a session whose every step was SKIPPED does not count.
     */
    static boolean hasAppliedStep(List<JournalEvent> segment) {
        String step = null;
        for (JournalEvent event : segment) {
            EventType type = event.getType();
            String detail = event.getDetail();
            if (type == EventType.ENTER_STEP) {
                step = detail;
            } else if (step != null && detail != null && (detail.equals(step) || detail.startsWith(step + ":"))) {
                if (type == EventType.VERIFY) return true;
                if (type == EventType.SKIPPED) step = null;
            }
        }
        return false;
    }

    private static boolean hasScreenOff(List<JournalEvent> events) {
        for (JournalEvent event : events) if (event.getType() == EventType.SCREEN_OFF) return true;
        return false;
    }

    private static long startOf(List<JournalEvent> segment) {
        for (JournalEvent event : segment) if (event.getType() == EventType.SCREEN_OFF) return event.getElapsedRealtime();
        return segment.isEmpty() ? Long.MIN_VALUE : segment.get(0).getElapsedRealtime();
    }
}
