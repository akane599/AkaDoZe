package com.akylas.enforcedoze.ui;

import com.akylas.enforcedoze.monitor.EventCodes;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.akylas.enforcedoze.ForceDozeService;
import com.akylas.enforcedoze.MainActivity;
import com.akylas.enforcedoze.MyApplication;
import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.SettingsActivity;
import com.akylas.enforcedoze.Utils;
import com.akylas.enforcedoze.access.AccessLevel;
import com.akylas.enforcedoze.access.Grants;
import com.akylas.enforcedoze.doze.DozeEvent;
import com.akylas.enforcedoze.doze.DozeEventSink;
import com.akylas.enforcedoze.access.Prefs;
import com.akylas.enforcedoze.doze.EventType;
import com.akylas.enforcedoze.monitor.JournalEvent;
import com.akylas.enforcedoze.monitor.SessionAggregator;
import com.akylas.enforcedoze.monitor.SessionSummary;
import com.akylas.enforcedoze.service.JournalSink;
import com.akylas.enforcedoze.service.SessionAccess;
import com.akylas.enforcedoze.service.SessionMode;

import android.os.AsyncTask;

import androidx.preference.PreferenceManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * App-lifetime presentation of service events as notifications on the existing tips channel.
 * Holds only the application context. emit() runs on the emitting (worker/receiver) thread and touches
 * no UI state; every notification is gated on the user's notification permission.
 */
public final class NoticeSink implements DozeEventSink {
    private static final int ID_ACCESS = 8801;
    private static final int ID_DEBT = 8802;
    private static final int ID_EXTERNAL = 8803;
    private static final int ID_START_DENIED = 8804;
    private static final String NOTICES = "notices";
    private static final String SHOWN_BASIC = "externalBasicRejectedShown";
    private static final String SHOWN_PRIVILEGED = "externalPrivilegedRejectedShown";

    private static final String DEBT_NOTIFIED = "debtNotified";

    private static volatile NoticeSink instance;
    /** Screens that show recovery debt themselves (access card, monitor) while they are in front. */
    private static final AtomicInteger debtViews = new AtomicInteger();

    private final Context app;
    private final DebtRules.NoticeGate debtGate;
    private Boolean lastPrivileged;
    private boolean pausedShown;

    private NoticeSink(Context context) {
        app = context.getApplicationContext();
        SharedPreferences notices = app.getSharedPreferences(NOTICES, Context.MODE_PRIVATE);
        debtGate = new DebtRules.NoticeGate(new DebtRules.NoticeGate.Store() {
            @Override
            public Set<String> load() {
                return notices.getStringSet(DEBT_NOTIFIED, Collections.emptySet());
            }

            @Override
            public void save(Set<String> keys) {
                notices.edit().putStringSet(DEBT_NOTIFIED, new HashSet<>(keys)).apply();
            }
        });
    }

    /** While a screen showing the debt is in front, a new debt is recorded without a notification. */
    public static void setDebtShownInApp(boolean shown) {
        if (shown) {
            debtViews.incrementAndGet();
            return;
        }
        int count;
        do {
            count = debtViews.get();
        } while (count > 0 && !debtViews.compareAndSet(count, count - 1));
    }

    public static NoticeSink get(Context context) {
        NoticeSink sink = instance;
        if (sink != null) return sink;
        synchronized (NoticeSink.class) {
            if (instance == null) instance = new NoticeSink(context);
            return instance;
        }
    }

    @Override
    public synchronized void emit(DozeEvent event) {
        String detail = event.getDetail();
        switch (event.getType()) {
            case ACCESS_CHANGED:
                onAccessChanged(detail);
                break;
            case RECOVERY_DEBT:
                notifyDebt(detail, event.getTarget());
                break;
            case VERIFY:
                // A verified restore (or a verified re-apply) settles that item; a failed readback does not.
                if (event.getReason() == null) debtGate.clear(DebtRules.key(detail, event.getTarget()));
                break;
            case SENSORS_RESTORED:
                debtGate.clear(DebtRules.key(detail, event.getTarget()));
                break;
            case EXTERNAL_CALL:
                onExternalCall(detail);
                break;
            case ERROR:
                if (EventCodes.FOREGROUND_START_DENIED.equals(detail)) notifyStartDenied();
                break;
            case SCREEN_ON:
                armSummary();
                break;
            case SCREEN_OFF:
                summaryArmed = false;
                break;
            default:
                break;
        }
    }

