package com.akylas.enforcedoze.ui.amber;

import android.graphics.Color;
import android.os.Build;
import android.view.View;

import androidx.annotation.RequiresApi;

import com.akylas.enforcedoze.R;
import com.google.android.material.color.MaterialColors;

/**
 * An amber glow under the one active element, drawn as a tinted elevation shadow.
 *
 * <p>API 28+ only (outline shadow colours); below 28 this is a no-op. The shadow follows the view's outline, so it
 * needs a convex outline: a squircle path that can't be expressed as a convex outline (possible below API 30)
 * casts no shadow, which falls back to no glow. That is acceptable.
 */
public final class AmberGlow {
    private AmberGlow() {
    }

    /** Active: shadow tinted with {@code ?attr/amberGlowColor} at {@code @dimen/glow_elevation}. Inactive: elevation 0. */
    public static void setActive(View view, boolean active) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        applyGlow(view, active);
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private static void applyGlow(View view, boolean active) {
        int color = MaterialColors.getColor(view.getContext(), R.attr.amberGlowColor, Color.TRANSPARENT);
        view.setOutlineAmbientShadowColor(color);
        view.setOutlineSpotShadowColor(color);
        view.setElevation(active ? view.getResources().getDimension(R.dimen.glow_elevation) : 0f);
    }
}
