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

import java.util.HashMap;
import java.util.Map;

/**
 * Theme-tuned spring animations for view properties. Damping and stiffness come from the Amber theme attrs
 * ({@code amberSpringDamping}, {@code amberSpringStiffnessLow|Medium|High}).
 *
 * <p>androidx.dynamicanimation 1.0.0 does not honour the animator duration scale: its AnimationHandler drives
 * frames from Choreographer with raw frame deltas and never reads ANIMATOR_DURATION_SCALE (checked with javap on
 * the 1.0.0 classes). So reduced motion is applied here: the property jumps straight to its target.
 *
 * <p>Each (view, property) pair owns one {@link SpringAnimation}, kept in the view's {@code R.id.amber_springs} tag.
 * A new call retargets that spring instead of starting a second one, so the last call's target always wins.
 *
 * <p>Must be called on the main thread, like any {@link SpringAnimation#start()}.
 */
public final class Springs {
    /** Springs {@code property} of {@code view} to {@code target} with the theme's medium stiffness. */
    @Nullable
    public static SpringAnimation animate(View view, DynamicAnimation.ViewProperty property, float target) {
        return animate(view, property, target, R.attr.amberSpringStiffnessMedium);
    }

    /**
     * Springs {@code property} of {@code view} to {@code target} with the stiffness held by {@code stiffnessAttr},
     * one of {@code R.attr.amberSpringStiffnessLow}, {@code ...Medium} or {@code ...High}.
     *
     * @return the pair's running animation, or {@code null} when reduced motion snapped the property to
     *     {@code target} (cancelling any spring still running on it)
     */
    @Nullable
    public static SpringAnimation animate(View view, DynamicAnimation.ViewProperty property, float target,
            @AttrRes int stiffnessAttr) {
        Context context = view.getContext();
        Map<DynamicAnimation.ViewProperty, SpringAnimation> springs = springsOf(view);
        if (MotionPolicy.reducedMotion(context)) {
            snap(springs.get(property), view, property, target);
            return null;
        }
        SpringAnimation animation = springs.get(property);
        if (animation == null) {
            animation = new SpringAnimation(view, property, target);
            springs.put(property, animation);
        }
        animation.getSpring()
                .setDampingRatio(themeFloat(context, R.attr.amberSpringDamping, SpringForce.DAMPING_RATIO_NO_BOUNCY))
                .setStiffness(themeFloat(context, stiffnessAttr, SpringForce.STIFFNESS_MEDIUM));
        animation.animateToFinalPosition(target);
        return animation;
    }

    /** Stops the pair's running spring, if any, so it can't overwrite the snapped value on its next frame. */
    private static void snap(@Nullable SpringAnimation running, View view, DynamicAnimation.ViewProperty property,
            float target) {
        if (running != null) {
            running.cancel();
        }
        property.setValue(view, target);
    }

    /** The view's per-property springs, created on first use. */
    @SuppressWarnings("unchecked")
    private static Map<DynamicAnimation.ViewProperty, SpringAnimation> springsOf(View view) {
        Object tag = view.getTag(R.id.amber_springs);
        if (tag != null) {
            return (Map<DynamicAnimation.ViewProperty, SpringAnimation>) tag;
        }
        Map<DynamicAnimation.ViewProperty, SpringAnimation> springs = new HashMap<>();
        view.setTag(R.id.amber_springs, springs);
        return springs;
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
