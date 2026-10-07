package com.akylas.enforcedoze.ui.amber;

import com.google.android.material.shape.ShapeAppearanceModel;

/**
 * Squircle geometry for the Amber kit: Figma-style corner smoothing (a continuous-curvature corner built from two
 * cubics around a shortened circular arc), plus nested-radius and clamp math. Everything except the two
 * {@link ShapeAppearanceModel} builders is plain math and runs on the JVM.
 */
public final class SquircleShapes {
    /** Figma's "iOS" corner smoothing. */
    public static final float SMOOTHING = 0.6f;

    private SquircleShapes() {
    }

    /** Radius of a corner nested {@code padding} inside an {@code outer} corner, never negative. */
    public static float innerRadius(float outer, float padding) {
        return Math.max(0f, outer - padding);
    }

    /**
     * Distance from the corner along each edge that the smoothed corner occupies: {@code radius * (1 + smoothing)},
     * clamped to half the shorter side so opposite corners never overlap (a 16dp corner on a 40dp button stops at
     * 20dp).
     */
    public static float footprint(float radius, float smoothing, float width, float height) {
        float budget = budget(width, height);
        return Math.min((1f + smoothing) * Math.min(radius, budget), budget);
    }

    /**
     * The top-left corner of a {@code width x height} shape, traced from the left edge at {@code (0, footprint)} to
     * the top edge at {@code (footprint, 0)}. When the footprint is clamped, smoothing drops so the corner still fits.
     */
    public static Corner corner(float radius, float smoothing, float width, float height) {
        float budget = budget(width, height);
        double r = Math.max(0f, Math.min(radius, budget));
        double p = footprint((float) r, smoothing, width, height);
        double s = r > 0 ? p / r - 1 : 0;
        double sweep = 90 * (1 - s);
        double arcLength = Math.sin(Math.toRadians(sweep / 2)) * r * Math.sqrt(2);
        double beta = Math.toRadians(45 * s);
        double c = r * Math.tan(beta / 2) * Math.cos(beta);
        double d = c * Math.tan(beta);
        double b = (p - arcLength - c - d) / 3;
        double a = 2 * b;
        float[] points = {
                0, (float) p,
                0, (float) (p - a),
                0, (float) (p - a - b),
                (float) d, (float) (p - a - b - c),
                (float) (p - a - b - c), (float) d,
                (float) (p - a - b), 0,
                (float) (p - a), 0,
                (float) p, 0,
        };
        return new Corner((float) r, (float) sweep, points);
    }

    /** Every corner squircle with an absolute {@code radiusPx}. */
    public static ShapeAppearanceModel model(float radiusPx) {
        return squircle(ShapeAppearanceModel.builder().setAllCornerSizes(radiusPx).build());
    }

    /**
     * {@code base} with squircle corners, keeping its corner sizes. Views resolve their radius from their style through
     * their shape appearance ({@code MaterialButton.getCornerRadius()} is 0 unless {@code app:cornerRadius} is set),
     * so the sizes are taken from the model rather than from a radius getter.
     */
    public static ShapeAppearanceModel squircle(ShapeAppearanceModel base) {
        return base.toBuilder().setAllCorners(new SquircleCornerTreatment()).build();
    }

    private static float budget(float width, float height) {
        return Math.max(0f, Math.min(width, height) / 2f);
    }

    /** One traced top-left corner. The arc is centred on {@code (radius, radius)}. */
    public static final class Corner {
        /** Arc radius after clamping. */
        public final float radius;
        /** Arc sweep in degrees, 90 when unsmoothed. */
        public final float sweep;
        /**
         * Sixteen floats, as x/y pairs: start, cubic controls 1 and 2, arc start, arc end, cubic controls 3 and 4, end.
         */
        public final float[] points;

        Corner(float radius, float sweep, float[] points) {
            this.radius = radius;
            this.sweep = sweep;
            this.points = points;
        }

        /** Arc start angle in degrees (clockwise from +x, y down); the arc is centred on the 225 degree diagonal. */
        public float startAngle() {
            return 225f - sweep / 2f;
        }
    }
}
