package com.akylas.enforcedoze.ui.amber;

import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * Light confirmation haptics. Uses the view's default flags, so the system "touch feedback" setting and the
 * view's {@code hapticFeedbackEnabled} are respected.
 */
public final class Haptics {
    private Haptics() {
    }

    /** A short tick: CONFIRM on API 30+, CONTEXT_CLICK below. Returns whether feedback was performed. */
    public static boolean tick(View view) {
        return view.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.CONTEXT_CLICK);
    }
}
