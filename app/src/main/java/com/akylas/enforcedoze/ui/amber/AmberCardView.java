package com.akylas.enforcedoze.ui.amber;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.card.MaterialCardView;

/**
 * Amber card: squircle corners at the radius its style resolves (24dp from {@code Widget.Amber.Card}, or a per-layout
 * {@code app:cardCornerRadius}) plus the smoked-glass {@link GlassOverlay}. Use it in place of MaterialCardView in
 * layouts.
 */
public class AmberCardView extends MaterialCardView {
    public AmberCardView(@NonNull Context context) {
        super(context);
        Amber.treat(this);
    }

    public AmberCardView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        Amber.treat(this);
    }

    public AmberCardView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        Amber.treat(this);
    }
}
