package com.akylas.enforcedoze;

import com.akylas.enforcedoze.monitor.EventCodes;

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
import com.akylas.enforcedoze.doze.parse.FocusedAppParser;
import com.akylas.enforcedoze.doze.parse.FocusedApps;
import com.akylas.enforcedoze.service.PackageSelection;
import com.akylas.enforcedoze.service.AccessReadiness;
import com.akylas.enforcedoze.service.DozeRuntime;
import com.akylas.enforcedoze.service.LegacyDozeStats;
import com.akylas.enforcedoze.service.SessionLifecycle;
import com.akylas.enforcedoze.service.TeardownTimeout;
import com.akylas.enforcedoze.service.SessionAccess;
import com.akylas.enforcedoze.service.SessionMode;
import com.akylas.enforcedoze.service.FeatureSelection;
import com.akylas.enforcedoze.service.DeferredFeatureSelection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

import eu.chainfire.libsuperuser.Shell;

import static android.preference.PreferenceManager.getDefaultSharedPreferences;
import static com.akylas.enforcedoze.Utils.logToLogcat;

public class ForceDozeService extends Service {

    public static final String ACTION_REAPPLY_DOZE = "com.akylas.enforcedoze.ACTION_REAPPLY_DOZE";
    public static final String EXTRA_REAPPLY_DEADLINE = "reapplyDeadlineElapsed";

    private static final String CHANNEL_STATS = "CHANNEL_STATS";
    public static final String CHANNEL_TIPS = "CHANNEL_TIPS";
    private static final String CHANNEL_SILENT = "CHANNEL_SILENT";
    private static final int PERSISTENT_NOTIF_ID = 1234;

