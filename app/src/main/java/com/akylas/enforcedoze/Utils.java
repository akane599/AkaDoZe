package com.akylas.enforcedoze;

import android.Manifest;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.doze.ExactAlarmAccessPolicy;
import com.akylas.enforcedoze.doze.SchedulePolicy;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import android.preference.PreferenceManager;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Display;
import android.content.ComponentName;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static android.content.Context.BATTERY_SERVICE;
import static android.preference.PreferenceManager.getDefaultSharedPreferences;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class Utils {

    private static final int DISABLED_NOTIFICATION_ID = 9876;
    private static final String CHANNEL_DISABLED = "CHANNEL_DISABLED";
    public static final String ACTION_CUSTOM_DOZE_PERIOD_BOUNDARY = "com.akylas.enforcedoze.ACTION_CUSTOM_DOZE_PERIOD_BOUNDARY";
    private static final int CUSTOM_DOZE_PERIOD_REQUEST_CODE = 9012;

    public static boolean startForceDozeService(Context context) {
        if (isMyServiceRunning(ForceDozeService.class, context)) {
            logToLogcat("EnforceDoze", "ForceDozeService already running");
            return true;
        }

        Intent intent = new Intent(context, ForceDozeService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent);
            } else {
                context.startService(intent);
            }
        } catch (IllegalStateException e) {
            logToLogcat("EnforceDoze", "Service start denied: " + e.getMessage());
            MyApplication.getJournal(context).emit(new com.akylas.enforcedoze.doze.DozeEvent(
                    com.akylas.enforcedoze.doze.EventType.ERROR,
                    com.akylas.enforcedoze.monitor.EventCodes.FOREGROUND_START_DENIED));
            return false;
        }

        // Hide disabled notification
        Utils.hideDisabledNotification(context);
        // Update tile state
        Utils.updateTileState(context);
        return true;
    }

    public static void stopForceDozeService(Context context) {
        if (isMyServiceRunning(ForceDozeService.class, context)) {
            context.stopService(new Intent(context, ForceDozeService.class));
        }

        // Hide disabled notification
        Utils.showDisabledNotification(context);
        // Update tile state
        Utils.updateTileState(context);
    }

    public static boolean applyForceDozeSchedule(Context context) {
        boolean userEnabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
                com.akylas.enforcedoze.access.Prefs.SERVICE_USER_ENABLED,
                com.akylas.enforcedoze.access.Prefs.DEFAULT_SERVICE_USER_ENABLED);
        scheduleNextCustomDozePeriodBoundary(context);
        boolean shouldRunService = SchedulePolicy.shouldRunService(userEnabled,
                getCustomDozePeriods(context), getCurrentMinuteOfDay());

        if (shouldRunService) {
            if (!startForceDozeService(context)) return false;
            updateSettingBool(context, "serviceEnabled", true);
        } else {
            updateSettingBool(context, "serviceEnabled", false);
            stopForceDozeService(context);
        }
        return true;
    }

    /** Requeries on app startup, grant broadcasts and foreground returns; never applies the current window. */
    public static ExactAlarmAccessPolicy.Access requeryExactAlarmAccess(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        boolean exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || alarmManager.canScheduleExactAlarms();
        ExactAlarmAccessPolicy.Access access = ExactAlarmAccessPolicy.requery(Build.VERSION.SDK_INT,
                exactAllowed, PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
                        com.akylas.enforcedoze.access.Prefs.SERVICE_USER_ENABLED,
                        com.akylas.enforcedoze.access.Prefs.DEFAULT_SERVICE_USER_ENABLED),
                hasCustomDozePeriods(context));
        if (access.getShouldRearm()) {
            // The scheduler rechecks access and retains its racing-revocation inexact fallback.
            scheduleNextCustomDozePeriodBoundary(context);
        }
        return access;
    }

    public static void scheduleNextCustomDozePeriodBoundary(Context context) {
        cancelCustomDozePeriodAlarm(context);
        if (!PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
                com.akylas.enforcedoze.access.Prefs.SERVICE_USER_ENABLED,
                com.akylas.enforcedoze.access.Prefs.DEFAULT_SERVICE_USER_ENABLED)
                || !hasCustomDozePeriods(context)) {
            return;
        }

        long delay = getMillisUntilNextCustomDozePeriodBoundary(context);
        if (delay < 0) {
            return;
        }

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pendingIntent = getCustomDozePeriodPendingIntent(context);
        long triggerAtMillis = System.currentTimeMillis() + delay;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            }
        } catch (SecurityException e) {
            logToLogcat("EnforceDoze", "Exact custom period alarm not allowed, using inexact alarm");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            }
        }
    }

    public static void cancelCustomDozePeriodAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmManager.cancel(getCustomDozePeriodPendingIntent(context));
    }

    public static boolean hasCustomDozePeriods(Context context) {
        return !getCustomDozePeriods(context).isEmpty();
    }

    public static boolean isInsideCustomDozePeriod(Context context) {
        return SchedulePolicy.isInside(getCustomDozePeriods(context), getCurrentMinuteOfDay());
    }

    private static PendingIntent getCustomDozePeriodPendingIntent(Context context) {
        Intent intent = new Intent(context, CustomDozePeriodReceiver.class);
        intent.setAction(ACTION_CUSTOM_DOZE_PERIOD_BOUNDARY);
        return PendingIntent.getBroadcast(context, CUSTOM_DOZE_PERIOD_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Set<String> getCustomDozePeriods(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet("customDozePeriods", new LinkedHashSet<String>());
    }

    private static long getMillisUntilNextCustomDozePeriodBoundary(Context context) {
        Calendar now = Calendar.getInstance();
        SchedulePolicy.Boundary next = SchedulePolicy.nextBoundary(getCustomDozePeriods(context),
                now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE));
        if (next == null) return -1;
        Calendar boundary = (Calendar) now.clone();
        boundary.add(Calendar.DAY_OF_YEAR, next.getDaysAhead());
        boundary.set(Calendar.HOUR_OF_DAY, next.getMinuteOfDay() / 60);
        boundary.set(Calendar.MINUTE, next.getMinuteOfDay() % 60);
        boundary.set(Calendar.SECOND, 0);
        boundary.set(Calendar.MILLISECOND, 0);
        return Math.max(1000, boundary.getTimeInMillis() - now.getTimeInMillis());
    }

    private static int getCurrentMinuteOfDay() {
        Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
    }

    public static boolean isMyServiceRunning(Class<?> serviceClass, Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPostNotificationPermissionGranted(Context context) {
        return context.checkCallingOrSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isReadPhoneStatePermissionGranted(Context context) {
        return context.checkCallingOrSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isConnectedToCharger(Context context) {
        Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent != null) {
            int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            return plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
        } else return false;
    }

    public static String getDateCurrentTimeZone(long timestamp) {
        //return DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, Locale.UK).format(new Date(timestamp));
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMMM yyyy HH:mm:ss");
        return dateFormat.format(cal.getTime());
    }

    public static int getBatteryLevel(Context context) {
        BatteryManager bm = (BatteryManager)context.getSystemService(BATTERY_SERVICE);
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    public static boolean checkForAutoPowerModesFlag() {
        return Resources.getSystem().getBoolean(Resources.getSystem().getIdentifier("config_enableAutoPowerModes", "bool", "android"));
    }

    public static boolean isDeviceRunningOnN() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    public static int diffInMins(long start, long end) {
        return (int) ((end - start) / 1000) / 60;
    }

    public static String timeSpentString(long start, long end) {
        long diff = end - start;

        if (diff < 0) {
            throw new IllegalArgumentException("Duration must be greater than zero!");
        }

        long days = TimeUnit.MILLISECONDS.toDays(diff);
        diff -= TimeUnit.DAYS.toMillis(days);
        long hours = TimeUnit.MILLISECONDS.toHours(diff);
        diff -= TimeUnit.HOURS.toMillis(hours);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(diff);
        diff -= TimeUnit.MINUTES.toMillis(minutes);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(diff);

        return String.valueOf(days) +
                " days, " +
                hours +
                " hours, " +
                minutes +
                " minutes, " +
                seconds +
                " seconds";
    }

    public static boolean isUserInCommunicationCall(Context context) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        return manager.getMode() == AudioManager.MODE_IN_CALL || manager.getMode() == AudioManager.MODE_IN_COMMUNICATION;
    }

    public static boolean isUserInCall(Context context) {
        if (Utils.isReadPhoneStatePermissionGranted(context)) {
            TelephonyManager manager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            return manager.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK || manager.getCallState() == TelephonyManager.CALL_STATE_RINGING;
        }
        return false;
    }

    public static boolean isWiFiEnabled(Context context) {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wifi.isWifiEnabled();
    }
    public static boolean isHotspotEnabled(Context context) {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        Method method = null;
        try {
            ConnectivityManager conMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            method = wifi.getClass().getDeclaredMethod("getWifiApState");
            method.setAccessible(true);
            int actualState = (Integer) method.invoke(wifi, (Object[]) null);
            boolean isActiveNetworkMetered = conMgr.isActiveNetworkMetered();
            return actualState == 12 || actualState == 13;
//            return conMgr.isActiveNetworkMetered() ||  actualState == 12 || actualState == 13;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean isLockscreenTimeoutValueTooHigh(ContentResolver contentResolver) {
        return Settings.Secure.getInt(contentResolver, "lock_screen_lock_after_timeout", 5000) >= 5000;
    }

    public static float getLockscreenTimeoutValue(ContentResolver contentResolver) {
        return ((Settings.Secure.getInt(contentResolver, "lock_screen_lock_after_timeout", 5000) / 1000f) / 60f);
    }

    public static void updateSettingBool(Context context, String settingName, boolean settingValue) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(settingName, settingValue).apply();
    }

    public static boolean isScreenOn(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }
    static class ReloadSettingsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadSettings();
        }    
    }
    static ReloadSettingsReceiver reloadSettingsReceiver;
    static boolean disableLogcat = false;
    static Context applicationContext;
    static {
        init();
    }
    private static void init() {
        applicationContext = MyApplication.getAppContext();
        reloadSettingsReceiver = new ReloadSettingsReceiver();
        LocalBroadcastManager.getInstance(applicationContext).registerReceiver(reloadSettingsReceiver, new IntentFilter("reload-settings"));
        disableLogcat = getDefaultSharedPreferences(applicationContext).getBoolean("disableLogcat", false);
    }

    public static void reloadSettings() {
        disableLogcat = getDefaultSharedPreferences(applicationContext).getBoolean("disableLogcat", false);
    }

    public static void logToLogcat(String TAG, String message) {
        if (!disableLogcat) {
            Log.i(TAG, message);
        }
    }

    public static void showDisabledNotification(Context context) {
        boolean showDisabledNotification = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("showDisabledNotification", false);
        if (!showDisabledNotification) {
            return;
        }

        // Create notification channel for disabled state (Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = 
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = notificationManager.getNotificationChannel(CHANNEL_DISABLED);
            
            if (channel == null) {
                CharSequence name = context.getString(R.string.notification_channel_disabled_name);
                String description = context.getString(R.string.notification_channel_disabled_description);
                int importance = NotificationManager.IMPORTANCE_LOW;
                channel = new NotificationChannel(CHANNEL_DISABLED, name, importance);
                channel.setDescription(description);
                notificationManager.createNotificationChannel(channel);
            }
        }

        // Create broadcast intent to enable ForceDoze when tapping the notification
        Intent enableIntent = new Intent(context, InternalEnableReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context, 
            0, 
            enableIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Build notification
        NotificationCompat.Builder builder = 
            new NotificationCompat.Builder(context, CHANNEL_DISABLED)
                .setSmallIcon(R.drawable.ic_battery_health)
                .setContentTitle(context.getString(R.string.enforcedoze_disabled_notif_title))
                .setContentText(context.getString(R.string.enforcedoze_disabled_notif_text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOngoing(false);

        NotificationManager notificationManager = 
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(DISABLED_NOTIFICATION_ID, builder.build());
    }

    public static void hideDisabledNotification(Context context) {
        NotificationManager notificationManager = 
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(DISABLED_NOTIFICATION_ID);
    }

    public static void updateTileState(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                TileService.requestListeningState(context, 
                    new ComponentName(context, ForceDozeTileService.class));
            } catch (Exception e) {
                Log.e("Utils", "Failed to update tile state: " + e.getMessage());
            }
        }
    }

    public static boolean isShizukuMode(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Prefs.EXECUTION_MODE, Prefs.DEFAULT_EXECUTION_MODE).equals(Prefs.MODE_SHIZUKU);
    }

    private static boolean preferencesRepaired;

    public static synchronized void repairPreferencesPermissions(Context context) {
        if (preferencesRepaired) return;
        File directory = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        preferencesRepaired = PreferencesPermissions.repairDirectory(directory);
    }

    static final class PreferencesPermissions {
        interface Chmod {
            boolean chmod(File file, int mode);
        }

        static boolean repairDirectory(File directory) {
            return repairDirectory(directory, (file, mode) -> {
                try {
                    android.system.Os.chmod(file.getPath(), mode);
                    return true;
                } catch (android.system.ErrnoException error) {
                    return false;
                }
            });
        }

        static boolean repairDirectory(File directory, Chmod chmod) {
            File[] files = directory.listFiles();
            if (files == null) return !directory.exists();
            boolean repaired = true;
            for (File file : files) {
                if (!file.isFile()) continue;
                // One mode change preserves owner access while removing group/other access.
                repaired &= chmod.chmod(file, 0600);
            }
            return repaired;
        }
    }

    public static void openUrl(android.app.Activity activity, String url) {
        CustomTabs.with(activity.getApplicationContext())
                .setStyle(new CustomTabs.Style(activity.getApplicationContext())
                        .setShowTitle(true)
                        .setExitAnimation(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
                        .setToolbarColor(R.color.colorPrimaryDark))
                .openUrl(url, activity);
    }

}
