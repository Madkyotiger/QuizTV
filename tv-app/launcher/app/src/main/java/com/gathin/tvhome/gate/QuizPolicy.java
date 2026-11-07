package com.gathin.tvhome.gate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** Android-free planning and selection; mastery and balanced planning are independently opt-in. */
public final class QuizPolicy {
    public static class Question {
        public String id, subject, kp, ref;
    }

    private final QuizProgress progress;
    private final boolean mastery;
    private final Random random;

    public QuizPolicy(QuizProgress progress, boolean mastery, Random random) {
        this.progress = progress; this.mastery = mastery; this.random = random;
    }

    private boolean available(Question q) { return !mastery || !progress.isMastered(q.subject, q.kp); }

    /** Requires positive evidence for every enabled subject, not vacuous truth over an empty bank. */
    public <T extends Question> boolean allMastered(List<T> bank, Map<String, Integer> mix) {
        if (!mastery) return false;
        List<String> enabled = subjects(mix);
        if (enabled.isEmpty()) return false;
        for (String subject : enabled) {
            boolean any = false;
            for (T q : bank) if (q.subject.equals(subject)) {
                any = true;
                if (!progress.isMastered(q.subject, q.kp)) return false;
            }
            if (!any) return false;
        }
        return true;
    }

    /** English, Chinese, Math form the stable rotation; arbitrary other subjects remain supported. */
    private static List<String> subjects(Map<String, Integer> mix) {
        List<String> result = new ArrayList<>();
        for (String s : new String[]{"英语", "语文", "数学"}) {
            if (mix.getOrDefault(s, 0) > 0) result.add(s);
        }
        for (String s : new TreeMap<>(mix).keySet()) {
            if (mix.getOrDefault(s, 0) > 0 && !result.contains(s)) result.add(s);
        }
        return result;
    }

