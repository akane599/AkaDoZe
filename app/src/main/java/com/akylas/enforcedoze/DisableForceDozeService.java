package com.akylas.enforcedoze;

import com.akylas.enforcedoze.access.ExternalControlPolicy.Action;

public class DisableForceDozeService extends ExternalControlReceiver {
    public DisableForceDozeService() { super(Action.DISABLE_SERVICE); }
}
