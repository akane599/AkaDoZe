package com.akylas.enforcedoze.ui;

import android.Manifest;
import android.app.Application;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;

import com.afollestad.materialdialogs.MaterialDialog;
import com.akylas.enforcedoze.TestAppState;
import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.SettingsActivity;
import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.Grants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * Hosts the real Settings screen in Shizuku mode with no Shizuku binder, so AccessManager never probes
 * su and no command runs. Preference listeners are driven through the preferences themselves.
 */
@RunWith(RobolectricTestRunner.class)
// MyApplication would build the doze runtime and journal; the screen itself doesn't need them.
@Config(application = Application.class)
public class SettingsFragmentRobolectricTest {
    private Application app;
    private AccessManager access;
    private SharedPreferences prefs;
    private ActivityController<SettingsActivity> controller;
    private SettingsActivity.SettingsFragment fragment;

    @Before
    public void hostSettings() throws Exception {
        app = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
        TestAppState.reset();
        prefs.edit().clear().commit();
        TestAppState.selectNonRootMode(app);
        TestAppState.setAppContext(app);
        host();
    }

    private void host() {
        controller = Robolectric.buildActivity(SettingsActivity.class).setup();
        idle();
        fragment = (SettingsActivity.SettingsFragment) controller.get().getSupportFragmentManager()
                .findFragmentById(R.id.settings);
        assertNotNull("Settings fragment is hosted", fragment);
        access = TestAppState.accessWithoutRoot(app);
    }

    @After
    public void tearDown() throws Exception {
        assertNull("No service is started by the Settings screen", shadowOf(app).getNextStartedService());
        controller.pause().stop().destroy();
        idle();
        app = null;
        TestAppState.reset();
        prefs.edit().clear().commit();
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Publishes a state the way AccessManager does: to every registered access listener. */
    @SuppressWarnings("unchecked")
    private void publish(AccessState state) throws Exception {
        AccessManager manager = access;
        for (AccessManager.Listener listener : (Collection<AccessManager.Listener>) get(manager, "listeners")) {
            listener.onAccessChanged(state);
        }
        idle();
    }

    private <T extends Preference> T pref(String key) {
        T pref = fragment.findPreference(key);
        assertNotNull(key, pref);
        return pref;
    }

    private boolean change(String key, Object value) {
        boolean accepted = pref(key).callChangeListener(value);
        idle();
        return accepted;
    }

    private void click(String key) {
        Preference pref = pref(key);
        assertNotNull(key + " has a click listener", pref.getOnPreferenceClickListener());
        pref.getOnPreferenceClickListener().onPreferenceClick(pref);
        idle();
    }

    private static CharSequence message(Dialog dialog) {
        if (dialog instanceof MaterialDialog) return ((MaterialDialog) dialog).getContentView().getText();
        TextView message = dialog.findViewById(android.R.id.message);
        return message == null ? null : message.getText();
    }

    private String latestMessage() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull("A dialog is shown", dialog);
        assertTrue("The latest dialog is showing", dialog.isShowing());
        return String.valueOf(message(dialog));
    }

    private void clickPositive() {
        ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
    }

    // ---- Behaviour pin: every preference's key -> enabled, visible, summary and wiring ----

    private String snapshot() {
        StringBuilder out = new StringBuilder();
        snapshot(fragment.getPreferenceScreen(), out);
        return out.toString();
    }

