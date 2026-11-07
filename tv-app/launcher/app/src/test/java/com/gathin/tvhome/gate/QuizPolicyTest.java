package com.gathin.tvhome.gate;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.Assert.*;

public class QuizPolicyTest {
    private static QuizPolicy.Question q(String subject, String kp, String id) {
        QuizPolicy.Question q = new QuizPolicy.Question();
        q.subject = subject; q.kp = kp; q.id = id; q.ref = ""; return q;
    }
    private static QuizPolicy policy(QuizProgress p) { return new QuizPolicy(p, true, new Random(17)); }
    private static Map<String, Integer> mix() {
        Map<String, Integer> m = new HashMap<>();
        m.put("数学", 3); m.put("语文", 3); m.put("英语", 4); return m;
    }
    private static List<QuizPolicy.Question> bank() {
        return Arrays.asList(q("英语", "point", "e1"), q("英语", "point", "e2"),
                q("语文", "point", "c1"), q("语文", "point", "c2"),
                q("数学", "point", "m1"), q("数学", "point", "m2"));
    }
    private static int count(List<String> plan, String subject) { return Collections.frequency(plan, subject); }
    private static void master(QuizProgress p, String subject, String kp) {
        for (int i = 0; i < 3; i++) {
            p.beginRound("master-" + subject + kp + i);
            p.present(subject, kp, i % 2 == 0 ? "a" : "b", false);
            p.record(subject, kp, i % 2 == 0 ? "a" : "b", true);
        }
    }

    @Test public void threeRoundRotationTotalsTenPerSubjectAcrossRestarts() {
        QuizProgress p = new QuizProgress(); Map<String, Integer> totals = new HashMap<>();
        String[] extras = {"英语", "语文", "数学"};
        for (int round = 0; round < 3; round++) {
            p.beginRound("round" + round); QuizPolicy policy = policy(p);
            List<String> plan = policy.plan(bank(), mix(), true);
            assertEquals(10, plan.size());
            for (String subject : mix().keySet()) {
                assertEquals(subject.equals(extras[round]) ? 4 : 3, count(plan, subject));
                totals.put(subject, totals.getOrDefault(subject, 0) + count(plan, subject));
            }
            p = QuizProgress.restore(p.encode());
            assertFalse(p.beginRound("round" + round));
            assertEquals(plan, new QuizPolicy(p, true, new Random(999)).plan(bank(), mix(), true));
        }
        for (int value : totals.values()) assertEquals(10, value);
    }

    @Test public void balancedModePreservesConfiguredTotalWithoutHardcodedTen() {
        QuizProgress p = new QuizProgress(); p.beginRound("r");
        Map<String, Integer> mix = mix(); mix.put("英语", 1);
        List<String> result = policy(p).plan(bank(), mix, true);
        assertEquals(7, result.size()); assertEquals(3, count(result, "英语"));
    }

    @Test public void oneSubjectExhaustedRedistributesAllTenSlots() {
        QuizProgress p = new QuizProgress(); master(p, "英语", "point"); p.beginRound("quiz");
        List<String> plan = policy(p).plan(bank(), mix(), true);
        assertEquals(10, plan.size()); assertEquals(0, count(plan, "英语"));
        assertEquals(5, count(plan, "语文")); assertEquals(5, count(plan, "数学"));
        assertNull(policy(p).next(bank(), "英语", new ArrayList<>(), Collections.emptyMap()));
    }

    @Test public void twoSubjectsExhaustedRemainingSubjectGetsTen() {
        QuizProgress p = new QuizProgress(); master(p, "英语", "point"); master(p, "语文", "point");
        p.beginRound("quiz"); List<String> result = policy(p).plan(bank(), mix(), true);
        assertEquals(10, result.size()); assertEquals(10, count(result, "数学"));
    }

    @Test public void allMasteredGivesEmptyPlanAndPositiveEvidenceCheck() {
        QuizProgress p = new QuizProgress();
        for (String s : mix().keySet()) master(p, s, "point");
        p.beginRound("quiz"); QuizPolicy policy = policy(p);
        assertTrue(policy.plan(bank(), mix(), true).isEmpty());
        assertTrue(policy.allMastered(bank(), mix()));
        assertNull(policy.next(bank(), "数学", new ArrayList<>(), Collections.emptyMap()));
        assertNull(policy.similar(bank(), bank().get(0), new ArrayList<>(), Collections.emptyMap()));
    }

