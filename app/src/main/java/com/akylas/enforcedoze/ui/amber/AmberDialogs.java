package com.akylas.enforcedoze.ui.amber;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.afollestad.materialdialogs.MaterialDialog;
import com.akylas.enforcedoze.R;

/** MaterialDialog builders that use the Amber sans family for titles/buttons (medium) and body text (regular). */
public final class AmberDialogs {
    private static final int WEIGHT_MEDIUM = 500;
    private static final int WEIGHT_REGULAR = 400;

    private AmberDialogs() {
    }

    /** Drop-in for {@code new MaterialDialog.Builder(context)}. */
    public static MaterialDialog.Builder builder(Context context) {
        Typeface family = ResourcesCompat.getFont(context, R.font.amber_sans);
        return new MaterialDialog.Builder(context)
                .typeface(weighted(family, WEIGHT_MEDIUM), weighted(family, WEIGHT_REGULAR));
    }

    /** The family's instance for {@code weight} on API 28+; below 28 the family as-is (its default instance). */
    @Nullable
    static Typeface weighted(@Nullable Typeface family, int weight) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? Typeface.create(family, weight, false) : family;
    }
}
