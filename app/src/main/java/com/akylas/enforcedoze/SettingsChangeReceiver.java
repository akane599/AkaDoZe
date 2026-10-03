package com.akylas.enforcedoze;

import com.akylas.enforcedoze.access.ExternalControlPolicy.Action;

public class SettingsChangeReceiver extends ExternalControlReceiver {
    public SettingsChangeReceiver() { super(Action.CHANGE_SETTING); }
}
