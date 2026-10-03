package com.akylas.enforcedoze;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.CommandResult;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.access.Feature;
import com.akylas.enforcedoze.doze.*;
import com.akylas.enforcedoze.doze.parse.DozeStateReading;
import com.akylas.enforcedoze.service.DozeRuntime;
import com.akylas.enforcedoze.service.SessionLifecycle;
import com.akylas.enforcedoze.service.FeatureSelection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.chainfire.libsuperuser.Shell;

import static android.preference.PreferenceManager.getDefaultSharedPreferences;
import static com.akylas.enforcedoze.Utils.logToLogcat;

public class ForceDozeService extends Service {

    private static final String CHANNEL_STATS = "CHANNEL_STATS";
    private static final String CHANNEL_TIPS = "CHANNEL_TIPS";
    private static final String CHANNEL_SILENT = "CHANNEL_SILENT";
    private static final int PERSISTENT_NOTIF_ID = 1234;

    private DozeRuntime runtime;
    private Handler worker;
    private volatile boolean destroyed;
    private volatile boolean waitForUnlock;
    private volatile boolean disableWhenCharging = true;
    private final AtomicLong exitEpoch = new AtomicLong();
    private Runnable pendingEnter;
    private Runnable pendingNotification;
    private long enterDueElapsed;
    private AccessLevel previousAccess;
    private AccessManager.Listener accessListener;
    boolean isSuAvailable = false;
    boolean isShizukuAvailable = false;
    boolean disableMotionSensors = true;
    boolean useAutoRotateAndBrightnessFix = false;
    boolean showPersistentNotif = false;
    boolean ignoreLockscreenTimeout = false;
    boolean turnOffAllSensorsInDoze = false;
    boolean turnOffBiometricsInDoze = false;
    boolean turnOnBatterySaverInDoze = false;
    boolean turnOnAirplaneInDoze = false;
    boolean turnOffBluetoothInDoze = false;
    boolean turnOffGPSInDoze = false;
    boolean turnOffWiFiInDoze = false;
    boolean ignoreIfHotspot = false;
    boolean turnOffDataInDoze = false;
    boolean whitelistMusicAppNetwork = false;
    boolean whitelistCurrentApp = false;
    boolean maintenance = false;
    private boolean verifiedIdleSeen;
    boolean disableStats = false;
    boolean disableLogcat = false;
    int dozeEnterDelay = 0;
    DozeReceiver localDozeReceiver;
    ReloadSettingsReceiver reloadSettingsReceiver;
    ReloadNotificationBlocklistReceiver reloadNotificationBlocklistReceiver;
    ReloadAppsBlocklistReceiver reloadAppsBlocklistReceiver;
    NotificationCompat.Builder mStatsBuilder;
    PowerManager pm;
    PowerManager.WakeLock tempWakeLock;
    Set<String> dozeUsageData = new LinkedHashSet<>();
    Set<String> dozeNotificationBlocklist = new LinkedHashSet<>();
    Set<String> dozeAppBlocklist = new LinkedHashSet<>();
    String sensorWhitelistPackage = "";
    Long timeEnterDoze = 0L;
    Long timeExitDoze = 0L;
    String lastScreenOff = "Unknown";
    int lastDozeEnterBatteryLife = 0;
    int lastDozeExitBatteryLife = 0;
    String TAG = "ForceDozeService";
    String lastKnownState = "null";

    // Add near the top of the class
    private static final String ACTION_IGNORE_RESULT = "com.akylas.enforcedoze.ACTION_IGNORE_BATTERY_OPTIMIZATION_RESULT";
    private static final String EXTRA_IGNORED = "com.akylas.enforcedoze.EXTRA_IGNORED";

