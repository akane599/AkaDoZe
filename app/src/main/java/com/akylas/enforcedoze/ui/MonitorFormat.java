package com.akylas.enforcedoze.ui;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateUtils;

import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.access.Reason;
import com.akylas.enforcedoze.doze.DeepState;
import com.akylas.enforcedoze.doze.EventType;
import com.akylas.enforcedoze.doze.LightState;
import com.akylas.enforcedoze.doze.SensorMode;
import com.akylas.enforcedoze.doze.parse.HistoryKind;
import com.akylas.enforcedoze.monitor.Coverage;
import com.akylas.enforcedoze.monitor.JournalEvent;
import com.akylas.enforcedoze.monitor.Problem;
import com.akylas.enforcedoze.monitor.SessionSummary;
import com.akylas.enforcedoze.monitor.SensorVerification;
import com.akylas.enforcedoze.service.SelfTestCommand;
import com.akylas.enforcedoze.service.SelfTestKind;
import com.akylas.enforcedoze.service.SelfTestOutcome;
import com.akylas.enforcedoze.service.SelfTestResult;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Presentation of monitor/journal/self-test data. Every user-visible word comes from resources. */
final class MonitorFormat {
    private MonitorFormat() {}

    static String duration(Context context, long ms) {
        long minutes = Math.max(0, ms) / 60_000L;
        if (minutes < 60) return context.getString(R.string.monitor_duration_m, (int) minutes);
        return context.getString(R.string.monitor_duration_hm, (int) (minutes / 60), (int) (minutes % 60));
    }

    /** "7 hours 2 minutes" for screen readers, instead of "7h02". */
    static String durationSpoken(Context context, long ms) {
        long minutes = Math.max(0, ms) / 60_000L;
        int hours = (int) (minutes / 60);
        int rest = (int) (minutes % 60);
        String m = context.getResources().getQuantityString(R.plurals.monitor_minutes_cd, rest, rest);
        if (hours == 0) return m;
        String h = context.getResources().getQuantityString(R.plurals.monitor_hours_cd, hours, hours);
        return rest == 0 ? h : h + " " + m;
    }

    /** wholePercent's value for "more than 0 but less than 1%". */
    static final int BELOW_ONE_PERCENT = -1;

    /**
     * Whole percent that never claims more coverage than measured: Doze/active states round down,
     * UNKNOWN rounds up (missing evidence is never hidden) and shows a nonzero sliver as "&lt;1%".
     */
    static int wholePercent(Coverage coverage, double value) {
        if (coverage != Coverage.UNKNOWN) return (int) Math.floor(Math.max(0.0, value));
        if (value <= 0.0) return 0;
        if (value < 1.0) return BELOW_ONE_PERCENT;
        return (int) Math.ceil(Math.min(100.0, value));
    }

    static String percent(Coverage coverage, double value) {
        NumberFormat format = NumberFormat.getPercentInstance();
        format.setMaximumFractionDigits(0);
        int whole = wholePercent(coverage, value);
        return whole == BELOW_ONE_PERCENT ? "<" + format.format(0.01) : format.format(whole / 100.0);
    }

    static String percent(SessionSummary summary, Coverage coverage) {
        Double value = summary.getCoveragePercent().get(coverage);
        return percent(coverage, value == null ? 0.0 : value);
    }

    static String batteryRate(Context context, Double perHour) {
        if (perHour == null) return context.getString(R.string.monitor_battery_unmeasured);
        NumberFormat format = NumberFormat.getPercentInstance();
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        return context.getString(R.string.monitor_battery_rate, format.format(perHour / 100.0));
    }

    static String sessionDate(Context context, long wallTime) {
        return DateUtils.formatDateTime(context, wallTime, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_ALL);
    }

