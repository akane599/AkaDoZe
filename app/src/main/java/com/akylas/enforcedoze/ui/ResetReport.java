package com.akylas.enforcedoze.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.access.Prefs;
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
        if (complete(result, prefsCleared)) return context.getString(R.string.reset_complete_dialog_text);
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
        text.append("\n\n").append(context.getString(R.string.reset_restart_text));
        return text.toString();
    }

    private static String step(Context context, ResetCommandResult command) {
        switch (command.getId()) {
            case DISABLE_DEVICE_IDLE: return context.getString(R.string.reset_step_disable_doze);
            case ENABLE_DEVICE_IDLE: return context.getString(R.string.reset_step_enable_doze);
            case REVOKE_DUMP: return context.getString(R.string.reset_step_revoke, "DUMP");
            case REVOKE_READ_LOGS: return context.getString(R.string.reset_step_revoke, "READ_LOGS");
            case REVOKE_READ_PHONE_STATE: return context.getString(R.string.reset_step_revoke, "READ_PHONE_STATE");
            case REVOKE_WRITE_SECURE_SETTINGS: return context.getString(R.string.reset_step_revoke, "WRITE_SECURE_SETTINGS");
            case REVOKE_WRITE_SETTINGS:
            default: return context.getString(R.string.reset_step_revoke, "WRITE_SETTINGS");
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