    private DozeRuntime runtime;
    /** Presentation subscribers own their lifecycle; the runtime survives service replacement. */
    public static void addSink(Context context, DozeEventSink sink) {
        MyApplication.getDozeRuntime(context).getJournal().addSink(sink);
    }
    public static void removeSink(Context context, DozeEventSink sink) {
        MyApplication.getDozeRuntime(context).getJournal().removeSink(sink);
    }
    private Handler worker;
    private volatile boolean destroyed;
    private volatile boolean foreground;
    private volatile boolean waitForUnlock;
    private volatile boolean disableWhenCharging = true;
    private final AtomicLong exitEpoch = new AtomicLong();
    private Runnable pendingEnter;
    private static final long MUSIC_SELECTION_TIMEOUT_MS = 2000;
    private Runnable selectionTimeout;
    private DeferredFeatureSelection featureSelection;
    private DozeConfig selectedGroups;
    private Runnable pendingNotification;
    private long enterDueElapsed;
    private AccessLevel previousAccess;
    private volatile AccessState forwardAccess;
    private volatile SessionMode forwardMode = SessionMode.RESTORE_ONLY;
    private volatile boolean forwardSensors;
    private final com.akylas.enforcedoze.service.RootProbeRetry rootProbeRetry = new com.akylas.enforcedoze.service.RootProbeRetry();
    private Runnable pendingRootRetry;
    private AccessManager.Listener accessListener;
    boolean isSuAvailable = false;
    boolean isShizukuAvailable = false;
    boolean disableMotionSensors = true;
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
        runtime.setForwardAdmission(() -> admitted(true));
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
        // Load settings now; recovery waits for discovery and still precedes every admitted enter.
        runtime.configureAllowToken(getDefaultSharedPreferences(this).getString("sensorWhitelistPackage", ""));
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
        if (previousAccess.isPrivileged()) {
            AccessManager.getInstance(this).grantHelpersAutomatically();
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

    private SessionMode sessionMode() {
        return runtime.sessionMode();
    }

    /** Caller-thread invalidation must interrupt a command before the worker can recover. */
    private synchronized boolean invalidateForwardAccess(AccessState access) {
        SessionMode mode = sessionMode();
        boolean sensors = getDefaultSharedPreferences(this).getBoolean("disableMotionSensors", true);
        if (AccessReadiness.sameCapability(access, forwardAccess) && mode == forwardMode && sensors == forwardSensors) return false;
        forwardAccess = access;
        forwardMode = mode;
        forwardSensors = sensors;
        runtime.invalidateAccess();
        return true;
    }

    private void onAccessChanged(AccessState access) {
        if (destroyed) return;
        invalidateForwardAccess(access);
        postWork(() -> {
            if (!access.equals(runtime.getAccess().getState())) return; // Ignore superseded discovery callbacks.
            boolean recoveryNeeded = !runtime.accessReadyForEnter();
            if (recoveryNeeded) {
                cancelEnter();
                selectedGroups = null;
                maintenance = false;
                verifiedIdleSeen = false;
            }
            if (!access.getResolved()) {
                runtime.checkSafety(); // Unresolved intent remains durable until discovery settles.
                scheduleRootProbeRetry();
                return;
            }
            runtime.announceAccess();
            if (!runtime.recoverAccess()) return;
            previousAccess = access.getLevel();
            updateAccessFlags(access.getLevel());
            if (recoveryNeeded) {
                if (Utils.isScreenOn(this)) {
                    if (!waitForUnlock) {
                        runtime.deactivateSession();
                        handleScreenOn(this, 0, 0);
                        runtime.importHistory();
                    }
                } else if (runtime.getSessionActive() && sessionMode() != SessionMode.RESTORE_ONLY) {
                    resumeEnforcement();
                }
            } else {
                runtime.checkSafety();
            }
        });
    }

    /** Worker-only, finite backoff; a timeout never triggers an immediate main-thread probe loop. */
    private void scheduleRootProbeRetry() {
        if (pendingRootRetry != null || !runtime.getAccess().getState().getRootProbeTimedOut()) return;
        Long delay = rootProbeRetry.nextDelay(runtime.getAccess().getState().getRootProbeTimedOut(),
                runtime.getSessionActive() || runtime.hasPendingRestore());
        if (delay == null) {
            runtime.getAccess().finishRootDiscovery(false);
            return;
        }
        pendingRootRetry = () -> {
            pendingRootRetry = null;
            if (!destroyed && (runtime.getSessionActive() || runtime.hasPendingRestore())) {
                runtime.getAccess().retryRootProbe();
            } else {
                runtime.getAccess().finishRootDiscovery(false);
            }
        };
        worker.postDelayed(pendingRootRetry, delay);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        runtime.setForwardAdmission(null);
        exitEpoch.incrementAndGet();
        runtime.deactivateSession();
        runtime.bumpGeneration();
        if (accessListener != null) runtime.getAccess().removeListener(accessListener);
        if (pendingRootRetry != null) worker.removeCallbacks(pendingRootRetry);
        unregisterReceiver(localDozeReceiver);
        LocalBroadcastManager broadcasts = LocalBroadcastManager.getInstance(this);
        broadcasts.unregisterReceiver(reloadSettingsReceiver);
        broadcasts.unregisterReceiver(reloadNotificationBlocklistReceiver);
        broadcasts.unregisterReceiver(reloadAppsBlocklistReceiver);
        broadcasts.unregisterReceiver(ignoreBatteryResultReceiver);
        // Cancel only service callbacks. Runtime self-tests must still deliver their result.
        cancelEnter();
        if (pendingNotification != null) worker.removeCallbacks(pendingNotification);
        CountDownLatch stopped = new CountDownLatch(1);
        runtime.detachService(() -> {
            long deadline = runtime.getClock().elapsedRealtime() + SessionLifecycle.TEARDOWN_COMMAND_MS;
            AtomicBoolean complete = new AtomicBoolean();
            try {
                runtime.withDeadline(deadline, () -> {
                    ExitResult result = runtime.getController().exit(Build.VERSION.SDK_INT, runtime.grants(),
                            () -> runtime.getClock().elapsedRealtime() < deadline);
                    complete.set(result.getComplete());
                    runtime.recordExit(result);
                    runtime.checkSafety();
                });
            } catch (Exception error) {
                complete.set(false);
                runtime.getJournal().emit(new DozeEvent(EventType.ERROR, EventCodes.TEARDOWN_FAILED));
            } finally {
                try {
                    if (!complete.get()) queueTeardownRestore();
                } finally {
                    runtime.getSession().recordExit(); // A destroyed session cannot suppress a replacement ENTER.
                    releaseWakeLock();
                    runtime.quitIfDetached();
                    stopped.countDown();
                }
            }
        });
        try {
            if (!stopped.await(SessionLifecycle.TEARDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
                    && TeardownTimeout.shouldReport(stopped.getCount() == 0)) {
                runtime.getJournal().emit(new DozeEvent(EventType.RECOVERY_DEBT, EventCodes.TEARDOWN_TIMEOUT));
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

    /** Queued before idle retirement, after withDeadline has cleared the teardown budget. */
    private void queueTeardownRestore() {
        // Separate from tempWakeLock: main-thread onDestroy cleanup must not release this lock.
        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forcedoze:restore");
        wakeLock.acquire(30_000L);
        worker.post(() -> {
            try {
                if (runtime.getAccess().getState().getResolved()) runtime.reconcileAndCheck();
                else runtime.requestSafetyCheck();
            } finally {
                try {
                    releaseWakeLock(wakeLock);
                } catch (RuntimeException ignored) {
                    // API 23-27 timeout release can race isHeld()/release() and under-lock.
                }
                runtime.quitIfDetached();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        boolean reapply = intent != null && ACTION_REAPPLY_DOZE.equals(intent.getAction());
        // A background reapply of an already-foreground service must not re-promote on API 31+.
        if (!reapply || !foreground) {
            try {
                if (showPersistentNotif) showPersistentNotification(); else showSilentNotification();
            } catch (IllegalStateException denied) {
                runtime.getJournal().emit(new DozeEvent(EventType.ERROR, EventCodes.FOREGROUND_START_DENIED));
                return START_NOT_STICKY;
            }
        }
        if (reapply) {
            final long generation = runtime.getController().getCurrentGeneration();
            final long epoch = exitEpoch.get();
            final long deadline = intent.getLongExtra(EXTRA_REAPPLY_DEADLINE, 0);
            postWork(() -> {
                long now = runtime.getClock().elapsedRealtime();
                // Also preserve a due callback already queued on the worker, rather than entering twice.
                if (enterDueElapsed > now || pendingEnter != null) {
                    ExternalControlReceiver.journalReapplySkipped(runtime, ReapplySkip.EXTERNAL_REAPPLY_ENTER_PENDING);
                    return;
                }
                ReapplySkip modeSkip = SessionAccess.reapplySkip(sessionMode());
                if (modeSkip != null) {
                    ExternalControlReceiver.journalReapplySkipped(runtime, modeSkip);
                    return;
                }
                ReapplySkip precheck = runtime.getWatchdog().precheckExternalReapply();
                if (precheck != null) {
                    ExternalControlReceiver.journalReapplySkipped(runtime, precheck);
                    return;
                }
                if (generation != runtime.getController().getCurrentGeneration() || epoch != exitEpoch.get()
                        || now >= deadline || !forceAdmitted() || !getDefaultSharedPreferences(this).getBoolean(
                                Prefs.ALLOW_EXTERNAL_BASIC_CONTROL, Prefs.DEFAULT_ALLOW_EXTERNAL_BASIC_CONTROL)) {
                    ExternalControlReceiver.journalReapplySkipped(runtime, ReapplySkip.EXTERNAL_REAPPLY_NOT_ADMITTED);
                    return;
                }
                DozeStateReading reading = runtime.readState();
                // State reads may block; retain deadline, consent and generation admission afterwards.
                now = runtime.getClock().elapsedRealtime();
                if (generation != runtime.getController().getCurrentGeneration() || epoch != exitEpoch.get()
                        || now >= deadline || !forceAdmitted() || !getDefaultSharedPreferences(this).getBoolean(
                                Prefs.ALLOW_EXTERNAL_BASIC_CONTROL, Prefs.DEFAULT_ALLOW_EXTERNAL_BASIC_CONTROL)) {
                    ExternalControlReceiver.journalReapplySkipped(runtime, ReapplySkip.EXTERNAL_REAPPLY_NOT_ADMITTED);
                    return;
                }
                Decision decision = runtime.getWatchdog().onExternalReapply(reading, maintenance, Build.VERSION.SDK_INT);
                if (decision instanceof Decision.SKIP) {
                    ExternalControlReceiver.journalReapplySkipped(runtime, ((Decision.SKIP) decision).getReason());
                    return;
                }
                // The broadcast has already completed REQUESTED. Its deadline is admission-only.
                reapplyEnter(generation, epoch);
            });
            return START_STICKY;
        }
        postWork(() -> {
            if (!runtime.getSessionActive()) runtime.reconcileAndCheck();
            else runtime.checkSafety();
            addSelfToDozeWhitelist();
            long epoch = exitEpoch.get();
            if (!runtime.getSessionActive() && runtime.getSession().activate(epoch, exitEpoch::get, () -> Utils.isScreenOn(this))) {
                verifiedIdleSeen = false;
                runtime.getWatchdog().resetSession();
                runtime.getJournal().beginSession();
                runtime.getJournal().screen(EventType.SCREEN_OFF, Utils.getBatteryLevel(this), Utils.isConnectedToCharger(this));
                scheduleEnter();
            }
            scheduleRootProbeRetry();
            lastKnownState = deepState();
            Utils.hideDisabledNotification(this);
            Utils.updateTileState(this);
        });
        return START_STICKY;
    }

    private void reapplyEnter(long generation, long epoch) {
        PowerManager.WakeLock wakeLock = acquireEnterWakeLock();
        AtomicBoolean completed = new AtomicBoolean();
        EnterCompletion completion = retryNeeded -> {
            if (!completed.compareAndSet(false, true)) return;
            releaseWakeLock(wakeLock);
            if (retryNeeded && generation == runtime.getController().getCurrentGeneration()
                    && epoch == exitEpoch.get() && forceAdmitted()
                    && !Utils.isScreenOn(this) && getDefaultSharedPreferences(this).getBoolean(
                            Prefs.ALLOW_EXTERNAL_BASIC_CONTROL, Prefs.DEFAULT_ALLOW_EXTERNAL_BASIC_CONTROL)) {
                // The scheduled retry is an ordinary enter, not another reapply/retry loop.
                scheduleEnter();
            }
        };
        try {
            enterDoze(disableMotionSensors, generation, completion);
        } catch (Exception error) {
            runtime.getJournal().emit(new DozeEvent(EventType.ERROR, EventCodes.EXTERNAL_REAPPLY_FAILED));
            completion.complete(true);
        }
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

    public void addSelfToDozeWhitelist() {
        log("Checking self-whitelist capability....");
        log("Nougat: " + Utils.isDeviceRunningOnN());
        log("SU available: " + isSuAvailable);
        String packageName = getPackageName();
        if (AccessManager.getInstance(this).getLevel().isPrivileged()) {
            AccessManager.getInstance(this).grantHelpersAutomatically();
        } else if (!pm.isIgnoringBatteryOptimizations(packageName)) {
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
        } else {
            log("Service already in Doze whitelist for stability");
        }
    }

    private DozeConfig config(boolean sensors, Boolean playingMusic) {
        SharedPreferences prefs = getDefaultSharedPreferences(this);
        Set<String> enabled = new HashSet<>();
        for (String key : Arrays.asList(Prefs.TURN_OFF_WIFI, Prefs.TURN_OFF_DATA, Prefs.TURN_OFF_BLUETOOTH,
                Prefs.TURN_OFF_LOCATION, Prefs.TURN_ON_AIRPLANE, Prefs.TURN_OFF_BIOMETRICS, Prefs.TURN_OFF_ALL_SENSORS)) {
            if (prefs.getBoolean(key, false)) enabled.add(key);
        }
        Set<Feature> features = FeatureSelection.features(enabled, Utils.isHotspotEnabled(this), ignoreIfHotspot,
                !Boolean.FALSE.equals(playingMusic), playingMusic != null && Utils.isWiFiEnabled(this));
        FocusedApps focused = new FocusedApps.Known(java.util.Collections.emptySet());
        if (whitelistCurrentApp) {
            if (runtime.getAccess().getLevel().isPrivileged()) focused = getFocusedApps();
            else {
                String pkg = getNonRootFocusedPackageName();
                focused = pkg != null && com.akylas.enforcedoze.access.PackageNames.isValid(pkg)
                        ? new FocusedApps.Known(java.util.Collections.singleton(pkg))
                        : new FocusedApps.Unknown(com.akylas.enforcedoze.access.Reason.UNVERIFIED);
            }
        }
        PackageSelection packages = FeatureSelection.packages(dozeAppBlocklist, dozeNotificationBlocklist,
                getPackageName(), whitelistCurrentApp, focused, runtime.getJournal());
        return new DozeConfig(Build.VERSION.SDK_INT, runtime.getAccess().getLevel(), runtime.grants(),
                sensors, runtime.getAllowToken(), prefs.getBoolean(Prefs.TURN_ON_BATTERY_SAVER, false),
                features, packages.getAppsToSuspend(), packages.getPackagesToBlockNotifications(),
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
        return admitted(false);
    }

    private boolean admitted(boolean retainingIntent) {
        return !destroyed && runtime.getSessionActive()
                && runtime.accessReadyForEnter()
                && sessionMode() != SessionMode.RESTORE_ONLY
                && !runtime.getStore().getLoadFailed() && runtime.getStore().getCorruptLines().isEmpty()
                && runtime.getClock().elapsedRealtime() >= enterDueElapsed
                && getDefaultSharedPreferences(this).getBoolean(Prefs.SERVICE_ENABLED, false)
                && SessionAccess.screenAdmitted(Utils.isScreenOn(this), waitForUnlock, retainingIntent)
                && Utils.isInsideCustomDozePeriod(this)
                && !(disableWhenCharging && Utils.isConnectedToCharger(this))
                && !Utils.isUserInCommunicationCall(this) && !Utils.isUserInCall(this);
    }

    private boolean forceAdmitted() {
        return sessionMode() == SessionMode.FORCE && admitted();
    }

    private void cancelEnter() {
        if (pendingEnter != null) worker.removeCallbacks(pendingEnter);
        pendingEnter = null;
        cancelFeatureSelection();
        releaseWakeLock();
    }

    private void cancelFeatureSelection() {
        if (selectionTimeout != null) worker.removeCallbacks(selectionTimeout);
        selectionTimeout = null;
        if (featureSelection != null) featureSelection.cancel();
        featureSelection = null;
    }

    private synchronized void releaseWakeLock() {
        releaseWakeLock(tempWakeLock);
    }

    private synchronized void releaseWakeLock(PowerManager.WakeLock wakeLock) {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private synchronized PowerManager.WakeLock acquireEnterWakeLock() {
        releaseWakeLock();
        tempWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forcedoze:tempWakelock");
        tempWakeLock.acquire(10 * 60 * 1000L);
        return tempWakeLock;
    }

    private void scheduleEnter() {
        cancelEnter();
        scheduleRootProbeRetry();
        if (pendingNotification != null) worker.removeCallbacks(pendingNotification);
        int lockTimeout = Settings.Secure.getInt(getContentResolver(), "lock_screen_lock_after_timeout", 5000);
        if (lockTimeout == 0) lockTimeout = 1000;
        long delay = Math.max(0L, (long) dozeEnterDelay * 1000L) + (ignoreLockscreenTimeout ? 0 : lockTimeout);
        enterDueElapsed = runtime.getClock().elapsedRealtime() + delay;
        long generation = runtime.getController().getCurrentGeneration();
        pendingEnter = () -> {
            pendingEnter = null;
            try {
                if (generation == runtime.getController().getCurrentGeneration()) enterDoze(this);
            } finally {
                releaseWakeLock();
            }
        };
        if (delay > 0) acquireEnterWakeLock();
        worker.postDelayed(pendingEnter, delay);
    }

    public void enterDoze(Context context) {
        enterDoze(disableMotionSensors);
    }

    private interface EnterCompletion { void complete(boolean retryNeeded); }

    private static boolean needsEnterRetry(EnterResult result) {
        if (result == null || result.getStatus() == EnterStatus.CANCELLED) return true;
        for (StepResult step : result.getSteps()) {
            if (step.getStatus() == StepStatus.UNVERIFIED || step.getReason() == com.akylas.enforcedoze.access.Reason.UNVERIFIED) return true;
        }
        return false;
    }

    private void enterDoze(boolean sensors) {
        enterDoze(sensors, runtime.getController().getCurrentGeneration(), retryNeeded -> { });
    }

    private void enterDoze(boolean sensors, long generation, EnterCompletion completion) {
        if (!admitted() || generation != runtime.getController().getCurrentGeneration()) {
            runtime.getJournal().emit(new DozeEvent(EventType.SKIPPED, EventCodes.ADMISSION));
            completion.complete(true);
            return;
        }
        cancelFeatureSelection();
        final boolean coreRetryNeeded;
        final SessionMode mode = sessionMode();
        try {
            DozeConfig core = new DozeConfig(Build.VERSION.SDK_INT, runtime.getAccess().getLevel(), runtime.grants(),
                    sensors, runtime.getAllowToken(), getDefaultSharedPreferences(this).getBoolean(Prefs.TURN_ON_BATTERY_SAVER, false),
                    java.util.Collections.emptySet(), java.util.Collections.emptySet(), java.util.Collections.emptySet(),
                    true, null, mode);
            if (mode == SessionMode.FORCE) runtime.getWatchdog().recordEnter();
            EnterResult result = runtime.getController().enterCore(core, generation, () -> admitted() && sessionMode() == mode);
            coreRetryNeeded = needsEnterRetry(result);
            if (result.getStatus() == EnterStatus.CANCELLED || !admitted()
                    || generation != runtime.getController().getCurrentGeneration()) {
                completion.complete(true);
                return;
            }
            if (mode == SessionMode.SENSOR_ONLY) {
                completion.complete(false);
                return;
            }
            recordVerifiedEnter();
        } catch (Exception error) {
            runtime.getJournal().emit(new DozeEvent(EventType.ERROR, EventCodes.ENTER_FAILED));
            completion.complete(true);
            return;
        }
        DeferredFeatureSelection selection = new DeferredFeatureSelection(generation,
                () -> runtime.getController().getCurrentGeneration(), this::admitted,
                playing -> {
                    EnterResult groups = enterConfiguredDoze(playing, generation);
                    completion.complete(coreRetryNeeded || needsEnterRetry(groups));
                });
        featureSelection = selection;
        if (whitelistMusicAppNetwork) {
            selectionTimeout = () -> {
                if (selection.complete(null)) runtime.getJournal().emit(new DozeEvent(EventType.ERROR, EventCodes.MUSIC_SELECTION_TIMEOUT));
                else completion.complete(true);
            };
            worker.postDelayed(selectionTimeout, MUSIC_SELECTION_TIMEOUT_MS);
            try {
                NotificationService listener = NotificationService.Companion.getInstance();
                if (listener != null) {
                    listener.getPlayingPackageName(pkg -> {
                        postWork(() -> {
                            try {
                                if (selection.complete(pkg != null) && selectionTimeout != null) worker.removeCallbacks(selectionTimeout);
                            } catch (Exception error) {
                                runtime.getJournal().emit(new DozeEvent(EventType.ERROR, EventCodes.MUSIC_SELECTION_FAILED));
                            }
                        });
                        return null;
                    }, error -> {
                        postWork(() -> {
                            if (selection.complete(null)) {
                                runtime.getJournal().emit(new DozeEvent(EventType.ERROR, EventCodes.MUSIC_SELECTION_FAILED));
                            }
                        });
                        return null;
                    });
                    return;
                }
                selection.noListener(runtime.getJournal());
                if (selectionTimeout != null) worker.removeCallbacks(selectionTimeout);
            } catch (Exception error) {
                runtime.getJournal().emit(new DozeEvent(EventType.ERROR, EventCodes.MUSIC_SELECTION_FAILED));
                selection.complete(null);
            }
            return;
        }
        selection.complete(false);
    }

    private EnterResult enterConfiguredDoze(Boolean playingMusic, long generation) {
        if (!forceAdmitted() || generation != runtime.getController().getCurrentGeneration()) return null;
        try {
            selectedGroups = config(false, playingMusic);
            return runtime.getController().enterGroupsSafely(selectedGroups, generation, this::forceAdmitted,
                    EventCodes.FEATURE_SELECTION_FAILED);
        } catch (Exception error) {
            runtime.getJournal().emit(new DozeEvent(EventType.ERROR, EventCodes.FEATURE_SELECTION_FAILED));
            return null;
        }
    }

    private void recordVerifiedEnter() {
        if (!forceAdmitted()) return;
        long generation = runtime.getController().getCurrentGeneration();
        lastKnownState = deepState();
        if (!forceAdmitted() || generation != runtime.getController().getCurrentGeneration()) return;
        if (!lastKnownState.equals("IDLE")) return;
        if (verifiedIdleSeen) return;
        verifiedIdleSeen = true;
        timeEnterDoze = System.currentTimeMillis();
        lastDozeEnterBatteryLife = Utils.getBatteryLevel(this);
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
        lastDozeExitBatteryLife = Utils.getBatteryLevel(this);
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

    /** Blocking read on doze-worker; retain exit status and timeout instead of a shell callback code. */
    private FocusedApps getFocusedApps() {
        CommandResult result = runtime.getControl().run("dumpsys window", 8000);
        return FocusedAppParser.parse(result);
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
        dozeUsageData = new LinkedHashSet<>(LegacyDozeStats.newest(dozeUsageData));
        getDefaultSharedPreferences(getApplicationContext()).edit()
                .putStringSet("dozeUsageDataAdvanced", new LinkedHashSet<>(dozeUsageData)).apply();
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
        foreground = true;
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
        foreground = true;
    }

    public void hidePersistentNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            foreground = false;
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
        foreground = true;
    }

    private String deepState() {
        DeepState deep = runtime.readState().getDeep();
        return deep == null ? "UNKNOWN" : deep.name();
    }

    private void idleChanged() {
        if (invalidateForwardAccess(runtime.getAccess().getState())) {
            onAccessChanged(runtime.getAccess().getState());
            return;
        }
        DozeStateReading reading = runtime.readState();
        lastKnownState = reading.getDeep() == null ? "UNKNOWN" : reading.getDeep().name();
        runtime.getJournal().emit(new DozeEvent(EventType.IDLE_CHANGED, EventCodes.IDLE_CHANGED,
                reading.getDeep() == null ? DeepState.UNKNOWN : reading.getDeep(),
                reading.getLight() == null ? LightState.UNKNOWN : reading.getLight()));
        if (!runtime.getSessionActive() || sessionMode() != SessionMode.FORCE) return;
        Boolean maintenanceReading = SessionLifecycle.maintenanceState(reading.getDeep(), reading.getLight());
        if (Boolean.TRUE.equals(maintenanceReading) && !maintenance) {
            runtime.getJournal().emit(new DozeEvent(EventType.MAINT_START, EventCodes.MAINT_START, reading.getDeep(), reading.getLight()));
            if (!disableStats && runtime.getSession().getHasEnter()) {
                dozeUsageData.add(System.currentTimeMillis() + "," + Float.toString((float) Utils.getBatteryLevel(this)) + ",EXIT_MAINTENANCE");
                saveDozeDataStats();
            }
            runtime.getController().maintenance(true, runtime.getController().getCurrentGeneration(), this::forceAdmitted);
            maintenance = true;
        } else if (Boolean.FALSE.equals(maintenanceReading) && maintenance) {
            runtime.getJournal().emit(new DozeEvent(EventType.MAINT_END, EventCodes.MAINT_END, reading.getDeep(), reading.getLight()));
            if (!disableStats && runtime.getSession().getHasEnter()) {
                dozeUsageData.add(System.currentTimeMillis() + "," + Float.toString((float) Utils.getBatteryLevel(this)) + ",ENTER_MAINTENANCE");
                saveDozeDataStats();
            }
            runtime.getController().maintenance(false, runtime.getController().getCurrentGeneration(), this::forceAdmitted);
            maintenance = false;
        }
        if (!maintenance && reading.getDeep() == DeepState.IDLE && !verifiedIdleSeen && admitted()) {
            recordVerifiedEnter();
            if (selectedGroups != null) runtime.getController().enterGroupsSafely(selectedGroups,
                    runtime.getController().getCurrentGeneration(), this::forceAdmitted, EventCodes.FEATURE_SELECTION_FAILED);
        }
        if (maintenance) return;
        if (!getDefaultSharedPreferences(this).getBoolean(Prefs.KEEP_DOZE_ENFORCED, Prefs.DEFAULT_KEEP_DOZE_ENFORCED)) return;
        Decision decision = runtime.getWatchdog().onIdleChanged(reading, Utils.isScreenOn(this),
                Utils.isConnectedToCharger(this), forceAdmitted());
        long generation = runtime.getController().getCurrentGeneration();
        if (decision instanceof Decision.DEFER) {
            runtime.deferWatchdog(() -> {
                if (!destroyed && generation == runtime.getController().getCurrentGeneration()) idleChanged();
            }, ((Decision.DEFER) decision).getUntilElapsed());
        } else if (decision == Decision.REFORCE.INSTANCE) {
            try {
                forceOnly(generation);
            } catch (Exception error) {
                runtime.getJournal().emit(new DozeEvent(EventType.ERROR, EventCodes.REFORCE_FAILED));
            }
        }
    }

    /** Reuse the durable force step without sensors, battery saver or deferred feature selection. */
    private void forceOnly(long generation) {
        if (generation != runtime.getController().getCurrentGeneration() || !forceAdmitted()) return;
        runtime.getJournal().emit(new DozeEvent(EventType.REFORCE, EventCodes.REFORCE));
        DozeConfig force = new DozeConfig(Build.VERSION.SDK_INT, runtime.getAccess().getLevel(), runtime.grants(),
                false, runtime.getAllowToken(), false);
        runtime.getController().enterCore(force, generation, this::forceAdmitted);
        if (generation != runtime.getController().getCurrentGeneration() || !forceAdmitted()) return;
        boolean firstVerified = !verifiedIdleSeen;
        recordVerifiedEnter();
        if (firstVerified && verifiedIdleSeen && selectedGroups != null) {
            runtime.getController().enterGroupsSafely(selectedGroups, generation, this::forceAdmitted, EventCodes.REFORCE_FAILED);
        }
    }

    private void resumeEnforcement() {
        // SafetyNet may have restored sensors while paused. Re-enter core on access return,
        // preserving the first enter's admission deadline instead of restarting its delay.
        long generation = runtime.getController().getCurrentGeneration();
        pendingEnter = () -> {
            pendingEnter = null;
            try {
                if (generation == runtime.getController().getCurrentGeneration() && admitted()) {
                    enterDoze(disableMotionSensors);
                }
            } finally {
                releaseWakeLock();
            }
        };
        worker.postDelayed(pendingEnter, Math.max(0, enterDueElapsed - runtime.getClock().elapsedRealtime()));
    }

    class ReloadSettingsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("User changed a setting, loading new settings into service");
            postWork(ForceDozeService.this::reloadSettings);
            onAccessChanged(runtime.getAccess().getState());
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
        selectedGroups = null;
    }

    class DozeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            long receivedElapsed = runtime.getClock().elapsedRealtime();
            long receivedWall = runtime.getClock().wallTime();
            String action = intent.getAction();
            boolean exitTrigger = Intent.ACTION_USER_PRESENT.equals(action) && waitForUnlock
                    || Intent.ACTION_SCREEN_ON.equals(action) && !waitForUnlock
                    || SessionLifecycle.shouldExit(Intent.ACTION_POWER_CONNECTED.equals(action), Utils.isScreenOn(context),
                        runtime.getSessionActive(), disableWhenCharging);
            // This MUST happen here, not behind an in-flight enter on the worker queue.
            if (exitTrigger) {
                exitEpoch.incrementAndGet();
                runtime.deactivateSession();
                runtime.bumpGeneration();
            }
            // Cancel an in-flight self-test at its next admission boundary, before worker dispatch.
            if (Intent.ACTION_SCREEN_OFF.equals(action)) runtime.screenOffReceived();
            long epoch = exitEpoch.get();
            long generation = runtime.getController().getCurrentGeneration();
            postWork(() -> receiveOnWorker(action, exitTrigger, epoch, generation, receivedElapsed, receivedWall));
        }
    }

    private void receiveOnWorker(String action, boolean exitTrigger, long epoch, long generation,
                                 long receivedElapsed, long receivedWall) {
        if (Intent.ACTION_SCREEN_ON.equals(action)) {
            cancelEnter();
            // Keyguard biometrics must wake before USER_PRESENT; all other intent stays owned.
            if (!exitTrigger) runtime.recordExit(runtime.getController().restoreBiometrics(generation,
                    () -> !destroyed && epoch == exitEpoch.get() && Utils.isScreenOn(this)));
            // Observe after restore at the exit trigger, so the journal sees sensors restored first.
            if (exitTrigger) handleScreenOn(this, 0, 0);
            runtime.getJournal().screen(EventType.SCREEN_ON, Utils.getBatteryLevel(this), Utils.isConnectedToCharger(this));
        } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
            if (exitTrigger) handleScreenOn(this, 0, 0);
        } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            runtime.getWatchdog().resetSession();
            runtime.getJournal().beginSession(receivedWall);
            runtime.getJournal().screen(EventType.SCREEN_OFF, Utils.getBatteryLevel(this), Utils.isConnectedToCharger(this),
                    receivedElapsed, receivedWall);
            if (!runtime.getSession().activate(epoch, exitEpoch::get, () -> Utils.isScreenOn(this))) return;
            runtime.bumpGeneration();
            maintenance = false;
            verifiedIdleSeen = false;
            selectedGroups = null;
            scheduleEnter();
        } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            if (exitTrigger) handleScreenOn(this, 0, 0);
        } else if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action)
                || "android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED".equals(action)) {
            idleChanged();
        }
        if (exitTrigger) runtime.importHistory();
    }

}
