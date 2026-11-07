package com.gathin.tvhome.gate;

import static org.junit.Assert.*;

import org.junit.Test;

/** Weekend/Friday time slots (family rule 2026-11-07): quiz first, at most 30 min per slot, locked between slots. */
public class GateSlotTest {
    private static final long S = 1000, MIN = GateRules.MIN;
    private static final String[] SLOTS = {"FRI 16:00-18:00", "FRI 19:00-20:30", "SAT 08:00-11:30", "SAT 13:30-17:00",
            "SAT 18:30-20:30", "SUN 08:00-11:30", "SUN 13:30-17:00", "SUN 18:30-20:30"};

    private static GateRules rules() {
        GateRules r = new GateRules();
        r.sessionMs = 30 * MIN; r.restMs = 10 * MIN; r.capWeekendMs = 120 * MIN; r.capWeekdayMs = 60 * MIN;
        r.slotMs = 30 * MIN;
        return r;
    }

    /** Ticks once a second; slot "a" ends at slotEnd. */
    private static GateRules.Screen run(GateRules r, long[] now, long ms, String slot, long slotEnd) {
        GateRules.Screen s = GateRules.Screen.NONE;
        for (long t = 0; t < ms; t += S) {
            now[0] += S;
            r.slotKey = now[0] < slotEnd ? slot : "";
            r.slotEndAt = now[0] < slotEnd ? slotEnd : 0;
            s = r.tick(now[0], true, "2026-11-07", true);
        }
        return s;
    }

    @Test public void slotLookup() {
        assertEquals(2, GateRules.slotIndex(SLOTS, 6, 9 * 60));          // SAT 09:00
        assertEquals(-1, GateRules.slotIndex(SLOTS, 6, 12 * 60));        // SAT 12:00 between slots
        assertEquals(-1, GateRules.slotIndex(SLOTS, 6, 11 * 60 + 30));   // end is exclusive
        assertEquals(1, GateRules.slotIndex(SLOTS, 5, 20 * 60 + 29));    // FRI 20:29
        assertEquals(30, GateRules.minutesToSlotEnd(SLOTS, 2, 6, 11 * 60));
        assertEquals("13:30", GateRules.nextSlotToday(SLOTS, 6, 12 * 60));
        assertEquals("19:00", GateRules.nextSlotToday(SLOTS, 5, 18 * 60 + 10));
        assertNull(GateRules.nextSlotToday(SLOTS, 7, 20 * 60 + 31));    // Sunday night: tomorrow
        assertEquals("08:00", GateRules.nextSlotToday(SLOTS, 6, 7 * 60)); // early Saturday morning
        String[] full = {"SAT 08:00-SAT 11:30"};                          // long form from tvctl also works
        assertEquals(0, GateRules.slotIndex(full, 6, 9 * 60));
        assertEquals(150, GateRules.minutesToSlotEnd(full, 0, 6, 9 * 60));
    }

    @Test public void betweenSlotsIsLocked() {
        GateRules r = rules();
        r.slotKey = "";
        assertEquals(GateRules.Screen.WAIT, r.tick(1_000_000, true, "2026-11-07", true));
        assertFalse(r.unlocked);
    }

    @Test public void quizThenThirtyMinutesThenWaitForNextSlot() {
        GateRules r = rules();
        long[] now = {1_000_000};
        long end = now[0] + 200 * MIN;
        assertEquals(GateRules.Screen.QUIZ, run(r, now, 2 * S, "d#2", end));
        r.unlock(now[0]);
        assertEquals(GateRules.Screen.NONE, run(r, now, 29 * MIN, "d#2", end));
        assertEquals(GateRules.Screen.WAIT, run(r, now, 1 * MIN + 2 * S, "d#2", end));
        assertEquals(GateRules.Screen.WAIT, run(r, now, 60 * MIN, "d#2", end)); // no second round in this slot
        assertEquals(0, r.restUntil);                                       // slot end is not a 10-minute rest
    }

    @Test public void comingBackInTheSameSlotAfterABreakNeedsQuizAndGetsOnlyTheRest() {
        GateRules r = rules();
        long[] now = {1_000_000};
        long end = now[0] + 200 * MIN;
        run(r, now, 2 * S, "d#2", end);
        r.unlock(now[0]);
        run(r, now, 15 * MIN, "d#2", end);
        now[0] += 40 * MIN; // TV off 40 minutes
        assertEquals(GateRules.Screen.QUIZ, run(r, now, 2 * S, "d#2", end));
        r.unlock(now[0]);
        assertEquals(GateRules.Screen.NONE, run(r, now, 14 * MIN, "d#2", end));
        assertEquals(GateRules.Screen.WAIT, run(r, now, 1 * MIN + 2 * S, "d#2", end));
    }

