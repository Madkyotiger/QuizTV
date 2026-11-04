package com.gathin.tvhome.gate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Keeps the quiz gate on top of whatever is showing (Xiaomi Home, Xiaomi video, any app, live TV).
 * Lives in the Home app's process: Xiaomi force-stops third-party apps 2 s after standby, then starts the default
 * Home again (verified 2026-11-04 on MiTV-MFTP0), and MainActivity starts this service each time.
 */
public final class GateService extends Service {
    private static final String CHANNEL = "quiz_gate";
    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager pm;
    private GateRules.Screen last = GateRules.Screen.NONE;
    private int relaunchCooldown;

    public static void start(Context c) {
        Intent i = new Intent(c, GateService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            tick();
            h.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                GateRules r = GateStore.rules(c);
                r.parentMode = false;
                GateStore.save(c);
            }
            tick();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "答题看电视", NotificationManager.IMPORTANCE_MIN));
            startForeground(4207, new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("答题看电视").build());
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);
        h.post(loop);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void tick() {
        if (!GateStore.enabled(this)) {
            if (GateActivity.visibleMode != null) { // switched off while a gate screen is up: close it
                try {
                    startActivity(new Intent(this, GateActivity.class).putExtra(GateActivity.EXTRA_MODE, "NONE")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                } catch (Exception ignored) {
                }
            }
            return;
        }
        GateRules r = GateStore.rules(this);
        boolean on = pm.isInteractive();
        GateRules.Screen s = r.tick(System.currentTimeMillis(), on, GateStore.today(), GateStore.weekend());
        GateStore.save(this);
        if (r.lastWarn == GateRules.Warn.FIVE) toast("还有 5 分钟就要休息眼睛啦");
        if (r.lastWarn == GateRules.Warn.ONE) toast("还有 1 分钟，准备休息啦");
        if (s != last && s != GateRules.Screen.NONE) {
            try {
                GateStore.log(this, new JSONObject().put("ev", s.name().toLowerCase()));
            } catch (Exception ignored) {
            }
        }
        last = s;
        if (!on || s == GateRules.Screen.NONE) return;
        if (GateActivity.visibleMode == s) return;
        if (relaunchCooldown > 0) { relaunchCooldown--; return; }
        Intent i = new Intent(this, GateActivity.class)
                .putExtra(GateActivity.EXTRA_MODE, s.name())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
        relaunchCooldown = 1;
    }

    private void toast(String text) {
        Toast t = Toast.makeText(this, "🐰 " + text, Toast.LENGTH_LONG);
        t.show();
        h.postDelayed(t::show, 3500); // twice, so it is noticed over a cartoon
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(loop);
        try {
            unregisterReceiver(screen);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
