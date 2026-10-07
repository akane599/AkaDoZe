package com.akylas.enforcedoze.ui.amber;

import android.content.Context;
import android.provider.Settings;

/**
 * Whether the user asked for reduced motion. The signal is the developer/accessibility "Animator duration scale"
 * set to off (0). It is read straight from {@link Settings.Global#ANIMATOR_DURATION_SCALE} so the answer is the
 * same on every API level ({@code ValueAnimator.areAnimatorsEnabled()} only exists from API 26).
 */
public final class MotionPolicy {
    private MotionPolicy() {
    }

    /** Pure rule: motion is reduced exactly when the animator duration scale is 0. */
    public static boolean reducedMotion(float animatorDurationScale) {
        return animatorDurationScale == 0f;
    }

    /** Reads the system animator duration scale (1 when unset) and applies {@link #reducedMotion(float)}. */
    public static boolean reducedMotion(Context context) {
        return reducedMotion(Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f));
    }
}
