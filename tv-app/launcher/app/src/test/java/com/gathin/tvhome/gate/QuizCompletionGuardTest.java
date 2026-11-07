package com.gathin.tvhome.gate;

import org.junit.Test;
import static org.junit.Assert.*;

public class QuizCompletionGuardTest {
    private GateRules fresh() {
        GateRules r = new GateRules();
        r.sessionMs = 30 * GateRules.MIN;
        r.restMs = 10 * GateRules.MIN;
        return r;
    }

    @Test public void unmasteredAndExemptCompletionsBothRespectRest() {
        GateRules r = fresh(); r.restUntil = 700_000;
        for (long now = 100_000; now < 700_000; now += 1000) {
            assertEquals(GateRules.Screen.REST, QuizCompletionGuard.required(r, now, true));
            assertEquals(700_000, r.restUntil);
            assertFalse(r.unlocked);
        }
        assertEquals(GateRules.Screen.NONE, QuizCompletionGuard.required(r, 700_000, true));
    }

    @Test public void dailyLimitCannotBeExempted() {
        GateRules r = fresh(); r.dayUsedMs = r.capWeekendMs;
        assertEquals(GateRules.Screen.LIMIT, QuizCompletionGuard.required(r, 10_000, true));
    }

    @Test public void weekdayLimitIsIndependentOfWeekendLimit() {
        GateRules r = fresh(); r.dayUsedMs = r.capWeekdayMs;
        assertEquals(GateRules.Screen.LIMIT, QuizCompletionGuard.required(r, 10_000, false));
        assertEquals(GateRules.Screen.NONE, QuizCompletionGuard.required(r, 10_000, true));
    }

    @Test public void betweenSlotsCannotBeExempted() {
        GateRules r = fresh(); r.slotKey = "";
        assertEquals(GateRules.Screen.WAIT, QuizCompletionGuard.required(r, 10_000, true));
    }

    @Test public void usedSlotCannotBeExempted() {
        GateRules r = fresh(); r.slotKey = r.usedSlotKey = "day#1"; r.slotUsedMs = r.slotMs;
        assertEquals(GateRules.Screen.WAIT, QuizCompletionGuard.required(r, 10_000, true));
    }

    @Test public void slotEndBoundaryCannotBeExempted() {
        GateRules r = fresh(); r.slotKey = r.usedSlotKey = "day#1"; r.slotEndAt = 11_000;
        assertEquals(GateRules.Screen.NONE, QuizCompletionGuard.required(r, 10_999, true));
        assertEquals(GateRules.Screen.WAIT, QuizCompletionGuard.required(r, 11_000, true));
    }

    @Test public void oldSlotUsageDoesNotBlockNewSlot() {
        GateRules r = fresh(); r.slotKey = "day#2"; r.usedSlotKey = "day#1"; r.slotUsedMs = r.slotMs;
        assertEquals(GateRules.Screen.NONE, QuizCompletionGuard.required(r, 10_000, true));
        assertEquals("day#1", r.usedSlotKey);
        assertEquals(r.slotMs, r.slotUsedMs);
    }

    @Test public void parentBonusBypassesSlotsButNotDailyCap() {
        GateRules r = fresh(); r.slotKey = ""; r.bonusUntil = 20_000;
        assertEquals(GateRules.Screen.NONE, QuizCompletionGuard.required(r, 10_000, true));
        r.dayUsedMs = r.capMs(true);
        assertEquals(GateRules.Screen.LIMIT, QuizCompletionGuard.required(r, 10_000, true));
    }

    @Test public void bonusDoesNotCancelExistingRest() {
        GateRules r = fresh(); r.slotKey = ""; r.bonusUntil = 20_000; r.restUntil = 15_000;
        assertEquals(GateRules.Screen.REST, QuizCompletionGuard.required(r, 10_000, true));
    }

    @Test public void parentModeStillRetainsExplicitOverride() {
        GateRules r = fresh(); r.parentMode = true; r.restUntil = 20_000; r.slotKey = "";
        r.dayUsedMs = r.capMs(true);
        assertEquals(GateRules.Screen.NONE, QuizCompletionGuard.required(r, 10_000, true));
    }

    @Test public void checkingNeverMutatesAccountingOrWarningState() {
        GateRules r = fresh(); r.unlocked = false; r.lastTick = 3000; r.sessionUsedMs = 6000;
        r.dayUsedMs = 12000; r.warned5 = true; r.lastWarn = GateRules.Warn.FIVE; r.justEnded = true;
        assertEquals(GateRules.Screen.NONE, QuizCompletionGuard.required(r, 10_000, true));
        assertFalse(r.unlocked); assertEquals(3000, r.lastTick); assertEquals(6000, r.sessionUsedMs);
        assertEquals(12000, r.dayUsedMs); assertTrue(r.warned5);
        assertEquals(GateRules.Warn.FIVE, r.lastWarn); assertTrue(r.justEnded);
    }
}
