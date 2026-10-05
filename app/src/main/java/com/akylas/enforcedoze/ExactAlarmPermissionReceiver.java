package com.akylas.enforcedoze;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms the next boundary after a system grant, without starting or applying a schedule. */
public class ExactAlarmPermissionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)) return;
        // Extras are not authority: capability may already have been revoked again.
        Utils.requeryExactAlarmAccess(context);
    }
}
