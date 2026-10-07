package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The launch glow's play rule and 500 ms curve, on the plain JVM. */
public class LaunchGlowRulesTest {
    private static final boolean[] BOOLS = {false, true};
    private static final float EPS = 1e-6f;

    @Test
    public void playsOnlyOnAFreshFirstLaunchWithMotionOn() {
        for (boolean fresh : BOOLS) {
            for (boolean first : BOOLS) {
                for (boolean reduced : BOOLS) {
                    assertEquals("fresh=" + fresh + " first=" + first + " reduced=" + reduced,
                            fresh && first && !reduced, LaunchGlowRules.shouldPlay(fresh, first, reduced));
                }
            }
        }
        assertTrue(LaunchGlowRules.shouldPlay(true, true, false));
        assertFalse("restored state never replays it", LaunchGlowRules.shouldPlay(false, true, false));
        assertFalse("a later launch in the same process skips it", LaunchGlowRules.shouldPlay(true, false, false));
        assertFalse("reduced motion skips it", LaunchGlowRules.shouldPlay(true, true, true));
    }

    @Test
    public void startsOnlyAPendingGlowThatHasNotStartedYet() {
        for (boolean pending : BOOLS) {
            for (boolean started : BOOLS) {
                assertEquals("pending=" + pending + " started=" + started,
                        pending && !started, LaunchGlowRules.startNow(pending, started));
            }
        }
        assertTrue("first enter-animation-complete starts the glow decided in onCreate",
                LaunchGlowRules.startNow(true, false));
        assertFalse("a later enter-animation-complete never replays it", LaunchGlowRules.startNow(true, true));
        assertFalse("nothing pending, nothing starts", LaunchGlowRules.startNow(false, false));
    }

    @Test
    public void peaksHighEnoughToReadOnTheDarkBackground() {
        assertEquals(0.55f, LaunchGlowRules.PEAK_ALPHA, EPS);
    }

    @Test
    public void lastsHalfASecond() {
        assertEquals(500L, LaunchGlowRules.DURATION_MS);
    }

    @Test
    public void alphaRisesFromZeroToThePeakAndFadesBackToZero() {
        assertEquals(0f, LaunchGlowRules.alpha(0f), EPS);
        assertEquals(0.55f, LaunchGlowRules.alpha(LaunchGlowRules.PEAK_AT), EPS);
        assertEquals(0f, LaunchGlowRules.alpha(1f), EPS);
        assertEquals(0.275f, LaunchGlowRules.alpha(0.2f), EPS);
        assertEquals(0.275f, LaunchGlowRules.alpha(0.7f), EPS);
        float previous = -1f;
        for (int step = 0; step <= 40; step++) {
            float alpha = LaunchGlowRules.alpha(step / 100f);
            assertTrue("rising at " + step, alpha > previous);
            previous = alpha;
        }
        for (int step = 41; step <= 100; step++) {
            float alpha = LaunchGlowRules.alpha(step / 100f);
            assertTrue("fading at " + step, alpha < previous);
            assertTrue("never above the peak", alpha <= LaunchGlowRules.PEAK_ALPHA + EPS);
            previous = alpha;
        }
    }

    @Test
    public void risesFromBelowTheBottomEdgeAndSettles() {
        assertEquals(0.2f, LaunchGlowRules.offset(0f), EPS);
        assertEquals(0.05f, LaunchGlowRules.offset(0.5f), EPS);
        assertEquals(0f, LaunchGlowRules.offset(1f), EPS);
        float previous = Float.MAX_VALUE;
        for (int step = 0; step <= 100; step++) {
            float offset = LaunchGlowRules.offset(step / 100f);
            assertTrue("moving up at " + step, offset < previous);
            previous = offset;
        }
    }

    @Test
    public void radiusIsNineTenthsOfTheHeight() {
        assertEquals(1800f, LaunchGlowRules.radius(2000), EPS);
        assertEquals(0f, LaunchGlowRules.radius(0), EPS);
    }
}