    @Test public void shortBreakInSlotContinuesWithoutQuiz() {
        GateRules r = rules();
        long[] now = {1_000_000};
        long end = now[0] + 200 * MIN;
        run(r, now, 2 * S, "d#2", end);
        r.unlock(now[0]);
        run(r, now, 10 * MIN, "d#2", end);
        now[0] += 5 * MIN;
        assertEquals(GateRules.Screen.NONE, run(r, now, 2 * S, "d#2", end));
    }

    @Test public void slotEndCutsWatchingWithWarnings() {
        GateRules r = rules();
        long[] now = {1_000_000};
        long end = now[0] + 12 * MIN; // started 12 minutes before the slot ends
        run(r, now, 2 * S, "d#2", end);
        r.unlock(now[0]);
        assertEquals(12 * MIN - 2 * S, r.watchLeftMs(now[0], true));
        boolean warned5 = false;
        GateRules.Screen s = GateRules.Screen.NONE;
        for (int i = 0; i < 12 * 60; i++) {
            s = run(r, now, S, "d#2", end);
            if (r.lastWarn == GateRules.Warn.FIVE) warned5 = true;
        }
        assertTrue(warned5);
        assertEquals(GateRules.Screen.WAIT, s);
    }

    @Test public void newSlotStartsWithQuizEvenIfUnlocked() {
        GateRules r = rules();
        long[] now = {1_000_000};
        long end = now[0] + 200 * MIN;
        run(r, now, 2 * S, "d#2", end);
        r.unlock(now[0]);
        run(r, now, 5 * MIN, "d#2", end);
        assertEquals(GateRules.Screen.QUIZ, run(r, now, 2 * S, "d#3", end));
        assertEquals(0, r.slotUsedMs);
    }

    @Test public void parentBonusWorksBetweenSlots() {
        GateRules r = rules();
        long[] now = {1_000_000};
        r.slotKey = "";
        r.lastTick = now[0];
        assertEquals(GateRules.Screen.WAIT, r.tick(now[0], true, "2026-11-07", true));
        r.addBonus(20 * MIN);
        r.unlock(now[0]);
        r.sessionUsedMs = Math.max(0, r.sessionMs - 20 * MIN);
        assertEquals(GateRules.Screen.NONE, run(r, now, 19 * MIN, "", 0));
        assertEquals(GateRules.Screen.WAIT, run(r, now, 2 * MIN, "", 0));
    }

    @Test public void noSlotsKeepsOldBehaviour() {
        GateRules r = rules();
        long[] now = {1_000_000};
        r.tick(now[0], true, "2026-11-07", true);
        r.unlock(now[0]);
        GateRules.Screen s = GateRules.Screen.NONE;
        for (int i = 0; i < 31 * 60; i++) { now[0] += S; s = r.tick(now[0], true, "2026-11-07", true); }
        assertEquals(GateRules.Screen.REST, s);
    }

    @Test public void goodbyeOnlyWhenWatchingJustEnded() {
        GateRules r = rules();
        long[] now = {1_000_000};
        long end = now[0] + 200 * MIN;
        run(r, now, 2 * S, "d#2", end);
        r.unlock(now[0]);
        int byes = 0;
        for (int i = 0; i < 40 * 60; i++) { run(r, now, S, "d#2", end); if (r.justEnded) byes++; }
        assertEquals(1, byes);                       // exactly once, at the end of the 30 minutes
        GateRules fresh = rules();
        fresh.slotKey = "";
        fresh.tick(5_000_000, true, "2026-11-07", true);
        assertFalse(fresh.justEnded);                // turning the TV on between slots: no goodbye
    }

    @Test public void goodbyeWhenSlotEndsMidProgramme() {
        GateRules r = rules();
        long[] now = {1_000_000};
        long end = now[0] + 10 * MIN;
        run(r, now, 2 * S, "d#2", end);
        r.unlock(now[0]);
        boolean bye = false;
        for (int i = 0; i < 11 * 60; i++) { run(r, now, S, "d#2", end); bye |= r.justEnded; }
        assertTrue(bye);
    }
}
