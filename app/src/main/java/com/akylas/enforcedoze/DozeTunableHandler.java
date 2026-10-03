package com.akylas.enforcedoze;

import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.ArrayList;

public class DozeTunableHandler {
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
        ArrayList<String> commands = new ArrayList();
        final String prefix = "device_config put ";
                commands.add(prefix + DozeTunableConstants.KEY_LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT + " " + LIGHT_IDLE_AFTER_INACTIVE_TIMEOUT);
        commands.add(prefix + DozeTunableConstants.KEY_LIGHT_PRE_IDLE_TIMEOUT + " " + LIGHT_PRE_IDLE_TIMEOUT);
        commands.add(prefix + DozeTunableConstants.KEY_LIGHT_IDLE_TIMEOUT + " " + LIGHT_IDLE_TIMEOUT);
        commands.add(prefix + DozeTunableConstants.KEY_LIGHT_IDLE_FACTOR + " " + LIGHT_IDLE_FACTOR);
        commands.add(prefix + DozeTunableConstants.KEY_LIGHT_MAX_IDLE_TIMEOUT + " " + LIGHT_MAX_IDLE_TIMEOUT);
        commands.add(prefix + DozeTunableConstants.KEY_LIGHT_IDLE_MAINTENANCE_MIN_BUDGET + " " + LIGHT_IDLE_MAINTENANCE_MIN_BUDGET);
        commands.add(prefix + DozeTunableConstants.KEY_LIGHT_IDLE_MAINTENANCE_MAX_BUDGET + " " + LIGHT_IDLE_MAINTENANCE_MAX_BUDGET);
        commands.add(prefix + DozeTunableConstants.KEY_MIN_LIGHT_MAINTENANCE_TIME + " " + MIN_LIGHT_MAINTENANCE_TIME);
        commands.add(prefix + DozeTunableConstants.KEY_MIN_DEEP_MAINTENANCE_TIME + " " + MIN_DEEP_MAINTENANCE_TIME);
        commands.add(prefix + DozeTunableConstants.KEY_INACTIVE_TIMEOUT + " " + INACTIVE_TIMEOUT);
        commands.add(prefix + DozeTunableConstants.KEY_SENSING_TIMEOUT + " " + SENSING_TIMEOUT);
        commands.add(prefix + DozeTunableConstants.KEY_LOCATING_TIMEOUT + " " + LOCATING_TIMEOUT);
        commands.add(prefix + DozeTunableConstants.KEY_LOCATION_ACCURACY + " " + LOCATION_ACCURACY);
        commands.add(prefix + DozeTunableConstants.KEY_MOTION_INACTIVE_TIMEOUT + " " + MOTION_INACTIVE_TIMEOUT);
        commands.add(prefix + DozeTunableConstants.KEY_IDLE_AFTER_INACTIVE_TIMEOUT + " " + IDLE_AFTER_INACTIVE_TIMEOUT);
        commands.add(prefix + DozeTunableConstants.KEY_IDLE_PENDING_TIMEOUT + " " + IDLE_PENDING_TIMEOUT);
        commands.add(prefix + DozeTunableConstants.KEY_MAX_IDLE_PENDING_TIMEOUT + " " + MAX_IDLE_PENDING_TIMEOUT);
        commands.add(prefix + DozeTunableConstants.KEY_IDLE_PENDING_FACTOR + " " + IDLE_PENDING_FACTOR);
        commands.add(prefix + DozeTunableConstants.KEY_IDLE_TIMEOUT + " " + IDLE_TIMEOUT);
        commands.add(prefix + DozeTunableConstants.KEY_MAX_IDLE_TIMEOUT + " " + MAX_IDLE_TIMEOUT);
        commands.add(prefix + DozeTunableConstants.KEY_IDLE_FACTOR + " " + IDLE_FACTOR);
        commands.add(prefix + DozeTunableConstants.KEY_MIN_TIME_TO_ALARM + " " + MIN_TIME_TO_ALARM);
        commands.add(prefix + DozeTunableConstants.KEY_MAX_TEMP_APP_WHITELIST_DURATION + " " + MAX_TEMP_APP_WHITELIST_DURATION);
        commands.add(prefix + DozeTunableConstants.KEY_MMS_TEMP_APP_WHITELIST_DURATION + " " + MMS_TEMP_APP_WHITELIST_DURATION);
        commands.add(prefix + DozeTunableConstants.KEY_SMS_TEMP_APP_WHITELIST_DURATION + " " + SMS_TEMP_APP_WHITELIST_DURATION);
        commands.add(prefix + DozeTunableConstants.KEY_NOTIFICATION_WHITELIST_DURATION + " " + NOTIFICATION_WHITELIST_DURATION);
        return commands;
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
