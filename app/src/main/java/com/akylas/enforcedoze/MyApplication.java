package com.akylas.enforcedoze;

import android.content.Context;

public class MyApplication extends android.app.Application {
    private static Context context;
    private static com.akylas.enforcedoze.service.DozeRuntime dozeRuntime;

    public static synchronized com.akylas.enforcedoze.service.DozeRuntime getDozeRuntime(Context context) {
        if (dozeRuntime == null) {
            dozeRuntime = new com.akylas.enforcedoze.service.DozeRuntime(context.getApplicationContext());
        }
        return dozeRuntime;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        MyApplication.context = getApplicationContext();
        // App-lifetime notices: events can arrive from receivers while the service is not running.
        ForceDozeService.addSink(this, com.akylas.enforcedoze.ui.NoticeSink.get(this));
    }

    public static Context getAppContext() {
        return MyApplication.context;
    }
}
