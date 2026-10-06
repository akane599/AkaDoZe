package com.akylas.enforcedoze.ui;

import com.akylas.enforcedoze.monitor.EventCodes;
import com.akylas.enforcedoze.service.DebtNoticeStore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void cleanLedgerSettlesStarvedWindowAndRearmsLaterStarvation() {
        DebtNoticeStore store = new DebtNoticeStore();
        DebtRules.NoticeGate gate = new DebtRules.NoticeGate(store);
        assertTrue(gate.offer(EventCodes.RESTORE_WINDOW_STARVED, false, store::post));
        gate.ledgerChecked(false);
        assertEquals("Clean ledger cancels the starved-window notice", 1, store.cancels);
        assertTrue("Starved key no longer blocks other cancellations", store.load().isEmpty());
        assertFalse(store.posted());
        gate.ledgerChecked(false);
        assertEquals("A settled notice cancels only once", 1, store.cancels);
        assertTrue("A later starvation is not silently deduped",
                gate.offer(EventCodes.RESTORE_WINDOW_STARVED, false, store::post));
    }

    @Test
    public void cleanLedgerSettlesStarvedWindowAlongsideAnotherLedgerKey() {
        DebtNoticeStore store = new DebtNoticeStore();
        DebtRules.NoticeGate gate = new DebtRules.NoticeGate(store);
        gate.offer(EventCodes.RESTORE_WINDOW_STARVED, false, store::post);
        gate.offer(EventCodes.LEDGER_LOAD_FAILED, false, store::post);
        gate.ledgerChecked(true);
        assertEquals("Unreadable or damaged ledger cannot settle starvation", 0, store.cancels);
        gate.ledgerChecked(false);
        assertEquals("All ledger-backed keys settle together", 1, store.cancels);
        assertTrue(store.load().isEmpty());
        assertFalse(store.posted());
    }
}
