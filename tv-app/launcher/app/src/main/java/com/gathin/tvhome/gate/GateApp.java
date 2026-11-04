package com.gathin.tvhome.gate;

import android.app.Application;

/**
 * Starts the gate as soon as the process exists. After Xiaomi's standby force-stop the system restarts this
 * process for the default Home activity, but while the screen is off that activity is never created, so a
 * start from MainActivity alone is missed (seen 2026-11-04 on MiTV-MFTP0).
 */
public final class GateApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        GateService.start(this);
    }
}
