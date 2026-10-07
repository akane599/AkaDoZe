package com.akylas.enforcedoze.ui.amber;

import android.annotation.SuppressLint;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * Light confirmation haptics. Uses the view's default flags, so the system "touch feedback" setting and the
 * view's {@code hapticFeedbackEnabled} are respected.
 */
public final class Haptics {
    /** A short tick: CONFIRM on API 30+, CONTEXT_CLICK below. Returns whether feedback was performed. */
    public static boolean tick(View view) {
        return view.performHapticFeedback(constantFor(Build.VERSION.SDK_INT));
    }

    /** The tick's feedback constant on {@code sdk}: CONFIRM exists from API 30, CONTEXT_CLICK from 23. */
    @SuppressLint("InlinedApi")
    static int constantFor(int sdk) {
        return sdk >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.CONTEXT_CLICK;
    }
}
