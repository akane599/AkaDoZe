package com.akylas.enforcedoze.ui;

import com.akylas.enforcedoze.monitor.EventCodes;

import com.akylas.enforcedoze.access.Feature;
import com.akylas.enforcedoze.doze.CorruptLedgerLine;
import com.akylas.enforcedoze.doze.LedgerEntry;
import com.akylas.enforcedoze.service.LedgerRecovery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure restoration-debt rules shared by the access card, the monitor and the debt notice (no android.*). */
public final class DebtRules {
    private DebtRules() { throw new AssertionError(); }

    /**
     * Debt is what a restore attempt has already failed on (debt flag or attempts &gt; 0), or a damaged
     * ledger. Entries that were never attempted belong to a session or an exit still in flight.
     */
    public static boolean isDebt(List<LedgerEntry> entries, boolean damaged, boolean sessionActive) {
        if (sessionActive) return false;
        if (damaged) return true;
        for (LedgerEntry entry : entries) {
            if (entry.getDebt() || entry.getAttempts() > 0) return true;
        }
        return false;
    }

    /** CLEAN is readable and undamaged with no attempted/debt-flagged entries; it need not be empty. */
    public enum LedgerState { DEBT, CLEAN, EMPTY }

    /** Runtime evidence without UI session suppression; damaged/unreadable intent never settles. */
    public static LedgerState ledgerState(List<LedgerEntry> entries, boolean damaged) {
        if (isDebt(entries, damaged, false)) return LedgerState.DEBT;
        return entries.isEmpty() ? LedgerState.EMPTY : LedgerState.CLEAN;
    }

    /**
     * Feature tokens of the damaged records the user may dismiss (LedgerRecovery: forced Doze and motion
     * sensor records are recovered automatically and never offered). Null stands for an unnamed record.
     * Empty when nothing can be dismissed.
     */
    public static List<String> dismissibleDamage(List<CorruptLedgerLine> corruptLines) {
        Set<String> tokens = new LinkedHashSet<>();
        for (CorruptLedgerLine line : corruptLines) {
            if (!LedgerRecovery.recoverable(line)) tokens.add(LedgerRecovery.featureToken(line));
        }
        return new ArrayList<>(tokens);
    }

    /** One debt item: a controller entry is detail(feature)+target, a runtime debt is its detail. */
    public static String key(String detail, String target) {
        return target == null ? String.valueOf(detail) : detail + "|" + target;
    }

    /** Announces each debt item once until it clears, instead of on every safety check. */
    public static final class NoticeGate {
        public interface Store {
            Set<String> load();

            void save(Set<String> keys);

            boolean posted();

            void setPosted(boolean posted);

            void cancel();
        }

        public interface Poster {
            /** Returns true when the notice was actually posted. */
            boolean post();
        }

        private final Store store;

        public NoticeGate(Store store) {
            this.store = store;
        }

        /**
         * Posts only for a debt item that has not been announced. While the app already shows the debt the
         * item is recorded without a notice; a post that was not allowed is not recorded, so it can retry.
         */
        public boolean offer(String key, boolean shownInApp, Poster poster) {
            Set<String> notified = new HashSet<>(store.load());
            if (notified.contains(key)) return false;
            boolean posted = !shownInApp && poster.post();
            if (!shouldRecord(posted, shownInApp)) return false;
            notified.add(key);
            store.save(notified);
            if (posted) store.setPosted(true);
            return posted;
        }

        /** A denied post retries later unless the app already presented this debt item. */
        static boolean shouldRecord(boolean posted, boolean shownInApp) {
            return posted || shownInApp;
        }

        public void clear(String key) {
            Set<String> notified = new HashSet<>(store.load());
            if (notified.remove(key)) store.save(notified);
        }

        public void clearAll() {
            if (!store.load().isEmpty()) store.save(Collections.emptySet());
            store.setPosted(false);
        }

        /** UI debt-free reads cannot disprove a starved window's unattempted restore intent. */
        public boolean clearFromUi() {
            if (store.load().contains(EventCodes.RESTORE_WINDOW_STARVED)) {
                rearmFromLedger(false);
                return false;
            }
            clearAll();
            return true;
        }

        /** A debt-only observation does not establish that a readable ledger is empty. */
        public void ledgerChecked(boolean debt) {
            ledgerChecked(debt ? LedgerState.DEBT : LedgerState.CLEAN);
        }

        /** Runtime settlement uses committed ledger evidence, including whether any intent remains. */
        public void ledgerChecked(LedgerState ledger) {
            if (ledger == LedgerState.DEBT) return;
            rearmFromLedger(ledger);
            cancelIfSettled();
        }

        /** UI reads suppress in-session debt: they may re-arm keys but cannot authorize cancellation. */
        public void rearmFromLedger(boolean debt) {
            rearmFromLedger(debt ? LedgerState.DEBT : LedgerState.CLEAN);
        }

        private void rearmFromLedger(LedgerState ledger) {
            if (ledger == LedgerState.DEBT) return;
            Set<String> notified = new HashSet<>(store.load());
            boolean changed = false;
            // No Collection.removeIf: minSdk 23.
            for (Iterator<String> keys = notified.iterator(); keys.hasNext(); ) {
                if (settledByLedger(keys.next(), ledger)) {
                    keys.remove();
                    changed = true;
                }
            }
            if (changed) store.save(notified);
        }

        /** VERIFY can clear the last key before persistence completes; only a ledger check cancels. */
        private void cancelIfSettled() {
            if (!store.load().isEmpty() || !store.posted()) return;
            store.cancel();
            store.setPosted(false);
        }
    }

    /** Starvation is disproved only by readable empty intent, not by a debt-free pending session. */
    static boolean settledByLedger(String key, LedgerState ledger) {
        if (EventCodes.RESTORE_WINDOW_STARVED.equals(key)) return ledger == LedgerState.EMPTY;
        return settledByCleanLedger(key);
    }

    /** Runtime debt about the ledger itself, plus every per-entry item (key(feature, target)). */
    static boolean settledByCleanLedger(String key) {
        int bar = key.indexOf('|');
        String detail = bar < 0 ? key : key.substring(0, bar);
        if (LEDGER_KEYS.contains(detail)) return true;
        for (Feature feature : Feature.values()) {
            if (feature.name().equals(detail)) return true;
        }
        return false;
    }

    /** Runtime debts a clean ledger read disproves (MonitorData.checkDebt fails closed on load errors). */
    private static final Set<String> LEDGER_KEYS = new HashSet<>(Arrays.asList(
            EventCodes.TEARDOWN_TIMEOUT, EventCodes.LEDGER_DAMAGED, EventCodes.SAFETY_READ_UNAVAILABLE,
            EventCodes.LEDGER_LOAD_FAILED, EventCodes.LEDGER_RECOVERY_COMMIT_FAILED));
}
