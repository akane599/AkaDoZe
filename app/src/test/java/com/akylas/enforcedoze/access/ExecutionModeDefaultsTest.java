package com.akylas.enforcedoze.access;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.SettingsActivity;
import com.akylas.enforcedoze.TestAppState;
import com.akylas.enforcedoze.Utils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class ExecutionModeDefaultsTest {
    private Application app;
    private SharedPreferences prefs;

    @Before
    public void clearPreferences() throws Exception {
        TestAppState.reset();
        app = RuntimeEnvironment.getApplication();
        TestAppState.setAppContext(app);
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
        assertTrue(prefs.edit().clear().commit());
        assertTrue(app.getSharedPreferences(PreferenceManager.KEY_HAS_SET_DEFAULT_VALUES,
                Context.MODE_PRIVATE).edit().clear().commit());
    }

    @After
    public void cleanUp() throws Exception {
        TestAppState.reset();
        prefs.edit().clear().commit();
    }

    @Test
    public void constantDefaultsToShizuku() {
        assertEquals(Prefs.MODE_SHIZUKU, Prefs.DEFAULT_EXECUTION_MODE);
    }

    @Test
    public void freshPreferencesSelectShizukuWithoutWritingAModeOrProbingRoot() {
        assertFalse(prefs.contains(Prefs.EXECUTION_MODE));
        assertTrue(Utils.isShizukuMode(app));
        assertNotNull(TestAppState.accessWithoutRoot(app));
        assertFalse("reading a default must not migrate preferences", prefs.contains(Prefs.EXECUTION_MODE));
    }

    @Test
    public void removingStoredModeMakesTheLiveManagerUseShizukuAgain() {
        prefs.edit().putString(Prefs.EXECUTION_MODE, "app").commit();
        AccessManager.getInstance(app);
        prefs.edit().remove(Prefs.EXECUTION_MODE).commit();
        assertNotNull("preference listener must use the same fallback", TestAppState.accessWithoutRoot(app));
        assertTrue(Utils.isShizukuMode(app));
    }

    @Test
    public void xmlDefaultsPersistShizukuOnFreshPreferences() {
        assertFalse(prefs.contains(Prefs.EXECUTION_MODE));
        PreferenceManager.setDefaultValues(app, R.xml.prefs, false);
        assertEquals(Prefs.MODE_SHIZUKU, prefs.getString(Prefs.EXECUTION_MODE, null));
    }

    @Test
    public void storedRootIsPreservedWhenXmlDefaultsAreApplied() {
        prefs.edit().putString(Prefs.EXECUTION_MODE, Prefs.MODE_ROOT).commit();
        PreferenceManager.setDefaultValues(app, R.xml.prefs, false);
        assertEquals(Prefs.MODE_ROOT, prefs.getString(Prefs.EXECUTION_MODE, null));
        assertFalse("root remains selectable", Utils.isShizukuMode(app));
    }

    @Test
    public void storedShizukuRemainsSelected() {
        prefs.edit().putString(Prefs.EXECUTION_MODE, Prefs.MODE_SHIZUKU).commit();
        assertTrue(Utils.isShizukuMode(app));
    }

    @Test
    public void switchCompletionUsesMissingModeDefaultAndRejectsStaleTokensOrSelections() throws Exception {
        SettingsActivity.SettingsFragment fragment = new SettingsActivity.SettingsFragment();
        Method current = method("isCurrentModeSwitch", int.class, String.class, SharedPreferences.class);
        assertTrue((Boolean) current.invoke(fragment, 0, Prefs.MODE_SHIZUKU, prefs));
        assertFalse((Boolean) current.invoke(fragment, 1, Prefs.MODE_SHIZUKU, prefs));
        assertFalse((Boolean) current.invoke(fragment, 0, Prefs.MODE_ROOT, prefs));
        prefs.edit().putString(Prefs.EXECUTION_MODE, Prefs.MODE_ROOT).commit();
        assertTrue((Boolean) current.invoke(fragment, 0, Prefs.MODE_ROOT, prefs));
        assertFalse((Boolean) current.invoke(fragment, 0, Prefs.MODE_SHIZUKU, prefs));
    }

    @Test
    public void pendingRootWaitOnlyFinishesForSelectedRootWithRootAccess() throws Exception {
        SettingsActivity.SettingsFragment fragment = new SettingsActivity.SettingsFragment();
        Method finish = method("finishPendingRootWait", String.class, AccessState.class);
        AccessState root = new AccessState(AccessLevel.ROOT, null, new Grants(false, false), null);
        AccessState shell = new AccessState(AccessLevel.SHELL, null, new Grants(false, false), null);
        finish.invoke(fragment, Prefs.MODE_ROOT, root);
        assertFalse((Boolean) field("awaitingRoot").get(fragment));
        field("awaitingRoot").set(fragment, true);
        finish.invoke(fragment, Prefs.MODE_SHIZUKU, root);
        assertTrue((Boolean) field("awaitingRoot").get(fragment));
        finish.invoke(fragment, Prefs.MODE_ROOT, shell);
        assertTrue((Boolean) field("awaitingRoot").get(fragment));
        finish.invoke(fragment, Prefs.MODE_ROOT, root);
        assertFalse((Boolean) field("awaitingRoot").get(fragment));
    }

    @Test
    public void switchCompletionDoesNothingWhenSupersededOrAccessIsNotPrivileged() throws Exception {
        SettingsActivity.SettingsFragment fragment = new SettingsActivity.SettingsFragment();
        field("accessManager").set(fragment, TestAppState.accessWithoutRoot(app));
        Method finish = method("finishModeSwitch", int.class, String.class, Context.class);
        finish.invoke(fragment, 1, Prefs.MODE_SHIZUKU, app);
        finish.invoke(fragment, 0, Prefs.MODE_ROOT, app);
        finish.invoke(fragment, 0, Prefs.MODE_SHIZUKU, app);
        Field runtime = com.akylas.enforcedoze.MyApplication.class.getDeclaredField("dozeRuntime");
        runtime.setAccessible(true);
        assertNull("rejected completion must not request a runtime safety check", runtime.get(null));
        assertNull(org.robolectric.Shadows.shadowOf(app).getNextStartedService());
    }

    @Test
    public void disabledServiceIsNotRestartedBySwitchCompletion() throws Exception {
        SettingsActivity.SettingsFragment fragment = new SettingsActivity.SettingsFragment();
        method("restartEnabledService", Context.class, SharedPreferences.class).invoke(fragment, app, prefs);
        assertNull(org.robolectric.Shadows.shadowOf(app).getNextStartedService());
    }

    private static Method method(String name, Class<?>... types) throws Exception {
        Method method = SettingsActivity.SettingsFragment.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    private static Field field(String name) throws Exception {
        Field field = SettingsActivity.SettingsFragment.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
