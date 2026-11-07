package com.gathin.tvhome.gate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;
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
    private MediaPlayer clip;
    private AudioManager clipAudio;
    private AudioFocusRequest clipFocus;

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
        long now = System.currentTimeMillis();
        GateStore.prepareSlot(this, now);
        GateRules.Screen s = r.tick(now, on, GateStore.today(), GateStore.weekend());
        GateStore.save(this);
        if (r.lastWarn != GateRules.Warn.NONE) {
            boolean five = r.lastWarn == GateRules.Warn.FIVE;
            toast(five ? "还有 5 分钟，准备和电视说再见啦" : "还有 1 分钟哦");
            playClip(five ? "warn5" : "warn1");
            try { // so the log shows whether the countdown reminder actually fired
                GateStore.log(this, new JSONObject().put("ev", "warn").put("left_min", five ? 5 : 1));
            } catch (Exception ignored) {
            }
        }
        if (r.justEnded) { // slot or day just ended while watching: one minute of goodbye before the wait page
            GateActivity.byeUntil = SystemClock.elapsedRealtime() + GateActivity.BYE_MS;
            try {
                GateStore.log(this, new JSONObject().put("ev", "bye"));
            } catch (Exception ignored) {
            }
        }
        if (s != last && s != GateRules.Screen.NONE) {
            try {
                JSONObject event = new JSONObject().put("ev", s.name().toLowerCase());
                if (s == GateRules.Screen.REST) event.put("rest_until", r.restUntil);
                GateStore.log(this, event);
            } catch (Exception ignored) {
            }
        }
        last = s;
        if (!on || s == GateRules.Screen.NONE) return;
        if (!GateRules.shouldReassert(s, GateActivity.visibleMode)) return;
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
        GatePalette.use(GateStore.theme(this));
        Toast t = Toast.makeText(this, "🐰 " + text, Toast.LENGTH_LONG);
        View view = t.getView();
        if (view != null) {
            float density = getResources().getDisplayMetrics().density;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(GatePalette.PANEL);
            bg.setCornerRadius(20 * density);
            view.setBackground(bg);
            view.setPadding(Math.round(28 * density), Math.round(16 * density), Math.round(28 * density), Math.round(16 * density));
            TextView message = view.findViewById(android.R.id.message);
            if (message != null) message.setTextColor(GatePalette.TEXT);
        }
        t.show();
        h.postDelayed(t::show, 3500); // twice, so it is noticed over a cartoon
    }

    /** Short spoken reminder over the programme: transient ducking focus, never touches the system volume. */
    private void playClip(String name) {
        stopClip();
        try (AssetFileDescriptor fd = getAssets().openFd(GateStore.audio(this, name))) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            clipAudio = (AudioManager) getSystemService(AUDIO_SERVICE);
            clipFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs).build();
            if (clipAudio.requestAudioFocus(clipFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { stopClip(); return; }
            MediaPlayer p = new MediaPlayer();
            clip = p;
            p.setAudioAttributes(attrs);
            p.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            p.setOnPreparedListener(MediaPlayer::start);
            p.setOnCompletionListener(mp -> stopClip());
            p.setOnErrorListener((mp, a, b) -> { stopClip(); return true; });
            p.prepareAsync();
        } catch (Exception ignored) {
            stopClip(); // a missing clip must never affect the gate
        }
    }

    private void stopClip() {
        MediaPlayer p = clip;
        clip = null;
        if (p != null) {
            p.setOnPreparedListener(null);
            p.setOnCompletionListener(null);
            p.setOnErrorListener(null);
            p.release();
        }
        if (clipAudio != null && clipFocus != null) clipAudio.abandonAudioFocusRequest(clipFocus);
        clipAudio = null;
        clipFocus = null;
    }

    @Override
    public void onDestroy() {
        stopClip();
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