    @Test public void missingOrEmptyBankAndEmptyMixAreNeverAllMastered() {
        QuizProgress p = new QuizProgress(); master(p, "英语", "point");
        QuizPolicy policy = policy(p);
        assertFalse(policy.allMastered(Collections.emptyList(), mix()));
        assertFalse(policy.allMastered(bank(), Collections.emptyMap()));
        assertFalse(policy.allMastered(bank().subList(0, 2), mix()));
        p.beginRound("quiz"); assertTrue(policy.plan(Collections.emptyList(), mix(), true).isEmpty());
        assertFalse(policy.allMastered(Collections.emptyList(), mix()));
    }

    @Test public void disabledSubjectsDoNotPreventPositiveMasteryCheck() {
        QuizProgress p = new QuizProgress(); master(p, "英语", "point");
        Map<String, Integer> m = new HashMap<>(); m.put("英语", 4); m.put("数学", 0);
        assertTrue(policy(p).allMastered(bank(), m));
    }

    @Test public void newPointImmediatelyRevokesAllMasteredExemption() {
        QuizProgress p = new QuizProgress(); master(p, "英语", "point");
        List<QuizPolicy.Question> questions = new ArrayList<>(bank().subList(0, 2));
        assertTrue(policy(p).allMastered(questions, Collections.singletonMap("英语", 10)));
        questions.add(q("英语", "new", "new1"));
        assertFalse(policy(p).allMastered(questions, Collections.singletonMap("英语", 10)));
    }

    @Test public void masteredPointsNeverEnterNormalOrAnyRetryTier() {
        QuizProgress p = new QuizProgress(); master(p, "s", "done"); p.beginRound("quiz");
        QuizPolicy policy = policy(p);
        QuizPolicy.Question done = q("s", "done", "done"), otherSubject = q("other", "todo", "other");
        QuizPolicy.Question todo = q("s", "todo", "todo");
        done.ref = "entry"; todo.ref = "entry";
        List<QuizPolicy.Question> questions = Arrays.asList(done, todo, otherSubject);
        for (int i = 0; i < 50; i++) {
            assertSame(todo, policy.next(questions, "s", new ArrayList<>(), Collections.singletonMap("done", 6)));
            assertSame(todo, policy.similar(questions, todo, new ArrayList<>(), Collections.emptyMap()));
        }
        assertNull(policy.next(questions, "missing", new ArrayList<>(), Collections.emptyMap()));
    }

    @Test public void samePointNameInOtherSubjectIsNeverCorrectiveCandidate() {
        QuizProgress p = new QuizProgress(); p.beginRound("r");
        QuizPolicy.Question wrong = q("s", "same", "a"), cross = q("other", "same", "b");
        QuizPolicy.Question selected = policy(p).similar(Arrays.asList(wrong, cross), wrong, new ArrayList<>(), Collections.emptyMap());
        assertSame(wrong, selected);
        assertFalse(p.record(selected.subject, selected.kp, selected.id, true).eligible);
    }

    @Test public void samePointRetryPrefersDifferentItemAndNeverCountsAsAssessment() {
        QuizProgress p = new QuizProgress(); p.beginRound("r"); QuizPolicy policy = policy(p);
        List<QuizPolicy.Question> questions = bank().subList(0, 2);
        QuizPolicy.Question first = policy.next(questions, "英语", new ArrayList<>(), Collections.emptyMap());
        assertTrue(p.record(first.subject, first.kp, first.id, false).eligible);
        QuizPolicy.Question retry = policy.similar(questions, first, new ArrayList<>(), Collections.emptyMap());
        assertNotEquals(first.id, retry.id);
        assertFalse(p.record(retry.subject, retry.kp, retry.id, true).eligible);
        assertEquals(0, p.stats("英语", "point").streak);
    }

    @Test public void differentPointFallbackIsStillCorrective() {
        QuizProgress p = new QuizProgress(); p.beginRound("r");
        QuizPolicy.Question wrong = q("s", "wrong", "a"), other = q("s", "new", "b");
        wrong.ref = other.ref = "entry";
        QuizPolicy.Question selected = policy(p).similar(Arrays.asList(wrong, other), wrong, new ArrayList<>(), Collections.emptyMap());
        assertSame(other, selected);
        assertFalse(p.record("s", "new", "b", true).eligible);
    }

