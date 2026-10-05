package com.akylas.enforcedoze;


import static com.akylas.enforcedoze.Utils.logToLogcat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;
import com.akylas.enforcedoze.service.BootRestore;

public class BootCompleteReceiver extends BroadcastReceiver {
    public static String TAG = "EnforceDoze";
    private static void log(String message) {
        logToLogcat(TAG, message);
    }
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        // Credential-protected preferences are unavailable until the ordinary boot broadcast.
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) return;
        boolean isServiceEnabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("serviceEnabled", false);
        log("Received BOOT_COMPLETED intent, isServiceEnabled=" + Boolean.toString(isServiceEnabled));
        if (isServiceEnabled) {
            Utils.startForceDozeService(context);
        } else {
            // Show disabled notification if EnforceDoze is disabled on startup
            Utils.stopForceDozeService(context);
            BootRestore.restoreIfPending(context, () -> {
                PendingResult pending = goAsync();
                MyApplication.getDozeRuntime(context).requestRestoreOnly(pending::finish);
            });
        }
        Utils.scheduleNextCustomDozePeriodBoundary(context);
    }
}
