package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.akylas.enforcedoze.access.CommandRunner;
import com.akylas.enforcedoze.access.CommandResult;
import com.akylas.enforcedoze.access.CommandCatalog;
import com.akylas.enforcedoze.access.CapabilityResolver;
import com.akylas.enforcedoze.access.Feature;
import com.akylas.enforcedoze.access.FeatureStatus;
import com.akylas.enforcedoze.access.Grants;
import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.Reason;
import com.akylas.enforcedoze.doze.parse.DozeStateParser;

public class DozeTunableHandler {
    /** DeviceIdleController reads DeviceConfig `device_idle` from Android 12 (AOSP android12-release); before that Settings.Global device_idle_constants. */
    public static final int DEVICE_CONFIG_MIN_API = 31;
    private static DozeTunableHandler single_instance = null;

    // Static method
    // Static method to create instance of Singleton class
    public static synchronized DozeTunableHandler getInstance()
    {
        if (single_instance == null)
            single_instance = new DozeTunableHandler();
            single_instance.loadTunables();

        return single_instance;
    }


    private static void log(String message) {
        logToLogcat(TAG, message);
    }

    public static String TAG = "EnforceDoze";
    public String TUNABLE_STRING = "null";

    private long LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT = 5 * 60 * 1000L;
    private long LIGHT_PRE_IDLE_TIMEOUT = 10 * 60 * 1000L;
    private long LIGHT_IDLE_TIMEOUT = 5 * 60 * 1000L;
    private float LIGHT_IDLE_FACTOR = 2f;
    private long LIGHT_MAX_IDLE_TIMEOUT = 15 * 60 * 1000L;
    private long LIGHT_IDLE_MAINTENANCE_MIN_BUDGET = 1 * 60 * 1000L;
    private long LIGHT_IDLE_MAINTENANCE_MAX_BUDGET = 5 * 60 * 1000L;
    private long MIN_LIGHT_MAINTENANCE_TIME = 5 * 1000L;
    private long MIN_DEEP_MAINTENANCE_TIME = 30 * 1000L;
    private long INACTIVE_TIMEOUT = 30 * 60 * 1000L;
    private long SENSING_TIMEOUT = 4 * 60 * 1000L;
    private long LOCATING_TIMEOUT = 30 * 1000L;
    private float LOCATION_ACCURACY = 20;
    private long MOTION_INACTIVE_TIMEOUT = 10 * 60 * 1000L;
    private long IDLE_AFTER_INACTIVE_TIMEOUT = 30 * 60 * 1000L;
    private long IDLE_PENDING_TIMEOUT = 5 * 60 * 1000L;
    private long MAX_IDLE_PENDING_TIMEOUT = 10 * 60 * 1000L;
    private float IDLE_PENDING_FACTOR = 2;
    private long IDLE_TIMEOUT = 60 * 60 * 1000L;
    private long MAX_IDLE_TIMEOUT = 6 * 60 * 60 * 1000L;
    private long IDLE_FACTOR = 2;
    private long MIN_TIME_TO_ALARM = 60 * 60 * 1000L;
    private long MAX_TEMP_APP_WHITELIST_DURATION = 5 * 60 * 1000L;
    private long MMS_TEMP_APP_WHITELIST_DURATION = 60 * 1000L;
    private long SMS_TEMP_APP_WHITELIST_DURATION = 20 * 1000L;
    private long NOTIFICATION_WHITELIST_DURATION = 30 * 1000L;
    private SharedPreferences preferences;

    public void loadTunables() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(MyApplication.getAppContext());

