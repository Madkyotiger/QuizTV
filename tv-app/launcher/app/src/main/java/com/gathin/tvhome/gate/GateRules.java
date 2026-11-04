package com.gathin.tvhome.gate;

/**
 * TV time rules, pure logic (no Android types) so it can be unit tested.
 * Watching time = screen on and unlocked. Rules decided by Jef 2026-11-04:
 * quiz before watching; at most SESSION continuous minutes, then REST minutes; daily cap; no interruption mid-session
 * apart from the end-of-session rest. Parent mode (PIN) bypasses everything until the TV goes to standby.
 */
public final class GateRules {
    public enum Screen { NONE, QUIZ, REST, LIMIT }
    public enum Warn { NONE, FIVE, ONE }

    public static final long MIN = 60_000L;

    // Config (minutes); overridable from gate.json.
    public long sessionMs = 40 * MIN;
    public long restMs = 20 * MIN;
    public long capWeekdayMs = 60 * MIN;
    public long capWeekendMs = 120 * MIN;
    /** An off-screen gap this long ends the session: next time starts with a quiz again. */
    public long gapResetMs = 20 * MIN;

    // Persistent state.
    public boolean unlocked;
    public long sessionUsedMs;
    public String dayKey = "";
    public long dayUsedMs;
    public long restUntil;
    public long lastTick;
    public boolean parentMode;
    public long bonusMs; // parent-granted extra minutes for today
    public boolean warned5, warned1;

    public Warn lastWarn = Warn.NONE;

    public long capMs(boolean weekend) {
        return (weekend ? capWeekendMs : capWeekdayMs) + bonusMs;
    }

    /** Called about once a second. Returns which gate screen must be on top (NONE = let the TV be used). */
    public Screen tick(long now, boolean interactive, String today, boolean weekend) {
        lastWarn = Warn.NONE;
        if (!today.equals(dayKey)) {
            dayKey = today;
            dayUsedMs = 0;
            bonusMs = 0;
        }
        if (!interactive) {
            parentMode = false;
            return Screen.NONE;
        }
        long gap = lastTick == 0 ? 0 : now - lastTick;
        if (gap < 0) gap = 0; // clock moved backwards
        if (gap >= gapResetMs) {
            unlocked = false;
            sessionUsedMs = 0;
        }
        long delta = Math.min(gap, 5_000L);
        lastTick = now;

        if (parentMode) return Screen.NONE;
        if (now < restUntil) return Screen.REST;
        long cap = capMs(weekend);
        if (dayUsedMs >= cap) {
            unlocked = false;
            return Screen.LIMIT;
        }
        if (!unlocked) return Screen.QUIZ;

        sessionUsedMs += delta;
        dayUsedMs += delta;
        if (sessionUsedMs >= sessionMs) {
            unlocked = false;
            sessionUsedMs = 0;
            restUntil = now + restMs;
            return Screen.REST;
        }
        if (dayUsedMs >= cap) {
            unlocked = false;
            return Screen.LIMIT;
        }
        long remaining = Math.min(sessionMs - sessionUsedMs, cap - dayUsedMs);
        if (remaining <= MIN && !warned1) {
            warned1 = true;
            warned5 = true;
            lastWarn = Warn.ONE;
        } else if (remaining <= 5 * MIN && !warned5) {
            warned5 = true;
            lastWarn = Warn.FIVE;
        }
        return Screen.NONE;
    }

    /** Quiz passed: a fresh session starts now. */
    public void unlock(long now) {
        unlocked = true;
        sessionUsedMs = 0;
        warned5 = warned1 = false;
        lastTick = now;
    }

    public void enterParentMode() {
        parentMode = true;
    }

    public void addBonus(long ms) {
        bonusMs += ms;
        restUntil = 0;
        warned5 = warned1 = false;
    }

    /**
     * Weekly windows like "FRI 16:00-SUN 19:00" (Jef 2026-11-04: the gate is in force Friday 16:00 to Sunday 19:00).
     * dow: Monday=1 .. Sunday=7. A window may wrap past Sunday into Monday.
     */
    public static boolean inWindows(String[] windows, int dow, int minuteOfDay) {
        int t = (dow - 1) * 1440 + minuteOfDay;
        for (String w : windows) {
            try {
                String[] p = w.trim().split("-");
                int a = weekMinute(p[0].trim()), b = weekMinute(p[1].trim());
                if (a <= b ? (t >= a && t < b) : (t >= a || t < b)) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static int weekMinute(String s) { // "FRI 16:00"
        String[] p = s.split("\\s+");
        int d = java.util.Arrays.asList("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").indexOf(p[0].toUpperCase(java.util.Locale.US));
        if (d < 0) throw new IllegalArgumentException(s);
        String[] hm = p[1].split(":");
        return d * 1440 + Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
    }

    public long restLeftMs(long now) {
        return Math.max(0, restUntil - now);
    }
}
