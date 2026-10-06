package com.akylas.enforcedoze.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DebtNoticeRulesTest {
    @Test
    public void recordOnlyWhenPostedOrAlreadyShownInApp() {
        assertFalse("Denied unseen debt remains eligible for retry", DebtRules.NoticeGate.shouldRecord(false, false));
        assertTrue("In-app debt is recorded without a post", DebtRules.NoticeGate.shouldRecord(false, true));
        assertTrue("Posted debt is recorded", DebtRules.NoticeGate.shouldRecord(true, false));
        assertTrue("Either presentation is sufficient", DebtRules.NoticeGate.shouldRecord(true, true));
    }
}
