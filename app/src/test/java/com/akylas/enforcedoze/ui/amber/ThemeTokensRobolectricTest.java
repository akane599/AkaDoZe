package com.akylas.enforcedoze.ui.amber;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.akylas.enforcedoze.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The Amber Night theme resolves its pinned tokens, and each accent overlay swaps the primary. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class ThemeTokensRobolectricTest {
    private static final int INK = 0xFF0E1014;
    private static final int DAY = 0xFFE8A54B;

    private static Context appTheme() {
        return new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
    }

    private static TypedValue resolve(Context context, int attr) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) {
            throw new AssertionError("unresolved attr 0x" + Integer.toHexString(attr));
        }
        return value;
    }

    private static int color(Context context, int attr) {
        return resolve(context, attr).data;
    }

    private static int overlayPrimary(int overlay) {
        Context context = appTheme();
        context.getTheme().applyStyle(overlay, true);
        return color(context, androidx.appcompat.R.attr.colorPrimary);
    }

    @Test
    public void appThemeResolvesInkPrimaryAndOutline() {
        Context context = appTheme();
        assertEquals(INK, color(context, android.R.attr.colorBackground));
        assertEquals(DAY, color(context, androidx.appcompat.R.attr.colorPrimary));
        assertEquals(color(context, androidx.appcompat.R.attr.colorPrimary),
                color(context, androidx.appcompat.R.attr.colorAccent));
        assertEquals(0xFF71757C, color(context, com.google.android.material.R.attr.colorOutline));
        assertEquals(0xFF16181C, color(context, com.google.android.material.R.attr.colorSurfaceContainerLow));
    }

    @Test
    public void legacyColorPrimaryKeepsItsNameForCustomTabs() {
        Resources res = RuntimeEnvironment.getApplication().getResources();
        assertEquals(DAY, res.getColor(R.color.colorPrimary, null));
    }

    @Test
    public void eachAccentOverlayYieldsItsPrimary() {
        assertEquals(0xFFE3C287, overlayPrimary(R.style.ThemeOverlay_Amber_Accent_Morning));
        assertEquals(DAY, overlayPrimary(R.style.ThemeOverlay_Amber_Accent_Day));
        assertEquals(0xFFE89A50, overlayPrimary(R.style.ThemeOverlay_Amber_Accent_Evening));
        assertEquals(0xFFE27C43, overlayPrimary(R.style.ThemeOverlay_Amber_Accent_Night));
    }

    @Test
    public void overlayMovesAccentAndGlowWithPrimary() {
        Context context = appTheme();
        context.getTheme().applyStyle(R.style.ThemeOverlay_Amber_Accent_Night, true);
        assertEquals(0xFFE27C43, color(context, androidx.appcompat.R.attr.colorAccent));
        assertEquals(0x14E27C43, color(context, R.attr.amberGlowColor));
    }

    @Test
    public void springAttrsResolveAsFloats() {
        Context context = appTheme();
        TypedValue damping = resolve(context, R.attr.amberSpringDamping);
        assertEquals(TypedValue.TYPE_FLOAT, damping.type);
        assertEquals(0.85f, damping.getFloat(), 0f);
        assertEquals(200f, resolve(context, R.attr.amberSpringStiffnessLow).getFloat(), 0f);
        assertEquals(400f, resolve(context, R.attr.amberSpringStiffnessMedium).getFloat(), 0f);
        assertEquals(1500f, resolve(context, R.attr.amberSpringStiffnessHigh).getFloat(), 0f);
    }

    @Test
    public void titleLargeUsesTheSerifFamily() {
        Context context = appTheme();
        TypedValue ref = new TypedValue();
        context.getTheme().resolveAttribute(com.google.android.material.R.attr.textAppearanceTitleLarge, ref, false);
        int appearance = ref.data;
        assertEquals(R.style.TextAppearance_Amber_TitleLarge, appearance);
        TypedArray a = context.obtainStyledAttributes(appearance, new int[] {android.R.attr.fontFamily});
        try {
            assertEquals(R.font.amber_serif, a.getResourceId(0, 0));
        } finally {
            a.recycle();
        }
    }

    private static String fontFeatures(int style) {
        TypedArray a = appTheme().obtainStyledAttributes(style, new int[] {android.R.attr.fontFeatureSettings});
        try {
            return a.getString(0);
        } finally {
            a.recycle();
        }
    }

    @Test
    public void proseStylesKeepProportionalHyphensAndOnlyNumericVariantsUseTnum() {
        // Inter's tnum gives the hyphen a figure-wide advance ("Wi - Fi", "adb  - d"), so prose stays off it.
        int[] prose = {
            R.style.TextAppearance_Amber_BodyLarge, R.style.TextAppearance_Amber_BodyMedium,
            R.style.TextAppearance_Amber_BodySmall, R.style.TextAppearance_Amber_LabelLarge,
            R.style.TextAppearance_Amber_LabelMedium, R.style.TextAppearance_Amber_LabelSmall,
        };
        for (int style : prose) {
            assertNull(fontFeatures(style));
        }
        assertEquals("tnum", fontFeatures(R.style.TextAppearance_Amber_BodyMedium_Numeric));
        assertEquals("tnum", fontFeatures(R.style.TextAppearance_Amber_LabelLarge_Numeric));
    }

    @Test
    public void statsRowFiguresAreTabular() {
        View row = LayoutInflater.from(appTheme()).inflate(R.layout.list_row_layout_stats, null);

        assertEquals("tnum", ((TextView) row.findViewById(R.id.dozeStateTimestamp)).getFontFeatureSettings());
        assertEquals("tnum", ((TextView) row.findViewById(R.id.batteryLevel)).getFontFeatureSettings());
    }

    @Test
    public void appCompatAlertDialogsUseTheAmberOverlay() {
        int overlay = reference(appTheme(), androidx.appcompat.R.attr.alertDialogTheme);
        assertEquals(R.style.ThemeOverlay_Amber_AlertDialog, overlay);

        // AppCompatDialog wraps the host context in the resolved overlay the same way.
        Context dialog = new ContextThemeWrapper(appTheme(), overlay);
        assertEquals(R.drawable.amber_dialog_background, reference(dialog, android.R.attr.windowBackground));
        assertEquals(R.style.MaterialAlertDialog_Amber_Title_Text, reference(dialog, android.R.attr.windowTitleStyle));
        assertEquals(R.style.Widget_Amber_Button_Dialog,
                reference(dialog, androidx.appcompat.R.attr.buttonBarPositiveButtonStyle));
        assertEquals(R.style.Widget_Amber_Button_Dialog,
                reference(dialog, androidx.appcompat.R.attr.buttonBarNegativeButtonStyle));
    }

    private static int reference(Context context, int attr) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, false)) {
            throw new AssertionError("unresolved attr 0x" + Integer.toHexString(attr));
        }
        return value.data;
    }
}
