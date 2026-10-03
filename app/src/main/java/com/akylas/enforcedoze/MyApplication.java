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
    }

    public static Context getAppContext() {
        return MyApplication.context;
    }
}
