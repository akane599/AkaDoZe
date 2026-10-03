package com.akylas.enforcedoze;

import com.akylas.enforcedoze.access.ExternalControlPolicy.Action;

public class AddWhiteListReceiver extends ExternalControlReceiver {
    public AddWhiteListReceiver() { super(Action.ADD_WHITELIST); }
}