    public <T extends Question> List<String> plan(List<T> bank, Map<String, Integer> mix, boolean balanced) {
        if (balanced && !progress.active()) throw new IllegalStateException("beginRound required for balanced planning");
        List<String> cached = progress.cachedPlan();
        if (progress.active() && cached != null) return cached;
        List<String> configured = subjects(mix), active = new ArrayList<>();
        int total = 0;
        for (String subject : configured) {
            total += mix.get(subject);
            for (T q : bank) if (q.subject.equals(subject) && available(q)) {
                active.add(subject); break;
            }
        }
        List<String> result = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String subject : active) counts.put(subject, 0);
        if (!active.isEmpty()) {
            if (balanced) {
                int n = configured.size(), offset = (int) ((progress.round() - 1) % n);
                int missing = 0;
                for (int i = 0; i < n; i++) {
                    String subject = configured.get(i);
                    int allocation = total / n + (((i - offset + n) % n) < total % n ? 1 : 0);
                    if (counts.containsKey(subject)) counts.put(subject, allocation);
                    else missing += allocation;
                }
                // Redistribute exhausted subjects' slots; balanced least-count allocation, stable rotating ties.
                while (missing-- > 0) {
                    String best = null;
                    for (int j = 0; j < n; j++) {
                        String s = configured.get((offset + j) % n);
                        if (counts.containsKey(s) && (best == null || counts.get(s) < counts.get(best))) best = s;
                    }
                    counts.put(best, counts.get(best) + 1);
                }
            } else {
                for (String subject : active) counts.put(subject, mix.get(subject));
                if (mastery) {
                    int missing = total;
                    for (int value : counts.values()) missing -= value;
                    while (missing-- > 0) {
                        String best = active.get(0);
                        for (String s : active) if (counts.get(s) < counts.get(best)) best = s;
                        counts.put(best, counts.get(best) + 1);
                    }
                }
            }
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                for (int i = 0; i < e.getValue(); i++) result.add(e.getKey());
            }
        } else if (!mastery && !balanced) {
            Collections.addAll(result, "数学", "数学", "数学", "英语", "英语");
        }
        Collections.shuffle(result, random);
        if (progress.active()) progress.cachePlan(result);
        return result;
    }

    public <T extends Question> T next(List<T> bank, String subject, List<String> recent, Map<String, Integer> weights) {
        Map<String, List<T>> byKp = new TreeMap<>();
        for (T q : bank) if (q.subject.equals(subject) && available(q)) {
            byKp.computeIfAbsent(q.kp, k -> new ArrayList<>()).add(q);
        }
        if (byKp.isEmpty()) return null;
        String kp;
        if (mastery) {
            List<String> points = new ArrayList<>(byKp.keySet());
            // Never let a large historic error weight outrank an untried/less-practiced point.
            Comparator<String> priority = Comparator
                    .comparing((String k) -> progress.encountered(subject, k))
                    .thenComparingInt(k -> progress.stats(subject, k).assessments)
                    .thenComparingInt(k -> progress.stats(subject, k).presentations)
                    .thenComparingLong(k -> progress.stats(subject, k).lastPresentedRound)
                    .thenComparingInt(k -> -weights.getOrDefault(k, 0));
            points.sort(priority);
            List<String> tied = new ArrayList<>();
            for (String k : points) if (priority.compare(k, points.get(0)) == 0) tied.add(k);
            kp = tied.get(random.nextInt(tied.size()));
        } else {
            int total = 0;
            for (String k : byKp.keySet()) total += 1 + 3 * weights.getOrDefault(k, 0);
            int pick = random.nextInt(Math.max(total, 1));
            kp = byKp.keySet().iterator().next();
            for (String k : byKp.keySet()) {
                pick -= 1 + 3 * weights.getOrDefault(k, 0);
                if (pick < 0) { kp = k; break; }
            }
        }
        return choose(byKp.get(kp), recent, false);
    }

    /** All fallback tiers are same-subject and unmastered; every tier is corrective, even a new KP. */
    public <T extends Question> T similar(List<T> bank, T wrong, List<String> recent, Map<String, Integer> weights) {
        List<T> same = new ArrayList<>(), ref = new ArrayList<>(), subject = new ArrayList<>();
        for (T q : bank) if (q.subject.equals(wrong.subject) && available(q)) {
            subject.add(q);
            if (!q.id.equals(wrong.id)) {
                if (q.kp.equals(wrong.kp)) same.add(q);
                if (wrong.ref != null && !wrong.ref.isEmpty() && wrong.ref.equals(q.ref)) ref.add(q);
            }
        }
        if (!same.isEmpty()) return choose(same, recent, true);
        if (!ref.isEmpty()) return choose(ref, recent, true);
        if (!mastery) return next(bank, wrong.subject, recent, weights);
        // Prefer another item, but a singleton must remain answerable without inventing assessment evidence.
        List<T> other = new ArrayList<>();
        for (T q : subject) if (!q.id.equals(wrong.id)) other.add(q);
        return choose(other.isEmpty() ? subject : other, recent, true);
    }

    private <T extends Question> T choose(List<T> pool, List<String> recent, boolean corrective) {
        if (pool.isEmpty()) return null;
        List<T> candidates = new ArrayList<>();
        int min = Integer.MAX_VALUE;
        for (T q : pool) {
            int seen = mastery ? progress.itemPresentations(q.subject, q.id) : 0;
            if (seen < min) { min = seen; candidates.clear(); }
            if (seen == min) candidates.add(q);
        }
        List<T> fresh = new ArrayList<>();
        for (T q : candidates) if (!recent.contains(q.id)) fresh.add(q);
        if (!fresh.isEmpty()) candidates = fresh;
        T pick = candidates.get(random.nextInt(candidates.size()));
        recent.add(pick.id);
        while (recent.size() > 120) recent.remove(0);
        if (mastery) progress.present(pick.subject, pick.kp, pick.id, corrective);
        return pick;
    }
}
