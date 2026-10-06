package com.akylas.enforcedoze;

import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.akylas.enforcedoze.doze.DozeEvent;
import com.akylas.enforcedoze.monitor.EventCodes;
import com.akylas.enforcedoze.service.AndroidClock;
import com.akylas.enforcedoze.service.DozeRuntime;
import com.akylas.enforcedoze.service.JournalSink;
import com.akylas.enforcedoze.service.ServiceResetQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowPowerManager.ShadowWakeLock;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.Prefs;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class ForceDozeServiceRobolectricTest {
    // buildService attaches the service but never calls onCreate: no runtime, probes, or FGS.
    public static class RecordingService extends ForceDozeService {
        final List<String> calls = new ArrayList<>();
        AccessLevel level = AccessLevel.APP;
        boolean stopOnToken;
        boolean stopOnGrant;
        boolean failLaunch;
        Intent launched;
        @Override void configureWorkerAllowToken(String token) {
            calls.add("token:" + token);
            if (stopOnToken) set(this, "destroyed", true);
        }
        @Override void loadWorkerSettings(SharedPreferences prefs, SharedPreferences other) {
            calls.add("load");
            super.loadWorkerSettings(prefs, other);
        }
        @Override AccessLevel workerAccessLevel() { calls.add("access"); return level; }
        @Override AccessLevel selfWhitelistAccessLevel() { return level; }
        @Override void grantHelpersAutomatically() {
            // Both flag assignments must precede the helper grant.
            assertEquals(level == AccessLevel.ROOT, get(this, "isSuAvailable"));
            assertEquals(level == AccessLevel.SHELL || level == AccessLevel.ROOT, get(this, "isShizukuAvailable"));
            calls.add("grant");
            if (stopOnGrant) set(this, "destroyed", true);
        }
        @Override public void startActivity(Intent intent) {
            launched = intent;
            if (failLaunch) throw new IllegalStateException("test launch failure");
        }
    }

    @After public void resetState() throws Exception {
        TestAppState.reset();
        PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication()).edit().clear().commit();
    }

    private RecordingService service() {
        try {
            Field context = MyApplication.class.getDeclaredField("context");
            context.setAccessible(true); context.set(null, RuntimeEnvironment.getApplication());
        } catch (Exception error) { throw new AssertionError(error); }
        RecordingService service = Robolectric.buildService(RecordingService.class).get();
        set(service, "pm", service.getSystemService(PowerManager.class));
        assertNull("no runtime construction", get(service, "runtime"));
        return service;
    }

    private void initialize(RecordingService service) throws Exception {
        Method method = ForceDozeService.class.getDeclaredMethod("initializeWorker");
        method.setAccessible(true);
        method.invoke(service);
    }

    @Test public void initializeConfiguresTokenBeforeDestroyedCheckAndNeverLoadsAfterDestroy() throws Exception {
        RecordingService service = service();
        service.stopOnToken = true;
        PreferenceManager.getDefaultSharedPreferences(service).edit().putString("sensorWhitelistPackage", "music.pkg").commit();
        initialize(service);
        assertEquals("token still configured before destroyed guard", Collections.singletonList("token:music.pkg"), service.calls);
        assertEquals("destroyed worker never loads settings", "", get(service, "sensorWhitelistPackage"));
    }

    @Test public void initializePreservesOrderFlagsAndPrivilegedGrant() throws Exception {
        for (AccessLevel level : Arrays.asList(AccessLevel.APP, AccessLevel.ROOT, AccessLevel.SHELL)) {
            RecordingService service = service();
            service.level = level;
            service.stopOnGrant = true;
            PreferenceManager.getDefaultSharedPreferences(service).edit().putString("sensorWhitelistPackage", "chosen.pkg").commit();
            initialize(service);
            List<String> expected = new ArrayList<>(Arrays.asList("token:chosen.pkg", "load", "token:chosen.pkg", "access"));
            if (level.isPrivileged()) expected.add("grant");
            assertEquals("token / guard / settings / token / flags / grant / guard", expected, service.calls);
            assertEquals(level, get(service, "previousAccess"));
            assertEquals(level == AccessLevel.ROOT, get(service, "isSuAvailable"));
            assertEquals(level.isPrivileged(), get(service, "isShizukuAvailable"));
        }
    }

    @Test public void settingsKeepEveryDefaultAndPreferenceSource() {
        RecordingService service = service();
        SharedPreferences normal = service.getSharedPreferences("normal", 0);
        SharedPreferences other = service.getSharedPreferences("other", 0);
        normal.edit().clear().commit(); other.edit().clear().commit();
        service.loadWorkerSettings(normal, other);
        String[] falseFields = {"turnOffDataInDoze", "turnOffWiFiInDoze", "turnOffAllSensorsInDoze", "turnOffBiometricsInDoze", "turnOnBatterySaverInDoze", "turnOnAirplaneInDoze", "turnOffBluetoothInDoze", "turnOffGPSInDoze", "whitelistMusicAppNetwork", "whitelistCurrentApp", "waitForUnlock", "disableStats", "disableLogcat", "isSuAvailable", "showPersistentNotif"};
        String[] trueFields = {"ignoreIfHotspot", "ignoreLockscreenTimeout", "disableMotionSensors", "disableWhenCharging"};
        for (String field : falseFields) assertEquals(field, false, get(service, field));
        for (String field : trueFields) assertEquals(field, true, get(service, field));
        assertEquals(0, get(service, "dozeEnterDelay")); assertEquals("", get(service, "sensorWhitelistPackage"));
        for (String field : Arrays.asList("dozeUsageData", "dozeNotificationBlocklist", "dozeAppBlocklist")) assertEquals(Collections.emptySet(), get(service, field));
        String[] keys = {Prefs.TURN_OFF_DATA, Prefs.TURN_OFF_WIFI, Prefs.TURN_OFF_ALL_SENSORS, Prefs.TURN_OFF_BIOMETRICS, Prefs.TURN_ON_BATTERY_SAVER, Prefs.TURN_ON_AIRPLANE, Prefs.TURN_OFF_BLUETOOTH, Prefs.TURN_OFF_LOCATION, "whitelistMusicAppNetwork", "whitelistCurrentApp", "waitForUnlock", "disableStats", "disableLogcat", "isSuAvailable", "showPersistentNotif"};
        SharedPreferences.Editor edit = normal.edit();
        for (String key : keys) edit.putBoolean(key, true);
        for (String field : trueFields) edit.putBoolean(field, false);
        edit.putInt("dozeEnterDelay", 17).putString("sensorWhitelistPackage", "app.pkg").commit();
        other.edit().putBoolean("showPersistentNotif", true)
                .putStringSet("dozeUsageDataAdvanced", new HashSet<>(Arrays.asList("usage")))
                .putStringSet(Prefs.NOTIFICATION_BLOCKLIST, new HashSet<>(Arrays.asList("notice")))
                .putStringSet(Prefs.APP_BLOCKLIST, new HashSet<>(Arrays.asList("blocked"))).commit();
        service.loadWorkerSettings(normal, other);
        for (String field : falseFields) assertEquals(field, true, get(service, field));
        for (String field : trueFields) assertEquals(field, false, get(service, field));
        assertEquals(17, get(service, "dozeEnterDelay")); assertEquals("app.pkg", get(service, "sensorWhitelistPackage"));
        assertEquals(Collections.singleton("usage"), get(service, "dozeUsageData"));
        assertEquals(Collections.singleton("notice"), get(service, "dozeNotificationBlocklist"));
        assertEquals(Collections.singleton("blocked"), get(service, "dozeAppBlocklist"));
    }

    @Test public void selfWhitelistKeepsAllBranchesAndFallbackNotification() {
        RecordingService service = service();
        PowerManager power = service.getSystemService(PowerManager.class);
        service.level = AccessLevel.ROOT;
        set(service, "isSuAvailable", true); set(service, "isShizukuAvailable", true);
        service.addSelfToDozeWhitelist();
        assertEquals(Collections.singletonList("grant"), service.calls); assertNull(service.launched);
        service.calls.clear(); service.level = AccessLevel.APP;
        shadowOf(power).setIgnoringBatteryOptimizations(service.getPackageName(), true);
        service.addSelfToDozeWhitelist(); assertTrue(service.calls.isEmpty()); assertNull(service.launched);
        shadowOf(power).setIgnoringBatteryOptimizations(service.getPackageName(), false);
        service.addSelfToDozeWhitelist();
        assertEquals(RequestIgnoreBatteryActivity.class.getName(), service.launched.getComponent().getClassName());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, service.launched.getFlags());
        NotificationManager manager = service.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(ForceDozeService.CHANNEL_TIPS, "Tips", NotificationManager.IMPORTANCE_DEFAULT));
        service.failLaunch = true; service.addSelfToDozeWhitelist();
        Notification notice = shadowOf(manager).getNotification(8765);
        assertNotNull(notice); assertEquals(ForceDozeService.CHANNEL_TIPS, notice.getChannelId());
        assertEquals("EnforceDoze", notice.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("EnforceDoze needs to be added to the Doze whitelist in order to work reliably. Please click on this notification to open the battery optimisation view, click on 'EnforceDoze' and select 'Don't' Optimize'", notice.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString());
        assertEquals(1, notice.priority); assertEquals(0, notice.flags & Notification.FLAG_ONGOING_EVENT);
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, shadowOf(notice.contentIntent).getSavedIntent().getAction());
        assertTrue(notice.contentIntent.isImmutable());
    }

    public static class TeardownService extends ForceDozeService {
        // onCreate is deliberately not run, so there is no registered platform receiver.
        @Override public void unregisterReceiver(android.content.BroadcastReceiver receiver) {}
    }

    @Implements(PowerManager.WakeLock.class)
    public static class RacingWakeLock extends ShadowWakeLock {
        boolean failRelease;
        int racedReleases;
        @Override @Implementation protected void release(int flags) {
            if (failRelease) {
                failRelease = false;
                racedReleases++;
                throw new RuntimeException("WakeLock under-locked after timeout");
            }
            super.release(flags);
        }
    }

    private TeardownService teardownService(List<DozeEvent> events,
            Consumer<Runnable> post) {
        try {
            Field context = MyApplication.class.getDeclaredField("context");
            context.setAccessible(true); context.set(null, RuntimeEnvironment.getApplication());
        } catch (Exception error) { throw new AssertionError(error); }
        TeardownService service = Robolectric.buildService(TeardownService.class).get();
        AndroidClock clock = new AndroidClock();
        JournalSink journal = new JournalSink(service, clock);
        journal.addSink(events::add);
        TestAppState.selectNonRootMode(service);
        DozeRuntime runtime = TestAppState.runtimeWithoutRoot(service, clock, journal);
        setField(runtime, "resets", new ServiceResetQueue(runtime, job -> {
            post.accept(job);
            return kotlin.Unit.INSTANCE;
        }));
        // No real HandlerThread or access discovery; the fixture owns all queued jobs.
        setField(runtime, "shutdownQueued", true);
        set(service, "runtime", runtime);
        set(service, "pm", service.getSystemService(PowerManager.class));
        set(service, "worker", new Handler(Looper.getMainLooper()));
        PreferenceManager.getDefaultSharedPreferences(service).edit().putBoolean("serviceEnabled", true).commit();
        return service;
    }

    @Test public void queuedButNotStartedTeardownEmitsTimeoutFromOnDestroy() {
        List<DozeEvent> events = new ArrayList<>();
        List<Runnable> jobs = new ArrayList<>();
        TeardownService service = teardownService(events, jobs::add);
        service.onDestroy();
        assertEquals("teardown queued, never executed", 1, jobs.size());
        assertEquals("actual service emits exactly one timeout for an unfinished wait", 1,
                events.stream().filter(e -> EventCodes.TEARDOWN_TIMEOUT.equals(e.getDetail())).count());
    }

    @Test public void startedButUnfinishedTeardownEmitsTimeoutFromOnDestroy() throws Exception {
        List<DozeEvent> events = new ArrayList<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> task = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        TeardownService service = teardownService(events, job -> task.set(executor.submit(job)));
        DozeRuntime runtime = (DozeRuntime) get(service, "runtime");
        setField(runtime, "app", new ContextWrapper(service.getApplicationContext()) {
            @Override public int checkSelfPermission(String permission) {
                started.countDown();
                try { assertTrue(resume.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                return super.checkSelfPermission(permission);
            }
        });
        try {
            service.onDestroy();
            assertEquals("production teardown entered grants inside withDeadline", 0, started.getCount());
            assertEquals("started but unfinished teardown reports timeout", 1, events.stream().filter(
                    e -> EventCodes.TEARDOWN_TIMEOUT.equals(e.getDetail())).count());
        } finally {
            resume.countDown();
            try { if (task.get() != null) task.get().get(10, TimeUnit.SECONDS); }
            finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test public void waitOutcomeSeamEmitsOnlyForUnfinishedTeardown() {
        List<String> events = new ArrayList<>();
        ForceDozeService.reportTeardownWait(false, () -> events.add("not-started"));
        ForceDozeService.reportTeardownWait(false, () -> events.add("started-unfinished"));
        ForceDozeService.reportTeardownWait(true, () -> events.add("finished"));
        assertEquals(Arrays.asList("not-started", "started-unfinished"), events);
    }

    @Test @Config(shadows = RacingWakeLock.class)
    public void throwingWakeLockReleaseStillCompletesTeardownLatch() throws Exception {
        List<DozeEvent> events = new ArrayList<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> task = new AtomicReference<>();
        AtomicReference<TeardownService> owner = new AtomicReference<>();
        AtomicReference<RacingWakeLock> race = new AtomicReference<>();
        TeardownService service = teardownService(events, job -> {
            PowerManager.WakeLock lock = owner.get().getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:teardown");
            lock.acquire();
            RacingWakeLock shadow = Shadow.extract(lock);
            shadow.failRelease = true;
            race.set(shadow);
            owner.get().tempWakeLock = lock;
            task.set(executor.submit(job));
        });
        owner.set(service);
        try {
            service.onDestroy();
            assertEquals("worker exercised the timeout/release race", 1, race.get().racedReleases);
            assertTrue("release failure must not skip latch countdown or create false timeout",
                    events.stream().noneMatch(e -> EventCodes.TEARDOWN_TIMEOUT.equals(e.getDetail())));
            task.get().get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test public void featureSelectionFailureJournalsAndLogsThrowableAtWarn() throws Exception {
        RecordingService service = service();
        AndroidClock clock = new AndroidClock();
        JournalSink journal = new JournalSink(service, clock);
        List<DozeEvent> events = new ArrayList<>();
        journal.addSink(events::add);
        TestAppState.selectNonRootMode(service);
        DozeRuntime runtime = TestAppState.runtimeWithoutRoot(service, clock, journal);
        set(service, "runtime", runtime);
        com.akylas.enforcedoze.access.AccessState state = new com.akylas.enforcedoze.access.AccessState(
                AccessLevel.SHELL, null, new com.akylas.enforcedoze.access.Grants(true, true), 2000);
        setField(runtime.getAccess(), "state", state);
        Field readinessField = DozeRuntime.class.getDeclaredField("readiness");
        readinessField.setAccessible(true);
        com.akylas.enforcedoze.service.AccessReadiness readiness =
                (com.akylas.enforcedoze.service.AccessReadiness) readinessField.get(runtime);
        assertTrue(readiness.recover(state, () -> state, () -> kotlin.Unit.INSTANCE));
        assertTrue(runtime.getSession().activate(0, () -> 0L, () -> false));
        shadowOf(service.getSystemService(PowerManager.class)).setIsInteractive(false);
        PreferenceManager.getDefaultSharedPreferences(service).edit()
                .putBoolean(Prefs.SERVICE_ENABLED, true).putInt(Prefs.TURN_OFF_WIFI, 1).commit();
        Method method = ForceDozeService.class.getDeclaredMethod("enterConfiguredDoze", Boolean.class, long.class);
        method.setAccessible(true);

        assertNull("selection failure retains null fallback", method.invoke(service, false, 0L));

        assertEquals("actual selection catch journals exactly one failure", 1,
                events.stream().filter(e -> EventCodes.FEATURE_SELECTION_FAILED.equals(e.getDetail())).count());
        assertTrue("actual feature selection catch logs the preference throwable at WARN",
                ShadowLog.getLogsForTag("ForceDozeService").stream().anyMatch(log ->
                        log.type == Log.WARN && log.throwable instanceof ClassCastException));
    }

    @Test public void teardownFailureLogsOriginalThrowable() throws Exception {
        List<DozeEvent> events = new ArrayList<>();
        List<Runnable> jobs = new ArrayList<>();
        TeardownService service = teardownService(events, jobs::add);
        DozeRuntime runtime = (DozeRuntime) get(service, "runtime");
        IllegalStateException error = new IllegalStateException("teardown ledger read failed");
        setField(runtime, "app", new ContextWrapper(service.getApplicationContext()) {
            @Override public int checkSelfPermission(String permission) { throw error; }
        });
        service.onDestroy();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(jobs.get(0)).get(10, TimeUnit.SECONDS);
            assertTrue("actual teardown catch journals failure", events.stream().anyMatch(
                    e -> EventCodes.TEARDOWN_FAILED.equals(e.getDetail())));
            assertTrue("actual teardown catch logs the exact throwable at WARN",
                    ShadowLog.getLogsForTag("ForceDozeService").stream()
                            .anyMatch(log -> log.type == Log.WARN && log.throwable == error));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void setField(Object instance, String name, Object value) {
        try { Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); field.set(instance, value); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    static Object get(Object instance, String name) {
        try { Field field = ForceDozeService.class.getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    static void set(Object instance, String name, Object value) {
        try { Field field = ForceDozeService.class.getDeclaredField(name); field.setAccessible(true); field.set(instance, value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
