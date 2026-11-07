package com.gathin.tvhome.gate;

/**
 * TV time rules, pure logic (no Android types) so it can be unit tested.
 * Watching time = screen on and unlocked. Rules decided 2026-11-04:
 * quiz before watching; at most SESSION continuous minutes, then REST minutes; daily cap; no interruption mid-session
 * apart from the end-of-session rest. Parent mode (PIN) bypasses everything until the TV goes to standby.
 */
public final class GateRules {
    public enum Screen { NONE, QUIZ, REST, LIMIT, WAIT }
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

    // Time slots (family rule 2026-11-07): within the gated window the TV may only be used inside a slot, at most
    // slotMs per slot, quiz first. Set by the caller before each tick (see GateStore.prepareSlot).
    public long slotMs = 30 * MIN;
    /** null = slots not configured (old behaviour); "" = between slots; else e.g. "2026-11-07#4". */
    public String slotKey;
    public long slotEndAt;
    // Persistent slot state.
    public String usedSlotKey = "";
    public long slotUsedMs;
    /** Parent "+20 min" may be used between slots: slot limits are skipped until this time. */
    public long bonusUntil;

    public Warn lastWarn = Warn.NONE;
    /** True on the tick where watching just ended at a slot end or the daily cap: show the goodbye page first. */
    public boolean justEnded;

    public long capMs(boolean weekend) {
        return (weekend ? capWeekendMs : capWeekdayMs) + bonusMs;
    }

    /** Called about once a second. Returns which gate screen must be on top (NONE = let the TV be used). */
    public Screen tick(long now, boolean interactive, String today, boolean weekend) {
        lastWarn = Warn.NONE;
        justEnded = false;
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
        boolean wasWatching = unlocked && !parentMode;

        if (parentMode) return Screen.NONE;
        if (now < restUntil) {
            unlocked = false;
            return Screen.REST;
        }
        long cap = capMs(weekend);
        if (dayUsedMs >= cap) {
            unlocked = false;
            justEnded = wasWatching;
            return Screen.LIMIT;
        }
        boolean slotsOn = slotKey != null && now >= bonusUntil;
        if (slotsOn) {
            if (slotKey.isEmpty()) {
                justEnded = wasWatching;
                unlocked = false;
                return Screen.WAIT;
            }
            if (!slotKey.equals(usedSlotKey)) { // a new slot always starts with the quiz
                usedSlotKey = slotKey;
                slotUsedMs = 0;
                unlocked = false;
            }
            if (slotUsedMs >= slotMs) {
                justEnded = wasWatching;
                unlocked = false;
                return Screen.WAIT;
            }
        }
        if (!unlocked) return Screen.QUIZ;

        sessionUsedMs += delta;
        dayUsedMs += delta;
        if (slotsOn) {
            slotUsedMs += delta;
            if (slotUsedMs >= slotMs) {
                unlocked = false;
                sessionUsedMs = 0;
                justEnded = wasWatching;
                return Screen.WAIT;
            }
        }
        if (sessionUsedMs >= sessionMs) {
            unlocked = false;
            sessionUsedMs = 0;
            restUntil = now + restMs;
            return Screen.REST;
        }
        if (dayUsedMs >= cap) {
            unlocked = false;
            justEnded = wasWatching;
            return Screen.LIMIT;
        }
        long remaining = watchLeftMs(now, weekend);
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

    /** How long the child may keep watching from now: session, daily cap, slot budget and slot end. */
    public long watchLeftMs(long now, boolean weekend) {
        long left = Math.min(sessionMs - sessionUsedMs, capMs(weekend) - dayUsedMs);
        if (slotKey != null && !slotKey.isEmpty() && now >= bonusUntil) {
            left = Math.min(left, slotMs - slotUsedMs);
            if (slotEndAt > 0) left = Math.min(left, slotEndAt - now);
        }
        if (now < bonusUntil) left = Math.min(left, bonusUntil - now);
        return Math.max(0, left);
    }

    /** Quiz passed: start a session only after any mandatory rest has actually ended. */
    public boolean unlock(long now) {
        if (mustKeepRest(now)) {
            unlocked = false;
            return false;
        }
        unlocked = true;
        sessionUsedMs = 0;
        restUntil = 0;
        warned5 = warned1 = false;
        lastTick = now;
        return true;
    }

    public boolean mustKeepRest(long now) {
        return !parentMode && now < restUntil;
    }

    /** REST is periodically reasserted even if an Activity's cached visibility is stale. */
    public static boolean shouldReassert(Screen required, Screen visible) {
        return required != Screen.NONE && (required == Screen.REST || required != visible);
    }

    public void enterParentMode() {
        parentMode = true;
    }

    public void addBonus(long ms) {
        bonusMs += ms;
        if (slotKey != null) bonusUntil = Math.max(bonusUntil, lastTick) + ms;
        restUntil = 0;
        warned5 = warned1 = false;
    }

    /**
     * Weekly windows like "FRI 16:00-SUN 19:00" (2026-11-04: the gate is in force Friday 16:00 to Sunday 19:00).
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

    /** Index of the slot containing (dow, minute), or -1. Slots use the window syntax, e.g. "SAT 08:00-11:30". */
    public static int slotIndex(String[] slots, int dow, int minuteOfDay) {
        for (int i = 0; i < slots.length; i++)
            if (inWindows(new String[]{full(slots[i])}, dow, minuteOfDay)) return i;
        return -1;
    }

    /** "SAT 08:00-11:30" -> "SAT 08:00-SAT 11:30" (the end may omit the day). */
    static String full(String slot) {
        String[] p = slot.trim().split("-");
        if (p.length != 2) return slot;
        String a = p[0].trim(), b = p[1].trim();
        if (!b.contains(" ")) b = a.split("\\s+")[0] + " " + b;
        return a + "-" + b;
    }

    /** Minutes from (dow, minute) until the end of slot i (slots do not wrap past the week). */
    public static int minutesToSlotEnd(String[] slots, int i, int dow, int minuteOfDay) {
        int t = (dow - 1) * 1440 + minuteOfDay;
        return weekMinute(full(slots[i]).split("-")[1].trim()) - t;
    }

    /** Start "HH:MM" of the next slot later the same day, or null. */
    public static String nextSlotToday(String[] slots, int dow, int minuteOfDay) {
        int t = (dow - 1) * 1440 + minuteOfDay, best = Integer.MAX_VALUE;
        String label = null;
        for (String w : slots) {
            try {
                String start = w.split("-")[0].trim();
                int a = weekMinute(start);
                if (a > t && a < dow * 1440 && a < best) { best = a; label = start.split("\\s+")[1]; }
            } catch (Exception ignored) {
            }
        }
        return label;
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
