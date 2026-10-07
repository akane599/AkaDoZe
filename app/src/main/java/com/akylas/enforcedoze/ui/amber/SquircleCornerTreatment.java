package com.akylas.enforcedoze.ui.amber;

import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.google.android.material.shape.CornerSize;
import com.google.android.material.shape.CornerTreatment;
import com.google.android.material.shape.ShapePath;

/**
 * Continuous-curvature (G2-approximating) corner with {@link SquircleShapes#SMOOTHING}: a cubic eases off the edge,
 * a shortened circular arc turns the corner, and a mirrored cubic eases onto the next edge. The footprint is clamped
 * to half the shorter side of the shape's bounds. Rectangles only: the corner angle is always 90 degrees.
 */
public class SquircleCornerTreatment extends CornerTreatment {
    @Override
    public void getCornerPath(@NonNull ShapePath shapePath, float angle, float interpolation, @NonNull RectF bounds,
            @NonNull CornerSize size) {
        float radius = size.getCornerSize(bounds) * interpolation;
        SquircleShapes.Corner corner =
                SquircleShapes.corner(radius, SquircleShapes.SMOOTHING, bounds.width(), bounds.height());
        float[] p = corner.points;
        shapePath.reset(p[0], p[1], 180f, 180f - angle);
        if (corner.radius <= 0f) {
            return;
        }
        float diameter = 2f * corner.radius;
        shapePath.cubicToPoint(p[2], p[3], p[4], p[5], p[6], p[7]);
        shapePath.addArc(0f, 0f, diameter, diameter, corner.startAngle(), corner.sweep);
        shapePath.cubicToPoint(p[10], p[11], p[12], p[13], p[14], p[15]);
    }
}
