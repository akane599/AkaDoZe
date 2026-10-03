package com.akylas.enforcedoze;

import com.akylas.enforcedoze.access.ExternalControlPolicy.Action;

public class RemoveWhiteListReceiver extends ExternalControlReceiver {
    public RemoveWhiteListReceiver() { super(Action.REMOVE_WHITELIST); }
}
