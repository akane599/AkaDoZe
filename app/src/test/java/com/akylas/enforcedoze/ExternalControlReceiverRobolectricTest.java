package com.akylas.enforcedoze;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import com.akylas.enforcedoze.access.ExternalControlPolicy;
import com.akylas.enforcedoze.access.ExternalControlPolicy.Action;
import com.akylas.enforcedoze.access.ExternalControlPolicy.DenialReason;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.service.DozeRuntime;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class ExternalControlReceiverRobolectricTest {
    @After public void resetState() throws Exception {
        TestAppState.reset();
    }

    @Test public void throwingPolicyIsNotAnInvalidExtraAndLogsItsThrowable() throws Exception {
        RuntimeException failure = new IllegalStateException("policy bug");
        List<DenialReason> reasons = new ArrayList<>();
        ExternalControlReceiver.Admission.run(new ExternalControlPolicy.Decision(null, null),
                () -> "decoded", input -> { throw failure; }, reasons::add,
                input -> fail("policy failure must not admit work"));
        assertEquals(1, reasons.size());
        assertEquals("internal policy bugs are not malformed extras", DenialReason.INTERNAL_ERROR, reasons.get(0));
        assertTrue("log must retain the original throwable", ShadowLog.getLogs().stream().anyMatch(log -> log.throwable == failure));
        TestAppState.assertNoRuntimeOrDiscovery();
    }

    @Test public void failedEnableCommitStopsTheServiceItJustStarted() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().putBoolean(Prefs.ALLOW_EXTERNAL_BASIC_CONTROL, true)
                .putBoolean(Prefs.SERVICE_ENABLED, false).putBoolean(Prefs.SERVICE_USER_ENABLED, false).commit();
        runCall(Action.ENABLE_SERVICE, failingCommit(prefs));
        Intent started = shadowOf(app).getNextStartedService();
        assertNotNull("explicit ON starts before attempting the intent commit", started);
        Intent stopped = shadowOf(app).getNextStoppedService();
        assertNotNull("failed intent commit must cancel even a pending service start", stopped);
        assertEquals(started.getComponent(), stopped.getComponent());
        assertFalse(prefs.getBoolean(Prefs.SERVICE_ENABLED, true));
        assertFalse(prefs.getBoolean(Prefs.SERVICE_USER_ENABLED, true));
        TestAppState.assertNoRuntimeOrDiscovery();
    }

    @Test public void executionFailureLogsOriginalThrowableAndJournalsExecutionFailed() throws Exception {
        RuntimeException failure = new IllegalStateException("preference read bug");
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> { throw failure; });
        List<com.akylas.enforcedoze.doze.DozeEvent> events = new ArrayList<>();
        MyApplication.getJournal(RuntimeEnvironment.getApplication()).addSink(events::add);
        runCall(Action.ENABLE_SERVICE, prefs);
        assertTrue("execution failure retains its throwable", ShadowLog.getLogs().stream().anyMatch(log -> log.throwable == failure));
        assertTrue("internal execution errors retain FAILED / EXECUTION_FAILED", events.stream().anyMatch(event -> event.getDetail().contains("outcome=FAILED reason=EXECUTION_FAILED")));
        TestAppState.assertNoRuntimeOrDiscovery();
    }

    @Test public void successfulBasicControlsAndSettingRetainTheirOutcomes() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().putBoolean(Prefs.ALLOW_EXTERNAL_BASIC_CONTROL, true)
                .putBoolean(Prefs.ALLOW_EXTERNAL_PRIVILEGED_CONTROL, true).commit();
        List<com.akylas.enforcedoze.doze.DozeEvent> events = new ArrayList<>();
        MyApplication.getJournal(app).addSink(events::add);
        runCall(Action.ENABLE_SERVICE, prefs);
        assertTrue(prefs.getBoolean(Prefs.SERVICE_ENABLED, false));
        assertTrue(prefs.getBoolean(Prefs.SERVICE_USER_ENABLED, false));
        assertNotNull(shadowOf(app).getNextStartedService());
        assertNull("successful enable must not stop", shadowOf(app).getNextStoppedService());
        runCall(Action.DISABLE_SERVICE, prefs);
        assertFalse(prefs.getBoolean(Prefs.SERVICE_ENABLED, true));
        assertFalse(prefs.getBoolean(Prefs.SERVICE_USER_ENABLED, true));
        runCall(Action.REAPPLY_DOZE, prefs);
        runCall(Action.CHANGE_SETTING, prefs);
        assertTrue(prefs.getBoolean("disableWhenCharging", false));
        assertTrue(events.stream().anyMatch(event -> event.getDetail().contains("reason=SERVICE_START_REQUESTED")));
        assertTrue(events.stream().anyMatch(event -> event.getDetail().contains("reason=SERVICE_STOP_REQUESTED")));
        assertTrue(events.stream().anyMatch(event -> event.getDetail().contains("reason=NOT_ADMITTED")));
        assertTrue(events.stream().anyMatch(event -> event.getDetail().contains("outcome=VERIFIED reason=PREFERENCE_WRITTEN")));
        TestAppState.assertNoRuntimeOrDiscovery();
    }

    @Test public void closedBasicGateStillDeniesWithoutStartingService() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().putBoolean(Prefs.ALLOW_EXTERNAL_BASIC_CONTROL, false).commit();
        runCall(Action.ENABLE_SERVICE, prefs);
        assertNull(shadowOf(app).getNextStartedService());
        new ExternalControlReceiver(Action.ENABLE_SERVICE) {}.onReceive(app, new Intent());
        assertNull(shadowOf(app).getNextStartedService());
        TestAppState.assertNoRuntimeOrDiscovery();
    }

    @Test public void admittedReapplyWithScreenOffAndRunningServiceRequestsReapply() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().putBoolean(Prefs.ALLOW_EXTERNAL_BASIC_CONTROL, true).commit();
        shadowOf(app.getSystemService(android.os.PowerManager.class)).setIsInteractive(false);
        android.app.ActivityManager.RunningServiceInfo info = new android.app.ActivityManager.RunningServiceInfo();
        info.service = new android.content.ComponentName(app, ForceDozeService.class);
        shadowOf(app.getSystemService(android.app.ActivityManager.class)).setServices(java.util.Collections.singletonList(info));
        List<com.akylas.enforcedoze.doze.DozeEvent> events = new ArrayList<>();
        MyApplication.getJournal(app).addSink(events::add);
        runCall(Action.REAPPLY_DOZE, prefs);
        Intent reapply = shadowOf(app).getNextStartedService();
        assertNotNull(reapply);
        assertEquals(ForceDozeService.ACTION_REAPPLY_DOZE, reapply.getAction());
        assertTrue(reapply.hasExtra(ForceDozeService.EXTRA_REAPPLY_DEADLINE));
        assertTrue(events.stream().anyMatch(event -> event.getDetail().contains("outcome=REQUESTED reason=REAPPLY_REQUESTED")));
        TestAppState.assertNoRuntimeOrDiscovery();
    }

    private static SharedPreferences failingCommit(SharedPreferences delegate) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(delegate, args);
                    SharedPreferences.Editor editor = delegate.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editProxy, editMethod, editArgs) -> {
                                if (editMethod.getName().equals("commit")) return false;
                                Object result = editMethod.invoke(editor, editArgs);
                                return result == editor ? editProxy : result;
                            });
                });
    }

    // Invoke the real Call synchronously without constructing a runtime, executor, or real service.
    private static void runCall(Action action, SharedPreferences prefs) throws Exception {
        java.lang.reflect.Field context = MyApplication.class.getDeclaredField("context");
        context.setAccessible(true);
        context.set(null, RuntimeEnvironment.getApplication());
        ExternalControlReceiver receiver = new ExternalControlReceiver(action) {};
        Class<?> callType = Class.forName(ExternalControlReceiver.class.getName() + "$Call");
        Constructor<?> constructor = callType.getDeclaredConstructor(ExternalControlReceiver.class,
                Context.class, DozeRuntime.class, SharedPreferences.class, String.class,
                String.class, String.class, String.class, BroadcastReceiver.PendingResult.class);
        constructor.setAccessible(true);
        Constructor<BroadcastReceiver.PendingResult> pendingConstructor = BroadcastReceiver.PendingResult.class
                .getDeclaredConstructor(int.class, String.class, android.os.Bundle.class, int.class,
                        boolean.class, boolean.class, android.os.IBinder.class, int.class, int.class);
        pendingConstructor.setAccessible(true);
        BroadcastReceiver.PendingResult pending = pendingConstructor.newInstance(
                0, null, null, 1, false, false, null, 0, 0);
        Runnable call = (Runnable) constructor.newInstance(receiver, RuntimeEnvironment.getApplication(),
                null, prefs, null, action == Action.CHANGE_SETTING ? "disableWhenCharging" : null,
                action == Action.CHANGE_SETTING ? "true" : null, null, pending);
        call.run();
    }
}
