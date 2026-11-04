package com.gathin.tvhome.gate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Cold boot: start the gate even before Home is drawn. */
public final class GateBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        GateService.start(c);
    }
}
