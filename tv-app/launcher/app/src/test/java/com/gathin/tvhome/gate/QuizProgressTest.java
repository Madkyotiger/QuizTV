package com.gathin.tvhome.gate;

import org.junit.Test;
import static org.junit.Assert.*;

public class QuizProgressTest {
    private static QuizProgress.Assessment answer(QuizProgress p, String token, String id, boolean correct) {
        p.beginRound(token);
        p.present("subject", "point", id, false);
        return p.record("subject", "point", id, correct);
    }

    @Test public void threeIndependentCorrectRoundsAndTwoIdsMasterPoint() {
        QuizProgress p = new QuizProgress();
        assertFalse(answer(p, "round1", "a", true).mastered);
        assertFalse(answer(p, "round2", "b", true).mastered);
        assertTrue(answer(p, "round3", "a", true).mastered);
        assertEquals(3, p.stats("subject", "point").assessments);
    }

    @Test public void oneVariantCannotMasterEvenAfterManyRounds() {
        QuizProgress p = new QuizProgress();
        for (int i = 0; i < 20; i++) assertFalse(answer(p, "r" + i, "only", true).mastered);
        assertTrue(answer(p, "different", "second", true).mastered);
    }

    @Test public void eligibleWrongResetsStreakAndVariantEvidence() {
        QuizProgress p = new QuizProgress();
        answer(p, "r1", "a", true); answer(p, "r2", "b", true);
        QuizProgress.Assessment wrong = answer(p, "r3", "a", false);
        assertTrue(wrong.eligible); assertEquals(0, wrong.streak); assertEquals(0, wrong.distinctIds);
        assertFalse(answer(p, "r4", "a", true).mastered);
        assertFalse(answer(p, "r5", "a", true).mastered);
        assertFalse(answer(p, "r6", "a", true).mastered);
        assertTrue(answer(p, "r7", "b", true).mastered);
    }

    @Test public void correctiveCorrectCannotRepairWrongAssessment() {
        QuizProgress p = new QuizProgress();
        answer(p, "r1", "a", false);
        p.present("subject", "point", "b", true);
        QuizProgress.Assessment retry = p.record("subject", "point", "b", true);
        assertFalse(retry.eligible); assertEquals("corrective_retry", retry.reason);
        assertEquals(0, retry.streak); assertEquals(1, retry.assessments);
    }

    @Test public void wrongCorrectiveDoesNotResetIndependentStreak() {
        QuizProgress p = new QuizProgress();
        answer(p, "r1", "a", true);
        p.present("subject", "point", "b", true);
        assertEquals(1, p.record("subject", "point", "b", false).streak);
    }

    @Test public void onlyFirstEncounterPerPointPerRoundCountsAcrossIds() {
        QuizProgress p = new QuizProgress();
        answer(p, "r1", "a", true);
        for (int i = 0; i < 8; i++) {
            p.present("subject", "point", "b" + i, false);
            QuizProgress.Assessment repeated = p.record("subject", "point", "b" + i, true);
            assertFalse(repeated.eligible); assertEquals(1, repeated.streak);
            assertEquals("already_encountered_this_round", repeated.reason);
        }
        assertFalse(p.isMastered("subject", "point"));
    }

    @Test public void correctiveNewPointRemainsIneligibleForRestOfRound() {
        QuizProgress p = new QuizProgress(); p.beginRound("r1");
        p.present("subject", "fallback", "a", true);
        assertFalse(p.record("subject", "fallback", "a", true).eligible);
        p.present("subject", "fallback", "b", false);
        assertFalse(p.record("subject", "fallback", "b", true).eligible);
        assertEquals(0, p.stats("subject", "fallback").assessments);
        p.beginRound("r2"); p.present("subject", "fallback", "b", false);
        assertTrue(p.record("subject", "fallback", "b", true).eligible);
    }

    @Test public void recreationRestorePreservesPendingAnswerAndConsumesExactlyOnce() {
        QuizProgress p = new QuizProgress(); p.beginRound("stable-token");
        p.present("subject", "point", "a", false);
        QuizProgress recreated = QuizProgress.restore(p.encode());
        assertFalse(recreated.beginRound("stable-token")); assertEquals(1L, recreated.round());
        assertTrue(recreated.record("subject", "point", "a", true).eligible);
        QuizProgress again = QuizProgress.restore(recreated.encode());
        assertFalse(again.beginRound("stable-token"));
        assertFalse(again.record("subject", "point", "a", true).eligible);
        again.present("subject", "point", "b", false);
        assertFalse(again.record("subject", "point", "b", true).eligible);
        assertEquals(1, again.stats("subject", "point").streak);
    }

    @Test public void subjectAndPointPairCannotCollide() {
        QuizProgress p = new QuizProgress();
        answer(p, "r1", "a", true); answer(p, "r2", "b", true); answer(p, "r3", "a", true);
        assertFalse(p.isMastered("other", "point"));
        p.present("a", "bc", "q", false); p.record("a", "bc", "q", true);
        assertEquals(0, p.stats("ab", "c").assessments);
    }

    @Test public void masteryLatchSurvivesRestoreAndNeverResetsThroughRecord() {
        QuizProgress p = new QuizProgress();
        answer(p, "r1", "a", true); answer(p, "r2", "b", true); answer(p, "r3", "a", true);
        p = QuizProgress.restore(p.encode());
        assertTrue(answer(p, "r4", "b", false).mastered);
        assertEquals(3, p.stats("subject", "point").streak);
        QuizProgress.Stats snapshot = p.stats("subject", "point"); snapshot.mastered = false;
        assertTrue(p.isMastered("subject", "point"));
    }

    @Test public void unpresentedAnswersAreNotEvidence() {
        QuizProgress p = new QuizProgress(); p.beginRound("r1");
        assertFalse(p.record("subject", "point", "a", true).eligible);
        assertEquals(0, p.stats("subject", "point").assessments);
    }

    @Test public void serializationIsDeterministicAndNewHistoryStartsBlank() {
        QuizProgress p = QuizProgress.restore("");
        assertEquals(0, p.stats("subject", "point").streak);
        assertFalse(p.active()); assertEquals(0L, p.round());
        answer(p, "r1", "a", true);
        assertEquals(p.encode(), QuizProgress.restore(p.encode()).encode());
    }

    @Test(expected = IllegalArgumentException.class) public void corruptStateDoesNotSilentlyResetMastery() {
        QuizProgress.restore("invalid history");
    }
    @Test(expected = IllegalArgumentException.class) public void emptyTokenIsNotNewRound() {
        new QuizProgress().beginRound("");
    }
}
