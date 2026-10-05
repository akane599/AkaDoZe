package com.akylas.enforcedoze.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AccessCardRestoreStateTest {
    @Test
    public void preRestoreStartReadCannotSettleTheNewRequest() {
        AccessCard.RestoreState state = new AccessCard.RestoreState();
        long startRead = state.beginRead();
        state.beginRestore();
        assertFalse("pre-request snapshot must be rejected", state.completeRead(startRead, false));
        assertTrue("stale start callback cannot clear progress", state.isRestoring());
    }

    @Test
    public void startDuringRestoreRefreshesDebtWithoutEndingProgress() {
        AccessCard.RestoreState state = new AccessCard.RestoreState();
        state.beginRestore();
        long restartedScreenRead = state.beginRead();
        assertTrue("current observational snapshot may refresh debt",
                state.completeRead(restartedScreenRead, false));
        assertTrue("start refresh is not a restore completion", state.isRestoring());
    }

    @Test
    public void oldRestoreRecheckCannotCompleteANewerRestore() {
        AccessCard.RestoreState state = new AccessCard.RestoreState();
        state.beginRestore();
        long previousRecheck = state.beginRead();
        state.beginRestore();
        assertFalse("old request callback rejected", state.completeRead(previousRecheck, true));
        assertTrue(state.isRestoring());
        long currentRecheck = state.beginRead();
        assertTrue("current recheck accepted", state.completeRead(currentRecheck, true));
        assertFalse("existing bounded recheck still settles progress", state.isRestoring());
    }

    @Test
    public void recoveryDebtStillEndsProgress() {
        AccessCard.RestoreState state = new AccessCard.RestoreState();
        state.beginRestore();
        state.onRecoveryDebt();
        assertFalse(state.isRestoring());
    }
}
