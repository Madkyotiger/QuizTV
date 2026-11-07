package com.gathin.tvhome.gate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GateRulesTest {
    private static final long S = 1000, MIN = GateRules.MIN;

    private GateRules.Screen run(GateRules r, long[] now, long ms, boolean weekend) {
        GateRules.Screen s = GateRules.Screen.NONE;
        for (long t = 0; t < ms; t += S) {
            now[0] += S;
            s = r.tick(now[0], true, "2026-11-04", weekend);
        }
        return s;
    }

    @Test public void lockedUntilQuiz() {
        GateRules r = new GateRules();
        assertEquals(GateRules.Screen.QUIZ, r.tick(1_000_000, true, "2026-11-04", false));
    }

    @Test public void fortyMinutesThenTwentyRestThenQuiz() {
        GateRules r = new GateRules();
        long[] now = {1_000_000};
        r.tick(now[0], true, "2026-11-04", true);
        r.unlock(now[0]);
        assertEquals(GateRules.Screen.NONE, run(r, now, 39 * MIN, true));
        assertEquals(GateRules.Screen.REST, run(r, now, 1 * MIN + 2 * S, true));
        assertEquals(GateRules.Screen.REST, run(r, now, 19 * MIN, true));
        assertEquals(GateRules.Screen.QUIZ, run(r, now, 1 * MIN + 2 * S, true));
    }

    @Test public void weekdayCapOneHour() {
        GateRules r = new GateRules();
        long[] now = {1_000_000};
        r.tick(now[0], true, "2026-11-04", false);
        r.unlock(now[0]);
        run(r, now, 41 * MIN, false);        // session 1 -> rest
        run(r, now, 21 * MIN, false);        // rest over -> quiz
        r.unlock(now[0]);
        assertEquals(GateRules.Screen.LIMIT, run(r, now, 21 * MIN, false));
    }

    @Test public void shortStandbyKeepsSessionLongResetsIt() {
        GateRules r = new GateRules();
        long[] now = {1_000_000};
        r.tick(now[0], true, "2026-11-04", true);
        r.unlock(now[0]);
        run(r, now, 10 * MIN, true);
        now[0] += 5 * MIN; // TV off five minutes (time not counted)
        assertEquals(GateRules.Screen.NONE, r.tick(now[0], true, "2026-11-04", true));
        assertTrue(r.sessionUsedMs < 11 * MIN);
        now[0] += 25 * MIN; // off long enough: session ends
        assertEquals(GateRules.Screen.QUIZ, r.tick(now[0], true, "2026-11-04", true));
    }

    @Test public void parentModeEndsAtStandby() {
        GateRules r = new GateRules();
        r.enterParentMode();
        assertEquals(GateRules.Screen.NONE, r.tick(1_000_000, true, "2026-11-04", false));
        r.tick(1_001_000, false, "2026-11-04", false);
        assertFalse(r.parentMode);
        assertEquals(GateRules.Screen.QUIZ, r.tick(1_002_000, true, "2026-11-04", false));
    }

    @Test public void warnsFiveAndOneMinuteBefore() {
        GateRules r = new GateRules();
        long[] now = {1_000_000};
        r.tick(now[0], true, "2026-11-04", true);
        r.unlock(now[0]);
        int five = 0, one = 0;
        for (long t = 0; t < 40 * MIN; t += S) {
            now[0] += S;
            r.tick(now[0], true, "2026-11-04", true);
            if (r.lastWarn == GateRules.Warn.FIVE) five++;
            if (r.lastWarn == GateRules.Warn.ONE) one++;
        }
        assertEquals(1, five);
        assertEquals(1, one);
    }

    @Test public void newDayResetsUsage() {
        GateRules r = new GateRules();
        r.dayKey = "2026-11-04";
        r.dayUsedMs = 60 * MIN;
        assertEquals(GateRules.Screen.LIMIT, r.tick(1_000_000, true, "2026-11-04", false));
        assertEquals(GateRules.Screen.QUIZ, r.tick(1_001_000, true, "2026-11-05", false));
    }

    @Test public void thirtyMinutesThenFullTenMinuteBlockingRest() {
        GateRules r = new GateRules();
        r.sessionMs = 30 * MIN;
        r.restMs = 10 * MIN;
        long[] now = {1_000_000};
        r.tick(now[0], true, "2026-11-04", true);
        r.unlock(now[0]);
        assertEquals(GateRules.Screen.REST, run(r, now, 30 * MIN, true));
        long until = r.restUntil;
        assertEquals(now[0] + 10 * MIN, until);
        for (long t = now[0] + S; t < until; t += S) {
            assertEquals("Rest may not briefly release TV", GateRules.Screen.REST,
                    r.tick(t, true, "2026-11-04", true));
            assertFalse(r.unlocked);
            assertEquals(until, r.restUntil);
        }
        assertEquals(GateRules.Screen.QUIZ, r.tick(until, true, "2026-11-04", true));
    }

    @Test public void lateQuizCompletionCannotUnlockAnActiveRest() {
        GateRules r = new GateRules();
        long now = 1_000_000;
        r.dayKey = "2026-11-04";
        r.restUntil = now + 10 * MIN;
        r.unlocked = false;
        r.unlock(now + S); // stale UI callback after the rest page has appeared
        assertFalse("A quiz callback must not unlock an active rest", r.unlocked);
        assertEquals(now + 10 * MIN, r.restUntil);
        assertEquals(GateRules.Screen.REST, r.tick(now + 2 * S, true, "2026-11-04", true));
        assertEquals(GateRules.Screen.QUIZ, r.tick(r.restUntil, true, "2026-11-04", true));
    }

    @Test public void standbyDoesNotCancelOrRestartRestDeadline() {
        GateRules r = new GateRules();
        long now = 1_000_000;
        r.dayKey = "2026-11-04";
        r.restUntil = now + 10 * MIN;
        assertEquals(GateRules.Screen.NONE, r.tick(now + MIN, false, "2026-11-04", true));
        assertEquals(GateRules.Screen.REST, r.tick(now + 2 * MIN, true, "2026-11-04", true));
        assertEquals(now + 10 * MIN, r.restUntil);
    }

    @Test public void restReassertsEvenWhenCachedActivityLooksVisible() {
        assertTrue(GateRules.shouldReassert(GateRules.Screen.REST, GateRules.Screen.REST));
        assertTrue(GateRules.shouldReassert(GateRules.Screen.REST, null));
        assertTrue(GateRules.shouldReassert(GateRules.Screen.REST, GateRules.Screen.QUIZ));
    }

    @Test public void otherScreensDoNotRelaunchUnnecessarily() {
        assertFalse(GateRules.shouldReassert(GateRules.Screen.NONE, null));
        assertFalse(GateRules.shouldReassert(GateRules.Screen.QUIZ, GateRules.Screen.QUIZ));
        assertTrue(GateRules.shouldReassert(GateRules.Screen.QUIZ, null));
    }

    @Test public void staleUnlockedFlagCannotSurviveRest() {
        GateRules r = new GateRules();
        long now = 1_000_000;
        r.dayKey = "2026-11-04";
        r.restUntil = now + 10 * MIN;
        r.unlocked = true;
        assertEquals(GateRules.Screen.REST, r.tick(now, true, "2026-11-04", true));
        assertFalse(r.unlocked);
        assertEquals(GateRules.Screen.QUIZ, r.tick(r.restUntil, true, "2026-11-04", true));
    }

    @Test public void aNewQuizCanUnlockAtButNotBeforeTheRestDeadline() {
        GateRules r = new GateRules();
        long now = 1_000_000;
        r.restUntil = now + 10 * MIN;
        assertFalse(r.unlock(r.restUntil - 1));
        long deadline = r.restUntil;
        assertTrue(r.unlock(deadline));
        assertTrue(r.unlocked);
        assertEquals(0, r.restUntil);
    }

    @Test public void weekendWindow() {
        String[] w = {"FRI 16:00-SUN 19:00"};
        assertFalse(GateRules.inWindows(w, 3, 20 * 60));      // Wednesday evening
        assertFalse(GateRules.inWindows(w, 5, 15 * 60 + 59)); // Friday 15:59
        assertTrue(GateRules.inWindows(w, 5, 16 * 60));       // Friday 16:00
        assertTrue(GateRules.inWindows(w, 6, 9 * 60));        // Saturday morning
        assertTrue(GateRules.inWindows(w, 7, 18 * 60 + 59));  // Sunday 18:59
        assertFalse(GateRules.inWindows(w, 7, 19 * 60));      // Sunday 19:00
        assertTrue(GateRules.inWindows(new String[]{"SUN 20:00-MON 08:00"}, 1, 7 * 60)); // wraps
    }
}
