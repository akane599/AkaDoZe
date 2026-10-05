package com.akylas.enforcedoze.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import com.akylas.enforcedoze.MyApplication;
import com.akylas.enforcedoze.R;
import com.akylas.enforcedoze.access.Feature;
import com.akylas.enforcedoze.service.DozeRuntime;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Damaged restore records that can't be restored automatically (DebtRules.dismissibleDamage): their
 * text and the user-confirmed dismiss, shared by the access card and the monitor.
 */
public final class DamagedRecords {
    private DamagedRecords() { throw new AssertionError(); }

    /** Card text: what was damaged and what the user can do about it. */
    public static String debtText(Context context, List<String> tokens) {
        return context.getString(R.string.damaged_records_debt, features(context, tokens));
    }

    /** Asks first; only a confirmed dismiss clears the records, on doze-worker, then runs {@code after} on main. */
    public static void confirmDismiss(Activity activity, List<String> tokens, Runnable after) {
        Context app = activity.getApplicationContext();
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.damaged_records_dismiss_title)
                .setMessage(activity.getString(R.string.damaged_records_dismiss_text, features(activity, tokens)))
                .setNegativeButton(R.string.cancel_button_text, null)
                .setPositiveButton(R.string.damaged_records_dismiss_confirm, (dialog, which) -> dismiss(app, after))
                .show();
    }

    private static void dismiss(Context app, Runnable after) {
        DozeRuntime runtime = MyApplication.getDozeRuntime(app);
        Handler main = new Handler(Looper.getMainLooper());
        Runnable job = () -> {
            int message;
            try {
                // Never on main: it commits synchronously. Forced Doze and motion sensor records are kept.
                runtime.clearRetainedCorruption();
                message = R.string.damaged_records_dismissed;
            } catch (Exception error) {
                message = runtime.getStore().getLoadFailed()
                        ? R.string.damaged_records_unreadable : R.string.damaged_records_dismiss_failed;
            } finally {
                runtime.quitIfDetached();
            }
            int shown = message;
            main.post(() -> {
                Toast.makeText(app, shown, Toast.LENGTH_LONG).show();
                after.run();
            });
        };
        boolean queued;
        // The runtime's lock: an idle worker can't retire between worker() and post().
        synchronized (runtime) {
            queued = runtime.worker().post(job);
        }
        if (!queued) Toast.makeText(app, R.string.damaged_records_dismiss_failed, Toast.LENGTH_LONG).show();
    }

    static String features(Context context, List<String> tokens) {
        List<String> names = new ArrayList<>();
        for (String token : tokens) {
            String name = context.getString(featureLabel(token));
            if (!names.contains(name)) names.add(name);
        }
        return TextUtils.join(", ", names);
    }

    /** The record's feature when its token names one; damaged records may not. */
    static int featureLabel(String token) {
        Feature feature = null;
        for (Feature candidate : Feature.values()) {
            if (candidate.name().equals(token)) feature = candidate;
        }
        if (feature == null) return R.string.damaged_feature_unknown;
        switch (feature) {
            case FORCE_DOZE: return R.string.monitor_feature_force_doze;
            case MOTION_SENSORS: return R.string.monitor_feature_motion_sensors;
            case BATTERY_SAVER: return R.string.monitor_feature_battery_saver;
            case WIFI: return R.string.damaged_feature_wifi;
            case MOBILE_DATA: return R.string.damaged_feature_mobile_data;
            case BLUETOOTH: return R.string.damaged_feature_bluetooth;
            case AIRPLANE: return R.string.damaged_feature_airplane;
            case LOCATION: return R.string.damaged_feature_location;
            case BIOMETRICS: return R.string.damaged_feature_biometrics;
            case APP_SUSPEND: return R.string.damaged_feature_app_suspend;
            case PM_DISABLE: return R.string.damaged_feature_pm_disable;
            case NOTIFICATION_BLOCK: return R.string.damaged_feature_notification_block;
            case SENSOR_PRIVACY_ALL: return R.string.damaged_feature_sensor_privacy;
            default: return R.string.damaged_feature_unknown;
        }
    }
}
