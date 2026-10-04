package com.akylas.enforcedoze;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;

import com.akylas.enforcedoze.access.Prefs;

/** Private entry point for the disabled notification's explicit enable action. */
public class InternalEnableReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Start directly during notification delivery to retain the user-action FGS exemption.
        if (!Utils.startForceDozeService(context)) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("serviceEnabled", true)
                .putBoolean(Prefs.SERVICE_USER_ENABLED, true).apply();
        Utils.scheduleNextCustomDozePeriodBoundary(context);
    }
}
