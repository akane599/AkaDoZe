package com.akylas.enforcedoze;

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

    static Object get(Object instance, String name) {
        try { Field field = ForceDozeService.class.getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    static void set(Object instance, String name, Object value) {
        try { Field field = ForceDozeService.class.getDeclaredField(name); field.setAccessible(true); field.set(instance, value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
