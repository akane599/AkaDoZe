package com.akylas.enforcedoze;

import android.app.Application;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.akylas.enforcedoze.ui.amber.AmberButton;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The number picker dialog in the Amber look: same values and selection, Amber builder, button and tabular digits. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class NumberPickerPreferenceRobolectricTest {
    private AppCompatActivity activity;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(AppCompatActivity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
    }

    @Test
    public void steppedRangeShowsItsValuesAndSelection() {
        NumberPickerPreference pref = picker(0, 30, 5, 10);

        assertTrue(pref.onPreferenceClick(pref));

        assertTrue(pref.mDialog.isShowing());
        assertArrayEquals(new String[]{"0", "5", "10", "15", "20", "25", "30"}, pref.mPicker.getDisplayedValues());
        assertEquals(2, pref.mPicker.getValue());
        assertEquals(10, pref.getValue());
        assertTrue(pref.mDialog.getCustomView().findViewById(R.id.btn_save) instanceof AmberButton);
        assertEquals("tnum", pickerText(pref).getFontFeatureSettings());
    }

    @Test
    public void plainRangeKeepsBoundsAndReusesItsDialog() {
        NumberPickerPreference pref = picker(1, 9, 1, 4);

        pref.onPreferenceClick(pref);
        Object first = pref.mDialog;
        pref.mDialog.dismiss();
        pref.onPreferenceClick(pref);

        assertSame(first, pref.mDialog);
        assertNull(pref.mPicker.getDisplayedValues());
        assertEquals(1, pref.mPicker.getMinValue());
        assertEquals(9, pref.mPicker.getMaxValue());
        assertEquals(4, pref.getValue());
    }

    @Test
    public void tabularDigitsReachOnlyTextChildren() {
        LinearLayout group = new LinearLayout(activity);
        TextView text = new TextView(activity);
        group.addView(new View(activity));
        group.addView(text);

        NumberPickerPreference.useTabularDigits(group);

        assertEquals("tnum", text.getFontFeatureSettings());
    }

    @Test
    public void settingsOverlayQuietsCategoryTitles() {
        Resources.Theme theme = activity.getResources().newTheme();
        theme.setTo(activity.getTheme());
        theme.applyStyle(R.style.ThemeOverlay_Amber_Settings, true);
        TypedValue preferenceTheme = new TypedValue();
        assertTrue(theme.resolveAttribute(androidx.preference.R.attr.preferenceTheme, preferenceTheme, true));
        theme.applyStyle(preferenceTheme.resourceId, false);

        assertEquals(color(theme, com.google.android.material.R.attr.colorOnSurfaceVariant),
                color(theme, androidx.preference.R.attr.preferenceCategoryTitleTextColor));
        assertEquals(R.style.TextAppearance_Amber_PreferenceCategory,
                resource(theme, androidx.preference.R.attr.preferenceCategoryTitleTextAppearance));
        assertEquals(R.style.PreferenceFragment_Amber,
                resource(theme, androidx.preference.R.attr.preferenceFragmentCompatStyle));
    }

    private NumberPickerPreference picker(int min, int max, int step, int current) {
        NumberPickerPreference pref = new NumberPickerPreference(activity);
        pref.mMin = min;
        pref.mMax = max;
        pref.mStep = step;
        pref.mCurrentValue = current;
        return pref;
    }

    private static TextView pickerText(NumberPickerPreference pref) {
        for (int i = 0; i < pref.mPicker.getChildCount(); i++) {
            if (pref.mPicker.getChildAt(i) instanceof TextView) return (TextView) pref.mPicker.getChildAt(i);
        }
        throw new AssertionError("NumberPicker has no text child");
    }

    private static int color(Resources.Theme theme, int attr) {
        TypedValue value = new TypedValue();
        assertTrue(theme.resolveAttribute(attr, value, true));
        return value.data;
    }

    private static int resource(Resources.Theme theme, int attr) {
        TypedValue value = new TypedValue();
        assertTrue(theme.resolveAttribute(attr, value, true));
        return value.resourceId;
    }
}
