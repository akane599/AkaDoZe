package com.akylas.enforcedoze.ui.amber;

import androidx.annotation.NonNull;

import com.akylas.enforcedoze.R;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;

/** Amber finish for views built in code, where a layout can't name {@link AmberCardView}. */
public final class Amber {
    /**
     * Squircle corners at the chip's resolved size, and a pill that grows to hold one line of its text
     * (plus space_1 above and below) at large font scales. At normal scales the themed chipMinHeight wins.
     * Call after the chip's text appearance is final.
     */
    public static void treat(@NonNull Chip chip) {
        float textFit = chip.getLineHeight() + 2 * chip.getResources().getDimension(R.dimen.space_1);
        chip.setChipMinHeight(Math.max(chip.getChipMinHeight(), textFit));
        chip.setShapeAppearanceModel(SquircleShapes.squircle(chip.getShapeAppearanceModel()));
    }

    /** Squircle corners at the card's resolved size plus the {@link GlassOverlay}. Call once per card. */
    public static void treat(@NonNull MaterialCardView card) {
        card.setShapeAppearanceModel(SquircleShapes.squircle(card.getShapeAppearanceModel()));
        GlassOverlay.attach(card);
    }
}
