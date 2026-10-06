package com.akylas.enforcedoze;

import android.app.Application;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import com.akylas.enforcedoze.access.*;
import com.akylas.enforcedoze.doze.*;
import com.akylas.enforcedoze.monitor.EventCodes;
import com.akylas.enforcedoze.service.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Service-boundary goldens. No onCreate, host shell, root probe, or controller replacement. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ForceDozeServiceEnterRobolectricTest {
    public static class RecordingService extends ForceDozeService {
        RuntimeException statsError;
        final ControllerCalls controller = new ControllerCalls();
        @Override EnterResult enterCore(DozeConfig config, long generation, Function0<Boolean> admission) {
            return controller.enterCore(config, generation, admission);
        }
        @Override EnterResult enterGroupsSafely(DozeConfig config, long generation, Function0<Boolean> admission, String code) {
            return controller.enterGroupsSafely(config, generation, admission, code);
        }
        @Override EnterResult maintenance(boolean restore, long generation, Function0<Boolean> admission) {
            return controller.maintenance(restore, generation, admission);
        }
        NotificationService listener;
        final ListenerCallbacks listenerCallbacks = new ListenerCallbacks();
        @Override NotificationService musicListener() { return listener; }
        @Override void requestPlayingPackage(NotificationService listener, Function1<String, Unit> onPackage,
                                             Function1<Exception, Unit> onError) {
            listenerCallbacks.getPlayingPackageName(onPackage, onError);
        }
        @Override public void saveDozeDataStats() {
            if (statsError != null) throw statsError;
            super.saveDozeDataStats();
        }
    }

    // Observe service adapters without transforming controller bytes; generation remains real.
    public static class ControllerCalls {
        final List<String> calls = new ArrayList<>();
        List<String> trace;
        private void record(String call) { calls.add(call); trace.add(call); }
        EnterResult core = completed();
        EnterResult groups = completed();
        Runnable afterCore = () -> { };
        RuntimeException coreError;
        EnterResult enterCore(DozeConfig config, long generation, Function0<Boolean> admission) {
            record("core:" + generation + ":" + config.getMode() + ":" + admission.invoke());
            if (coreError != null) throw coreError;
            afterCore.run();
            return core;
        }
        EnterResult enterGroupsSafely(DozeConfig config, long generation,
                Function0<Boolean> admission, String code) {
            record("groups:" + generation + ":" + admission.invoke() + ":" + code);
            return groups;
        }
        EnterResult maintenance(boolean restore, long generation, Function0<Boolean> admission) {
            record("maintenance:" + restore + ":" + generation + ":" + admission.invoke());
            return completed();
        }
    }

    public static class ListenerCallbacks {
        Function1<String, Unit> selected;
        Function1<Exception, Unit> failed;
        RuntimeException requestError;
        void getPlayingPackageName(Function1<String, Unit> selected, Function1<Exception, Unit> failed) {
            this.selected = selected;
            this.failed = failed;
            if (requestError != null) throw requestError;
        }
    }

    private RecordingService service;
    private DozeRuntime runtime;
    private ControllerCalls controller;
    private SharedPreferences prefs;
    private final List<String> trace = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> reads = new ArrayList<>();
    private final List<Boolean> completions = new ArrayList<>();
    private String deep = "IDLE";
    private String light = "IDLE";
    private RuntimeException completionError;

    @Before public void setUp() throws Exception {
        TestAppState.reset();
        TestAppState.setAppContext(RuntimeEnvironment.getApplication());
        service = Robolectric.buildService(RecordingService.class).get();
        service.pm = service.getSystemService(PowerManager.class);
        shadowOf(service.pm).setIsInteractive(false);
        shadowOf(service.getSystemService(android.os.BatteryManager.class))
                .setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 60);
        prefs = PreferenceManager.getDefaultSharedPreferences(service);
        prefs.edit().clear().putBoolean(Prefs.SERVICE_ENABLED, true)
                .putBoolean(Prefs.ALLOW_EXTERNAL_BASIC_CONTROL, true).putBoolean("disableMotionSensors", false).commit();
        TestAppState.selectNonRootMode(service);
        AndroidClock clock = new AndroidClock();
        JournalSink journal = new JournalSink(service, clock);
        journal.addSink(event -> {
            events.add(event.getType() + ":" + event.getDetail());
            trace.add("event:" + event.getType() + ":" + event.getDetail());
        });
        runtime = TestAppState.runtimeWithoutRoot(service, clock, journal);
        put(service, "runtime", runtime);
        Handler worker = new Handler(Looper.getMainLooper());
        put(service, "worker", worker);
        put(runtime, "handler", worker);
        AccessState state = new AccessState(AccessLevel.SHELL, null, new Grants(true, true), 2000);
        put(runtime.getAccess(), "state", state);
        AccessReadiness readiness = (AccessReadiness) field(runtime, "readiness");
        assertTrue(readiness.recover(state, () -> state, () -> Unit.INSTANCE));
        assertTrue(runtime.getSession().activate(0, () -> 0L, () -> false));
        put(service, "forwardAccess", state);
        put(service, "forwardMode", SessionMode.FORCE);
        put(service, "forwardSensors", false);
        put(runtime, "control", new CommandRunner() {
            @Override public AccessLevel getLevel() { return AccessLevel.SHELL; }
            @Override public CommandResult run(String command) { return run(command, 8000); }
            @Override public CommandResult run(String command, long timeoutMs) {
                reads.add(command);
                return new CommandResult(0, Collections.singletonList(command.endsWith("light") ? light : deep),
                        Collections.emptyList(), 0, false);
            }
        });
        controller = service.controller;
        controller.trace = trace;
        events.clear();
        trace.clear();
    }

    @After public void tearDown() throws Exception {
        ((Handler) field(service, "worker")).removeCallbacksAndMessages(null);
        TestAppState.reset();
        prefs.edit().clear().commit();
    }

    private static EnterResult completed() { return new EnterResult(EnterStatus.COMPLETED, Collections.emptyList()); }
    private static EnterResult cancelled() { return new EnterResult(EnterStatus.CANCELLED, Collections.emptyList()); }

    private void enter(long generation) throws Exception {
        Class<?> completionType = Class.forName(ForceDozeService.class.getName() + "$EnterCompletion");
        Object completion = Proxy.newProxyInstance(completionType.getClassLoader(), new Class<?>[]{completionType}, (proxy, method, args) -> {
            completions.add((Boolean) args[0]);
            trace.add("complete:" + args[0]);
            if (completionError != null) throw completionError;
            return null;
        });
        invoke("enterDoze", new Class<?>[]{boolean.class, long.class, completionType}, false, generation, completion);
    }

    private void invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = ForceDozeService.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { method.invoke(service, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }
    private void idle() throws Exception { invoke("idleChanged", new Class<?>[0]); }
    private void reapply(long generation, long epoch) throws Exception {
        invoke("reapplyEnter", new Class<?>[]{long.class, long.class}, generation, epoch);
    }
    private ListenerCallbacks listener() throws Exception {
        service.whitelistMusicAppNetwork = true;
        service.listener = new NotificationService();
        return service.listenerCallbacks;
    }
    private void callbacks() { shadowOf(Looper.getMainLooper()).idle(); }
    private Runnable timeout() { return (Runnable) field(service, "selectionTimeout"); }
    private void assertEvents(String... expected) { assertEquals(Arrays.asList(expected), events); }
    private void assertCalls(String... expected) { assertEquals(Arrays.asList(expected), controller.calls); }
    private void assertCompleted(boolean retry) { assertEquals(Collections.singletonList(retry), completions); }
    private void assertSelection(boolean exists) {
        assertEquals(exists, field(service, "featureSelection") != null);
        assertEquals(exists, field(service, "selectedGroups") != null);
    }
    private List<String> statKinds() {
        List<String> kinds = new ArrayList<>();
        for (String row : service.dozeUsageData) kinds.add(row.substring(row.lastIndexOf(',') + 1));
        return kinds;
    }
    private RuntimeException failGrants() {
        RuntimeException error = new IllegalStateException("permission read failed");
        put(runtime, "app", new ContextWrapper(service.getApplicationContext()) {
            @Override public int checkSelfPermission(String permission) { throw error; }
        });
        return error;
    }
    private static Object field(Object target, String name) {
        try { return reflectedField(target, name).get(target instanceof Class ? null : target); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void put(Object target, String name, Object value) {
        try { reflectedField(target, name).set(target instanceof Class ? null : target, value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static Field reflectedField(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        if (target instanceof ForceDozeService) type = ForceDozeService.class;
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test public void deniedEnterJournalsAndCompletesWithoutSelectionOrCore() throws Exception {
        prefs.edit().putBoolean(Prefs.SERVICE_ENABLED, false).commit();
        enter(0);
        assertEvents("SKIPPED:" + EventCodes.ADMISSION);
        assertCalls();
        assertCompleted(true);
        assertSelection(false);
        assertNull(timeout());
    }
    @Test public void staleEnterJournalsAndCompletesWithoutCore() throws Exception {
        enter(1);
        assertEvents("SKIPPED:" + EventCodes.ADMISSION);
        assertCalls();
        assertCompleted(true);
    }
    @Test public void forceEnterRecordsStatsBeforeGroupsAndCompletesOnce() throws Exception {
        runtime.getController().bumpGeneration();
        enter(1);
        assertCalls("core:1:FORCE:true", "groups:1:true:" + EventCodes.FEATURE_SELECTION_FAILED);
        assertEvents();
        assertCompleted(false);
        assertEquals(Arrays.asList("cmd deviceidle get deep", "cmd deviceidle get light"), reads);
        assertEquals(Collections.singletonList("ENTER"), statKinds());
        assertEquals(true, field(service, "verifiedIdleSeen"));
        assertEquals("IDLE", service.lastKnownState);
        assertFalse(service.maintenance);
        assertSelection(true);
        assertNull(timeout());
    }
    @Test public void cancelledCoreCompletesRetryWithoutStatsOrGroups() throws Exception {
        controller.core = cancelled();
        enter(0);
        assertCalls("core:0:FORCE:true");
        assertCompleted(true);
        assertEquals(Collections.emptyList(), statKinds());
        assertSelection(false);
    }
    @Test public void generationChangeAfterCoreDoesNotSelectGroups() throws Exception {
        controller.afterCore = () -> runtime.getController().bumpGeneration();
        enter(0);
        assertCalls("core:0:FORCE:true");
        assertCompleted(true);
        assertSelection(false);
    }
    @Test public void admissionLossAfterCoreDoesNotSelectGroups() throws Exception {
        controller.afterCore = () -> prefs.edit().putBoolean(Prefs.SERVICE_ENABLED, false).commit();
        enter(0);
        assertCalls("core:0:FORCE:true");
        assertCompleted(true);
        assertSelection(false);
    }
    @Test public void coreExceptionJournalsAndCompletesRetry() throws Exception {
        controller.coreError = new IllegalStateException("core failed");
        enter(0);
        assertEvents("ERROR:" + EventCodes.ENTER_FAILED);
        assertCompleted(true);
        assertSelection(false);
    }
    @Test public void verifiedStatExceptionRemainsInsideEnterCatch() throws Exception {
        service.statsError = new IllegalStateException("stats failed");
        enter(0);
        assertEvents("ERROR:" + EventCodes.ENTER_FAILED);
        assertCalls("core:0:FORCE:true");
        assertCompleted(true);
        assertEquals(true, field(service, "verifiedIdleSeen"));
        assertEquals(Collections.singletonList("ENTER"), statKinds());
        assertSelection(false);
    }
    @Test public void initialAdmissionExceptionRemainsOutsideEnterCatch() throws Exception {
        RuntimeException error = failGrants();
        assertSame(error, assertThrows(RuntimeException.class, () -> enter(0)));
        assertEvents();
        assertCalls();
        assertTrue(completions.isEmpty());
    }
    @Test public void missingMusicListenerCompletesGroupsAndRetainsCancelledTimeoutField() throws Exception {
        service.whitelistMusicAppNetwork = true;
        enter(0);
        assertEvents("ERROR:" + EventCodes.MUSIC_SELECTION_UNAVAILABLE);
        assertCompleted(false);
        assertNotNull(timeout());
        assertSelection(true);
    }
    @Test public void musicPackageCallbackCompletesOnceAndCancelsTimeout() throws Exception {
        ListenerCallbacks listener = listener();
        enter(0);
        assertTrue(completions.isEmpty());
        listener.selected.invoke("music.pkg");
        callbacks();
        assertCompleted(false);
        assertCalls("core:0:FORCE:true", "groups:0:true:" + EventCodes.FEATURE_SELECTION_FAILED);
        assertEvents();
        assertNotNull(timeout());
        listener.selected.invoke(null);
        callbacks();
        assertCompleted(false);
    }
    @Test public void musicTimeoutCompletesGroupsThenJournalsTimeout() throws Exception {
        listener();
        enter(0);
        timeout().run();
        assertCompleted(false);
        assertEvents("ERROR:" + EventCodes.MUSIC_SELECTION_TIMEOUT);
        assertEquals(Arrays.asList("core:0:FORCE:true", "groups:0:true:" + EventCodes.FEATURE_SELECTION_FAILED,
                "complete:false", "event:ERROR:" + EventCodes.MUSIC_SELECTION_TIMEOUT), trace);
        assertSelection(true);
    }
    @Test public void staleMusicTimeoutCompletesRetryWithoutGroupsOrTimeoutJournal() throws Exception {
        listener();
        enter(0);
        runtime.getController().bumpGeneration();
        timeout().run();
        assertCompleted(true);
        assertCalls("core:0:FORCE:true");
        assertEvents();
        assertNull(field(service, "selectedGroups"));
    }
    @Test public void listenerErrorCompletesThenJournalsOnlyOnce() throws Exception {
        ListenerCallbacks listener = listener();
        enter(0);
        listener.failed.invoke(new IllegalStateException("listener failed"));
        callbacks();
        listener.failed.invoke(new IllegalStateException("late failure"));
        callbacks();
        assertCompleted(false);
        assertEvents("ERROR:" + EventCodes.MUSIC_SELECTION_FAILED);
    }
    @Test public void packageCompletionExceptionJournalsWithoutRepeatingCompletion() throws Exception {
        ListenerCallbacks listener = listener();
        completionError = new IllegalStateException("completion failed");
        enter(0);
        listener.selected.invoke(null);
        callbacks();
        assertCompleted(false);
        assertEvents("ERROR:" + EventCodes.MUSIC_SELECTION_FAILED);
    }
    @Test public void listenerRequestExceptionJournalsBeforeFallbackCompletion() throws Exception {
        ListenerCallbacks listener = listener();
        listener.requestError = new IllegalStateException("request failed");
        enter(0);
        assertEquals(Arrays.asList("core:0:FORCE:true", "event:ERROR:" + EventCodes.MUSIC_SELECTION_FAILED,
                "groups:0:true:" + EventCodes.FEATURE_SELECTION_FAILED, "complete:false"), trace);
        assertCompleted(false);
        assertEvents("ERROR:" + EventCodes.MUSIC_SELECTION_FAILED);
        assertSelection(true);
    }
    @Test public void unguardedSelectionCompletionExceptionStillEscapesEnter() throws Exception {
        completionError = new IllegalStateException("completion failed");
        assertSame(completionError, assertThrows(RuntimeException.class, () -> enter(0)));
        assertCompleted(false);
        assertEvents();
    }
    @Test public void reapplySuccessReleasesWakeLockWithoutSchedulingRetry() throws Exception {
        reapply(0, 0);
        assertFalse(service.tempWakeLock.isHeld());
        assertNull(field(service, "pendingEnter"));
        assertCalls("core:0:FORCE:true", "groups:0:true:" + EventCodes.FEATURE_SELECTION_FAILED);
        assertEvents();
    }
    @Test public void reapplyRetrySchedulesOrdinaryEnterAndNewWakeLock() throws Exception {
        controller.core = cancelled();
        reapply(0, 0);
        assertNotNull(field(service, "pendingEnter"));
        assertTrue("new delayed enter owns a lock", service.tempWakeLock.isHeld());
        controller.core = completed();
        ((Runnable) field(service, "pendingEnter")).run();
        assertFalse(service.tempWakeLock.isHeld());
        assertNull(field(service, "pendingEnter"));
    }
    @Test public void staleReapplyReleasesLockWithoutRetry() throws Exception {
        reapply(1, 0);
        assertFalse(service.tempWakeLock.isHeld());
        assertNull(field(service, "pendingEnter"));
        assertEvents("SKIPPED:" + EventCodes.ADMISSION);
    }
    @Test public void epochChangeVetoesReapplyRetry() throws Exception {
        controller.core = cancelled();
        ((AtomicLong) field(service, "exitEpoch")).incrementAndGet();
        reapply(0, 0);
        assertFalse(service.tempWakeLock.isHeld());
        assertNull(field(service, "pendingEnter"));
    }
    @Test public void consentChangeVetoesReapplyRetry() throws Exception {
        controller.core = cancelled();
        prefs.edit().putBoolean(Prefs.ALLOW_EXTERNAL_BASIC_CONTROL, false).commit();
        reapply(0, 0);
        assertFalse(service.tempWakeLock.isHeld());
        assertNull(field(service, "pendingEnter"));
    }
    @Test public void idleWithoutActiveSessionOnlyUpdatesStateAndJournal() throws Exception {
        runtime.deactivateSession();
        idle();
        assertEvents("IDLE_CHANGED:" + EventCodes.IDLE_CHANGED);
        assertCalls();
        assertEquals("IDLE", service.lastKnownState);
    }
    @Test public void maintenanceTransitionsPinJournalControllerAndStatOrder() throws Exception {
        runtime.getSession().recordEnter(true);
        runtime.getController().bumpGeneration();
        deep = "IDLE_MAINTENANCE";
        idle();
        assertTrue(service.maintenance);
        deep = "IDLE";
        put(service, "verifiedIdleSeen", true);
        idle();
        assertFalse(service.maintenance);
        assertCalls("maintenance:true:1:true", "maintenance:false:1:true");
        assertEvents("IDLE_CHANGED:" + EventCodes.IDLE_CHANGED, "MAINT_START:" + EventCodes.MAINT_START,
                "IDLE_CHANGED:" + EventCodes.IDLE_CHANGED, "MAINT_END:" + EventCodes.MAINT_END);
        assertEquals(Arrays.asList("ENTER_MAINTENANCE", "EXIT_MAINTENANCE"), statKinds());
        assertEquals("IDLE", service.lastKnownState);
    }
    @Test public void maintenanceWithoutPriorEnterDoesNotCreateStats() throws Exception {
        deep = "IDLE_MAINTENANCE";
        idle();
        idle();
        assertCalls("maintenance:true:0:true");
        assertTrue(service.dozeUsageData.isEmpty());
    }
    @Test public void newlyVerifiedIdleReentersPreviouslySelectedGroups() throws Exception {
        enter(0);
        controller.calls.clear();
        reads.clear();
        put(service, "verifiedIdleSeen", false);
        idle();
        assertCalls("groups:0:true:" + EventCodes.FEATURE_SELECTION_FAILED);
        assertEvents("IDLE_CHANGED:" + EventCodes.IDLE_CHANGED);
        assertEquals(Collections.singletonList("ENTER"), statKinds());
        assertEquals(true, field(service, "verifiedIdleSeen"));
    }
    @Test public void disabledWatchdogStillRecordsIdleChangedWithoutReforce() throws Exception {
        prefs.edit().putBoolean(Prefs.KEEP_DOZE_ENFORCED, false).commit();
        deep = "ACTIVE";
        idle();
        assertCalls();
        assertEvents("IDLE_CHANGED:" + EventCodes.IDLE_CHANGED);
    }
    @Test public void reforceExceptionIsCaughtAfterReforceJournal() throws Exception {
        deep = "ACTIVE";
        prefs.edit().putBoolean(Prefs.KEEP_DOZE_ENFORCED, true).commit();
        controller.coreError = new IllegalStateException("reforce failed");
        idle();
        assertCalls("core:0:FORCE:true");
        assertEvents("IDLE_CHANGED:" + EventCodes.IDLE_CHANGED, "REFORCE:" + EventCodes.REFORCE,
                "ERROR:" + EventCodes.REFORCE_FAILED);
    }
    @Test public void sensorOnlyEnterCompletesWithoutStatsGroupsOrForceWatchdog() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.DUMP);
        prefs.edit().putBoolean("disableMotionSensors", true).commit();
        AccessState state = new AccessState(AccessLevel.APP, null, new Grants(true, false), null);
        put(runtime.getAccess(), "state", state);
        AccessReadiness readiness = (AccessReadiness) field(runtime, "readiness");
        assertTrue(readiness.recover(state, () -> state, () -> Unit.INSTANCE));
        enter(0);
        assertCalls("core:0:SENSOR_ONLY:true");
        assertCompleted(false);
        assertEvents();
        assertSelection(false);
        assertTrue(reads.isEmpty());
        assertTrue(service.dozeUsageData.isEmpty());
        assertNull(field(runtime.getWatchdog(), "lastEnter"));
    }
    @Test public void unverifiedCoreCarriesRetryThroughSuccessfulGroups() throws Exception {
        controller.core = new EnterResult(EnterStatus.COMPLETED, Collections.singletonList(
                new StepResult(Feature.FORCE_DOZE, null, StepStatus.UNVERIFIED, Reason.UNVERIFIED, false)));
        enter(0);
        assertCalls("core:0:FORCE:true", "groups:0:true:" + EventCodes.FEATURE_SELECTION_FAILED);
        assertCompleted(true);
        assertEvents();
    }
    @Test public void cancelledGroupsRequestRetryWithoutExtraJournal() throws Exception {
        controller.groups = cancelled();
        enter(0);
        assertCompleted(true);
        assertEvents();
    }
    @Test public void disabledStatsStillMarksVerifiedIdleWithoutRows() throws Exception {
        service.disableStats = true;
        enter(0);
        assertCompleted(false);
        assertEquals(true, field(service, "verifiedIdleSeen"));
        assertFalse(runtime.getSession().getHasEnter());
        assertTrue(service.dozeUsageData.isEmpty());
    }
    @Test public void replacementEnterCancelsPreviousMusicSelection() throws Exception {
        ListenerCallbacks listener = listener();
        enter(0);
        Function1<String, Unit> oldCallback = listener.selected;
        Runnable oldTimeout = timeout();
        Object oldSelection = field(service, "featureSelection");
        enter(0);
        assertNotSame(oldSelection, field(service, "featureSelection"));
        assertNotSame(oldTimeout, timeout());
        oldCallback.invoke("old.pkg");
        callbacks();
        assertTrue(completions.isEmpty());
        listener.selected.invoke(null);
        callbacks();
        assertCompleted(false);
        assertCalls("core:0:FORCE:true", "core:0:FORCE:true", "groups:0:true:" + EventCodes.FEATURE_SELECTION_FAILED);
    }
    @Test public void outerMusicFallbackCompletionExceptionStillEscapes() throws Exception {
        ListenerCallbacks listener = listener();
        listener.requestError = new IllegalStateException("request failed");
        completionError = new IllegalStateException("fallback failed");
        assertSame(completionError, assertThrows(RuntimeException.class, () -> enter(0)));
        assertCompleted(false);
        assertEvents("ERROR:" + EventCodes.MUSIC_SELECTION_FAILED);
    }
    @Test public void listenerErrorCompletionExceptionStillEscapesWorkerCallback() throws Exception {
        ListenerCallbacks listener = listener();
        completionError = new IllegalStateException("error callback completion failed");
        enter(0);
        listener.failed.invoke(new IllegalStateException("listener failed"));
        assertSame(completionError, assertThrows(RuntimeException.class, this::callbacks));
        assertCompleted(false);
        assertEvents();
    }
    @Test public void reapplyCatchesInitialAdmissionErrorAndReleasesBeforeRetryCheck() throws Exception {
        RuntimeException error = failGrants();
        // The first failure is caught; the same failing admission in the retry completion escapes.
        assertSame(error, assertThrows(RuntimeException.class, () -> reapply(0, 0)));
        assertEvents("ERROR:" + EventCodes.EXTERNAL_REAPPLY_FAILED);
        assertCalls();
        assertFalse(service.tempWakeLock.isHeld());
        assertNull(field(service, "pendingEnter"));
    }
    @Test public void reapplyCompletionIsOneShotEvenWhenTimeoutFiresAfterSuccess() throws Exception {
        ListenerCallbacks listener = listener();
        reapply(0, 0);
        Runnable oldTimeout = timeout();
        PowerManager.WakeLock lock = service.tempWakeLock;
        assertTrue(lock.isHeld());
        listener.selected.invoke(null);
        callbacks();
        assertFalse(lock.isHeld());
        oldTimeout.run();
        assertSame(lock, service.tempWakeLock);
        assertNull(field(service, "pendingEnter"));
        assertEvents();
    }
    @Test public void screenOnVetoesReapplyRetry() throws Exception {
        controller.core = cancelled();
        controller.afterCore = () -> shadowOf(service.pm).setIsInteractive(true);
        reapply(0, 0);
        assertFalse(service.tempWakeLock.isHeld());
        assertNull(field(service, "pendingEnter"));
    }
    @Test public void maintenanceWithDisabledStatsKeepsControllerAndJournalButNoRows() throws Exception {
        runtime.getSession().recordEnter(true);
        service.disableStats = true;
        deep = "IDLE_MAINTENANCE";
        idle();
        deep = "IDLE";
        put(service, "verifiedIdleSeen", true);
        idle();
        assertCalls("maintenance:true:0:true", "maintenance:false:0:true");
        assertEvents("IDLE_CHANGED:" + EventCodes.IDLE_CHANGED, "MAINT_START:" + EventCodes.MAINT_START,
                "IDLE_CHANGED:" + EventCodes.IDLE_CHANGED, "MAINT_END:" + EventCodes.MAINT_END);
        assertTrue(service.dozeUsageData.isEmpty());
    }
    @Test public void unknownIdleStateNeverEndsMaintenanceOrReforces() throws Exception {
        service.maintenance = true;
        deep = "UNKNOWN";
        light = "UNKNOWN";
        idle();
        assertTrue(service.maintenance);
        assertEquals("UNKNOWN", service.lastKnownState);
        assertCalls();
        assertEvents("IDLE_CHANGED:" + EventCodes.IDLE_CHANGED);
    }
    @Test public void maintenanceStatFailureRemainsOutsideReforceCatch() throws Exception {
        runtime.getSession().recordEnter(true);
        service.statsError = new IllegalStateException("maintenance stats failed");
        deep = "IDLE_MAINTENANCE";
        assertSame(service.statsError, assertThrows(RuntimeException.class, this::idle));
        assertFalse(service.maintenance);
        assertCalls();
        assertEvents("IDLE_CHANGED:" + EventCodes.IDLE_CHANGED, "MAINT_START:" + EventCodes.MAINT_START);
    }
    @Test public void deferredWatchdogRunsAgainOnlyWhenGenerationAndLifetimeStillMatch() throws Exception {
        deep = "ACTIVE";
        prefs.edit().putBoolean(Prefs.KEEP_DOZE_ENFORCED, true).commit();
        idle();
        idle();
        Runnable deferred = (Runnable) field(runtime, "deferred");
        assertNotNull(deferred);
        // Run at the actual reserved deadline; the second reforce receives the same generation.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(60_000));
        deferred.run();
        assertCalls("core:0:FORCE:true", "core:0:FORCE:true");
        int eventCount = events.size();
        put(service, "destroyed", true);
        deferred.run();
        assertEquals(eventCount, events.size());
    }

    @Test public void watchdogDefersThenGenerationGuardRejectsStaleReentry() throws Exception {
        deep = "ACTIVE";
        prefs.edit().putBoolean(Prefs.KEEP_DOZE_ENFORCED, true).commit();
        idle();
        idle();
        Runnable deferred = (Runnable) field(runtime, "deferred");
        assertNotNull(deferred);
        assertCalls("core:0:FORCE:true");
        runtime.getController().bumpGeneration();
        deferred.run();
        assertEvents("IDLE_CHANGED:" + EventCodes.IDLE_CHANGED, "REFORCE:" + EventCodes.REFORCE,
                "IDLE_CHANGED:" + EventCodes.IDLE_CHANGED);
    }

    private void assertWarnOriginal(Throwable error) {
        assertTrue("WARN retains the exact throwable", org.robolectric.shadows.ShadowLog.getLogsForTag("ForceDozeService")
                .stream().anyMatch(log -> log.type == android.util.Log.WARN && log.throwable == error));
    }
    @Test public void logsCoreFailureOriginalThrowableAtWarn() throws Exception {
        coreExceptionJournalsAndCompletesRetry();
        assertWarnOriginal(controller.coreError);
    }
    @Test public void logsReforceFailureOriginalThrowableAtWarn() throws Exception {
        reforceExceptionIsCaughtAfterReforceJournal();
        assertWarnOriginal(controller.coreError);
    }
    @Test public void logsPackageCallbackFailureOriginalThrowableAtWarn() throws Exception {
        packageCompletionExceptionJournalsWithoutRepeatingCompletion();
        assertWarnOriginal(completionError);
    }
    @Test public void logsMusicRequestFailureOriginalThrowableAtWarn() throws Exception {
        ListenerCallbacks listener = listener();
        listener.requestError = new IllegalStateException("request failed");
        enter(0);
        assertWarnOriginal(listener.requestError);
    }
    @Test public void logsListenerErrorOriginalThrowableAtWarn() throws Exception {
        ListenerCallbacks listener = listener();
        Exception error = new IllegalStateException("listener failed");
        enter(0);
        listener.failed.invoke(error);
        callbacks();
        assertWarnOriginal(error);
    }
    @Test public void logsExternalReapplyFailureOriginalThrowableAtWarn() throws Exception {
        RuntimeException error = failGrants();
        assertSame(error, assertThrows(RuntimeException.class, () -> reapply(0, 0)));
        assertWarnOriginal(error);
    }
}
