package com.akylas.enforcedoze.ui;

import com.akylas.enforcedoze.doze.LedgerEntry;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure restoration-debt rules shared by the access card, the monitor and the debt notice (no android.*). */
public final class DebtRules {
    private DebtRules() {}

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

    /** One debt item: a controller entry is detail(feature)+target, a runtime debt is its detail. */
    public static String key(String detail, String target) {
        return target == null ? String.valueOf(detail) : detail + "|" + target;
    }

    /** Announces each debt item once until it clears, instead of on every safety check. */
    public static final class NoticeGate {
        public interface Store {
            Set<String> load();

            void save(Set<String> keys);
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
            if (!posted && !shownInApp) return false;
            notified.add(key);
            store.save(notified);
            return posted;
        }

        public void clear(String key) {
            Set<String> notified = new HashSet<>(store.load());
            if (notified.remove(key)) store.save(notified);
        }

        public void clearAll() {
            if (!store.load().isEmpty()) store.save(Collections.emptySet());
        }
    }
}