    private BroadcastReceiver ignoreBatteryResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            postWork(() -> handleIgnoreBatteryResult(intent));
        }
    };

    private void handleIgnoreBatteryResult(Intent intent) {
            boolean ignored = intent.getBooleanExtra(EXTRA_IGNORED, false);
            String packageName = getPackageName();
            if (!ignored && !pm.isIgnoringBatteryOptimizations(packageName)) {
                log("Service still optimized after user prompt, showing notification...");
                Intent notificationIntent = new Intent();
                notificationIntent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                PendingIntent pi = PendingIntent.getActivity(getApplicationContext(), 0,
                        notificationIntent, PendingIntent.FLAG_IMMUTABLE);
                Notification n = new NotificationCompat.Builder(ForceDozeService.this, CHANNEL_TIPS)
                        .setContentTitle("EnforceDoze")
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(
                                "EnforceDoze needs to be added to the Doze whitelist in order to work reliably. Please open the battery optimisation view and select 'Don't optimize' for EnforceDoze."))
                        .setSmallIcon(R.drawable.ic_battery_health)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setContentIntent(pi)
                        .setOngoing(false)
                        .build();
                NotificationManager notificationManager =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                notificationManager.notify(8765, n);
            } else {
                log("User granted ignore battery optimizations for service.");
            }
    }

    private void log(String message) {
        logToLogcat(TAG, message);
    }

    public ForceDozeService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        localDozeReceiver = new DozeReceiver();
        reloadSettingsReceiver = new ReloadSettingsReceiver();
        reloadNotificationBlocklistReceiver = new ReloadNotificationBlocklistReceiver();
        reloadAppsBlocklistReceiver = new ReloadAppsBlocklistReceiver();
        runtime = MyApplication.getDozeRuntime(this);
        worker = runtime.attachService();
        showPersistentNotif = getDefaultSharedPreferences(this).getBoolean("showPersistentNotif", false);
        waitForUnlock = getDefaultSharedPreferences(this).getBoolean("waitForUnlock", false);
        disableWhenCharging = getDefaultSharedPreferences(this).getBoolean("disableWhenCharging", true);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence statsName = getString(R.string.notification_channel_stats_name);
            String statsDescription = getString(R.string.notification_channel_stats_description);
            int statsImportance = NotificationManager.IMPORTANCE_MIN;
            NotificationChannel statsChannel = new NotificationChannel(CHANNEL_STATS, statsName, statsImportance);
            statsChannel.setDescription(statsDescription);

            CharSequence tipsName = getString(R.string.notification_channel_tips_name);
            String tipsDescription = getString(R.string.notification_channel_tips_description);
            int tipsImportance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel tipsChannel = new NotificationChannel(CHANNEL_TIPS, tipsName, tipsImportance);
            tipsChannel.setDescription(tipsDescription);
            
            // Create a silent channel for Android 12+ foreground service requirement
            CharSequence silentName = getString(R.string.notification_channel_silent_name);
            String silentDescription = getString(R.string.notification_channel_silent_description);
            int silentImportance = NotificationManager.IMPORTANCE_MIN;
            NotificationChannel silentChannel = new NotificationChannel(CHANNEL_SILENT, silentName, silentImportance);
            silentChannel.setDescription(silentDescription);
            silentChannel.setSound(null, null);
            silentChannel.setShowBadge(false);
            
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(statsChannel);
            notificationManager.createNotificationChannel(tipsChannel);
            notificationManager.createNotificationChannel(silentChannel);
        }

        mStatsBuilder = new NotificationCompat.Builder(this, CHANNEL_STATS);
        pm = (PowerManager) getSystemService(POWER_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
//        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        if (Utils.isDeviceRunningOnN()) {
            filter.addAction("android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED");
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(reloadSettingsReceiver, new IntentFilter("reload-settings"));
        LocalBroadcastManager.getInstance(this).registerReceiver(reloadNotificationBlocklistReceiver, new IntentFilter("reload-notification-blocklist"));
        LocalBroadcastManager.getInstance(this).registerReceiver(reloadAppsBlocklistReceiver, new IntentFilter("reload-app-blocklist"));
        LocalBroadcastManager.getInstance(this).registerReceiver(ignoreBatteryResultReceiver, new IntentFilter(ACTION_IGNORE_RESULT));
        ContextCompat.registerReceiver(this, localDozeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        postWork(this::initializeWorker);
        accessListener = this::onAccessChanged;
        runtime.getAccess().addListener(accessListener);
    }

    private void initializeWorker() {
        // Recovery is the first worker operation, before settings, grants or new enforcement.
        runtime.bumpGeneration();
        runtime.recordExit(runtime.getController().reconcile(Build.VERSION.SDK_INT, runtime.grants()));
        runtime.configureAllowToken(getDefaultSharedPreferences(this).getString("sensorWhitelistPackage", ""));
        runtime.checkSafety();
        if (destroyed) return;
        turnOffDataInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_OFF_DATA, false);
        ignoreIfHotspot = getDefaultSharedPreferences(getApplicationContext()).getBoolean("ignoreIfHotspot", true);
        turnOffWiFiInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_OFF_WIFI, false);
        turnOffAllSensorsInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_OFF_ALL_SENSORS, false);
        turnOffBiometricsInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_OFF_BIOMETRICS, false);
        turnOnBatterySaverInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_ON_BATTERY_SAVER, false);
        turnOnAirplaneInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_ON_AIRPLANE, false);
        turnOffBluetoothInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_OFF_BLUETOOTH, false);
        turnOffGPSInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_OFF_LOCATION, false);
        whitelistMusicAppNetwork = getDefaultSharedPreferences(getApplicationContext()).getBoolean("whitelistMusicAppNetwork", false);
        whitelistCurrentApp = getDefaultSharedPreferences(getApplicationContext()).getBoolean("whitelistCurrentApp", false);
        ignoreLockscreenTimeout = getDefaultSharedPreferences(getApplicationContext()).getBoolean("ignoreLockscreenTimeout", true);
        waitForUnlock = getDefaultSharedPreferences(getApplicationContext()).getBoolean("waitForUnlock", false);
        dozeEnterDelay = getDefaultSharedPreferences(getApplicationContext()).getInt("dozeEnterDelay", 0);
        useAutoRotateAndBrightnessFix = getDefaultSharedPreferences(getApplicationContext()).getBoolean("autoRotateAndBrightnessFix", false);
        sensorWhitelistPackage = getDefaultSharedPreferences(getApplicationContext()).getString("sensorWhitelistPackage", "");
        disableMotionSensors = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableMotionSensors", true);
        disableStats = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableStats", false);
        disableLogcat = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableLogcat", false);
        disableWhenCharging = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableWhenCharging", true);
        isSuAvailable = getDefaultSharedPreferences(getApplicationContext()).getBoolean("isSuAvailable", false);
        showPersistentNotif = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean("showPersistentNotif", false);
        dozeUsageData = new LinkedHashSet<>(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getStringSet("dozeUsageDataAdvanced", new LinkedHashSet<String>()));
        dozeNotificationBlocklist = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getStringSet(Prefs.NOTIFICATION_BLOCKLIST, new LinkedHashSet<String>());
        dozeAppBlocklist = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getStringSet(Prefs.APP_BLOCKLIST, new LinkedHashSet<String>());

        runtime.configureAllowToken(sensorWhitelistPackage);
        previousAccess = runtime.getAccess().getLevel();
        updateAccessFlags(previousAccess);
        if (previousAccess.compareTo(AccessLevel.SHELL) >= 0) {
            if (!Utils.isDumpPermissionGranted(this)) grantDumpPermission();
            if (!Utils.isSecureSettingsPermissionGranted(this)) grantSecureSettingsPermission();
            if (!Utils.isReadPhoneStatePermissionGranted(this)) grantReadPhoneStatePermission();
        }
        if (destroyed) return;
    }


    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    private void postWork(Runnable action) {
        if (!destroyed) worker.post(() -> { if (!destroyed) action.run(); });
    }

    public static void requestSafetyCheck(Context context) {
        MyApplication.getDozeRuntime(context).requestSafetyCheck();
    }

    private void updateAccessFlags(AccessLevel level) {
        isSuAvailable = level == AccessLevel.ROOT;
        isShizukuAvailable = level == AccessLevel.SHELL || level == AccessLevel.ROOT;
    }

    private void onAccessChanged(AccessState access) {
        if (destroyed) return;
        if (access.getLevel().compareTo(AccessLevel.SHELL) < 0) runtime.bumpGeneration();
        postWork(() -> {
            AccessLevel old = previousAccess;
            previousAccess = access.getLevel();
            updateAccessFlags(access.getLevel());
            runtime.getJournal().emit(new DozeEvent(EventType.ACCESS_CHANGED,
                    access.getLevel().name() + (access.getLevel().compareTo(AccessLevel.SHELL) < 0 ? " NO_ACCESS" : "")));
            if (old != null && access.getLevel().compareTo(old) > 0) {
                runtime.reconcileAndCheck();
                if (runtime.getSessionActive()) scheduleEnter();
            } else if (access.getLevel().compareTo(AccessLevel.SHELL) < 0) {
                cancelEnter();
                runtime.checkSafety();
            }
        });
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        exitEpoch.incrementAndGet();
        runtime.setSessionActive(false);
        runtime.bumpGeneration();
        if (accessListener != null) runtime.getAccess().removeListener(accessListener);
        unregisterReceiver(localDozeReceiver);
        LocalBroadcastManager broadcasts = LocalBroadcastManager.getInstance(this);
        broadcasts.unregisterReceiver(reloadSettingsReceiver);
        broadcasts.unregisterReceiver(reloadNotificationBlocklistReceiver);
        broadcasts.unregisterReceiver(reloadAppsBlocklistReceiver);
        broadcasts.unregisterReceiver(ignoreBatteryResultReceiver);
        worker.removeCallbacksAndMessages(null);
        runtime.detachService();
        CountDownLatch stopped = new CountDownLatch(1);
        long deadline = runtime.getClock().elapsedRealtime() + SessionLifecycle.TEARDOWN_COMMAND_MS;
        worker.post(() -> {
            try {
                runtime.withDeadline(deadline, () -> {
                    runtime.recordExit(runtime.getController().exit(Build.VERSION.SDK_INT, runtime.grants()));
                    runtime.checkSafety();
                });
            } catch (Exception error) {
                runtime.getJournal().emit(new DozeEvent(EventType.ERROR, "TEARDOWN_FAILED"));
            } finally {
                runtime.getSession().recordExit(); // A destroyed session cannot suppress a replacement ENTER.
                releaseWakeLock();
                runtime.quitIfDetached();
                stopped.countDown();
            }
        });
        try {
            if (!stopped.await(SessionLifecycle.TEARDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                runtime.getJournal().emit(new DozeEvent(EventType.RECOVERY_DEBT, "TEARDOWN_TIMEOUT"));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        releaseWakeLock();
        if (!getDefaultSharedPreferences(this).getBoolean("serviceEnabled", false)) {
            Utils.showDisabledNotification(this);
        }
        Utils.updateTileState(this);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        // Promotion cannot wait behind reconciliation or command work, on any supported API.
        if (showPersistentNotif) showPersistentNotification(); else showSilentNotification();
        postWork(() -> {
            if (!runtime.getSessionActive()) runtime.reconcileAndCheck();
            else runtime.checkSafety();
            addSelfToDozeWhitelist();
            long epoch = exitEpoch.get();
            if (!runtime.getSessionActive() && runtime.getSession().activate(epoch, exitEpoch::get, () -> Utils.isScreenOn(this))) {
                verifiedIdleSeen = false;
                runtime.getJournal().beginSession();
                runtime.getJournal().screen(EventType.SCREEN_OFF, Utils.getBatteryLevel(this), Utils.isConnectedToCharger(this));
                scheduleEnter();
            }
            lastKnownState = deepState();
            Utils.hideDisabledNotification(this);
            Utils.updateTileState(this);
        });
        return START_STICKY;
    }

    public void reloadSettings() {
        log("EnforceDoze settings reloaded ----------------------------------");
        dozeUsageData = new LinkedHashSet<>(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getStringSet("dozeUsageDataAdvanced", new LinkedHashSet<String>()));
        log("dozeUsageData: " + "Total Entries -> " + dozeUsageData.size());
        turnOffDataInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_OFF_DATA, false);
        log("turnOffDataInDoze: " + turnOffDataInDoze);
        ignoreIfHotspot = getDefaultSharedPreferences(getApplicationContext()).getBoolean("ignoreIfHotspot", true);
        log("ignoreIfHotspot: " + ignoreIfHotspot);
        turnOffWiFiInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_OFF_WIFI, false);
        log("turnOffWiFiInDoze: " + turnOffWiFiInDoze);
        turnOffAllSensorsInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_OFF_ALL_SENSORS, false);
        log("turnOffAllSensorsInDoze: " + turnOffAllSensorsInDoze);
        turnOffBiometricsInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_OFF_BIOMETRICS, false);
        log("turnOffBiometricsInDoze: " + turnOffBiometricsInDoze);
        turnOnBatterySaverInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_ON_BATTERY_SAVER, false);
        log("turnOnBatterySaverInDoze: " + turnOnBatterySaverInDoze);
        turnOnAirplaneInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_ON_AIRPLANE, false);
        log("turnOnAirplaneInDoze: " + turnOnAirplaneInDoze);
        turnOffBluetoothInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_OFF_BLUETOOTH, false);
        log("turnOffBluetoothInDoze: " + turnOffBluetoothInDoze);
        turnOffGPSInDoze = getDefaultSharedPreferences(getApplicationContext()).getBoolean(Prefs.TURN_OFF_LOCATION, false);
        log("turnOffGPSInDoze: " + turnOffGPSInDoze);
        whitelistMusicAppNetwork = getDefaultSharedPreferences(getApplicationContext()).getBoolean("whitelistMusicAppNetwork", false);
        log("whitelistMusicAppNetwork: " + whitelistMusicAppNetwork);
        whitelistCurrentApp = getDefaultSharedPreferences(getApplicationContext()).getBoolean("whitelistCurrentApp", false);
        log("whitelistCurrentApp: " + whitelistCurrentApp);
        ignoreLockscreenTimeout = getDefaultSharedPreferences(getApplicationContext()).getBoolean("ignoreLockscreenTimeout", true);
        log("ignoreLockscreenTimeout: " + ignoreLockscreenTimeout);
        waitForUnlock = getDefaultSharedPreferences(getApplicationContext()).getBoolean("waitForUnlock", false);
        log("waitForUnlock: " + waitForUnlock);
        dozeEnterDelay = getDefaultSharedPreferences(getApplicationContext()).getInt("dozeEnterDelay", 0);
        log("dozeEnterDelay: " + dozeEnterDelay);
        useAutoRotateAndBrightnessFix = getDefaultSharedPreferences(getApplicationContext()).getBoolean("autoRotateAndBrightnessFix", false);
        log("useAutoRotateAndBrightnessFix: " + useAutoRotateAndBrightnessFix);
        sensorWhitelistPackage = getDefaultSharedPreferences(getApplicationContext()).getString("sensorWhitelistPackage", "");
        log("sensorWhitelistPackage: " + sensorWhitelistPackage);
        disableMotionSensors = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableMotionSensors", true);
        log("disableMotionSensors: " + disableMotionSensors);
        disableStats = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableStats", false);
        log("disableStats: " + disableStats);
        disableLogcat = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableLogcat", false);
        log("disableLogcat: " + disableLogcat);
        disableWhenCharging = getDefaultSharedPreferences(getApplicationContext()).getBoolean("disableWhenCharging", true);
        log("disableWhenCharging: " + disableWhenCharging);
        showPersistentNotif = getDefaultSharedPreferences(getApplicationContext()).getBoolean("showPersistentNotif", false);
        log("showPersistentNotif: " + showPersistentNotif);
        log("EnforceDoze settings reloaded ----------------------------------");
        runtime.configureAllowToken(sensorWhitelistPackage);
        if (showPersistentNotif) showPersistentNotification(); else showSilentNotification();
    }

    public void reloadNotificationBlockList() {
        log("Notification blocklist reloaded ----------------------------------");
        dozeNotificationBlocklist = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getStringSet(Prefs.NOTIFICATION_BLOCKLIST, new LinkedHashSet<String>());
        log("notificationBlockList: " + dozeNotificationBlocklist.size() + " items");
        log("Notification blocklist reloaded ----------------------------------");
    }

    public void reloadAppsBlockList() {
        log("Apps blocklist reloaded ----------------------------------");
        dozeAppBlocklist = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getStringSet(Prefs.APP_BLOCKLIST, new LinkedHashSet<String>());
        log("dozeAppBlockList: " + dozeAppBlocklist.size() + " items");
        log("Apps blocklist reloaded ----------------------------------");
    }

    public void grantDumpPermission() {
        log("Granting android.permission.DUMP to com.akylas.enforcedoze");
        executeCommandWithRoot("pm grant com.akylas.enforcedoze android.permission.DUMP");
    }

    public void grantDumpPermissionViaShizuku() {
        grantDumpPermission();
    }

    public void grantSecureSettingsPermission() {
        log("Granting android.permission.WRITE_SECURE_SETTINGS to com.akylas.enforcedoze");
        executeCommandWithRoot("pm grant com.akylas.enforcedoze android.permission.WRITE_SECURE_SETTINGS");
    }

    public void grantSecureSettingsPermissionViaShizuku() {
        grantSecureSettingsPermission();
    }

    public void grantReadPhoneStatePermission() {
        log("Granting android.permission.READ_PHONE_STATE to com.akylas.enforcedoze");
        executeCommandWithRoot("pm grant com.akylas.enforcedoze android.permission.READ_PHONE_STATE");
    }

    public void grantReadPhoneStatePermissionViaShizuku() {
        grantReadPhoneStatePermission();
    }

    public void addSelfToDozeWhitelist() {
        log("Checking self-whitelist capability....");
        log("Nougat: " + Utils.isDeviceRunningOnN());
        log("SU available: " + isSuAvailable);
        String packageName = getPackageName();
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            if (!Utils.isDeviceRunningOnN()) {
                log("Adding service to Doze whitelist for stability");
                executeCommand("dumpsys deviceidle whitelist +com.akylas.enforcedoze");
            } else if (Utils.isDeviceRunningOnN() && (isSuAvailable || isShizukuAvailable)) {
                log("Adding service to Doze whitelist for stability");
                executeCommandWithRoot("dumpsys deviceidle whitelist +com.akylas.enforcedoze");
            } else {
                log("Requesting user to disable battery optimizations via system dialog...");
                try {
                    Intent reqActivity = new Intent(this, RequestIgnoreBatteryActivity.class);
                    reqActivity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(reqActivity);
                } catch (Exception e) {
                    log("Failed to launch RequestIgnoreBatteryActivity: " + e.getMessage());
                    // fallback: show the old notification immediately
                    // (optional) reuse existing notification code here
                    log("Service cannot be added to Doze whitelist because user is on Nougat. Showing notification...");
                    Intent notificationIntent = new Intent();
                    notificationIntent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    PendingIntent intent = PendingIntent.getActivity(getApplicationContext(), 0,
                            notificationIntent, PendingIntent.FLAG_IMMUTABLE);
                    Notification n = new NotificationCompat.Builder(this, CHANNEL_TIPS)
                            .setContentTitle("EnforceDoze")
                            .setStyle(new NotificationCompat.BigTextStyle().bigText("EnforceDoze needs to be added to the Doze whitelist in order to work reliably. Please click on this notification to open the battery optimisation view, click on 'EnforceDoze' and select 'Don't' Optimize'"))
                            .setSmallIcon(R.drawable.ic_battery_health)
                            .setPriority(1)
                            .setContentIntent(intent)
                            .setOngoing(false).build();
                    NotificationManager notificationManager =
                            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    notificationManager.notify(8765, n);
                }
            }

        } else {
            log("Service already in Doze whitelist for stability");
        }
    }

    private DozeConfig config(boolean sensors, boolean playingMusic) {
        SharedPreferences prefs = getDefaultSharedPreferences(this);
        Set<String> enabled = new HashSet<>();
        for (String key : Arrays.asList(Prefs.TURN_OFF_WIFI, Prefs.TURN_OFF_DATA, Prefs.TURN_OFF_BLUETOOTH,
                Prefs.TURN_OFF_LOCATION, Prefs.TURN_ON_AIRPLANE, Prefs.TURN_OFF_BIOMETRICS, Prefs.TURN_OFF_ALL_SENSORS)) {
            if (prefs.getBoolean(key, false)) enabled.add(key);
        }
        Set<Feature> features = FeatureSelection.features(enabled, Utils.isHotspotEnabled(this), ignoreIfHotspot,
                playingMusic, Utils.isWiFiEnabled(this));
        Set<String> apps = new HashSet<>(dozeAppBlocklist);
        Set<String> focused = new HashSet<>();
        if (whitelistCurrentApp) {
            if (runtime.getAccess().getLevel().compareTo(AccessLevel.SHELL) >= 0) getFocusedApps(focused::addAll);
            else {
                String pkg = getNonRootFocusedPackageName();
                if (pkg != null) focused.add(pkg);
            }
        }
        apps.removeAll(focused);
        apps.remove(getPackageName());
        Set<String> notifications = new HashSet<>(dozeNotificationBlocklist);
        notifications.removeAll(apps);
        notifications.remove(getPackageName());
        return new DozeConfig(Build.VERSION.SDK_INT, runtime.getAccess().getLevel(), runtime.grants(),
                sensors, runtime.getAllowToken(), prefs.getBoolean(Prefs.TURN_ON_BATTERY_SAVER, false),
                features, apps, notifications,
                prefs.getBoolean(Prefs.KEEP_DOZE_ENFORCED, Prefs.DEFAULT_KEEP_DOZE_ENFORCED),
                legacyNotificationTransaction());
    }

    private Integer legacyNotificationTransaction() {
        if (Build.VERSION.SDK_INT >= 33 || runtime.getAccess().getLevel() != AccessLevel.ROOT
                || dozeNotificationBlocklist.isEmpty()) return null;
        try {
            Class<?> stub = Class.forName("android.app.INotificationManager$Stub");
            Field field = stub.getDeclaredField("TRANSACTION_setNotificationsEnabledForPackage");
            field.setAccessible(true);
            int transaction = field.getInt(null);
            return transaction > 0 ? transaction : null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null; // Never guess a hidden transaction number.
        }
    }

    private boolean admitted() {
        return !destroyed && runtime.getSessionActive()
                && runtime.getAccess().getLevel().compareTo(AccessLevel.SHELL) >= 0
                && !runtime.getStore().getLoadFailed() && runtime.getStore().getCorruptLines().isEmpty()
                && runtime.getClock().elapsedRealtime() >= enterDueElapsed
                && getDefaultSharedPreferences(this).getBoolean(Prefs.SERVICE_ENABLED, false)
                && !Utils.isScreenOn(this) && Utils.isInsideCustomDozePeriod(this)
                && !(disableWhenCharging && Utils.isConnectedToCharger(this))
                && !Utils.isUserInCommunicationCall(this) && !Utils.isUserInCall(this);
    }

    private void cancelEnter() {
        if (pendingEnter != null) worker.removeCallbacks(pendingEnter);
        pendingEnter = null;
        releaseWakeLock();
    }

    private synchronized void releaseWakeLock() {
        PowerManager.WakeLock wakeLock = tempWakeLock;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void scheduleEnter() {
        cancelEnter();
        if (pendingNotification != null) worker.removeCallbacks(pendingNotification);
        int lockTimeout = Settings.Secure.getInt(getContentResolver(), "lock_screen_lock_after_timeout", 5000);
        if (lockTimeout == 0) lockTimeout = 1000;
        long delay = Math.max(0L, (long) dozeEnterDelay * 1000L) + (ignoreLockscreenTimeout ? 0 : lockTimeout);
        enterDueElapsed = runtime.getClock().elapsedRealtime() + delay;
        long generation = runtime.getController().getCurrentGeneration();
        pendingEnter = () -> {
            if (generation == runtime.getController().getCurrentGeneration()) enterDoze(this);
            releaseWakeLock();
        };
        if (delay > 0) {
            tempWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forcedoze:tempWakelock");
            tempWakeLock.acquire(10 * 60 * 1000L);
        }
        worker.postDelayed(pendingEnter, delay);
    }

    public void enterDoze(Context context) {
        enterDoze(disableMotionSensors);
    }

    private void enterDoze(boolean sensors) {
        if (!admitted()) {
            runtime.getJournal().emit(new DozeEvent(EventType.SKIPPED, "ADMISSION"));
            return;
        }
        long generation = runtime.getController().getCurrentGeneration();
        if (whitelistMusicAppNetwork) {
            NotificationService listener = NotificationService.Companion.getInstance();
            if (listener != null) {
                listener.getPlayingPackageName(pkg -> {
                    postWork(() -> enterConfiguredDoze(sensors, pkg != null, generation));
                    return null;
                });
                return;
            }
        }
        enterConfiguredDoze(sensors, false, generation);
    }

    private void enterConfiguredDoze(boolean sensors, boolean playingMusic, long generation) {
        if (!admitted() || generation != runtime.getController().getCurrentGeneration()) return;
        try {
            EnterResult result = runtime.getController().enter(config(sensors, playingMusic), generation, this::admitted);
            if (result.getStatus() == EnterStatus.CANCELLED || !admitted()
                    || generation != runtime.getController().getCurrentGeneration()) return;
            lastKnownState = deepState();
            if (!lastKnownState.equals("IDLE")) return;
        } catch (Exception error) {
            runtime.getJournal().emit(new DozeEvent(EventType.ERROR, "ENTER_FAILED"));
            return;
        }
        releaseWakeLock();
        if (verifiedIdleSeen) return;
        verifiedIdleSeen = true;
        timeEnterDoze = System.currentTimeMillis();
        lastDozeEnterBatteryLife = Utils.isConnectedToCharger(this) ? 0 : Utils.getBatteryLevel(this);
        lastScreenOff = Utils.getDateCurrentTimeZone(timeEnterDoze);
        if (runtime.getSession().recordEnter(!disableStats)) {
            dozeUsageData.add(timeEnterDoze + "," + Float.toString((float) lastDozeEnterBatteryLife) + ",ENTER");
            saveDozeDataStats();
        }
    }

    public void exitDoze(String newDeviceIdleState) {
        cancelEnter();
        if (pendingNotification != null) worker.removeCallbacks(pendingNotification);
        // Restoration walks durable intent only, even if settings changed mid-session.
        runtime.bumpGeneration();
        runtime.recordExit(runtime.getController().exit(Build.VERSION.SDK_INT, runtime.grants()));
        runtime.checkSafety();
        timeExitDoze = System.currentTimeMillis();
        lastDozeExitBatteryLife = Utils.isConnectedToCharger(this) ? 0 : Utils.getBatteryLevel(this);
        lastKnownState = deepState();
        if (runtime.getSession().recordExit()) {
            dozeUsageData.add(timeExitDoze + "," + Float.toString((float) lastDozeExitBatteryLife) + ",EXIT");
            saveDozeDataStats();
        }
        if (showPersistentNotif) {
            pendingNotification = () -> updatePersistentNotification(lastScreenOff,
                    Utils.diffInMins(timeEnterDoze, timeExitDoze), lastDozeEnterBatteryLife - lastDozeExitBatteryLife);
            worker.postDelayed(pendingNotification, 2000);
        }
    }

    public void executeCommand(final String command) {
        executeCommand(command, null, false);
    }

    public void executeCommand(final String command, Shell.OnCommandResultListener2 onResult, Boolean printOutput) {
        if (Looper.myLooper() != worker.getLooper()) {
            postWork(() -> executeCommand(command, onResult, printOutput));
            return;
        }
        CommandResult result = runtime.getControl().run(command, 8000);
        if (onResult != null) onResult.onCommandResult(0, result.getExitCode(), result.getStdout(), result.getStderr());
        if (printOutput) {
            printShellOutput(result.getStdout());
            printShellOutput(result.getStderr());
        }
    }

    public interface OnGetFocusedApp {
        void onGetFocusedApps(HashSet<String> result);
    }

    public HashSet<String> parseFocusedApps(String services) {
        if (!services.isEmpty()) {
            return new HashSet<String>(Arrays.asList(services.split("\\r?\\n")));
        }
        return new HashSet<String>();
    }

    public String getNonRootFocusedPackageName() {
        var usm = (UsageStatsManager) this.getSystemService(Context.USAGE_STATS_SERVICE);
        long time = System.currentTimeMillis();
        List<UsageStats> appList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 10000, time);
        if (appList != null && !appList.isEmpty()) {
            SortedMap<Long, UsageStats> mySortedMap = new TreeMap<>();
            for (UsageStats usageStats : appList) {
                mySortedMap.put(usageStats.getLastTimeUsed(), usageStats);
            }
            if (!mySortedMap.isEmpty()) {
                return Objects.requireNonNull(mySortedMap.get(mySortedMap.lastKey())).getPackageName();
            }
        }
        return null;
    }

    String FOCUSED_APP_REGEXP = "\\{[a-z0-9]+\\s[a-z0-9]+\\s(.*)\\/";

    public void getFocusedApps(OnGetFocusedApp callback) {
        executeCommandWithRoot("dumpsys activity activities | grep -E 'CurrentFocus|ResumedActivity|FocusedApp'", (commandCode, exitCode, STDOUT, STDERR) -> {
            String result = "";
            if (commandCode == 0) {
                if (!STDOUT.isEmpty()) {
                    Matcher m = Pattern.compile(FOCUSED_APP_REGEXP).matcher(STDOUT.get(0));
                    if (m.find()) {
                        result = m.group(1);
                    }
                }
            }
            callback.onGetFocusedApps(parseFocusedApps(result));
        });
    }

    public void executeCommandWithRoot(final String command) {
        executeCommandWithRoot(command, null);
    }

    public void executeCommandWithRoot(final String command, Shell.OnCommandResultListener2 onResult) {
        executeCommand(command, onResult, true);
    }

    public void printShellOutput(List<String> output) {
        if (disableLogcat) {
            return;
        }
        if (output != null && !output.isEmpty()) {
            for (String s : output) {
                log(s);
            }
        }
    }

    public void saveDozeDataStats() {
        SharedPreferences sharedPreferences = getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove("dozeUsageDataAdvanced");
        editor.apply();
        editor.putStringSet("dozeUsageDataAdvanced", dozeUsageData);
        editor.apply();
    }

    public void showPersistentNotification() {
        Context context = getApplicationContext();
        Intent notificationIntent = new Intent(context, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent intent = PendingIntent.getActivity(getApplicationContext(), 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification n = mStatsBuilder
                .setStyle(
                        new NotificationCompat.BigTextStyle()
                                .bigText(getString(R.string.stats_no_data)))
                .setSmallIcon(R.drawable.ic_battery_health)
                .setPriority(-2)
                .setContentIntent(intent)
                .setOngoing(true)
                .build();
        ServiceCompat.startForeground(this, PERSISTENT_NOTIF_ID, n,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
    }

    public void updatePersistentNotification(String lastScreenOff, int timeSpentDozing, int batteryUsage) {
        Intent notificationIntent = new Intent(getApplicationContext(), MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent intent = PendingIntent.getActivity(getApplicationContext(), 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification n = mStatsBuilder
                .setStyle(
                        new NotificationCompat.BigTextStyle()
                                .bigText(getString(R.string.stats_long_text, lastScreenOff, timeSpentDozing, batteryUsage))
                                .setSummaryText(getString(R.string.stats_summary_text, batteryUsage)))
                .setShowWhen(false)
                .setSmallIcon(R.drawable.ic_battery_health)
                .setPriority(-2)
                .setContentIntent(intent)
                .setOngoing(true)
                .build();
        ServiceCompat.startForeground(this, PERSISTENT_NOTIF_ID, n,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
    }

    public void hidePersistentNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        }
    }

    public void showSilentNotification() {
        // On Android 12+, foreground services require a notification.
        // Clicking this notification opens the channel settings where the user can disable it
        // or minimize it further by setting it to "Silent" or "Minimized" importance.
        Intent notificationIntent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
        notificationIntent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        notificationIntent.putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_SILENT);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        PendingIntent intent = PendingIntent.getActivity(getApplicationContext(), 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        
        Notification n = new NotificationCompat.Builder(this, CHANNEL_SILENT)
                .setSmallIcon(R.drawable.ic_battery_health)
                .setContentTitle(getString(R.string.silent_notification_title))
                .setContentText(getString(R.string.silent_notification_text))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(intent)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .build();
        
        ServiceCompat.startForeground(this, PERSISTENT_NOTIF_ID, n,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
    }

    private String deepState() {
        DeepState deep = runtime.readState().getDeep();
        return deep == null ? "UNKNOWN" : deep.name();
    }

    private void idleChanged() {
        DozeStateReading reading = runtime.readState();
        lastKnownState = reading.getDeep() == null ? "UNKNOWN" : reading.getDeep().name();
        runtime.getJournal().emit(new DozeEvent(EventType.IDLE_CHANGED, "IDLE_CHANGED",
                reading.getDeep() == null ? DeepState.UNKNOWN : reading.getDeep(),
                reading.getLight() == null ? LightState.UNKNOWN : reading.getLight()));
        if (!runtime.getSessionActive()) return;
        Boolean maintenanceReading = SessionLifecycle.maintenanceState(reading.getDeep(), reading.getLight());
        if (Boolean.TRUE.equals(maintenanceReading) && !maintenance) {
            runtime.getJournal().emit(new DozeEvent(EventType.MAINT_START, "MAINT_START", reading.getDeep(), reading.getLight()));
            if (!disableStats) {
                dozeUsageData.add(System.currentTimeMillis() + "," + Float.toString((float) Utils.getBatteryLevel(this)) + ",EXIT_MAINTENANCE");
                saveDozeDataStats();
            }
            runtime.getController().maintenance(true, runtime.getController().getCurrentGeneration(), this::admitted);
            maintenance = true;
        } else if (Boolean.FALSE.equals(maintenanceReading) && maintenance) {
            runtime.getJournal().emit(new DozeEvent(EventType.MAINT_END, "MAINT_END", reading.getDeep(), reading.getLight()));
            if (!disableStats) {
                dozeUsageData.add(System.currentTimeMillis() + "," + Float.toString((float) Utils.getBatteryLevel(this)) + ",ENTER_MAINTENANCE");
                saveDozeDataStats();
            }
            runtime.getController().maintenance(false, runtime.getController().getCurrentGeneration(), this::admitted);
            maintenance = false;
        }
        if (!maintenance && reading.getDeep() == DeepState.IDLE && !verifiedIdleSeen) enterDoze(false);
        if (maintenance) return;
        if (!getDefaultSharedPreferences(this).getBoolean(Prefs.KEEP_DOZE_ENFORCED, Prefs.DEFAULT_KEEP_DOZE_ENFORCED)) return;
        Decision decision = runtime.getWatchdog().onIdleChanged(reading, Utils.isScreenOn(this),
                Utils.isConnectedToCharger(this), admitted());
        long generation = runtime.getController().getCurrentGeneration();
        if (decision instanceof Decision.DEFER) {
            runtime.deferWatchdog(() -> {
                if (!destroyed && generation == runtime.getController().getCurrentGeneration()) idleChanged();
            }, ((Decision.DEFER) decision).getUntilElapsed());
        } else if (decision == Decision.REFORCE.INSTANCE) {
            runtime.getJournal().emit(new DozeEvent(EventType.REFORCE, "REFORCE"));
            try {
                enterDoze(false);
            } catch (Exception error) {
                runtime.getJournal().emit(new DozeEvent(EventType.ERROR, "REFORCE_FAILED"));
            }
        }
    }

    class ReloadSettingsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("User changed a setting, loading new settings into service");
            postWork(ForceDozeService.this::reloadSettings);
        }
    }

    class ReloadNotificationBlocklistReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("User modified Notification blocklist, loading new packages into service");
            postWork(ForceDozeService.this::reloadNotificationBlockList);
        }
    }

    class ReloadAppsBlocklistReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("User modified Doze app blocklist, loading new packages into service");
            postWork(ForceDozeService.this::reloadAppsBlockList);
        }
    }

    public void handleScreenOn(Context context, int time, int delay) {
        exitDoze("UNKNOWN");
        maintenance = false;
        verifiedIdleSeen = false;
    }

    class DozeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            boolean exitTrigger = Intent.ACTION_USER_PRESENT.equals(action) && waitForUnlock
                    || Intent.ACTION_SCREEN_ON.equals(action) && !waitForUnlock
                    || SessionLifecycle.shouldExit(Intent.ACTION_POWER_CONNECTED.equals(action), Utils.isScreenOn(context),
                        runtime.getSessionActive(), disableWhenCharging);
            // This MUST happen here, not behind an in-flight enter on the worker queue.
            if (exitTrigger) {
                exitEpoch.incrementAndGet();
                runtime.setSessionActive(false);
                runtime.bumpGeneration();
            }
            long epoch = exitEpoch.get();
            postWork(() -> receiveOnWorker(action, exitTrigger, epoch));
        }
    }

    private void receiveOnWorker(String action, boolean exitTrigger, long epoch) {
        if (Intent.ACTION_SCREEN_ON.equals(action)) {
            // Observe after restore at the exit trigger, so the journal sees sensors restored first.
            if (exitTrigger) handleScreenOn(this, 0, 0);
            runtime.getJournal().screen(EventType.SCREEN_ON, Utils.getBatteryLevel(this), Utils.isConnectedToCharger(this));
        } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
            if (exitTrigger) handleScreenOn(this, 0, 0);
        } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            runtime.getJournal().beginSession();
            runtime.getJournal().screen(EventType.SCREEN_OFF, Utils.getBatteryLevel(this), Utils.isConnectedToCharger(this));
            if (!runtime.getSession().activate(epoch, exitEpoch::get, () -> Utils.isScreenOn(this))) return;
            runtime.bumpGeneration();
            verifiedIdleSeen = false;
            scheduleEnter();
        } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            if (exitTrigger) handleScreenOn(this, 0, 0);
        } else if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action)
                || "android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED".equals(action)) {
            idleChanged();
        }
    }

}
