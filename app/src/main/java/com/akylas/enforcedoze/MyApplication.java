package com.akylas.enforcedoze;

import android.content.Context;

public class MyApplication extends android.app.Application {
    private static Context context;
    private static com.akylas.enforcedoze.service.DozeRuntime dozeRuntime;

    /**
     * Built on first use only: constructing it starts AccessManager, which probes su in root mode, so a
     * cold start that never needs it (listener rebind, tile bind, alarm, package replaced) must not.
     */
    public static synchronized com.akylas.enforcedoze.service.DozeRuntime getDozeRuntime(Context context) {
        if (dozeRuntime == null) {
            Context app = context.getApplicationContext();
            dozeRuntime = new com.akylas.enforcedoze.service.DozeRuntime(app);
            // App-lifetime notices (and the screen-on summary) for every event this runtime emits,
            // including those from receivers while the service is not running.
            dozeRuntime.getJournal().addSink(com.akylas.enforcedoze.ui.NoticeSink.get(app));
        }
        return dozeRuntime;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        MyApplication.context = getApplicationContext();
    }

    public static Context getAppContext() {
        return MyApplication.context;
    }
}
