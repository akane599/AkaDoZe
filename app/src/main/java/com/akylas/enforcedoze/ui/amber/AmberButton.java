package com.akylas.enforcedoze.ui.amber;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

/**
 * Amber button: squircle corners at the size its style resolves (16dp from {@code Widget.Amber.Button}). The
 * footprint clamps to half the button's height. Use it in place of Button/MaterialButton in layouts.
 */
public class AmberButton extends MaterialButton {
    public AmberButton(@NonNull Context context) {
        super(context);
        squircle();
    }

    public AmberButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        squircle();
    }

    public AmberButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        squircle();
    }

    private void squircle() {
        setShapeAppearanceModel(SquircleShapes.squircle(getShapeAppearanceModel()));
    }
}
