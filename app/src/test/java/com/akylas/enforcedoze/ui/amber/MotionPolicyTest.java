package com.akylas.enforcedoze.ui.amber;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Reduced motion is exactly "animator duration scale is off". */
public class MotionPolicyTest {
    @Test
    public void zeroScaleIsReducedMotion() {
        assertTrue(MotionPolicy.reducedMotion(0f));
        assertTrue("negative zero compares equal to 0", MotionPolicy.reducedMotion(-0f));
    }

    @Test
    public void anyNonZeroScaleKeepsMotion() {
        assertFalse("default", MotionPolicy.reducedMotion(1f));
        assertFalse("0.5x", MotionPolicy.reducedMotion(0.5f));
        assertFalse("10x", MotionPolicy.reducedMotion(10f));
        assertFalse("tiny but not off", MotionPolicy.reducedMotion(Float.MIN_VALUE));
    }
}