    @Test public void untriedPointOutranksHistoricErrorWeightsAndPreviouslySeenPoint() {
        QuizProgress p = new QuizProgress(); p.beginRound("old");
        p.present("s", "practiced", "p", false); p.record("s", "practiced", "p", false);
        p.beginRound("quiz");
        QuizPolicy.Question practiced = q("s", "practiced", "p"), untried = q("s", "new", "n");
        QuizPolicy policy = policy(p); List<QuizPolicy.Question> questions = Arrays.asList(practiced, untried);
        assertSame(untried, policy.next(questions, "s", new ArrayList<>(), Collections.singletonMap("practiced", 6)));
        p.record("s", "new", "n", true);
        assertSame(practiced, policy.next(questions, "s", new ArrayList<>(), Collections.emptyMap()));
    }

    @Test public void lessPracticedPointWinsAcrossRounds() {
        QuizProgress p = new QuizProgress();
        for (int i = 0; i < 2; i++) {
            p.beginRound("r" + i); p.present("s", "more", "a", false); p.record("s", "more", "a", false);
        }
        p.present("s", "less", "b", false); p.record("s", "less", "b", false);
        p.beginRound("quiz");
        QuizPolicy.Question less = q("s", "less", "b"), more = q("s", "more", "a");
        assertSame(less, policy(p).next(Arrays.asList(more, less), "s", new ArrayList<>(), Collections.singletonMap("more", 6)));
    }

    @Test public void itemVariantsAreExercisedAndActualDrawsReachMastery() {
        QuizProgress p = new QuizProgress(); List<String> recent = new ArrayList<>();
        List<QuizPolicy.Question> questions = bank().subList(0, 2);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            p.beginRound("r" + i);
            QuizPolicy.Question pick = policy(p).next(questions, "英语", recent, Collections.emptyMap());
            ids.add(pick.id); p.record(pick.subject, pick.kp, pick.id, true);
            p = QuizProgress.restore(p.encode());
        }
        assertNotEquals(ids.get(0), ids.get(1)); assertTrue(p.isMastered("英语", "point"));
    }

    @Test public void recentListKeepsBoundAndAvoidsRecentVariantOnTies() {
        QuizProgress p = new QuizProgress(); p.beginRound("r"); List<String> recent = new ArrayList<>();
        for (int i = 0; i < 120; i++) recent.add("old" + i);
        recent.set(119, "e1");
        QuizPolicy.Question pick = policy(p).next(bank().subList(0, 2), "英语", recent, Collections.emptyMap());
        assertEquals("e2", pick.id); assertEquals(120, recent.size()); assertEquals("old1", recent.get(0));
    }

    @Test public void legacyModeKeepsFixedMixAndDoesNotExcludeMasteredPoints() {
        QuizProgress p = new QuizProgress(); master(p, "英语", "point");
        QuizPolicy legacy = new QuizPolicy(p, false, new Random(0));
        List<String> plan = legacy.plan(bank(), mix(), false);
        assertEquals(4, count(plan, "英语")); assertEquals(3, count(plan, "语文"));
        assertNotNull(legacy.next(bank(), "英语", new ArrayList<>(), Collections.emptyMap()));
        assertFalse(legacy.allMastered(bank(), mix()));
    }

    @Test public void legacyModeNeedsNoRoundAndRetainsDefaultFallback() {
        QuizPolicy legacy = new QuizPolicy(new QuizProgress(), false, new Random(0));
        assertEquals(5, legacy.plan(Collections.emptyList(), Collections.emptyMap(), false).size());
        assertNotNull(legacy.next(bank(), "英语", new ArrayList<>(), Collections.emptyMap()));
    }

    @Test public void balancingCanRunWithoutMasteryAndMasteryWithoutBalancing() {
        QuizProgress p = new QuizProgress(); p.beginRound("r1");
        List<String> balanced = new QuizPolicy(p, false, new Random(0)).plan(bank(), mix(), true);
        assertEquals(4, count(balanced, "英语"));
        p.beginRound("r2"); balanced = new QuizPolicy(p, false, new Random(0)).plan(bank(), mix(), true);
        assertEquals(4, count(balanced, "语文"));
        master(p, "英语", "point"); p.beginRound("quiz");
        List<String> filtered = policy(p).plan(bank(), mix(), false);
        assertEquals(10, filtered.size()); assertEquals(0, count(filtered, "英语"));
    }

    @Test(expected = IllegalStateException.class) public void balancedPlanningRequiresExplicitRoundBoundary() {
        policy(new QuizProgress()).plan(bank(), mix(), true);
    }
}
