package com.gathin.tvhome.gate;

/** Pure, non-mutating checks for delayed quiz completion and mastery exemption. */
public final class QuizCompletionGuard {
    private QuizCompletionGuard() {}

    /** A quiz exemption never grants additional viewing time or bypasses a lock. */
    public static GateRules.Screen required(GateRules r, long now, boolean weekend) {
        if (r.parentMode) return GateRules.Screen.NONE;
        if (r.mustKeepRest(now)) return GateRules.Screen.REST;
        if (r.dayUsedMs >= r.capMs(weekend)) return GateRules.Screen.LIMIT;
        if (r.slotKey != null && now >= r.bonusUntil) {
            if (r.slotKey.isEmpty()) return GateRules.Screen.WAIT;
            if (r.slotEndAt > 0 && now >= r.slotEndAt) return GateRules.Screen.WAIT;
            if (r.slotKey.equals(r.usedSlotKey) && r.slotUsedMs >= r.slotMs)
                return GateRules.Screen.WAIT;
        }
        return GateRules.Screen.NONE;
    }
}