    private void onAccessChanged(String detail) {
        AccessLevel level = parseLevel(detail);
        if (level == null) return;
        boolean privileged = level == AccessLevel.SHELL || level == AccessLevel.ROOT;
        Boolean previous = lastPrivileged;
        lastPrivileged = privileged;
        // Losing access again later is a new ACCESS_LOST debt worth announcing.
        if (privileged) debtGate.clear(EventCodes.ACCESS_LOST);
        if (previous == null) return;
        if (previous && !privileged && MyApplication.getDozeRuntime(app).getSessionActive()) {
            boolean shizuku = Utils.isShizukuMode(app);
            // A downgrade to sensor-only still runs: not "paused", but Android now times Doze.
            SessionMode mode = modeAfterLoss(level, MyApplication.getDozeRuntime(app).grants().getDump(),
                    AccessUi.sensorsEnabled(app));
            NotificationCompat.Builder builder = builder(accessLossTitle(mode), accessLossText(mode, shizuku))
                    .setContentIntent(openMain(0, null));
            Intent shizukuIntent = shizuku ? AccessUi.shizukuLaunchIntent(app) : null;
            if (shizukuIntent != null) {
                builder.addAction(0, app.getString(R.string.access_action_open_shizuku),
                        PendingIntent.getActivity(app, 1, shizukuIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
            pausedShown = post(ID_ACCESS, builder.build());
        } else if (!previous && privileged && pausedShown) {
            pausedShown = false;
            post(ID_ACCESS, builder(R.string.notice_resumed_title, R.string.notice_resumed_text)
                    .setSilent(true)
                    .setTimeoutAfter(60_000)
                    .setContentIntent(openMain(0, null))
                    .build());
        }
    }

    /** What still runs after Shizuku or root is lost: the live DUMP grant and the sensor setting decide. */
    static SessionMode modeAfterLoss(AccessLevel level, boolean dump, boolean sensorsEnabled) {
        return SessionAccess.mode(level, new Grants(dump, false), sensorsEnabled, true);
    }

    static int accessLossTitle(SessionMode mode) {
        return mode == SessionMode.SENSOR_ONLY ? R.string.notice_sensors_only_title : R.string.notice_paused_title;
    }

    static int accessLossText(SessionMode mode, boolean shizuku) {
        if (mode == SessionMode.SENSOR_ONLY) {
            return shizuku ? R.string.notice_sensors_only_shizuku_text : R.string.notice_sensors_only_root_text;
        }
        return shizuku ? R.string.notice_paused_shizuku_text : R.string.notice_paused_root_text;
    }

    private static AccessLevel parseLevel(String detail) {
        if (detail == null) return null;
        int space = detail.indexOf(' ');
        try {
            return AccessLevel.valueOf(space < 0 ? detail : detail.substring(0, space));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** Every safety check re-emits outstanding debt; only a debt item not yet announced posts. */
    private void notifyDebt(String detail, String target) {
        boolean accessLost = EventCodes.ACCESS_LOST.equals(detail);
        debtGate.offer(DebtRules.key(detail, target), debtViews.get() > 0, () -> post(ID_DEBT,
                builder(R.string.notice_debt_title,
                        accessLost ? R.string.notice_debt_access_lost_text : R.string.notice_debt_generic_text)
                        .setContentIntent(openMain(2, null))
                        .addAction(0, app.getString(R.string.access_restore_now), openMain(3, MainActivity.ACTION_RESTORE_NOW))
                        .build()));
    }

    /** Called by the UI once a ledger check shows nothing left to restore; later debt is announced again. */
    public static void cancelDebt(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(ID_DEBT);
        NoticeSink sink = get(context);
        synchronized (sink) {
            sink.debtGate.clearAll();
        }
    }

    /**
     * Called by the UI after every ledger check (DebtRules.isDebt). No debt re-arms the ledger-backed
     * items, even while event-raised debt is still shown, so a recurrence is announced again.
     */
    public static void ledgerChecked(Context context, boolean debt) {
        NoticeSink sink = get(context);
        synchronized (sink) {
            sink.debtGate.ledgerChecked(debt);
        }
    }

    private void onExternalCall(String detail) {
        if (detail == null) return;
        if (detail.contains("reason=" + EventCodes.FOREGROUND_START_DENIED)) {
            notifyStartDenied();
            return;
        }
        if (!detail.startsWith("denied")) return;
        boolean basic = detail.contains("reason=BASIC_CONTROL_DISABLED");
        boolean privileged = detail.contains("reason=PRIVILEGED_CONTROL_DISABLED");
        if (!basic && !privileged) return;
        SharedPreferences notices = app.getSharedPreferences(NOTICES, Context.MODE_PRIVATE);
        String key = basic ? SHOWN_BASIC : SHOWN_PRIVILEGED;
        if (notices.getBoolean(key, false)) return;
        Intent settings = new Intent(app, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean shown = post(ID_EXTERNAL, builder(
                basic ? R.string.notice_external_basic_title : R.string.notice_external_privileged_title,
                basic ? R.string.notice_external_basic_text : R.string.notice_external_privileged_text)
                .setContentIntent(PendingIntent.getActivity(app, 4, settings,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build());
        // "First time" means first time the user could actually see it.
        if (shown) notices.edit().putBoolean(key, true).apply();
    }

    private void notifyStartDenied() {
        post(ID_START_DENIED, builder(R.string.notice_start_denied_title, R.string.notice_start_denied_text)
                .setContentIntent(openMain(5, null))
                .build());
    }

    private NotificationCompat.Builder builder(int title, int text) {
        String body = app.getString(text);
        return new NotificationCompat.Builder(app, ForceDozeService.CHANNEL_TIPS)
                .setSmallIcon(R.drawable.ic_battery_health)
                .setContentTitle(app.getString(title))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true);
    }

    private PendingIntent openMain(int requestCode, String action) {
        Intent intent = new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (action != null) intent.setAction(action);
        return PendingIntent.getActivity(app, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Returns false when the user has not allowed notifications; nothing is posted then. */
    private boolean post(int id, Notification notification) {
        if (Build.VERSION.SDK_INT >= 33
                && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false;
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager == null) return false;
        ensureChannel(manager);
        // A blocked tips channel drops notify() silently: report it as not shown.
        if (!tipsChannelEnabled()) return false;
        try {
            manager.notify(id, notification);
            return true;
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    // --- Screen-on summary (opt-in, default off): armed by SCREEN_ON, posted after the exit-time
    // history import so OS history is merged first. Every gate is re-checked at post time, because
    // the pref can also be switched on externally without the Settings permission flow.

    private static final int ID_SUMMARY = 8805;
    private boolean summaryArmed;
    private final Runnable afterHistoryImport = this::onHistoryImported;

    private void armSummary() {
        if (!summaryEnabled()) return;
        summaryArmed = true;
        // The runtime already exists (it is emitting this event); the hook runs on doze-worker.
        MyApplication.getDozeRuntime(app).setAfterHistoryImport(afterHistoryImport);
    }

    private synchronized void onHistoryImported() {
        if (!summaryArmed) return;
        summaryArmed = false;
        JournalSink journal = MyApplication.getDozeRuntime(app).getJournal();
        long sessionId = journal.getSessionId();
        int bootId = journal.getBootId();
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> postSummary(journal, sessionId, bootId));
    }

    private void postSummary(JournalSink journal, long sessionId, int bootId) {
        try {
            List<JournalEvent> segment = MonitorData.lastSegment(
                    journal.querySession(sessionId, bootId).get(2, TimeUnit.SECONDS));
            // Nothing was enforced (a short screen-off, or every step SKIPPED): nothing worth summarizing.
            if (!MonitorData.hasAppliedStep(segment)) return;
            List<SessionSummary> summaries = SessionAggregator.summarize(segment);
            if (summaries.isEmpty() || !summaryEnabled()) return; // post() re-checks every notification gate.
            SessionSummary summary = summaries.get(summaries.size() - 1);
            String line = MonitorFormat.summaryLine(app, summary);
            Intent open = DozeMonitorActivity.sessionIntent(app, summary.getBootId(), summary.getSessionId(),
                    summary.getStartElapsed()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            post(ID_SUMMARY, new NotificationCompat.Builder(app, ForceDozeService.CHANNEL_TIPS)
                    .setSmallIcon(R.drawable.ic_battery_health)
                    .setContentTitle(app.getString(R.string.notice_summary_title))
                    .setContentText(line)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(line))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setSilent(true)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(app, 6, open,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                    .build());
        } catch (Exception unavailable) {
            // Journal busy/unreadable: skip quietly, this is presentation only.
        }
    }

    private boolean summaryEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(app)
                .getBoolean(Prefs.SCREEN_ON_SUMMARY, Prefs.DEFAULT_SCREEN_ON_SUMMARY);
    }

    /** post() checks POST_NOTIFICATIONS and app-level enablement; this adds the channel's own switch. */
    private boolean tipsChannelEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager == null) return false;
        NotificationChannel channel = manager.getNotificationChannel(ForceDozeService.CHANNEL_TIPS);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    /** The service creates the channel, but notices can arrive in a process where it never started. */
    private void ensureChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (manager.getNotificationChannel(ForceDozeService.CHANNEL_TIPS) != null) return;
        NotificationChannel channel = new NotificationChannel(ForceDozeService.CHANNEL_TIPS,
                app.getString(R.string.notification_channel_tips_name), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(app.getString(R.string.notification_channel_tips_description));
        manager.createNotificationChannel(channel);
    }
}