    private static void snapshot(PreferenceGroup group, StringBuilder out) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            out.append(pref.getKey())
                    .append(" enabled=").append(pref.isEnabled())
                    .append(" visible=").append(pref.isVisible())
                    .append(" change=").append(pref.getOnPreferenceChangeListener() != null)
                    .append(" click=").append(pref.getOnPreferenceClickListener() != null)
                    .append(" summary=").append(String.valueOf(pref.getSummary()).replace("\n", "\\n"))
                    .append('\n');
            if (pref instanceof PreferenceGroup) snapshot((PreferenceGroup) pref, out);
        }
    }

    // Captured from the screen before the onCreatePreferences split (Shizuku mode, no binder).
    private static final String INITIAL = """
            null enabled=false visible=true change=false click=false summary=null
            sponsorProject enabled=true visible=true change=false click=true summary=EnforceDoze is free & open-source — consider sponsoring
            mainSettings enabled=false visible=true change=false click=false summary=null
            accessStatus enabled=true visible=true change=false click=true summary=No privileged access · Checking access…
            executionMode enabled=true visible=true change=true click=false summary=Choose between Root or Shizuku for executing commands
            keepDozeEnforced enabled=false visible=true change=false click=false summary=Checking access…
            disableWhenCharging enabled=true visible=true change=false click=false summary=Turn off EnforceDoze when device is plugged in to a charger
            autoRotateAndBrightnessFix enabled=false visible=true change=false click=false summary=No longer used. EnforceDoze now restores sensors from its own records, so this workaround isn't needed. Your saved choice is kept.
            showPersistentNotif enabled=true visible=true change=true click=false summary=Show Doze stats in persistent notification
            screenOnSummary enabled=true visible=true change=true click=false summary=After the screen turns on, show what happened while it was off
            showDisabledNotification enabled=true visible=true change=false click=false summary=Show a status bar notification when EnforceDoze is disabled (tap to enable)
            ignoreLockscreenTimeout enabled=true visible=true change=false click=false summary=Ignore lockscreen timeout and enable Doze immediately after screen off
            waitForUnlock enabled=true visible=true change=false click=false summary=Wait for device unlock to leave doze and enable disabled features
            whitelistAppsFromDozeMode enabled=false visible=true change=false click=false summary=Checking access…
            dozeSettings enabled=false visible=true change=false click=false summary=null
            turnOffWiFiInDoze enabled=false visible=true change=false click=false summary=Checking access…
            turnOffDataInDoze enabled=false visible=true change=true click=false summary=Checking access…
            turnOnAirplaneInDoze enabled=false visible=true change=false click=false summary=Checking access…
            turnOffBluetoothInDoze enabled=false visible=true change=false click=false summary=Checking access…
            turnOffGPSInDoze enabled=false visible=true change=false click=false summary=Checking access…
            ignoreIfHotspot enabled=true visible=true change=false click=false summary=Disable to prevent disabling wifi/data during hotspot
            whitelistMusicAppNetwork enabled=true visible=true change=true click=false summary=Don't turn off all networks if music is playing. Needs notification access: without it, music is treated as not playing.
            whitelistCurrentApp enabled=false visible=true change=true click=false summary=Checking access…
            disableMotionSensors enabled=false visible=true change=false click=false summary=Checking access…
            turnOffAllSensorsInDoze enabled=false visible=true change=false click=false summary=Checking access…
            turnOffBiometricsInDoze enabled=false visible=true change=false click=false summary=Checking access…
            turnOnBatterySaverInDoze enabled=false visible=true change=false click=false summary=Checking access…
            dozeEnterDelay enabled=true visible=true change=true click=true summary=Add a delay before Doze mode kicks in after screen-off
            customDozePeriods enabled=true visible=true change=false click=true summary=No custom periods. EnforceDoze can run at any time.
            exactAlarmAccess enabled=true visible=false change=false click=true summary=null
            blacklistAppNotifications enabled=false visible=true change=false click=false summary=Checking access…
            blacklistApps enabled=false visible=true change=false click=false summary=Checking access…
            externalControl enabled=false visible=true change=false click=false summary=null
            allowExternalBasicControl enabled=true visible=true change=false click=false summary=Lets automation apps like Tasker turn EnforceDoze on or off and re-apply Doze
            allowExternalPrivilegedControl enabled=true visible=true change=false click=false summary=Warning: any installed app could then edit the Doze whitelist and change EnforceDoze settings. Only turn this on if you need it.
            taskerBroadcasts enabled=true visible=true change=false click=false summary=Show broadcasts that you can use via Tasker
            null enabled=false visible=true change=false click=false summary=null
            disableStats enabled=true visible=true change=false click=false summary=Stop collecting data for the legacy battery-stats chart. The Doze Monitor journal still records
            disableLogcat enabled=true visible=true change=false click=false summary=Prevent ForceDoze from logging to system logcat
            resetForceDoze enabled=true visible=true change=false click=true summary=Reset EnforceDoze to the default state
            resetDozeStats enabled=true visible=true change=false click=true summary=Clear the legacy battery-stats chart. The Doze Monitor journal is not affected
            debugLogs enabled=true visible=true change=false click=false summary=Show application debug logs
            aboutForceDoze enabled=true visible=true change=false click=false summary=null
            """;

    private static final String APP = """
            null enabled=false visible=true change=false click=false summary=null
            sponsorProject enabled=true visible=true change=false click=true summary=EnforceDoze is free & open-source — consider sponsoring
            mainSettings enabled=false visible=true change=false click=false summary=null
            accessStatus enabled=true visible=true change=false click=true summary=No privileged access · Needs Shizuku access
            executionMode enabled=true visible=true change=true click=false summary=Choose between Root or Shizuku for executing commands
            keepDozeEnforced enabled=false visible=true change=false click=false summary=Needs Shizuku or root (the DUMP permission covers motion sensors only)
            disableWhenCharging enabled=true visible=true change=false click=false summary=Turn off EnforceDoze when device is plugged in to a charger
            autoRotateAndBrightnessFix enabled=false visible=true change=false click=false summary=No longer used. EnforceDoze now restores sensors from its own records, so this workaround isn't needed. Your saved choice is kept.
            showPersistentNotif enabled=true visible=true change=true click=false summary=Show Doze stats in persistent notification
            screenOnSummary enabled=true visible=true change=true click=false summary=After the screen turns on, show what happened while it was off
            showDisabledNotification enabled=true visible=true change=false click=false summary=Show a status bar notification when EnforceDoze is disabled (tap to enable)
            ignoreLockscreenTimeout enabled=true visible=true change=false click=false summary=Ignore lockscreen timeout and enable Doze immediately after screen off
            waitForUnlock enabled=true visible=true change=false click=false summary=Wait for device unlock to leave doze and enable disabled features
            whitelistAppsFromDozeMode enabled=false visible=true change=false click=false summary=Needs Shizuku access
            dozeSettings enabled=false visible=true change=false click=false summary=null
            turnOffWiFiInDoze enabled=false visible=true change=false click=false summary=Needs Shizuku or root (the DUMP permission covers motion sensors only)
            turnOffDataInDoze enabled=false visible=true change=true click=false summary=Needs Shizuku or root (the DUMP permission covers motion sensors only)
            turnOnAirplaneInDoze enabled=false visible=true change=false click=false summary=Needs Shizuku or root (the DUMP permission covers motion sensors only)
            turnOffBluetoothInDoze enabled=false visible=true change=false click=false summary=Needs Shizuku or root (the DUMP permission covers motion sensors only)
            turnOffGPSInDoze enabled=false visible=true change=false click=false summary=Needs Shizuku or root (the DUMP permission covers motion sensors only)
            ignoreIfHotspot enabled=true visible=true change=false click=false summary=Disable to prevent disabling wifi/data during hotspot
            whitelistMusicAppNetwork enabled=true visible=true change=true click=false summary=Don't turn off all networks if music is playing. Needs notification access: without it, music is treated as not playing.
            whitelistCurrentApp enabled=false visible=true change=true click=false summary=Needs Shizuku access
            disableMotionSensors enabled=false visible=true change=false click=false summary=Needs DUMP permission — tap Access above to grant
            turnOffAllSensorsInDoze enabled=false visible=true change=false click=false summary=Requires root — not available with Shizuku
            turnOffBiometricsInDoze enabled=false visible=true change=false click=false summary=Needs Shizuku or root (the DUMP permission covers motion sensors only)
            turnOnBatterySaverInDoze enabled=false visible=true change=false click=false summary=Needs Shizuku or root (the DUMP permission covers motion sensors only)
            dozeEnterDelay enabled=true visible=true change=true click=true summary=Add a delay before Doze mode kicks in after screen-off
            customDozePeriods enabled=true visible=true change=false click=true summary=No custom periods. EnforceDoze can run at any time.
            exactAlarmAccess enabled=true visible=false change=false click=true summary=null
            blacklistAppNotifications enabled=false visible=true change=false click=false summary=Needs Shizuku or root (the DUMP permission covers motion sensors only)
            blacklistApps enabled=false visible=true change=false click=false summary=Needs Shizuku or root (the DUMP permission covers motion sensors only)
            externalControl enabled=false visible=true change=false click=false summary=null
            allowExternalBasicControl enabled=true visible=true change=false click=false summary=Lets automation apps like Tasker turn EnforceDoze on or off and re-apply Doze
            allowExternalPrivilegedControl enabled=true visible=true change=false click=false summary=Warning: any installed app could then edit the Doze whitelist and change EnforceDoze settings. Only turn this on if you need it.
            taskerBroadcasts enabled=true visible=true change=false click=false summary=Show broadcasts that you can use via Tasker
            null enabled=false visible=true change=false click=false summary=null
            disableStats enabled=true visible=true change=false click=false summary=Stop collecting data for the legacy battery-stats chart. The Doze Monitor journal still records
            disableLogcat enabled=true visible=true change=false click=false summary=Prevent ForceDoze from logging to system logcat
            resetForceDoze enabled=true visible=true change=false click=true summary=Reset EnforceDoze to the default state
            resetDozeStats enabled=true visible=true change=false click=true summary=Clear the legacy battery-stats chart. The Doze Monitor journal is not affected
            debugLogs enabled=true visible=true change=false click=false summary=Show application debug logs
            aboutForceDoze enabled=true visible=true change=false click=false summary=null
            """;

    private static final String SHELL = """
            null enabled=false visible=true change=false click=false summary=null
            sponsorProject enabled=true visible=true change=false click=true summary=EnforceDoze is free & open-source — consider sponsoring
            mainSettings enabled=false visible=true change=false click=false summary=null
            accessStatus enabled=true visible=true change=false click=true summary=Shizuku (shell) · Ready. EnforceDoze can force Doze and restrict motion sensors.
            executionMode enabled=true visible=true change=true click=false summary=Choose between Root or Shizuku for executing commands
            keepDozeEnforced enabled=true visible=true change=false click=false summary=Force Doze again if the system leaves it while the screen is off
            disableWhenCharging enabled=true visible=true change=false click=false summary=Turn off EnforceDoze when device is plugged in to a charger
            autoRotateAndBrightnessFix enabled=false visible=true change=false click=false summary=No longer used. EnforceDoze now restores sensors from its own records, so this workaround isn't needed. Your saved choice is kept.
            showPersistentNotif enabled=true visible=true change=true click=false summary=Show Doze stats in persistent notification
            screenOnSummary enabled=true visible=true change=true click=false summary=After the screen turns on, show what happened while it was off
            showDisabledNotification enabled=true visible=true change=false click=false summary=Show a status bar notification when EnforceDoze is disabled (tap to enable)
            ignoreLockscreenTimeout enabled=true visible=true change=false click=false summary=Ignore lockscreen timeout and enable Doze immediately after screen off
            waitForUnlock enabled=true visible=true change=false click=false summary=Wait for device unlock to leave doze and enable disabled features
            whitelistAppsFromDozeMode enabled=true visible=true change=false click=false summary=Whitelist apps from Doze mode
            dozeSettings enabled=false visible=true change=false click=false summary=null
            turnOffWiFiInDoze enabled=true visible=true change=false click=false summary=Turn off WiFi during Doze
            turnOffDataInDoze enabled=true visible=true change=true click=false summary=Turn off mobile data during Doze
            turnOnAirplaneInDoze enabled=true visible=true change=false click=false summary=Turn on Airplane mode while the device is in Doze mode
            turnOffBluetoothInDoze enabled=true visible=true change=false click=false summary=Turn off Bluetooth while the device is in Doze mode
            turnOffGPSInDoze enabled=true visible=true change=false click=false summary=Turn off GPS/Location while the device is in Doze mode
            ignoreIfHotspot enabled=true visible=true change=false click=false summary=Disable to prevent disabling wifi/data during hotspot
            whitelistMusicAppNetwork enabled=true visible=true change=true click=false summary=Don't turn off all networks if music is playing. Needs notification access: without it, music is treated as not playing.
            whitelistCurrentApp enabled=true visible=true change=true click=false summary=Currently focused app will be whitelisted even if in block list
            disableMotionSensors enabled=true visible=true change=false click=false summary=Disable device motion sensors when the device goes into Doze mode
            turnOffAllSensorsInDoze enabled=false visible=true change=false click=false summary=Requires root — not available with Shizuku
            turnOffBiometricsInDoze enabled=true visible=true change=false click=false summary=Disable biometrics unlock while screen off. Might not work on all devices
            turnOnBatterySaverInDoze enabled=true visible=true change=false click=false summary=Turn on Battery Saver while the device is in Doze mode
            dozeEnterDelay enabled=true visible=true change=true click=true summary=Add a delay before Doze mode kicks in after screen-off
            customDozePeriods enabled=true visible=true change=false click=true summary=No custom periods. EnforceDoze can run at any time.
            exactAlarmAccess enabled=true visible=false change=false click=true summary=null
            blacklistAppNotifications enabled=true visible=true change=false click=false summary=Prevent notifications from being delivered during Doze
            blacklistApps enabled=true visible=true change=false click=false summary=Prevent apps (and its background services) from running during Doze
            externalControl enabled=false visible=true change=false click=false summary=null
            allowExternalBasicControl enabled=true visible=true change=false click=false summary=Lets automation apps like Tasker turn EnforceDoze on or off and re-apply Doze
            allowExternalPrivilegedControl enabled=true visible=true change=false click=false summary=Warning: any installed app could then edit the Doze whitelist and change EnforceDoze settings. Only turn this on if you need it.
            taskerBroadcasts enabled=true visible=true change=false click=false summary=Show broadcasts that you can use via Tasker
            null enabled=false visible=true change=false click=false summary=null
            disableStats enabled=true visible=true change=false click=false summary=Stop collecting data for the legacy battery-stats chart. The Doze Monitor journal still records
            disableLogcat enabled=true visible=true change=false click=false summary=Prevent ForceDoze from logging to system logcat
            resetForceDoze enabled=true visible=true change=false click=true summary=Reset EnforceDoze to the default state
            resetDozeStats enabled=true visible=true change=false click=true summary=Clear the legacy battery-stats chart. The Doze Monitor journal is not affected
            debugLogs enabled=true visible=true change=false click=false summary=Show application debug logs
            aboutForceDoze enabled=true visible=true change=false click=false summary=null
            """;

    private static final String ROOT = """
            null enabled=false visible=true change=false click=false summary=null
            sponsorProject enabled=true visible=true change=false click=true summary=EnforceDoze is free & open-source — consider sponsoring
            mainSettings enabled=false visible=true change=false click=false summary=null
            accessStatus enabled=true visible=true change=false click=true summary=Shizuku (root) · Ready. EnforceDoze can force Doze and restrict motion sensors.
            executionMode enabled=true visible=true change=true click=false summary=Choose between Root or Shizuku for executing commands
            keepDozeEnforced enabled=true visible=true change=false click=false summary=Force Doze again if the system leaves it while the screen is off
            disableWhenCharging enabled=true visible=true change=false click=false summary=Turn off EnforceDoze when device is plugged in to a charger
            autoRotateAndBrightnessFix enabled=false visible=true change=false click=false summary=No longer used. EnforceDoze now restores sensors from its own records, so this workaround isn't needed. Your saved choice is kept.
            showPersistentNotif enabled=true visible=true change=true click=false summary=Show Doze stats in persistent notification
            screenOnSummary enabled=true visible=true change=true click=false summary=After the screen turns on, show what happened while it was off
            showDisabledNotification enabled=true visible=true change=false click=false summary=Show a status bar notification when EnforceDoze is disabled (tap to enable)
            ignoreLockscreenTimeout enabled=true visible=true change=false click=false summary=Ignore lockscreen timeout and enable Doze immediately after screen off
            waitForUnlock enabled=true visible=true change=false click=false summary=Wait for device unlock to leave doze and enable disabled features
            whitelistAppsFromDozeMode enabled=true visible=true change=false click=false summary=Whitelist apps from Doze mode
            dozeSettings enabled=false visible=true change=false click=false summary=null
            turnOffWiFiInDoze enabled=true visible=true change=false click=false summary=Turn off WiFi during Doze
            turnOffDataInDoze enabled=true visible=true change=true click=false summary=Turn off mobile data during Doze
            turnOnAirplaneInDoze enabled=true visible=true change=false click=false summary=Turn on Airplane mode while the device is in Doze mode
            turnOffBluetoothInDoze enabled=true visible=true change=false click=false summary=Turn off Bluetooth while the device is in Doze mode
            turnOffGPSInDoze enabled=true visible=true change=false click=false summary=Turn off GPS/Location while the device is in Doze mode
            ignoreIfHotspot enabled=true visible=true change=false click=false summary=Disable to prevent disabling wifi/data during hotspot
            whitelistMusicAppNetwork enabled=true visible=true change=true click=false summary=Don't turn off all networks if music is playing. Needs notification access: without it, music is treated as not playing.
            whitelistCurrentApp enabled=true visible=true change=true click=false summary=Currently focused app will be whitelisted even if in block list
            disableMotionSensors enabled=true visible=true change=false click=false summary=Disable device motion sensors when the device goes into Doze mode
            turnOffAllSensorsInDoze enabled=true visible=true change=false click=false summary=Root · Disable seall sensors when the device goes into Doze mode (motion, camera...)
            turnOffBiometricsInDoze enabled=true visible=true change=false click=false summary=Disable biometrics unlock while screen off. Might not work on all devices
            turnOnBatterySaverInDoze enabled=true visible=true change=false click=false summary=Turn on Battery Saver while the device is in Doze mode
            dozeEnterDelay enabled=true visible=true change=true click=true summary=Add a delay before Doze mode kicks in after screen-off
            customDozePeriods enabled=true visible=true change=false click=true summary=No custom periods. EnforceDoze can run at any time.
            exactAlarmAccess enabled=true visible=false change=false click=true summary=null
            blacklistAppNotifications enabled=true visible=true change=false click=false summary=Prevent notifications from being delivered during Doze
            blacklistApps enabled=true visible=true change=false click=false summary=Prevent apps (and its background services) from running during Doze
            externalControl enabled=false visible=true change=false click=false summary=null
            allowExternalBasicControl enabled=true visible=true change=false click=false summary=Lets automation apps like Tasker turn EnforceDoze on or off and re-apply Doze
            allowExternalPrivilegedControl enabled=true visible=true change=false click=false summary=Warning: any installed app could then edit the Doze whitelist and change EnforceDoze settings. Only turn this on if you need it.
            taskerBroadcasts enabled=true visible=true change=false click=false summary=Show broadcasts that you can use via Tasker
            null enabled=false visible=true change=false click=false summary=null
            disableStats enabled=true visible=true change=false click=false summary=Stop collecting data for the legacy battery-stats chart. The Doze Monitor journal still records
            disableLogcat enabled=true visible=true change=false click=false summary=Prevent ForceDoze from logging to system logcat
            resetForceDoze enabled=true visible=true change=false click=true summary=Reset EnforceDoze to the default state
            resetDozeStats enabled=true visible=true change=false click=true summary=Clear the legacy battery-stats chart. The Doze Monitor journal is not affected
            debugLogs enabled=true visible=true change=false click=false summary=Show application debug logs
            aboutForceDoze enabled=true visible=true change=false click=false summary=null
            """;

    private static AccessState state(AccessLevel level, boolean grants) {
        return new AccessState(level, null, new Grants(grants, grants), null);
    }

    @Test
    public void preferencesKeepTheirGatingAtEachAccessLevel() throws Exception {
        assertEquals(INITIAL, snapshot());
        publish(state(AccessLevel.APP, false));
        assertEquals(APP, snapshot());
        publish(state(AccessLevel.SHELL, true));
        assertEquals(SHELL, snapshot());
        publish(state(AccessLevel.ROOT, true));
        assertEquals(ROOT, snapshot());
    }

    @Test
    public void savedCustomPeriodsShowInTheirSummaryAndExactAlarmStatus() throws Exception {
        controller.pause().stop().destroy();
        idle();
        prefs.edit().putStringSet("customDozePeriods", new HashSet<>(Arrays.asList("22:00-07:00", "12:00-13:00")))
                .commit();
        host();
        Preference periods = pref("customDozePeriods");
        assertEquals(app.getString(R.string.custom_doze_periods_setting_summary, "12:00-13:00, 22:00-07:00"),
                String.valueOf(periods.getSummary()));
        Preference exact = pref("exactAlarmAccess");
        assertTrue("Exact-alarm status shows with custom periods on API 31+", exact.isVisible());
        assertEquals("Robolectric grants no exact alarms", app.getString(R.string.exact_alarm_status_best_effort),
                String.valueOf(exact.getSummary()));
    }

    @Test
    public void longDozeDelayWarnsAndAShortOneDoesNot() {
        assertTrue(change("dozeEnterDelay", 299));
        assertNull("No warning below five minutes", ShadowDialog.getLatestDialog());
        assertTrue(change("dozeEnterDelay", 300));
        assertEquals(app.getString(R.string.doze_delay_warning_dialog_text), latestMessage());
    }

    @Test
    public void persistentNotificationTurnsOnOnlyWithPostPermission() {
        assertTrue(change("showPersistentNotif", false));
        assertFalse("Refused until the permission is granted", change("showPersistentNotif", true));
        assertEquals(Manifest.permission.POST_NOTIFICATIONS,
                shadowOf(controller.get()).getLastRequestedPermission().requestedPermissions[0]);
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertTrue(change("showPersistentNotif", true));
    }

    @Test
    public void screenOnSummaryTurnsOnOnlyWithPostPermission() {
        assertTrue(change("screenOnSummary", false));
        assertFalse("Refused until the permission is granted", change("screenOnSummary", true));
        assertEquals(Manifest.permission.POST_NOTIFICATIONS,
                shadowOf(controller.get()).getLastRequestedPermission().requestedPermissions[0]);
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertTrue(change("screenOnSummary", true));
    }

    @Test
    public void rePickingTheActiveModeArmsNothing() {
        assertTrue(change("executionMode", "shizuku"));
        assertNull("No wait and no explanation", ShadowDialog.getLatestDialog());
    }

    @Test
    public void pickingRootWaitsForTheSuProbe() {
        // Only the listener runs: the stored mode (and so AccessManager) stays on Shizuku.
        assertTrue(change("executionMode", "root"));
        assertEquals(app.getString(R.string.mode_switch_waiting_root), latestMessage());
        assertEquals("shizuku", prefs.getString("executionMode", null));
    }

    private String revertedRootMessage() {
        return app.getString(R.string.mode_switch_root_failed_text, app.getString(R.string.execution_mode_shizuku));
    }

    @Test
    public void rootProbeTimingOutRevertsToThePreviousMode() {
        assertTrue(change("executionMode", "root"));
        Dialog wait = ShadowDialog.getLatestDialog();
        assertTrue(wait.isShowing());
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(5));
        assertFalse("The wait is dismissed", wait.isShowing());
        assertEquals(revertedRootMessage(), latestMessage());
        assertEquals("shizuku", ((androidx.preference.ListPreference) pref("executionMode")).getValue());
    }

    @Test
    public void cancellingTheRootWaitRevertsToThePreviousMode() {
        assertTrue(change("executionMode", "root"));
        MaterialDialog wait = (MaterialDialog) ShadowDialog.getLatestDialog();
        wait.getActionButton(com.afollestad.materialdialogs.DialogAction.NEGATIVE).performClick();
        idle();
        assertFalse("The wait is dismissed", wait.isShowing());
        assertEquals(revertedRootMessage(), latestMessage());
        assertEquals("shizuku", ((androidx.preference.ListPreference) pref("executionMode")).getValue());
    }

    @Test
    public void rootBecomingAvailableEndsTheWaitWithoutReverting() throws Exception {
        assertTrue(change("executionMode", "root"));
        MaterialDialog wait = (MaterialDialog) ShadowDialog.getLatestDialog();
        // The stored mode follows the pick, but AccessManager must never see it: root probes su.
        AccessManager manager = access;
        prefs.unregisterOnSharedPreferenceChangeListener(
                (SharedPreferences.OnSharedPreferenceChangeListener) get(manager, "prefListener"));
        prefs.edit().putString("executionMode", "root").commit();
        idle();
        publish(state(AccessLevel.ROOT, true));
        assertFalse("The wait ends", wait.isShowing());
        assertSame("No revert dialog replaces the wait", wait, ShadowDialog.getLatestDialog());
        assertEquals("root", prefs.getString("executionMode", null));
    }

    @Test
    public void pickingShizukuWhileItIsNotRunningExplainsWhy() throws Exception {
        // The screen must read root as the previous mode, but AccessManager must never see it: root probes su.
        AccessManager manager = access;
        prefs.unregisterOnSharedPreferenceChangeListener(
                (SharedPreferences.OnSharedPreferenceChangeListener) get(manager, "prefListener"));
        prefs.edit().putString("executionMode", "root").commit();
        idle();
        assertTrue(change("executionMode", "shizuku"));
        assertEquals(app.getString(R.string.mode_switch_not_running_text), latestMessage());
    }

    @Test
    public void retiredRotationFixAcceptsAChangeWithoutAPrompt() {
        // The control is disabled and has no listener: a change is just stored.
        assertTrue(change("autoRotateAndBrightnessFix", true));
        assertNull(ShadowDialog.getLatestDialog());
    }

    @Test
    public void confirmingAResetWhileOneRunsOnlyShowsItsWait() {
        assertTrue(ResetReport.TRACKER.begin());
        click("resetForceDoze");
        assertEquals(app.getString(R.string.forcedoze_reset_initial_dialog_text), latestMessage());
        clickPositive();
        assertSame(ResetReport.Tracker.Phase.RUNNING, ResetReport.TRACKER.phase());
        assertEquals(app.getString(R.string.reset_running_text), latestMessage());
        assertNull("The running reset keeps the service as it is", shadowOf(app).getNextStoppedService());
    }

    @Test
    public void mobileDataNeedsShizukuOrRoot() {
        assertTrue(change("turnOffDataInDoze", false));
        assertFalse(change("turnOffDataInDoze", true));
        assertEquals(app.getString(R.string.su_perm_denied_msg), latestMessage());
    }

    @Test
    public void musicWhitelistOffersNotificationListenerSettings() {
        assertTrue(change("whitelistMusicAppNetwork", false));
        assertNull("Turning it off asks nothing", ShadowDialog.getLatestDialog());
        assertTrue("Kept on: without access music only counts as not playing",
                change("whitelistMusicAppNetwork", true));
        assertEquals(app.getString(R.string.notifications_permission_explanation), latestMessage());
        clickPositive();
        Intent settings = shadowOf(controller.get()).getNextStartedActivity();
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, settings.getAction());
        assertEquals(app.getPackageName(), settings.getStringExtra(Settings.EXTRA_APP_PACKAGE));
    }

    @Test
    public void focusedAppWhitelistFollowsTheResolver() {
        assertTrue(change("whitelistCurrentApp", false));
        assertFalse("FOCUSED_APP is unavailable without Shizuku or root", change("whitelistCurrentApp", true));
    }

    @Test
    public void customPeriodsClickOpensTheirList() {
        click("customDozePeriods");
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
    }

    @Test
    public void exactAlarmClickOpensAlarmsAndReminders() {
        click("exactAlarmAccess");
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                shadowOf(controller.get()).getNextStartedActivity().getAction());
    }
}
