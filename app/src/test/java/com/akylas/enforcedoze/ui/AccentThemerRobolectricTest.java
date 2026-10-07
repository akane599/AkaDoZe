package com.akylas.enforcedoze.ui;

import android.app.Activity;
import android.app.Application;
import android.util.TypedValue;

import com.akylas.enforcedoze.AboutAppActivity;
import com.akylas.enforcedoze.R;
import com.google.android.material.button.MaterialButton;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The time-of-day overlay reaches views inflated by a real AppTheme activity, on both lifecycle paths. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {28, 36})
public class AccentThemerRobolectricTest {
    private static final int MORNING = 0xFFE3C287;
    private static final int DAY = 0xFFE8A54B;
    private static final int EVENING = 0xFFE89A50;
    private static final int NIGHT = 0xFFE27C43;

    private AccentThemer registered;

    private void register(AccentThemer themer) {
        registered = themer;
        RuntimeEnvironment.getApplication().registerActivityLifecycleCallbacks(themer);
    }

    @After
    public void unregister() {
        if (registered != null) {
            RuntimeEnvironment.getApplication().unregisterActivityLifecycleCallbacks(registered);
        }
    }

    // The M3 selector lists its disabled (12% onSurface) entry first, so read the enabled state, not getDefaultColor().
    private static int buttonTint(Activity activity) {
        MaterialButton button = new MaterialButton(activity);
        return button.getBackgroundTintList().getColorForState(button.getDrawableState(), 0);
    }

    private static int themePrimary(Activity activity) {
        TypedValue value = new TypedValue();
        activity.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true);
        return value.data;
    }

    private static ActivityController<AboutAppActivity> startAbout() {
        return Robolectric.buildActivity(AboutAppActivity.class).setup();
    }

    @Test
    public void nightHourTintsInflatedButtonNightAndSurvivesRecreate() {
        register(new AccentThemer(() -> 23));
        ActivityController<AboutAppActivity> controller = startAbout();
        assertEquals(NIGHT, buttonTint(controller.get()));
        assertEquals(NIGHT, themePrimary(controller.get()));

        controller.recreate();
        assertEquals("recreate() must re-apply the overlay", NIGHT, buttonTint(controller.get()));
    }

    @Test
    public void morningHourTintsInflatedButtonMorning() {
        register(new AccentThemer(() -> 7));
        ActivityController<AboutAppActivity> controller = startAbout();
        assertEquals(MORNING, buttonTint(controller.get()));
    }

    @Test
    public void withoutThemerAppThemeKeepsDayPrimary() {
        ActivityController<AboutAppActivity> controller = startAbout();
        assertEquals(DAY, buttonTint(controller.get()));
    }

    @Test
    public void systemClockThemerAppliesOneOfThePhasePrimaries() {
        register(new AccentThemer());
        ActivityController<AboutAppActivity> controller = startAbout();
        List<Integer> phases = Arrays.asList(MORNING, DAY, EVENING, NIGHT);
        assertTrue(phases.contains(buttonTint(controller.get())));
    }

    @Test
    public void overlayForMapsEveryPhase() {
        assertEquals(R.style.ThemeOverlay_Amber_Accent_Morning, AccentThemer.overlayFor(AccentClock.Phase.MORNING));
        assertEquals(R.style.ThemeOverlay_Amber_Accent_Day, AccentThemer.overlayFor(AccentClock.Phase.DAY));
        assertEquals(R.style.ThemeOverlay_Amber_Accent_Evening, AccentThemer.overlayFor(AccentClock.Phase.EVENING));
        assertEquals(R.style.ThemeOverlay_Amber_Accent_Night, AccentThemer.overlayFor(AccentClock.Phase.NIGHT));
        assertEquals("a new phase needs an overlay", 4, AccentClock.Phase.values().length);
    }
}
