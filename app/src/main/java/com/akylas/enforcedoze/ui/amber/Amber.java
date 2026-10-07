package com.akylas.enforcedoze.ui.amber;

import androidx.annotation.NonNull;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;

/** Amber finish for views built in code, where a layout can't name {@link AmberCardView}. */
public final class Amber {
    /** Squircle corners at the chip's resolved size. */
    public static void treat(@NonNull Chip chip) {
        chip.setShapeAppearanceModel(SquircleShapes.squircle(chip.getShapeAppearanceModel()));
    }

    /** Squircle corners at the card's resolved size plus the {@link GlassOverlay}. Call once per card. */
    public static void treat(@NonNull MaterialCardView card) {
        card.setShapeAppearanceModel(SquircleShapes.squircle(card.getShapeAppearanceModel()));
        GlassOverlay.attach(card);
    }
}
