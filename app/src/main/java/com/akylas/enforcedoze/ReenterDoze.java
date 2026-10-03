package com.akylas.enforcedoze;

import com.akylas.enforcedoze.access.ExternalControlPolicy.Action;

public class ReenterDoze extends ExternalControlReceiver {
    public ReenterDoze() { super(Action.REAPPLY_DOZE); }
}
