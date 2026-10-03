package com.akylas.enforcedoze.ui;

import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.preference.PreferenceManager;

import com.akylas.enforcedoze.ForceDozeService;
import com.akylas.enforcedoze.MyApplication;
import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.access.AccessManager;
import com.akylas.enforcedoze.access.AccessState;
import com.akylas.enforcedoze.doze.DozeEvent;
import com.akylas.enforcedoze.doze.DozeEventSink;
import com.akylas.enforcedoze.doze.EventType;
import com.akylas.enforcedoze.doze.RestoreLedger;
import com.akylas.enforcedoze.service.DozeRuntime;

import java.util.List;

/**
 * Main-screen access card. Subscribed only between start() and stop(); event callbacks arrive on the
 * doze-worker thread and are posted to main, so nothing outlives the Activity's visible lifetime.
 */
public final class AccessCard {
    private static final long[] RESTORE_RECHECK_MS = {2_000, 6_000};

    private final Activity activity;
    private final AccessManager access;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView mode;
    private final TextView status;
    private final TextView problems;
    private final View debtCard;
    private final TextView debtText;
    private final Button restore;
    private final Button action;
    private final AccessManager.Listener accessListener = this::bind;
    private final DozeEventSink events = this::onEvent;

    private boolean started;
    private AccessState state;
    private boolean ledgerDebt;
    private String debtDetail;
    private boolean restoring;

    public AccessCard(Activity activity, AccessManager access) {
        this.activity = activity;
        this.access = access;
        mode = activity.findViewById(R.id.accessModeText);
        status = activity.findViewById(R.id.accessStatusText);
        problems = activity.findViewById(R.id.accessProblemsText);
        debtCard = activity.findViewById(R.id.accessDebtCard);
        debtText = activity.findViewById(R.id.accessDebtText);
        restore = activity.findViewById(R.id.accessRestoreButton);
        action = activity.findViewById(R.id.accessActionButton);
        ViewCompat.setAccessibilityHeading(activity.findViewById(R.id.accessCardTitle), true);
        action.setOnClickListener(v -> {
            if (state != null) {
                AccessUi.perform(activity, access, AccessUi.primaryAction(state, AccessUi.isShizukuMode(activity)));
            }
        });
        restore.setOnClickListener(v -> restoreNow());
    }

    public void start() {
        if (started) return;
        started = true;
        access.addListener(accessListener);
        ForceDozeService.addSink(activity, events);
        checkLedger();
    }

    public void stop() {
        if (!started) return;
        started = false;
        access.removeListener(accessListener);
        ForceDozeService.removeSink(activity, events);
        main.removeCallbacksAndMessages(null);
    }

    /** Asks the runtime to reconcile the ledger (the service's restore path), then re-reads it. */
    public void restoreNow() {
        debtDetail = null;
        restoring = true;
        renderDebt();
        ForceDozeService.requestSafetyCheck(activity);
        for (long delay : RESTORE_RECHECK_MS) main.postDelayed(this::checkLedger, delay);
    }

    private void onEvent(DozeEvent event) {
        EventType type = event.getType();
        if (type == EventType.RECOVERY_DEBT) {
            String detail = event.getDetail();
            main.post(() -> {
                if (!started) return;
                debtDetail = detail;
                restoring = false;
                renderDebt();
            });
        } else if (type == EventType.SENSORS_RESTORED || type == EventType.RESTORE_FAILED || type == EventType.VERIFY) {
            main.post(() -> { if (started) checkLedger(); });
        }
    }

    private void bind(AccessState next) {
        if (!started) return;
        state = next;
        boolean shizukuMode = AccessUi.isShizukuMode(activity);
        mode.setText(activity.getString(R.string.access_mode_line, AccessUi.modeLabel(activity, next, shizukuMode)));
        status.setText(AccessUi.statusText(activity, next, shizukuMode));
        boolean music = PreferenceManager.getDefaultSharedPreferences(activity).getBoolean("whitelistMusicAppNetwork", false);
        List<String> list = AccessUi.problems(activity, next, shizukuMode, music);
        problems.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        problems.setText(TextUtils.join("\n", list));
        action.setText(AccessUi.actionLabel(AccessUi.primaryAction(next, shizukuMode)));
        renderDebt();
    }

    /** Remaining ledger entries outside an active session are restoration debt (fail closed on load errors). */
    private void checkLedger() {
        if (!started) return;
        Context app = activity.getApplicationContext();
        AsyncTask.execute(() -> {
            DozeRuntime runtime = MyApplication.getDozeRuntime(app);
            boolean debt;
            try {
                RestoreLedger ledger = runtime.getStore().load();
                debt = !ledger.getEntries().isEmpty() || !runtime.getStore().getCorruptLines().isEmpty();
            } catch (Exception unreadable) {
                debt = true;
            }
            boolean result = debt && !runtime.getSessionActive();
            main.post(() -> {
                if (!started) return;
                ledgerDebt = result;
                restoring = false;
                // Event-raised debt (e.g. SafetyNet RAISE_DEBT) is not ledger-backed; only Restore clears it.
                if (!result && debtDetail == null) NoticeSink.cancelDebt(app);
                renderDebt();
            });
        });
    }

    private void renderDebt() {
        boolean visible = restoring || ledgerDebt || debtDetail != null;
        debtCard.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        boolean privileged = state != null && AccessUi.isPrivileged(state);
        String text;
        if (restoring && debtDetail == null) {
            text = activity.getString(R.string.access_restoring);
        } else {
            text = activity.getString("ACCESS_LOST".equals(debtDetail)
                    ? R.string.access_debt_access_lost : R.string.access_debt_generic);
            if (!privileged) text = text + "\n" + activity.getString(R.string.access_debt_needs_access);
        }
        debtText.setText(text);
        restore.setEnabled(privileged && !(restoring && debtDetail == null));
    }
}
