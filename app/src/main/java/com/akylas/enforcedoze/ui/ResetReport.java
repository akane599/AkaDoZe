package com.akylas.enforcedoze.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.service.ResetCommandId;
import com.akylas.enforcedoze.service.ResetCommandOutcome;
import com.akylas.enforcedoze.service.ResetCommandResult;
import com.akylas.enforcedoze.service.ResetRestoreOutcome;
import com.akylas.enforcedoze.service.SystemResetResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Presentation and preference clearing for the Settings reset, after DozeRuntime.resetSystemState reports. */
public final class ResetReport {
    private ResetReport() {}

    /** What a remaining restore still needs: the runner that restores, and the sensor allow token. */
    static final Set<String> RESTORE_INTENT_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            Prefs.EXECUTION_MODE, Prefs.RESTORE_LEDGER, Prefs.RESTRICT_SENSORS_ALLOW_TOKEN)));

    /** Preferences that must survive the reset: none once everything was restored. */
    public static Set<String> keysToKeep(SystemResetResult result) {
        return result.getRestoreOutcome() == ResetRestoreOutcome.COMPLETE
                ? Collections.emptySet() : RESTORE_INTENT_KEYS;
    }

    /** The stored values among {@code keep}, to be written back in the same commit as the clear. */
    public static Map<String, Object> retained(Map<String, ?> all, Set<String> keep) {
        Map<String, Object> kept = new LinkedHashMap<>();
        for (String key : keep) {
            if (all.containsKey(key) && all.get(key) != null) kept.put(key, all.get(key));
        }
        return kept;
    }

    /** Clears every preference except what a remaining restore needs, in one commit. Call off main. */
    public static boolean clearPreferences(SharedPreferences prefs, SystemResetResult result) {
        if (result.getFailed()) return false;
        Map<String, Object> kept = retained(prefs.getAll(), keysToKeep(result));
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, Object> entry : kept.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
        }
        return editor.commit();
    }

    /** Steps that did not end readback-confirmed, in the order they ran. */
    public static List<ResetCommandResult> unconfirmed(SystemResetResult result) {
        List<ResetCommandResult> problems = new ArrayList<>();
        for (ResetCommandResult command : result.getCommands()) {
            if (command.getOutcome() != ResetCommandOutcome.OK) problems.add(command);
        }
        return problems;
    }

    /** "Reset complete" only when restore finished, every step was readback-confirmed and settings cleared. */
    public static boolean complete(SystemResetResult result, boolean prefsCleared) {
        return result.getComplete() && prefsCleared;
    }

    public static int title(SystemResetResult result, boolean prefsCleared) {
        return complete(result, prefsCleared) ? R.string.reset_complete_dialog_title : R.string.reset_incomplete_title;
    }

    public static String message(Context context, SystemResetResult result, boolean prefsCleared) {
        List<ResetCommandId> deferred = result.getDeferred();
        if (complete(result, prefsCleared) && deferred.isEmpty()) {
            return context.getString(R.string.reset_complete_dialog_text);
        }
        if (result.getFailed()) return context.getString(R.string.reset_failed_not_run);
        StringBuilder text = new StringBuilder();
        if (result.getRestoreOutcome() == ResetRestoreOutcome.REMAINING_DEBT) {
            text.append(context.getString(R.string.reset_debt_remaining));
        }
        List<ResetCommandResult> problems = unconfirmed(result);
        if (!problems.isEmpty()) {
            if (text.length() > 0) text.append("\n\n");
            text.append(context.getString(R.string.reset_steps_unconfirmed));
            for (ResetCommandResult problem : problems) {
                text.append('\n').append(context.getString(R.string.reset_step_line,
                        step(context, problem), context.getString(outcome(problem.getOutcome()))));
            }
        }
        if (text.length() > 0) text.append("\n\n");
        text.append(context.getString(!prefsCleared ? R.string.reset_prefs_failed
                : result.getRestoreOutcome() == ResetRestoreOutcome.COMPLETE ? R.string.reset_prefs_cleared
                : R.string.reset_prefs_cleared_kept));
        text.append("\n\n");
        if (deferred.isEmpty()) {
            text.append(context.getString(R.string.reset_restart_text));
        } else {
            // Not run yet, so not counted as confirmed: they run on OK, and Android then closes the app.
            StringBuilder names = new StringBuilder();
            for (ResetCommandId id : deferred) {
                if (names.length() > 0) names.append(", ");
                names.append(permission(id));
            }
            text.append(context.getString(R.string.reset_deferred_text, names.toString()));
        }
        return text.toString();
    }

    private static String step(Context context, ResetCommandResult command) {
        switch (command.getId()) {
            case DISABLE_DEVICE_IDLE: return context.getString(R.string.reset_step_disable_doze);
            case ENABLE_DEVICE_IDLE: return context.getString(R.string.reset_step_enable_doze);
            default: return context.getString(R.string.reset_step_revoke, permission(command.getId()));
        }
    }

    private static String permission(ResetCommandId id) {
        switch (id) {
            case REVOKE_DUMP: return "DUMP";
            case REVOKE_READ_LOGS: return "READ_LOGS";
            case REVOKE_READ_PHONE_STATE: return "READ_PHONE_STATE";
            case REVOKE_WRITE_SECURE_SETTINGS: return "WRITE_SECURE_SETTINGS";
            case REVOKE_WRITE_SETTINGS:
            default: return "WRITE_SETTINGS";
        }
    }

    /** The one reset this process runs, kept past the Settings screen that started it (rotation, dark mode). */
    public static final Tracker TRACKER = new Tracker();

    /**
     * Reset progress for whichever Settings screen is showing. The worker records the result here, so a
     * recreated screen still shows it and still finishes the reset. Pure state: callers own the threads.
     */
    public static final class Tracker {
        public enum Phase { IDLE, RUNNING, REPORTED, FINISHING }

        /** Main thread: render {@link #phase()} again. */
        public interface Listener { void onResetChanged(); }

        private Phase phase = Phase.IDLE;
        private SystemResetResult result;
        private boolean prefsCleared;
        private Listener listener;

        /** Main: false when a reset is already under way in this process. */
        public synchronized boolean begin() {
            if (phase != Phase.IDLE) return false;
            phase = Phase.RUNNING;
            return true;
        }

        /** Worker: the result is known and the preference clear has finished (or failed). */
        public synchronized void deliver(SystemResetResult result, boolean prefsCleared) {
            if (phase != Phase.RUNNING) return;
            this.result = result;
            this.prefsCleared = prefsCleared;
            phase = Phase.REPORTED;
        }

        public synchronized Phase phase() { return phase; }

        public synchronized SystemResetResult result() { return result; }

        public synchronized boolean prefsCleared() { return prefsCleared; }

        /** Main: a failed job is dismissed for retry; otherwise finish the reset once before restart. */
        public synchronized List<ResetCommandId> confirm() {
            if (phase != Phase.REPORTED) return null;
            if (result.getFailed()) {
                phase = Phase.IDLE;
                result = null;
                prefsCleared = false;
                return null;
            }
            phase = Phase.FINISHING;
            return result.getDeferred();
        }

        /** Main: the screen now showing; it renders the current phase itself. */
        public synchronized void setListener(Listener listener) { this.listener = listener; }

        /** Main: only the screen that registered can unregister, so a newer screen keeps receiving. */
        public synchronized void removeListener(Listener listener) {
            if (this.listener == listener) this.listener = null;
        }

        /** Main: tell the screen now showing, if any; one that starts later renders from {@link #phase()}. */
        public void notifyListener() {
            Listener current;
            synchronized (this) { current = listener; }
            if (current != null) current.onResetChanged();
        }
    }

    private static int outcome(ResetCommandOutcome outcome) {
        switch (outcome) {
            case FAILED: return R.string.reset_outcome_failed;
            case TIMEOUT: return R.string.reset_outcome_timeout;
            case UNVERIFIED:
            default: return R.string.reset_outcome_unverified;
        }
    }
}
