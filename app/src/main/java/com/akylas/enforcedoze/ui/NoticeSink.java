package com.akylas.enforcedoze.ui;

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
import com.akylas.enforcedoze.doze.DozeEvent;
import com.akylas.enforcedoze.doze.DozeEventSink;

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

    private static volatile NoticeSink instance;

    private final Context app;
    private Boolean lastPrivileged;
    private boolean pausedShown;

    private NoticeSink(Context context) {
        app = context.getApplicationContext();
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
                notifyDebt(detail);
                break;
            case EXTERNAL_CALL:
                onExternalCall(detail);
                break;
            case ERROR:
                if ("FOREGROUND_START_DENIED".equals(detail)) notifyStartDenied();
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
        if (previous == null) return;
        if (previous && !privileged && MyApplication.getDozeRuntime(app).getSessionActive()) {
            boolean shizuku = Utils.isShizukuMode(app);
            NotificationCompat.Builder builder = builder(R.string.notice_paused_title,
                    shizuku ? R.string.notice_paused_shizuku_text : R.string.notice_paused_root_text)
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

    private static AccessLevel parseLevel(String detail) {
        if (detail == null) return null;
        int space = detail.indexOf(' ');
        try {
            return AccessLevel.valueOf(space < 0 ? detail : detail.substring(0, space));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private void notifyDebt(String detail) {
        boolean accessLost = "ACCESS_LOST".equals(detail);
        post(ID_DEBT, builder(R.string.notice_debt_title,
                accessLost ? R.string.notice_debt_access_lost_text : R.string.notice_debt_generic_text)
                .setContentIntent(openMain(2, null))
                .addAction(0, app.getString(R.string.access_restore_now), openMain(3, MainActivity.ACTION_RESTORE_NOW))
                .build());
    }

    /** Called by the UI once a ledger check shows nothing left to restore. */
    public static void cancelDebt(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(ID_DEBT);
    }

    private void onExternalCall(String detail) {
        if (detail == null) return;
        if (detail.contains("reason=FOREGROUND_START_DENIED")) {
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
        try {
            manager.notify(id, notification);
            return true;
        } catch (RuntimeException rejected) {
            return false;
        }
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
