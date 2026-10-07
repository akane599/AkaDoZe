package com.akylas.enforcedoze.ui.amber;

import android.content.Context;
import android.content.res.TypedArray;
import android.view.View;

import androidx.annotation.AttrRes;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.akylas.enforcedoze.R;

/**
 * Theme-tuned spring animations for view properties. Damping and stiffness come from the Amber theme attrs
 * ({@code amberSpringDamping}, {@code amberSpringStiffnessLow|Medium|High}).
 *
 * <p>androidx.dynamicanimation 1.0.0 does not honour the animator duration scale: its AnimationHandler drives
 * frames from Choreographer with raw frame deltas and never reads ANIMATOR_DURATION_SCALE (checked with javap on
 * the 1.0.0 classes). So reduced motion is applied here: the property jumps straight to its target.
 *
 * <p>Must be called on the main thread, like any {@link SpringAnimation#start()}.
 */
public final class Springs {
    private Springs() {
    }

    /** Springs {@code property} of {@code view} to {@code target} with the theme's medium stiffness. */
    @Nullable
    public static SpringAnimation animate(View view, DynamicAnimation.ViewProperty property, float target) {
        return animate(view, property, target, R.attr.amberSpringStiffnessMedium);
    }

    /**
     * Springs {@code property} of {@code view} to {@code target} with the stiffness held by {@code stiffnessAttr},
     * one of {@code R.attr.amberSpringStiffnessLow}, {@code ...Medium} or {@code ...High}.
     *
     * @return the started animation, or {@code null} when reduced motion snapped the property to {@code target}
     */
    @Nullable
    public static SpringAnimation animate(View view, DynamicAnimation.ViewProperty property, float target,
            @AttrRes int stiffnessAttr) {
        Context context = view.getContext();
        if (MotionPolicy.reducedMotion(context)) {
            property.setValue(view, target);
            return null;
        }
        SpringAnimation animation = new SpringAnimation(view, property, target);
        animation.getSpring()
                .setDampingRatio(themeFloat(context, R.attr.amberSpringDamping, SpringForce.DAMPING_RATIO_NO_BOUNCY))
                .setStiffness(themeFloat(context, stiffnessAttr, SpringForce.STIFFNESS_MEDIUM));
        animation.start();
        return animation;
    }

    private static float themeFloat(Context context, @AttrRes int attr, float fallback) {
        TypedArray values = context.obtainStyledAttributes(new int[] {attr});
        try {
            return values.getFloat(0, fallback);
        } finally {
            values.recycle();
        }
    }
}
