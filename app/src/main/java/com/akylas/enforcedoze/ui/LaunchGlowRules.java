package com.akylas.enforcedoze.ui;

/**
 * Pure rules for the main screen's launch glow (no android.*), so they can be JVM-tested: when it plays, and the
 * shape of its 500 ms curve. MainActivity only wires these into a full-bleed overlay and a ValueAnimator.
 *
 * <p>The glow is a radial {@code ?attr/colorPrimary} gradient centred on the bottom edge. Its alpha rises from 0 to
 * {@link #PEAK_ALPHA} and falls back to 0, while it slides up from below the bottom edge.
 */
public final class LaunchGlowRules {
    /** Total length of the glow, rise and fade together. */
    public static final long DURATION_MS = 500L;
    /**
     * Highest overlay alpha, reached at {@link #PEAK_AT}. The radial gradient already fades to nothing towards its
     * rim, so on the near-black background a lower peak (0.35) read as no glow at all.
     */
    public static final float PEAK_ALPHA = 0.55f;
    /** Fraction of the duration at which the alpha peaks: a quick rise, then a longer fade. */
    public static final float PEAK_AT = 0.4f;
    /** Gradient radius as a fraction of the overlay height. */
    public static final float RADIUS_OF_HEIGHT = 0.9f;
    /** How far below its resting place the glow starts, as a fraction of the overlay height. */
    public static final float RISE_OF_HEIGHT = 0.2f;

    private LaunchGlowRules() { throw new AssertionError(); }

    /**
     * Plays only on a fresh start (no saved state), on the first MainActivity launch in this process, and never
     * under reduced motion.
     */
    public static boolean shouldPlay(boolean savedInstanceStateNull, boolean firstLaunchInProcess,
                                     boolean reducedMotion) {
        return savedInstanceStateNull && firstLaunchInProcess && !reducedMotion;
    }

    /**
     * The glow decided in onCreate starts on the first enter-animation-complete callback after it, once: never when
     * none is pending, and never again once an animator was started (returning to the screen fires that callback
     * too).
     */
    public static boolean startNow(boolean pending, boolean alreadyStarted) {
        return pending && !alreadyStarted;
    }

    /** Overlay alpha at {@code fraction} (0..1) of the duration: 0, linearly up to the peak, linearly back to 0. */
    public static float alpha(float fraction) {
        return fraction < PEAK_AT
                ? PEAK_ALPHA * fraction / PEAK_AT
                : PEAK_ALPHA * (1f - fraction) / (1f - PEAK_AT);
    }

    /** Downward offset at {@code fraction}, as a fraction of the overlay height: decelerates from the rise to 0. */
    public static float offset(float fraction) {
        float left = 1f - fraction;
        return RISE_OF_HEIGHT * left * left;
    }

    /** Gradient radius in pixels for an overlay {@code heightPx} tall. */
    public static float radius(int heightPx) {
        return RADIUS_OF_HEIGHT * heightPx;
    }
}
