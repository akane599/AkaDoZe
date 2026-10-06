package com.akylas.enforcedoze.ui;

import com.akylas.enforcedoze.monitor.EventCodes;
import com.akylas.enforcedoze.service.DebtNoticeStore;

import org.junit.Test;

import static com.akylas.enforcedoze.ui.DebtRules.LedgerState.*;
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
    public void emptyLedgerSettlesStarvedWindowAndRearmsLaterStarvation() {
        DebtNoticeStore store = new DebtNoticeStore();
        DebtRules.NoticeGate gate = new DebtRules.NoticeGate(store);
        assertTrue(gate.offer(EventCodes.RESTORE_WINDOW_STARVED, false, store::post));
        gate.ledgerChecked(EMPTY);
        assertEquals("Empty ledger cancels the starved-window notice", 1, store.cancels);
        assertTrue("Starved key no longer blocks other cancellations", store.load().isEmpty());
        assertFalse(store.posted());
        gate.ledgerChecked(EMPTY);
        assertEquals("A settled notice cancels only once", 1, store.cancels);
        assertTrue("A later starvation is not silently deduped",
                gate.offer(EventCodes.RESTORE_WINDOW_STARVED, false, store::post));
    }

    @Test
    public void cleanPendingIntentSettlesOtherLedgerKeysButNotStarvation() {
        DebtNoticeStore store = new DebtNoticeStore();
        DebtRules.NoticeGate gate = new DebtRules.NoticeGate(store);
        gate.offer(EventCodes.RESTORE_WINDOW_STARVED, false, store::post);
        gate.offer(EventCodes.LEDGER_LOAD_FAILED, false, store::post);
        gate.offer(EventCodes.TEARDOWN_TIMEOUT, false, store::post);
        gate.offer(EventCodes.SAFETY_READ_UNAVAILABLE, false, store::post);
        gate.ledgerChecked(DEBT);
        assertEquals("Unreadable or damaged ledger cannot settle starvation", 0, store.cancels);
        assertEquals(4, store.load().size());
        gate.ledgerChecked(CLEAN);
        assertEquals("Clean is not empty: starvation must remain", 0, store.cancels);
        assertEquals(java.util.Collections.singleton(EventCodes.RESTORE_WINDOW_STARVED), store.load());
        assertTrue(store.posted());
        gate.ledgerChecked(EMPTY);
        assertEquals("Empty intent settles the last starvation key", 1, store.cancels);
        assertTrue(store.load().isEmpty());
        assertFalse(store.posted());
    }

    @Test
    public void debtOnlyAndUiChecksPreserveStarvationKey() {
        DebtNoticeStore store = new DebtNoticeStore();
        DebtRules.NoticeGate gate = new DebtRules.NoticeGate(store);
        gate.offer(EventCodes.RESTORE_WINDOW_STARVED, false, store::post);
        gate.ledgerChecked(false);
        gate.rearmFromLedger(false);
        assertFalse("UI cannot clear unknown pending intent", gate.clearFromUi());
        assertTrue(store.load().contains(EventCodes.RESTORE_WINDOW_STARVED));
        assertEquals(0, store.cancels);
        assertFalse("Pending starvation remains deduped", gate.offer(EventCodes.RESTORE_WINDOW_STARVED, false, store::post));
        gate.ledgerChecked(EMPTY);
        assertTrue("Ordinary UI clearing is unchanged after starvation settles", gate.clearFromUi());
    }
}
