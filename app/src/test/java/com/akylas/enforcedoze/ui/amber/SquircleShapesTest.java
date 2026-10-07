package com.akylas.enforcedoze.ui.amber;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Squircle corner geometry, nested radii and the footprint clamp. */
public class SquircleShapesTest {
    private static final float EPS = 1e-3f;
    private static final float S = SquircleShapes.SMOOTHING;

    @Test
    public void innerRadiusIsOuterMinusPaddingNeverNegative() {
        assertEquals(8f, SquircleShapes.innerRadius(24f, 16f), EPS);
        assertEquals(0f, SquircleShapes.innerRadius(24f, 24f), EPS);
        assertEquals(0f, SquircleShapes.innerRadius(8f, 16f), EPS);
    }

    @Test
    public void footprintGrowsBySmoothingWhenThereIsRoom() {
        assertEquals(38.4f, SquircleShapes.footprint(24f, S, 300f, 200f), EPS);
    }

    @Test
    public void footprintOfA16dpCornerOnA40dpButtonStopsAtHalfItsHeight() {
        // 16 * 1.6 = 25.6 would overflow; opposite corners meet at 20.
        assertEquals(20f, SquircleShapes.footprint(16f, S, 200f, 40f), EPS);
    }

    @Test
    public void footprintClampsOversizedRadiusAndEmptyBounds() {
        assertEquals(20f, SquircleShapes.footprint(30f, S, 40f, 40f), EPS);
        assertEquals(0f, SquircleShapes.footprint(24f, S, 0f, 0f), EPS);
    }

    @Test
    public void unclampedCornerRunsFromLeftEdgeToTopEdgeWithA36DegreeArc() {
        SquircleShapes.Corner corner = SquircleShapes.corner(24f, S, 300f, 200f);
        float[] p = corner.points;
        assertEquals(24f, corner.radius, EPS);
        assertEquals(36f, corner.sweep, EPS);
        assertPoint(p, 0, 0f, 38.4f);
        assertPoint(p, 7, 38.4f, 0f);
        assertArcOnCircle(corner);
    }

    @Test
    public void clampedButtonCornerDropsSmoothingToFit() {
        // 20 / 16 - 1 = 0.25 smoothing left, so the arc sweeps 90 * 0.75.
        SquircleShapes.Corner corner = SquircleShapes.corner(16f, S, 200f, 40f);
        assertEquals(16f, corner.radius, EPS);
        assertEquals(67.5f, corner.sweep, EPS);
        assertPoint(corner.points, 0, 0f, 20f);
        assertPoint(corner.points, 7, 20f, 0f);
        assertArcOnCircle(corner);
    }

    @Test
    public void radiusAtHalfTheSideIsAPlainQuarterCircle() {
        SquircleShapes.Corner corner = SquircleShapes.corner(50f, S, 40f, 40f);
        assertEquals(20f, corner.radius, EPS);
        assertEquals(90f, corner.sweep, EPS);
        assertPoint(corner.points, 3, 0f, 20f);
        assertPoint(corner.points, 4, 20f, 0f);
    }

    @Test
    public void zeroRadiusCollapsesToTheCornerPoint() {
        SquircleShapes.Corner corner = SquircleShapes.corner(0f, S, 300f, 200f);
        assertEquals(0f, corner.radius, EPS);
        for (float v : corner.points) {
            assertEquals(0f, v, EPS);
        }
    }

    @Test
    public void cornerIsSymmetricAboutItsDiagonalAndMonotoneAlongEachEdge() {
        float[][] cases = {{24f, 300f, 200f}, {16f, 200f, 40f}, {8f, 100f, 100f}, {30f, 40f, 40f}};
        for (float[] c : cases) {
            float[] p = SquircleShapes.corner(c[0], S, c[1], c[2]).points;
            for (int k = 0; k < 8; k++) {
                assertEquals(p[2 * k], p[2 * (7 - k) + 1], EPS);
                assertEquals(p[2 * k + 1], p[2 * (7 - k)], EPS);
            }
            assertTrue(p[3] <= p[1] && p[5] <= p[3] && p[7] <= p[5]);
            assertTrue(p[6] >= 0f && p[6] <= p[8]);
        }
    }

    private static void assertPoint(float[] points, int index, float x, float y) {
        assertEquals(x, points[2 * index], EPS);
        assertEquals(y, points[2 * index + 1], EPS);
    }

    /** Arc start and end sit on the circle centred at (r, r), at the start angle and start + sweep. */
    private static void assertArcOnCircle(SquircleShapes.Corner corner) {
        float r = corner.radius;
        double start = Math.toRadians(corner.startAngle());
        double end = Math.toRadians(corner.startAngle() + corner.sweep);
        assertPoint(corner.points, 3, (float) (r + r * Math.cos(start)), (float) (r + r * Math.sin(start)));
        assertPoint(corner.points, 4, (float) (r + r * Math.cos(end)), (float) (r + r * Math.sin(end)));
    }
}
