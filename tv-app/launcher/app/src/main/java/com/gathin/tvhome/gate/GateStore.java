package com.gathin.tvhome.gate;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Persists GateRules across the firmware's standby force-stop, reads gate.json, writes the answer log. */
public final class GateStore {
    private static final String PREFS = "quiz_gate";
    private static GateRules rules;
    private static String pin = "2580";
    private static String childName = "宝贝";
    private static boolean enabled = true;
    private static long enableAfter = 0;
    private static long testUntil = 0;
    private static String[] windows = new String[0];
    private static java.util.LinkedHashMap<String, Integer> mix = defaultMix();
    private static long customStamp = -1;

    private static java.util.LinkedHashMap<String, Integer> defaultMix() {
        java.util.LinkedHashMap<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("数学", 3);
        m.put("英语", 2);
        return m;
    }
    private static long configStamp = -1;

    private GateStore() {}

    public static synchronized GateRules rules(Context c) {
        if (rules == null) {
            rules = new GateRules();
            SharedPreferences p = prefs(c);
            rules.unlocked = p.getBoolean("unlocked", false);
            rules.sessionUsedMs = p.getLong("sessionUsed", 0);
            rules.dayKey = p.getString("dayKey", "");
            rules.dayUsedMs = p.getLong("dayUsed", 0);
            rules.restUntil = p.getLong("restUntil", 0);
            rules.lastTick = p.getLong("lastTick", 0);
            rules.bonusMs = p.getLong("bonus", 0);
            rules.warned5 = p.getBoolean("warned5", false);
            rules.warned1 = p.getBoolean("warned1", false);
            rules.parentMode = false; // a process start always follows a standby or a boot here
        }
        loadConfig(c);
        return rules;
    }

    public static synchronized void save(Context c) {
        if (rules == null) return;
        prefs(c).edit()
                .putBoolean("unlocked", rules.unlocked)
                .putLong("sessionUsed", rules.sessionUsedMs)
                .putString("dayKey", rules.dayKey)
                .putLong("dayUsed", rules.dayUsedMs)
                .putLong("restUntil", rules.restUntil)
                .putLong("lastTick", rules.lastTick)
                .putLong("bonus", rules.bonusMs)
                .putBoolean("warned5", rules.warned5)
                .putBoolean("warned1", rules.warned1)
                .apply();
    }

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** /sdcard/Android/data/<pkg>/files/gate.json: {"enabled":true,"pin":"1234","session_min":40,...}. */
    private static void loadConfig(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        File f = new File(dir, "gate.json");
        long stamp = f.exists() ? f.lastModified() : 0;
        if (stamp == configStamp) return;
        configStamp = stamp;
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            JSONObject o = new JSONObject(new String(b, 0, Math.max(n, 0), StandardCharsets.UTF_8));
            enabled = o.optBoolean("enabled", true);
            String after = o.optString("enable_after", "");
            enableAfter = after.isEmpty() ? 0 : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(after).getTime();
            String test = o.optString("test_until", "");
            testUntil = test.isEmpty() ? 0 : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(test).getTime();
            JSONObject mj = o.optJSONObject("mix");
            mix = defaultMix();
            if (mj != null) {
                mix.clear();
                for (java.util.Iterator<String> it = mj.keys(); it.hasNext(); ) {
                    String k = it.next();
                    int v = mj.optInt(k, 0);
                    if (v > 0) mix.put(k, Math.min(v, 10));
                }
                if (mix.isEmpty()) mix = defaultMix();
            }
            org.json.JSONArray wa = o.optJSONArray("windows");
            windows = new String[wa == null ? 0 : wa.length()];
            for (int i = 0; i < windows.length; i++) windows[i] = wa.getString(i);
            pin = o.optString("pin", pin);
            childName = o.optString("child_name", "宝贝");
            rules.sessionMs = o.optLong("session_min", 30) * GateRules.MIN;
            rules.restMs = o.optLong("rest_min", 10) * GateRules.MIN;
            rules.capWeekdayMs = o.optLong("weekday_cap_min", 60) * GateRules.MIN;
            rules.capWeekendMs = o.optLong("weekend_cap_min", 120) * GateRules.MIN;
            rules.gapResetMs = o.optLong("gap_reset_min", 20) * GateRules.MIN;
            // A new reset_token wipes counters and the missed-topic weights (after testing, or when the family wants).
            String tok = o.optString("reset_token", "");
            SharedPreferences p = prefs(c);
            if (!tok.isEmpty() && !tok.equals(p.getString("resetToken", ""))) {
                p.edit().clear().putString("resetToken", tok).commit();
                GateRules fresh = new GateRules();
                fresh.sessionMs = rules.sessionMs; fresh.restMs = rules.restMs; fresh.capWeekdayMs = rules.capWeekdayMs;
                fresh.capWeekendMs = rules.capWeekendMs; fresh.gapResetMs = rules.gapResetMs;
                rules = fresh;
            }
        } catch (Exception ignored) {
            // keep defaults; a broken file must never lock the family out of the PIN
        }
    }

    public static synchronized boolean enabled(Context c) {
        rules(c);
        long now = System.currentTimeMillis();
        if (!enabled || now < enableAfter) return false;
        if (now < testUntil) return true;
        if (windows.length == 0) return true; // no windows configured: always on
        Calendar cal = Calendar.getInstance();
        int dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1; // Monday=1 .. Sunday=7
        return GateRules.inWindows(windows, dow, cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE));
    }

    public static synchronized java.util.Map<String, Integer> mix(Context c) {
        rules(c);
        File dir = c.getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, "custom-bank.json");
        long stamp = f != null && f.exists() ? f.lastModified() : 0;
        if (stamp != customStamp) { customStamp = stamp; Bank.invalidate(); }
        return new java.util.LinkedHashMap<>(mix);
    }

    /** What the screens call the child ("child_name" in gate.json). */
    public static synchronized String childName(Context c) {
        rules(c);
        return childName;
    }

    public static synchronized boolean pinOk(Context c, String entered) {
        rules(c);
        return pin.equals(entered);
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public static boolean weekend() {
        int d = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        return d == Calendar.SATURDAY || d == Calendar.SUNDAY;
    }

    public static synchronized void log(Context c, JSONObject event) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        try (FileOutputStream out = new FileOutputStream(new File(dir, "gate-log.jsonl"), true)) {
            event.put("t", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            out.write((event.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
