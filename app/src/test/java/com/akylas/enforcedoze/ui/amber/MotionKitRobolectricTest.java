package com.akylas.enforcedoze.ui.amber;

import android.app.Application;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.afollestad.materialdialogs.MaterialDialog;
import com.akylas.enforcedoze.R;
import com.google.android.material.color.MaterialColors;

import java.lang.reflect.Field;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The motion half of the Amber kit against the real AppTheme. Runtimes are the cached 28 and 36 images; API levels
 * 23, 27 and 30 are exercised by overriding Build.VERSION.SDK_INT on those runtimes for one call.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class MotionKitRobolectricTest {
    private static Context appTheme() {
        return new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
    }

    private static void setAnimatorScale(float scale) {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, scale);
    }

    private static void withSdkInt(int sdk, Runnable body) {
        int real = Build.VERSION.SDK_INT;
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", sdk);
        try {
            body.run();
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", real);
        }
    }

    @Test
    public void reducedMotionReadsGlobalAnimatorScaleDefaultingToOn() {
        Context context = appTheme();
        assertFalse("unset scale means 1", MotionPolicy.reducedMotion(context));
        setAnimatorScale(0f);
        assertTrue(MotionPolicy.reducedMotion(context));
    }

    @Test
    public void springsSnapToTargetWhenAnimatorDurationScaleIsZero() {
        setAnimatorScale(0f);
        View view = new View(appTheme());
        SpringAnimation animation = Springs.animate(view, DynamicAnimation.TRANSLATION_X, 42f);
        assertNull("reduced motion returns no animation", animation);
        assertEquals(42f, view.getTranslationX(), 0f);
    }

    @Test
    public void springsStartThemeTunedSpringWhenMotionIsOn() {
        setAnimatorScale(1f);
        View view = new View(appTheme());
        SpringAnimation medium = Springs.animate(view, DynamicAnimation.ALPHA, 0.5f);
        assertNotNull(medium);
        assertTrue(medium.isRunning());
        assertEquals(0.85f, medium.getSpring().getDampingRatio(), 1e-6f);
        assertEquals(400f, medium.getSpring().getStiffness(), 1e-3f);
        assertEquals(0.5f, medium.getSpring().getFinalPosition(), 0f);
        medium.cancel();

        SpringAnimation high = Springs.animate(view, DynamicAnimation.SCALE_X, 1.1f, R.attr.amberSpringStiffnessHigh);
        assertEquals(1500f, high.getSpring().getStiffness(), 1e-3f);
        high.cancel();
        SpringAnimation low = Springs.animate(view, DynamicAnimation.SCALE_Y, 1.1f, R.attr.amberSpringStiffnessLow);
        assertEquals(200f, low.getSpring().getStiffness(), 1e-3f);
        low.cancel();
    }

    @Test
    @Config(sdk = 28)
    public void glowTintsShadowAndRaisesElevationOnApi28() {
        Context context = appTheme();
        int glow = MaterialColors.getColor(context, R.attr.amberGlowColor, 0);
        ShadowColorView view = new ShadowColorView(context);

        AmberGlow.setActive(view, true);
        assertEquals(context.getResources().getDimension(R.dimen.glow_elevation), view.getElevation(), 0f);
        assertTrue("glow elevation is visible", view.getElevation() > 0f);
        assertTrue("glow colour carries alpha", glow != 0);
        assertEquals(Integer.valueOf(glow), view.ambient);
        assertEquals(Integer.valueOf(glow), view.spot);

        AmberGlow.setActive(view, false);
        assertEquals(0f, view.getElevation(), 0f);
    }

    @Test
    @Config(sdk = 28)
    public void glowIsNoOpBelowApi28() {
        ShadowColorView view = new ShadowColorView(appTheme());
        view.setElevation(3f);
        withSdkInt(27, () -> AmberGlow.setActive(view, true));
        assertEquals(3f, view.getElevation(), 0f);
        assertNull("ambient shadow colour untouched", view.ambient);
        assertNull("spot shadow colour untouched", view.spot);
    }

    /**
     * Records outline shadow colours: Robolectric's legacy RenderNode doesn't keep them, so the getters read 0.
     */
    private static final class ShadowColorView extends View {
        Integer ambient;
        Integer spot;

        ShadowColorView(Context context) {
            super(context);
        }

        @Override
        public void setOutlineAmbientShadowColor(int color) {
            ambient = color;
            super.setOutlineAmbientShadowColor(color);
        }

        @Override
        public void setOutlineSpotShadowColor(int color) {
            spot = color;
            super.setOutlineSpotShadowColor(color);
        }
    }

    @Test
    @Config(sdk = 28)
    public void dialogsBuilderCarriesAmberFontsOnApi28() throws Exception {
        assertAmberFonts(AmberDialogs.builder(appTheme()));
    }

    @Test
    public void dialogsBuilderCarriesAmberFontsOnApi36() throws Exception {
        assertAmberFonts(AmberDialogs.builder(appTheme()));
    }

    @Test
    @Config(sdk = 28)
    public void weightedKeepsFamilyAsIsBelowApi28() {
        Typeface family = Typeface.create("sans-serif", Typeface.NORMAL);
        Typeface[] result = new Typeface[1];
        withSdkInt(27, () -> result[0] = AmberDialogs.weighted(family, 500));
        assertSame(family, result[0]);
    }

    @Test
    @Config(sdk = 28)
    public void hapticsTickDoesNotThrowOnApi23() {
        View view = new View(appTheme());
        withSdkInt(23, () -> Haptics.tick(view));
    }

    @Test
    public void hapticsTickDoesNotThrowOnApi30Plus() {
        View view = new View(appTheme());
        Haptics.tick(view);
        withSdkInt(30, () -> Haptics.tick(view));
    }

    private static void assertAmberFonts(MaterialDialog.Builder builder) throws Exception {
        assertNotNull("regular font", builder.getRegularFont());
        Field medium = MaterialDialog.Builder.class.getDeclaredField("mediumFont");
        medium.setAccessible(true);
        assertNotNull("medium font", medium.get(builder));
    }
}
