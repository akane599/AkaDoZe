package com.akylas.enforcedoze.ui;

import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Looper;
import android.view.MenuItem;

import androidx.appcompat.widget.Toolbar;
import androidx.core.view.MenuItemCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.akylas.enforcedoze.DozeTunablesActivity;
import com.akylas.enforcedoze.MainActivity;
import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.TestAppState;
import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.access.Feature;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * SQ-46: the Doze tunables entry and screen follow the TUNABLES capability, and opening the screen no longer
 * claims tunables are "overridden when not using root". Hosted in Shizuku mode with no Shizuku binder, so
 * AccessManager never probes su; access states are published the way AccessManager does.
 */
@RunWith(RobolectricTestRunner.class)
// MyApplication would build the doze runtime and journal; these screens don't need them.
@Config(application = Application.class)
public class TunablesEntryRobolectricTest {
    private Application app;
    private SharedPreferences prefs;
    private final List<ActivityController<?>> hosted = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
        TestAppState.reset();
        prefs.edit().clear().commit();
        TestAppState.selectNonRootMode(app);
        TestAppState.setAppContext(app);
    }

    @After
    public void tearDown() throws Exception {
        for (ActivityController<?> controller : hosted) controller.pause().stop().destroy();
        idle();
        TestAppState.reset();
        prefs.edit().clear().commit();
    }

    private <T extends android.app.Activity> T host(Class<T> type) {
        ActivityController<T> controller = Robolectric.buildActivity(type).setup();
        hosted.add(controller);
        idle();
        return controller.get();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static AccessState state(AccessLevel level, boolean dump, boolean wss) {
        return new AccessState(level, null, new Grants(dump, wss), null);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * A first root/Shizuku state starts a background helper grant whose own publish() would replace the
     * published state with the binder-less real one. The screens already asked: no grant runs.
     */
    private void markHelpersRequested() throws Exception {
        for (ActivityController<?> controller : hosted) {
            Object activity = controller.get();
            if (activity instanceof MainActivity) {
                setField(MainActivity.class, activity, "helpersRequested", true);
            } else if (activity instanceof DozeTunablesActivity) {
                Object fragment = ((DozeTunablesActivity) activity).getSupportFragmentManager()
                        .findFragmentById(R.id.content);
                setField(DozeTunablesActivity.DozeTunablesFragment.class, fragment, "helpersRequested", true);
            }
        }
    }

    /** Sets AccessManager's current state and notifies every listener, as a refresh does. */
    @SuppressWarnings("unchecked")
    private void publish(AccessState state) throws Exception {
        markHelpersRequested();
        AccessManager access = TestAppState.accessWithoutRoot(app);
        Field published = AccessManager.class.getDeclaredField("state");
        published.setAccessible(true);
        published.set(access, state);
        for (AccessManager.Listener listener
                : new ArrayList<>((Collection<AccessManager.Listener>) get(access, "listeners"))) {
            // Main's onResume safety check arms the service's restore-only window, which on SHELL would
            // run a real restore and republish the binder-less state. Only the screens listen here.
            if (listener.getClass().getName().startsWith("com.akylas.enforcedoze.service.")) {
                access.removeListener(listener);
                continue;
            }
            listener.onAccessChanged(state);
        }
        idle();
    }

    /** Whatever the launch started (permission screens and the like) isn't what these tests open. */
    private void drainStartedActivities() {
        while (shadowOf(app).getNextStartedActivity() != null) { /* drain */ }
    }

    /** The toolbar re-prepares its menu on the next animation frame after invalidateOptionsMenu. */
    private static MenuItem tunablesItem(MainActivity main) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20));
        Toolbar toolbar = main.findViewById(R.id.toolbar);
        MenuItem item = toolbar.getMenu().findItem(R.id.action_show_doze_tunables);
        assertNotNull("the toolbar shows the tunables entry", item);
        return item;
    }

    private static void setServiceEnabled(MainActivity main, boolean enabled) throws Exception {
        Field field = MainActivity.class.getDeclaredField("serviceEnabled");
        field.setAccessible(true);
        field.setBoolean(main, enabled);
    }

    private void assertOpenedTunables() {
        Intent started = shadowOf(app).getNextStartedActivity();
        assertNotNull("the tunables screen opened", started);
        assertEquals(DozeTunablesActivity.class.getName(), started.getComponent().getClassName());
    }

    // ---- Main toolbar entry ----

    @Test
    public void entryIsGreyedOutWithoutAccessAndEnabledWhereTunablesCanBeWritten() throws Exception {
        MainActivity main = host(MainActivity.class);
        publish(state(AccessLevel.APP, true, false));
        assertFalse("APP without WRITE_SECURE_SETTINGS can't write tunables", tunablesItem(main).isEnabled());
        publish(state(AccessLevel.SHELL, false, false));
        assertTrue("Shizuku (shell) writes tunables: the access change re-prepared the menu",
                tunablesItem(main).isEnabled());
        publish(state(AccessLevel.APP, false, true));
        assertTrue("an adb WRITE_SECURE_SETTINGS grant is enough", tunablesItem(main).isEnabled());
        publish(state(AccessLevel.NONE, false, false));
        assertFalse(tunablesItem(main).isEnabled());
    }

    @Test
    public void disabledEntryIsVisiblyDimmedWhileTheEnabledTintStaysAsBefore() throws Exception {
        MainActivity main = host(MainActivity.class);
        ColorStateList tint = MenuItemCompat.getIconTintList(tunablesItem(main));
        assertNotNull(tint);
        int enabled = tint.getColorForState(new int[] {android.R.attr.state_enabled}, 0);
        int disabled = tint.getColorForState(new int[] {-android.R.attr.state_enabled}, 0);
        int normal = com.google.android.material.color.MaterialColors.getColor(
                main.findViewById(R.id.toolbar), androidx.appcompat.R.attr.colorControlNormal);
        assertEquals("enabled: the toolbar's colorControlNormal, as before", normal, enabled);
        assertNotEquals("disabled renders differently", enabled, disabled);
        assertTrue("disabled is translucent", android.graphics.Color.alpha(disabled) < android.graphics.Color.alpha(enabled));
    }

    // ---- Opening the screen: no stale root warning ----

    @Test
    public void aForcingServiceOpensThroughOneAccurateNoteNotTheOldRootWarning() throws Exception {
        MainActivity main = host(MainActivity.class);
        publish(state(AccessLevel.SHELL, true, true));
        setServiceEnabled(main, true);
        drainStartedActivities();
        int before = ShadowDialog.getShownDialogs().size();
        main.showDozeTunablesActivity();
        idle();
        assertEquals("one note", before + 1, ShadowDialog.getShownDialogs().size());
        Dialog latest = ShadowDialog.getLatestDialog();
        assertTrue(latest instanceof MaterialDialog);
        MaterialDialog note = (MaterialDialog) latest;
        String text = note.getContentView().getText().toString();
        assertEquals(app.getString(R.string.amber_SQ46_tunables_forced_doze), text);
        assertFalse("no stale override claim", text.contains("overriden") || text.contains("not using root"));
        assertTrue("names what forcing skips", text.contains("inactive_to"));
        assertNull("nothing opens before the note is accepted", shadowOf(app).peekNextStartedActivity());
        note.getActionButton(DialogAction.POSITIVE).performClick();
        idle();
        assertOpenedTunables();
    }

    @Test
    public void anEnabledServiceThatDoesNotForceDozeOpensTheScreenDirectly() throws Exception {
        MainActivity main = host(MainActivity.class);
        // APP with DUMP and WRITE_SECURE_SETTINGS: sensor-only sessions, Android keeps its own timing.
        publish(state(AccessLevel.APP, true, true));
        setServiceEnabled(main, true);
        drainStartedActivities();
        int before = ShadowDialog.getShownDialogs().size();
        main.showDozeTunablesActivity();
        idle();
        assertEquals("no dialog", before, ShadowDialog.getShownDialogs().size());
        assertOpenedTunables();
    }

    @Test
    public void aStoppedServiceOpensTheScreenDirectly() throws Exception {
        MainActivity main = host(MainActivity.class);
        publish(state(AccessLevel.SHELL, true, true));
        setServiceEnabled(main, false);
        drainStartedActivities();
        int before = ShadowDialog.getShownDialogs().size();
        main.showDozeTunablesActivity();
        idle();
        assertEquals("no dialog", before, ShadowDialog.getShownDialogs().size());
        assertOpenedTunables();
    }

    // ---- The tunables screen itself ----

    private static List<Preference> leaves(PreferenceGroup group, List<Preference> out) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            if (pref instanceof PreferenceGroup) leaves((PreferenceGroup) pref, out);
            else out.add(pref);
        }
        return out;
    }

    @Test
    public void screenOpenedWithoutAccessDisablesEveryTunableAndSaysWhyWithoutTouchingStoredValues() throws Exception {
        prefs.edit().putString("inactive_to", "123456").putString("idle_factor", "3").commit();
        DozeTunablesActivity screen = host(DozeTunablesActivity.class);
        PreferenceFragmentCompat fragment = (PreferenceFragmentCompat) screen.getSupportFragmentManager()
                .findFragmentById(R.id.content);
        assertNotNull(fragment);
        Map<String, ?> stored = new HashMap<>(prefs.getAll());
        List<Preference> tunables = leaves(fragment.getPreferenceScreen(), new ArrayList<>());
        assertTrue(tunables.size() > 10);

        AccessState none = state(AccessLevel.APP, true, false);
        publish(none);
        String why = AccessUi.unavailableText(app, Feature.TUNABLES, none, true);
        assertNotNull(why);
        for (Preference pref : tunables) {
            assertFalse(pref.getKey() + " disabled", pref.isEnabled());
            assertEquals(pref.getKey() + " says why", why, String.valueOf(pref.getSummary()));
        }

        publish(state(AccessLevel.SHELL, false, false));
        for (Preference pref : tunables) {
            assertTrue(pref.getKey() + " enabled", pref.isEnabled());
            assertNotEquals(pref.getKey() + " has its own summary back", why, String.valueOf(pref.getSummary()));
        }
        assertTrue(String.valueOf(fragment.findPreference("inactive_to").getSummary())
                .startsWith("This is the time, after becoming inactive"));
        assertEquals("only enabled state and summaries changed", stored, new HashMap<>(prefs.getAll()));
        assertEquals("123456", prefs.getString("inactive_to", null));
    }
}