    static String clock(long wallTime) {
        return DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(wallTime));
    }

    static String yesNo(Context context, Boolean value) {
        if (value == null) return context.getString(R.string.monitor_value_unknown);
        return context.getString(value ? R.string.monitor_value_yes : R.string.monitor_value_no);
    }

    static String deep(Context context, DeepState state) {
        if (state == null) return context.getString(R.string.monitor_value_unknown);
        switch (state) {
            case ACTIVE: return context.getString(R.string.monitor_state_active);
            case INACTIVE: return context.getString(R.string.monitor_state_inactive);
            case IDLE_PENDING: return context.getString(R.string.monitor_state_idle_pending);
            case SENSING: return context.getString(R.string.monitor_state_sensing);
            case LOCATING: return context.getString(R.string.monitor_state_locating);
            case IDLE: return context.getString(R.string.monitor_state_idle);
            case IDLE_MAINTENANCE: return context.getString(R.string.monitor_state_maintenance);
            case QUICK_DOZE_DELAY: return context.getString(R.string.monitor_state_quick_doze_delay);
            default: return context.getString(R.string.monitor_value_unknown);
        }
    }

    static String light(Context context, LightState state) {
        if (state == null) return context.getString(R.string.monitor_value_unknown);
        switch (state) {
            case ACTIVE: return context.getString(R.string.monitor_state_active);
            case INACTIVE: return context.getString(R.string.monitor_state_inactive);
            case IDLE: return context.getString(R.string.monitor_state_idle);
            case WAITING_FOR_NETWORK: return context.getString(R.string.monitor_state_waiting_network);
            case IDLE_MAINTENANCE: return context.getString(R.string.monitor_state_maintenance);
            case OVERRIDE: return context.getString(R.string.monitor_state_override);
            case PRE_IDLE: return context.getString(R.string.monitor_state_pre_idle);
            default: return context.getString(R.string.monitor_value_unknown);
        }
    }

    static String sensor(Context context, SensorMode mode) {
        if (mode == null) return context.getString(R.string.monitor_sensor_unverified);
        switch (mode) {
            case NORMAL: return context.getString(R.string.monitor_sensor_normal);
            case RESTRICTED: return context.getString(R.string.monitor_sensor_restricted);
            case OTHER: return context.getString(R.string.monitor_sensor_other);
            default: return context.getString(R.string.monitor_sensor_unverified);
        }
    }

    static String sensors(Context context, SensorVerification verification) {
        switch (verification) {
            case YES: return context.getString(R.string.monitor_sensors_yes);
            case NO: return context.getString(R.string.monitor_sensors_no);
            default: return context.getString(R.string.monitor_sensors_unverified);
        }
    }

    static String sensorsSpoken(Context context, SensorVerification verification) {
        switch (verification) {
            case YES: return context.getString(R.string.monitor_sensors_yes_cd);
            case NO: return context.getString(R.string.monitor_sensors_no_cd);
            default: return context.getString(R.string.monitor_sensors_unverified_cd);
        }
    }

    static String problem(Context context, Problem problem) {
        switch (problem) {
            case NEVER_REACHED_DEEP: return context.getString(R.string.monitor_problem_never_deep);
            case SENSORS_UNVERIFIED: return context.getString(R.string.monitor_problem_sensors_unverified);
            case RESTORE_FAILED: return context.getString(R.string.monitor_problem_restore_failed);
            case RECOVERY_DEBT: return context.getString(R.string.monitor_problem_recovery_debt);
            case HISTORY_TRUNCATED: return context.getString(R.string.monitor_problem_history_truncated);
            case PARTIAL_SESSION: return context.getString(R.string.monitor_problem_partial);
            default: return context.getString(R.string.monitor_problem_access_lost);
        }
    }

    static String coverage(Context context, SessionSummary summary) {
        return context.getString(R.string.monitor_session_coverage, percent(summary, Coverage.DEEP_IDLE),
                percent(summary, Coverage.LIGHT_IDLE), percent(summary, Coverage.UNKNOWN));
    }

    static String coverageDetail(Context context, SessionSummary summary) {
        return context.getString(R.string.monitor_detail_coverage, percent(summary, Coverage.DEEP_IDLE),
                percent(summary, Coverage.LIGHT_IDLE), percent(summary, Coverage.ACTIVE), percent(summary, Coverage.UNKNOWN));
    }

    static String coverageSpoken(Context context, SessionSummary summary) {
        return context.getString(R.string.monitor_coverage_cd, percent(summary, Coverage.DEEP_IDLE),
                percent(summary, Coverage.LIGHT_IDLE), percent(summary, Coverage.UNKNOWN));
    }

    static String counts(Context context, SessionSummary summary) {
        List<String> parts = new ArrayList<>();
        parts.add(context.getResources().getQuantityString(R.plurals.monitor_maintenance_count,
                summary.getMaintenanceCount(), summary.getMaintenanceCount()));
        parts.add(context.getResources().getQuantityString(R.plurals.monitor_reforce_count,
                summary.getReforceCount(), summary.getReforceCount()));
        parts.add(sensors(context, summary.getSensorsRestricted()));
        parts.add(batteryRate(context, summary.getBatteryPercentPerHour()));
        return TextUtils.join(context.getString(R.string.monitor_separator), parts);
    }

    static String countsSpoken(Context context, SessionSummary summary) {
        List<String> parts = new ArrayList<>();
        parts.add(context.getResources().getQuantityString(R.plurals.monitor_maintenance_count,
                summary.getMaintenanceCount(), summary.getMaintenanceCount()));
        parts.add(context.getResources().getQuantityString(R.plurals.monitor_reforce_count,
                summary.getReforceCount(), summary.getReforceCount()));
        parts.add(sensorsSpoken(context, summary.getSensorsRestricted()));
        Double rate = summary.getBatteryPercentPerHour();
        if (rate == null) {
            parts.add(context.getString(R.string.monitor_battery_unmeasured));
        } else {
            NumberFormat format = NumberFormat.getPercentInstance();
            format.setMaximumFractionDigits(1);
            parts.add(context.getString(R.string.monitor_battery_rate_cd, format.format(rate / 100.0)));
        }
        return TextUtils.join(", ", parts);
    }

    static String exits(Context context, SessionSummary summary) {
        Map<String, Integer> exits = summary.getExitsByReason();
        if (exits.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : exits.entrySet()) parts.add(entry.getKey() + " ×" + entry.getValue());
        return context.getString(R.string.monitor_detail_exits, TextUtils.join(", ", parts));
    }

    /** One line, e.g. "Deep Doze 94% of 7h02 · 2 maintenance · sensors off ✓ · 0.9%/h". */
    static String summaryLine(Context context, SessionSummary summary) {
        List<String> parts = new ArrayList<>();
        parts.add(context.getString(R.string.notice_summary_deep, percent(summary, Coverage.DEEP_IDLE),
                duration(context, summary.getDurationMs())));
        Double unknown = summary.getCoveragePercent().get(Coverage.UNKNOWN);
        // Missing evidence is never folded into a state: show any of it, "<1%" included.
        if (unknown != null && unknown > 0) {
            parts.add(context.getString(R.string.notice_summary_unknown, percent(Coverage.UNKNOWN, unknown)));
        }
        parts.add(context.getResources().getQuantityString(R.plurals.monitor_maintenance_count,
                summary.getMaintenanceCount(), summary.getMaintenanceCount()));
        switch (summary.getSensorsRestricted()) {
            case YES: parts.add(context.getString(R.string.notice_summary_sensors_yes)); break;
            case NO: parts.add(context.getString(R.string.notice_summary_sensors_no)); break;
            default: parts.add(context.getString(R.string.notice_summary_sensors_unverified)); break;
        }
        if (summary.getBatteryPercentPerHour() != null) parts.add(batteryRate(context, summary.getBatteryPercentPerHour()));
        return TextUtils.join(context.getString(R.string.monitor_separator), parts);
    }

    // --- Self-tests ---

    static String testName(Context context, SelfTestKind kind) {
        return context.getString(kind == SelfTestKind.DOZE ? R.string.monitor_test_doze : R.string.monitor_test_sensors);
    }

    static String outcome(Context context, SelfTestOutcome outcome) {
        switch (outcome) {
            case PASSED: return context.getString(R.string.monitor_outcome_passed);
            case NOT_VERIFIED: return context.getString(R.string.monitor_outcome_not_verified);
            case RESTORE_INCOMPLETE: return context.getString(R.string.monitor_outcome_restore_incomplete);
            case UNAVAILABLE: return context.getString(R.string.monitor_outcome_unavailable);
            case CANCELLED: return context.getString(R.string.monitor_outcome_cancelled);
            case BUSY: return context.getString(R.string.monitor_outcome_busy);
            default: return context.getString(R.string.monitor_outcome_failed);
        }
    }

    static String resultText(Context context, SelfTestResult result, boolean shizukuMode, boolean sessionsAvailable) {
        boolean doze = result.getKind() == SelfTestKind.DOZE;
        String reading = doze ? deep(context, result.getDeep()) : sensor(context, result.getSensor());
        switch (result.getOutcome()) {
            case PASSED:
                return context.getString(doze ? R.string.monitor_result_doze_passed : R.string.monitor_result_sensors_passed, reading);
            case NOT_VERIFIED:
                return context.getString(R.string.monitor_result_not_verified, reading);
            case RESTORE_INCOMPLETE:
                return context.getString(R.string.monitor_result_restore_incomplete);
            case UNAVAILABLE: {
                // The session gate refuses a test below SHELL (no reason, or a generic no-access one):
                // say that sessions need Shizuku or root rather than a transport guess.
                Reason reason = result.getReason();
                if (reason == Reason.NO_ACCESS || reason == null && !sessionsAvailable) {
                    return context.getString(R.string.reason_sessions_need_access);
                }
                return AccessUi.reasonText(context, reason == null ? Reason.UNVERIFIED : reason, shizukuMode);
            }
            case CANCELLED:
                return context.getString(R.string.monitor_result_cancelled);
            case BUSY:
                return context.getString(R.string.monitor_result_busy);
            default:
                return context.getString(R.string.monitor_result_failed);
        }
    }

    /** A full dumpsys runs to thousands of lines; the expandable section shows each command's head. */
    static final int RAW_LINES_PER_COMMAND = 200;

    /** The first {@code max} output lines as returned: stdout, then stderr marked "! ". */
    static List<String> cappedOutput(SelfTestCommand command, int max) {
        List<String> lines = new ArrayList<>(Math.min(max, command.getStdout().size() + command.getStderr().size()));
        for (String line : command.getStdout()) {
            if (lines.size() >= max) return lines;
            lines.add(line);
        }
        for (String line : command.getStderr()) {
            if (lines.size() >= max) return lines;
            lines.add("! " + line);
        }
        return lines;
    }

    /** Raw commands and the head of their outputs, for the expandable section. Off main: can be long. */
    static String raw(Context context, List<SelfTestCommand> commands) {
        if (commands.isEmpty()) return context.getString(R.string.monitor_test_raw_empty);
        StringBuilder out = new StringBuilder();
        for (SelfTestCommand command : commands) {
            if (out.length() > 0) out.append("\n\n");
            out.append("$ ").append(command.getCommand()).append('\n');
            out.append(command.getTimedOut() ? context.getString(R.string.monitor_raw_timeout)
                    : context.getString(R.string.monitor_raw_exit, command.getExitCode()));
            List<String> shown = cappedOutput(command, RAW_LINES_PER_COMMAND);
            for (String line : shown) out.append('\n').append(line);
            int hidden = command.getStdout().size() + command.getStderr().size() - shown.size();
            if (hidden > 0) {
                out.append('\n').append(context.getResources().getQuantityString(R.plurals.monitor_raw_more_lines, hidden, hidden));
            }
        }
        return out.toString();
    }

    // --- Timeline events ---

    static int eventIcon(JournalEvent event) {
        HistoryKind kind = event.getHistoryKind();
        if (kind != null) {
            switch (kind) {
                case DEEP_IDLE:
                case LIGHT_IDLE: return R.drawable.ic_monitor_idle;
                case DEEP_MAINT:
                case LIGHT_MAINT: return R.drawable.ic_monitor_maintenance;
                default: return isMotion(event) ? R.drawable.ic_monitor_motion : R.drawable.ic_monitor_info;
            }
        }
        EventType type = event.getType();
        if (type == null) return R.drawable.ic_monitor_info;
        switch (type) {
            case SCREEN_OFF: return R.drawable.ic_monitor_screen_off;
            case SCREEN_ON: return R.drawable.ic_monitor_screen_on;
            case VERIFY:
                if (event.getSensor() != null) return R.drawable.ic_monitor_sensors;
                if (event.getDeep() == DeepState.IDLE) return R.drawable.ic_monitor_verified;
                if (event.getDeep() != null) return R.drawable.ic_monitor_idle;
                return isUnverified(event) ? R.drawable.ic_monitor_error : R.drawable.ic_monitor_verified;
            case REFORCE: return R.drawable.ic_monitor_refresh;
            case IDLE_CHANGED: return R.drawable.ic_monitor_idle;
            case MAINT_START:
            case MAINT_END: return R.drawable.ic_monitor_maintenance;
            case SENSORS_RESTRICTED:
            case SENSORS_RESTORED: return R.drawable.ic_monitor_sensors;
            case SKIPPED: return R.drawable.ic_monitor_skip;
            case RESTORE_FAILED:
            case RECOVERY_DEBT:
            case ERROR: return R.drawable.ic_monitor_error;
            default: return R.drawable.ic_monitor_info;
        }
    }

    static boolean isProblem(JournalEvent event) {
        EventType type = event.getType();
        return type == EventType.RESTORE_FAILED || type == EventType.RECOVERY_DEBT || type == EventType.ERROR
                || type == EventType.VERIFY && event.getDeep() == null && event.getSensor() == null && isUnverified(event);
    }

    static String eventLabel(Context context, JournalEvent event, boolean shizukuMode) {
        HistoryKind kind = event.getHistoryKind();
        if (kind != null) {
            switch (kind) {
                case DEEP_IDLE: return context.getString(R.string.monitor_event_history_deep);
                case DEEP_MAINT: return context.getString(R.string.monitor_event_history_deep_maint);
                case LIGHT_IDLE: return context.getString(R.string.monitor_event_history_light);
                case LIGHT_MAINT: return context.getString(R.string.monitor_event_history_light_maint);
                default: return context.getString(isMotion(event)
                        ? R.string.monitor_event_motion_exit : R.string.monitor_event_history_normal);
            }
        }
        EventType type = event.getType();
        if (type == null) return context.getString(R.string.monitor_value_unknown);
        switch (type) {
            case SCREEN_OFF: return context.getString(R.string.monitor_event_screen_off);
            case SCREEN_ON: return context.getString(R.string.monitor_event_screen_on);
            case ENTER_STEP: return context.getString(R.string.monitor_event_enter_step, feature(context, event.getDetail()));
            case VERIFY:
                if (event.getDeep() == DeepState.IDLE) return context.getString(R.string.monitor_event_verified_idle);
                if (event.getDeep() != null) return context.getString(R.string.monitor_event_doze_check, deep(context, event.getDeep()));
                if (event.getSensor() != null) return context.getString(R.string.monitor_event_sensor_check, sensor(context, event.getSensor()));
                return context.getString(isUnverified(event) ? R.string.monitor_event_unverified : R.string.monitor_event_verified,
                        feature(context, event.getDetail()));
            case REFORCE: return context.getString(R.string.monitor_event_reforce);
            case IDLE_CHANGED: return context.getString(R.string.monitor_event_idle_changed);
            case MAINT_START: return context.getString(R.string.monitor_event_maint_start);
            case MAINT_END: return context.getString(R.string.monitor_event_maint_end);
            case SENSORS_RESTRICTED: return context.getString(R.string.monitor_event_sensors_restricted);
            case SENSORS_RESTORED: return context.getString(R.string.monitor_event_sensors_restored);
            case SKIPPED: {
                Reason reason = trailingReason(event.getDetail());
                String why = reason != null ? AccessUi.reasonText(context, reason, shizukuMode)
                        : event.getDetail() == null ? context.getString(R.string.monitor_value_unknown) : event.getDetail();
                return context.getString(R.string.monitor_event_skipped, why);
            }
            case RESTORE_FAILED: return context.getString(R.string.monitor_event_restore_failed);
            case RECOVERY_DEBT: return context.getString(R.string.monitor_event_recovery_debt);
            case ACCESS_CHANGED: return context.getString(R.string.monitor_event_access_changed);
            case EXTERNAL_CALL: return context.getString(R.string.monitor_event_external);
            default: return context.getString(R.string.monitor_event_error);
        }
    }

    /** The raw journal detail, unless it only repeats the event type. Technical tokens, shown LTR. */
    static String eventDetail(Context context, JournalEvent event) {
        List<String> parts = new ArrayList<>();
        String detail = event.getDetail();
        if (!TextUtils.isEmpty(detail) && (event.getType() == null || !detail.equals(event.getType().name()))) parts.add(detail);
        if (event.getType() == EventType.IDLE_CHANGED || event.getType() == EventType.MAINT_START
                || event.getType() == EventType.MAINT_END) {
            parts.add("deep=" + (event.getDeep() == null ? "?" : event.getDeep().name())
                    + " light=" + (event.getLight() == null ? "?" : event.getLight().name()));
        }
        if (event.getBattery() != null) parts.add(event.getBattery() + "%");
        return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
    }

    private static String feature(Context context, String detail) {
        String token = detail == null ? "" : detail.split(":", 2)[0].trim();
        switch (token) {
            case "FORCE_DOZE": return context.getString(R.string.monitor_feature_force_doze);
            case "MOTION_SENSORS": return context.getString(R.string.monitor_feature_motion_sensors);
            case "BATTERY_SAVER": return context.getString(R.string.monitor_feature_battery_saver);
            default: return context.getString(R.string.monitor_feature_other);
        }
    }

    private static boolean isUnverified(JournalEvent event) {
        String detail = event.getDetail();
        return detail != null && detail.endsWith(Reason.UNVERIFIED.name());
    }

    private static boolean isMotion(JournalEvent event) {
        String detail = event.getDetail();
        return detail != null && detail.toLowerCase(Locale.ROOT).contains("motion");
    }

    private static Reason trailingReason(String detail) {
        if (detail == null) return null;
        int colon = detail.lastIndexOf(':');
        String token = (colon < 0 ? detail : detail.substring(colon + 1)).trim();
        for (Reason reason : Reason.values()) if (reason.name().equals(token)) return reason;
        return null;
    }
}
