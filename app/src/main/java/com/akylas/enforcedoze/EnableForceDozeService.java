package com.akylas.enforcedoze;

import com.akylas.enforcedoze.access.ExternalControlPolicy.Action;

public class EnableForceDozeService extends ExternalControlReceiver {
    public EnableForceDozeService() { super(Action.ENABLE_SERVICE); }
}
