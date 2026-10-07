package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** SQ-38: broadcast identifiers get a line-break opportunity after every '.' and '_', and nowhere else. */
public class BroadcastNameBreaksTest {
    private static final String Z = "​";

    @Test
    public void breaksFollowEveryDotAndUnderscore() {
        assertEquals("com." + Z + "akylas." + Z + "enforcedoze." + Z + "ENABLE_" + Z + "FORCEDOZE",
                BroadcastNameBreaks.withBreaks("com.akylas.enforcedoze.ENABLE_FORCEDOZE"));
    }

    @Test
    public void namesWithoutSeparatorsAreUnchanged() {
        assertEquals("ReenterDoze", BroadcastNameBreaks.withBreaks("ReenterDoze"));
        assertEquals("", BroadcastNameBreaks.withBreaks(""));
    }

    @Test
    public void transformationOnlyAddsBreaks() {
        CharSequence shown = new BroadcastNameBreaks().getTransformation("a.b_c", null);

        assertEquals("a." + Z + "b_" + Z + "c", shown.toString());
        assertEquals("a.b_c", shown.toString().replace(Z, ""));
    }
}