        //TODO: load current tunables
        LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT, "300000"));
        LIGHT_PRE_IDLE_TIMEOUT = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_LIGHT_PRE_IDLE_TIMEOUT, "600000"));
        LIGHT_IDLE_TIMEOUT = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_LIGHT_IDLE_TIMEOUT, "300000"));
        LIGHT_IDLE_FACTOR = Float.parseFloat(preferences.getString(DozeTunableConstants.KEY_LIGHT_IDLE_FACTOR, "2"));
        LIGHT_MAX_IDLE_TIMEOUT = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_LIGHT_MAX_IDLE_TIMEOUT, "900000"));
        LIGHT_IDLE_MAINTENANCE_MIN_BUDGET = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_LIGHT_IDLE_MAINTENANCE_MIN_BUDGET, "60000"));
        LIGHT_IDLE_MAINTENANCE_MAX_BUDGET = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_LIGHT_IDLE_MAINTENANCE_MAX_BUDGET, "300000"));
        MIN_LIGHT_MAINTENANCE_TIME = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_MIN_LIGHT_MAINTENANCE_TIME, "5000"));
        MIN_DEEP_MAINTENANCE_TIME = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_MIN_DEEP_MAINTENANCE_TIME, "30000"));
        INACTIVE_TIMEOUT = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_INACTIVE_TIMEOUT, "1800000"));
        SENSING_TIMEOUT = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_SENSING_TIMEOUT, "240000"));
        LOCATING_TIMEOUT = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_LOCATING_TIMEOUT, "30000"));
        LOCATION_ACCURACY = Float.parseFloat(preferences.getString(DozeTunableConstants.KEY_LOCATION_ACCURACY, "20"));
        MOTION_INACTIVE_TIMEOUT = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_MOTION_INACTIVE_TIMEOUT, "600000"));
        IDLE_AFTER_INACTIVE_TIMEOUT = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_IDLE_AFTER_INACTIVE_TIMEOUT, "1800000"));
        IDLE_PENDING_TIMEOUT = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_IDLE_PENDING_TIMEOUT, "30000"));
        MAX_IDLE_PENDING_TIMEOUT = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_MAX_IDLE_PENDING_TIMEOUT, "600000"));
        IDLE_PENDING_FACTOR = Float.parseFloat(preferences.getString(DozeTunableConstants.KEY_IDLE_PENDING_FACTOR, "2"));
        IDLE_TIMEOUT = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_IDLE_TIMEOUT, "3600000"));
        MAX_IDLE_TIMEOUT = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_MAX_IDLE_TIMEOUT, "21600000"));
        IDLE_FACTOR  = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_IDLE_FACTOR, "2"));
        MIN_TIME_TO_ALARM = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_MIN_TIME_TO_ALARM, "3600000"));
        MAX_TEMP_APP_WHITELIST_DURATION = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_MAX_TEMP_APP_WHITELIST_DURATION, "300000"));
        MMS_TEMP_APP_WHITELIST_DURATION = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_MMS_TEMP_APP_WHITELIST_DURATION, "60000"));
        SMS_TEMP_APP_WHITELIST_DURATION = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_SMS_TEMP_APP_WHITELIST_DURATION, "20000"));
        NOTIFICATION_WHITELIST_DURATION = Long.parseLong(preferences.getString(DozeTunableConstants.KEY_NOTIFICATION_WHITELIST_DURATION, "30000"));
    }

    public String getTunableString() {
        StringBuilder sb = new StringBuilder();
        sb.append(DozeTunableConstants.KEY_LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT + "=" + LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT + ",");
        sb.append(DozeTunableConstants.KEY_LIGHT_PRE_IDLE_TIMEOUT + "=" + LIGHT_PRE_IDLE_TIMEOUT + ",");
        sb.append(DozeTunableConstants.KEY_LIGHT_IDLE_TIMEOUT + "=" + LIGHT_IDLE_TIMEOUT + ",");
        sb.append(DozeTunableConstants.KEY_LIGHT_IDLE_FACTOR + "=" + LIGHT_IDLE_FACTOR + ",");
        sb.append(DozeTunableConstants.KEY_LIGHT_MAX_IDLE_TIMEOUT + "=" + LIGHT_MAX_IDLE_TIMEOUT + ",");
        sb.append(DozeTunableConstants.KEY_LIGHT_IDLE_MAINTENANCE_MIN_BUDGET + "=" + LIGHT_IDLE_MAINTENANCE_MIN_BUDGET + ",");
        sb.append(DozeTunableConstants.KEY_LIGHT_IDLE_MAINTENANCE_MAX_BUDGET + "=" + LIGHT_IDLE_MAINTENANCE_MAX_BUDGET + ",");
        sb.append(DozeTunableConstants.KEY_MIN_LIGHT_MAINTENANCE_TIME + "=" + MIN_LIGHT_MAINTENANCE_TIME + ",");
        sb.append(DozeTunableConstants.KEY_MIN_DEEP_MAINTENANCE_TIME + "=" + MIN_DEEP_MAINTENANCE_TIME + ",");
        sb.append(DozeTunableConstants.KEY_INACTIVE_TIMEOUT + "=" + INACTIVE_TIMEOUT + ",");
        sb.append(DozeTunableConstants.KEY_SENSING_TIMEOUT + "=" + SENSING_TIMEOUT + ",");
        sb.append(DozeTunableConstants.KEY_LOCATING_TIMEOUT + "=" + LOCATING_TIMEOUT + ",");
        sb.append(DozeTunableConstants.KEY_LOCATION_ACCURACY + "=" + LOCATION_ACCURACY + ",");
        sb.append(DozeTunableConstants.KEY_MOTION_INACTIVE_TIMEOUT + "=" + MOTION_INACTIVE_TIMEOUT + ",");
        sb.append(DozeTunableConstants.KEY_IDLE_AFTER_INACTIVE_TIMEOUT + "=" + IDLE_AFTER_INACTIVE_TIMEOUT + ",");
        sb.append(DozeTunableConstants.KEY_IDLE_PENDING_TIMEOUT + "=" + IDLE_PENDING_TIMEOUT + ",");
        sb.append(DozeTunableConstants.KEY_MAX_IDLE_PENDING_TIMEOUT + "=" + MAX_IDLE_PENDING_TIMEOUT + ",");
        sb.append(DozeTunableConstants.KEY_IDLE_PENDING_FACTOR + "=" + IDLE_PENDING_FACTOR + ",");
        sb.append(DozeTunableConstants.KEY_IDLE_TIMEOUT + "=" + IDLE_TIMEOUT + ",");
        sb.append(DozeTunableConstants.KEY_MAX_IDLE_TIMEOUT + "=" + MAX_IDLE_TIMEOUT + ",");
        sb.append(DozeTunableConstants.KEY_IDLE_FACTOR + "=" + IDLE_FACTOR + ",");
        sb.append(DozeTunableConstants.KEY_MIN_TIME_TO_ALARM + "=" + MIN_TIME_TO_ALARM + ",");
        sb.append(DozeTunableConstants.KEY_MAX_TEMP_APP_WHITELIST_DURATION + "=" + MAX_TEMP_APP_WHITELIST_DURATION + ",");
        sb.append(DozeTunableConstants.KEY_MMS_TEMP_APP_WHITELIST_DURATION + "=" + MMS_TEMP_APP_WHITELIST_DURATION + ",");
        sb.append(DozeTunableConstants.KEY_SMS_TEMP_APP_WHITELIST_DURATION + "=" + SMS_TEMP_APP_WHITELIST_DURATION + ",");
        sb.append(DozeTunableConstants.KEY_NOTIFICATION_WHITELIST_DURATION + "=" + NOTIFICATION_WHITELIST_DURATION);
        return sb.toString();
    }

    public ArrayList<String> getCommandsList() {
        ArrayList<String> commands = new ArrayList<>();
        for (String pair : getTunableString().split(",")) {
            commands.add("cmd device_config put device_idle " + pair.replace('=', ' '));
        }
        return commands;
    }

    public enum Outcome { APPLIED, NOT_EFFECTIVE, UNVERIFIED, UNAVAILABLE }

    public static final class ApplyResult {
        public final Map<String, Outcome> keys;
        public final Reason reason;
        public final List<String> applied;
        public final List<String> notEffective;
        public final List<String> failed;
        ApplyResult(Map<String, Outcome> keys, Reason reason) {
            this.keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
            this.reason = reason;
            ArrayList<String> appliedKeys = new ArrayList<>();
            ArrayList<String> notEffectiveKeys = new ArrayList<>();
            ArrayList<String> failedKeys = new ArrayList<>();
            for (Map.Entry<String, Outcome> key : keys.entrySet()) {
                switch (key.getValue()) {
                    case APPLIED: appliedKeys.add(key.getKey()); break;
                    case NOT_EFFECTIVE: notEffectiveKeys.add(key.getKey()); break;
                    default: failedKeys.add(key.getKey());
                }
            }
            applied = Collections.unmodifiableList(appliedKeys);
            notEffective = Collections.unmodifiableList(notEffectiveKeys);
            failed = Collections.unmodifiableList(failedKeys);
        }
        public boolean allApplied() {
            if (keys.isEmpty()) return false;
            for (Outcome outcome : keys.values()) if (outcome != Outcome.APPLIED) return false;
            return true;
        }
    }

    /** Blocking: callers run off-main. Only effective dumpsys Settings values establish success. */
    public static ApplyResult apply(CommandRunner control, CommandRunner reads, int apiLevel,
                                    Grants grants, String tunables) {
        List<String> fallback = CommandCatalog.apply(Feature.TUNABLES, apiLevel, null, tunables);
        Map<String, String> requested = new LinkedHashMap<>();
        for (String pair : tunables.split(",")) {
            String[] fields = pair.split("=", -1);
            if (fields.length == 2) requested.put(fields[0], fields[1]);
        }
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        FeatureStatus status = CapabilityResolver.status(Feature.TUNABLES, control.getLevel(), apiLevel, grants);
        if (status instanceof FeatureStatus.Unavailable || fallback == null) {
            for (String key : requested.keySet()) outcomes.put(key, Outcome.UNAVAILABLE);
            return new ApplyResult(outcomes, status instanceof FeatureStatus.Unavailable
                    ? ((FeatureStatus.Unavailable) status).getReason() : Reason.API_TOO_OLD);
        }
        boolean deviceConfig = apiLevel >= DEVICE_CONFIG_MIN_API
                && (control.getLevel() == AccessLevel.SHELL || control.getLevel() == AccessLevel.ROOT);
        if (deviceConfig) {
            for (Map.Entry<String, String> pair : requested.entrySet()) {
                control.run("cmd device_config put device_idle " + pair.getKey() + " " + pair.getValue());
            }
        } else {
            for (String command : fallback) control.run(command);
        }
        CommandResult readback = reads.run("dumpsys deviceidle");
        Map<String, String> actual = DozeStateParser.parse(readback.getStdout()).getSettings();
        for (Map.Entry<String, String> pair : requested.entrySet()) {
            outcomes.put(pair.getKey(), !readback.getOk() ? Outcome.UNVERIFIED
                    : equivalentValue(pair.getValue(), actual.get(pair.getKey())) ? Outcome.APPLIED : Outcome.NOT_EFFECTIVE);
        }
        Reason reason = outcomes.containsValue(Outcome.UNVERIFIED) ? Reason.UNVERIFIED
                : outcomes.containsValue(Outcome.NOT_EFFECTIVE) ? Reason.NOT_EFFECTIVE_ON_THIS_VERSION : null;
        return new ApplyResult(outcomes, reason);
    }

    // dumpsys formats timeout values using TimeUtils, while factors are decimal numbers.
    static boolean equivalentValue(String expected, String actual) {
        if (actual == null) return false;
        try {
            BigDecimal desired = new BigDecimal(expected);
            try {
                return desired.compareTo(new BigDecimal(actual)) == 0;
            } catch (NumberFormatException duration) {
                String text = actual.startsWith("+") ? actual.substring(1) : actual;
                Matcher parts = Pattern.compile("([0-9]+)(ms|d|h|m|s)").matcher(text);
                BigDecimal millis = BigDecimal.ZERO;
                int end = 0;
                while (parts.find()) {
                    if (parts.start() != end) return false;
                    long scale;
                    switch (parts.group(2)) {
                        case "d": scale = 86400000; break;
                        case "h": scale = 3600000; break;
                        case "m": scale = 60000; break;
                        case "s": scale = 1000; break;
                        default: scale = 1;
                    }
                    millis = millis.add(new BigDecimal(parts.group(1)).multiply(BigDecimal.valueOf(scale)));
                    end = parts.end();
                }
                return end > 0 && end == text.length() && desired.compareTo(millis) == 0;
            }
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    public long getLightAfterInactiveTo() { return LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT;}
    public long getLightPreIdleTo() {return LIGHT_PRE_IDLE_TIMEOUT;}
    public long getLightIdleTo() {return LIGHT_IDLE_TIMEOUT;}
    public float getLightIdleFactor() {return LIGHT_IDLE_FACTOR;}
    public long getLightMaxIdleTo() {return LIGHT_MAX_IDLE_TIMEOUT;}
    public long getLightIdleMaintenanceMinBudget() {return LIGHT_IDLE_MAINTENANCE_MIN_BUDGET;}
    public long getLightIdleMaintenanceMaxBudget() {return LIGHT_IDLE_MAINTENANCE_MAX_BUDGET;}
    public long getMinLightMaintenanceTime() {return MIN_LIGHT_MAINTENANCE_TIME;}
    public long getMinDeepMaintenanceTime() {return MIN_DEEP_MAINTENANCE_TIME;}
    public long getInactiveTo() {return INACTIVE_TIMEOUT;}
    public long getSensingTo() {return SENSING_TIMEOUT;}
    public long getLocationTo() {return LOCATING_TIMEOUT;}
    public float getLocationAccuracy() {return LOCATION_ACCURACY;}
    public long getMotionInactiveTo() {return MOTION_INACTIVE_TIMEOUT;}
    public long getIdleAfterInactiveTo() {return IDLE_AFTER_INACTIVE_TIMEOUT;}
    public long getIdlePendingTo() {return IDLE_PENDING_TIMEOUT;}
    public long getMaxIdlePendingTo() {return MAX_IDLE_PENDING_TIMEOUT;}
    public float getIdlePendingFactor() {return IDLE_PENDING_FACTOR;}
    public long getIdleTo() {return IDLE_TIMEOUT;}
    public long getMaxIdleTo() {return MAX_IDLE_TIMEOUT;}
    public float getIdleFactor() {return IDLE_FACTOR;}
    public long getMinTimeToAlarm() {return MIN_TIME_TO_ALARM;}
    public long getMaxTempAppWhitelistDuration() {return MAX_TEMP_APP_WHITELIST_DURATION;}
    public long getMmsTempAppWhitelistDuration() {return MMS_TEMP_APP_WHITELIST_DURATION;}
    public long getSmsTempAppWhitelistDuration() {return SMS_TEMP_APP_WHITELIST_DURATION;}
    public long getNotificationWhitelistDuration() {return NOTIFICATION_WHITELIST_DURATION;}

    //    public void applyTunables() {
//        loadTunables();
//        TUNABLE_STRING = getTunableString();
//        log("Setting device_idle_constants=" + TUNABLE_STRING);
//        executeCommand("settings put global device_idle_constants " + TUNABLE_STRING);
//        Toast.makeText(this, getString(R.string.applied_success_text), Toast.LENGTH_SHORT).show();
//    }
}
