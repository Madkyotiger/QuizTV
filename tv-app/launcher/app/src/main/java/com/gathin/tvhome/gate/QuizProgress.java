package com.gathin.tvhome.gate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** New, versioned assessment history only; never inferred from legacy weights or answer logs. */
public final class QuizProgress {
    public static final String PREF_KEY = "quizAssessmentV1";
    private static final int VERSION = 1;

    public static final class Stats {
        public int assessments, presentations, streak;
        public long firstCorrectRound, lastCorrectRound, lastPresentedRound;
        public boolean mastered;
        public final Set<String> correctIds = new HashSet<>();

        private Stats copy() {
            Stats s = new Stats();
            s.assessments = assessments; s.presentations = presentations; s.streak = streak;
            s.firstCorrectRound = firstCorrectRound; s.lastCorrectRound = lastCorrectRound;
            s.lastPresentedRound = lastPresentedRound; s.mastered = mastered;
            s.correctIds.addAll(correctIds);
            return s;
        }
    }

    public static final class Assessment {
        public final boolean eligible, correct, mastered;
        public final int streak, assessments, distinctIds;
        public final long round;
        public final String reason;

        private Assessment(boolean eligible, boolean correct, Stats s, long round, String reason) {
            this.eligible = eligible; this.correct = correct; this.mastered = s.mastered;
            this.streak = s.streak; this.assessments = s.assessments;
            this.distinctIds = s.correctIds.size(); this.round = round; this.reason = reason;
        }
    }

    private final Map<String, Stats> points = new HashMap<>();
    private final Map<String, Integer> itemPresentations = new HashMap<>();
    private final Set<String> encountered = new HashSet<>();
    private String token = "", pendingKey = "", pendingId = "", pendingReason = "no_presentation";
    private boolean pendingEligible, pendingAnswered = true;
    private long round;
    private List<String> cachedPlan;

    static String key(String subject, String kp) {
        return subject.length() + ":" + subject + kp;
    }

    private Stats mutable(String subject, String kp) {
        return points.computeIfAbsent(key(subject, kp), k -> new Stats());
    }

    /** Defensive snapshot; callers cannot reset mastery by modifying this object. */
    public Stats stats(String subject, String kp) {
        Stats s = points.get(key(subject, kp));
        return s == null ? new Stats() : s.copy();
    }

    public boolean isMastered(String subject, String kp) { return stats(subject, kp).mastered; }
    public long round() { return round; }
    public boolean active() { return !token.isEmpty(); }
    public String roundToken() { return token; }
    public boolean encountered(String subject, String kp) { return encountered.contains(key(subject, kp)); }
    public int itemPresentations(String subject, String id) {
        return itemPresentations.getOrDefault(key(subject, id), 0);
    }

    /** Same token means the same round, including after serialization/process restart. */
    public boolean beginRound(String newToken) {
        if (newToken == null || newToken.isEmpty()) throw new IllegalArgumentException("round token required");
        if (newToken.equals(token)) return false;
        token = newToken;
        round++;
        encountered.clear();
        pendingKey = ""; pendingId = ""; pendingEligible = false; pendingAnswered = true;
        cachedPlan = null;
        return true;
    }

    List<String> cachedPlan() { return cachedPlan == null ? null : new ArrayList<>(cachedPlan); }
    void cachePlan(List<String> plan) { cachedPlan = new ArrayList<>(plan); }

    /** Reserve eligibility when shown, not when answered; re-draws cannot create another assessment. */
    void present(String subject, String kp, String id, boolean corrective) {
        if (!active()) throw new IllegalStateException("beginRound required before mastery draws");
        Stats s = mutable(subject, kp);
        boolean first = encountered.add(key(subject, kp));
        pendingKey = key(subject, kp); pendingId = id; pendingAnswered = false;
        pendingEligible = first && !corrective && !s.mastered;
        pendingReason = s.mastered ? "mastered" : corrective ? "corrective_retry" :
                first ? "first_assessment" : "already_encountered_this_round";
        s.presentations++; s.lastPresentedRound = round;
        String itemKey = key(subject, id);
        itemPresentations.put(itemKey, itemPresentations.getOrDefault(itemKey, 0) + 1);
    }

    public Assessment record(String subject, String kp, String id, boolean correct) {
        Stats s = mutable(subject, kp);
        if (!key(subject, kp).equals(pendingKey) || !id.equals(pendingId) || pendingAnswered) {
            return new Assessment(false, correct, s, round, "no_unanswered_presentation");
        }
        pendingAnswered = true;
        boolean eligible = pendingEligible && !s.mastered;
        if (eligible) {
            s.assessments++;
            if (correct) {
                if (s.streak == 0) s.firstCorrectRound = round;
                s.streak++; s.lastCorrectRound = round; s.correctIds.add(id);
                s.mastered = s.streak >= 3 && s.lastCorrectRound > s.firstCorrectRound && s.correctIds.size() >= 2;
            } else {
                s.streak = 0; s.firstCorrectRound = 0; s.lastCorrectRound = 0; s.correctIds.clear();
            }
        }
        return new Assessment(eligible, correct, s, round, pendingReason);
    }

    /** Compact deterministic storage, independent of Android and org.json. */
    public String encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION); out.writeUTF(token); out.writeLong(round);
            out.writeInt(points.size());
            for (Map.Entry<String, Stats> e : new TreeMap<>(points).entrySet()) {
                out.writeUTF(e.getKey()); Stats s = e.getValue();
                out.writeInt(s.assessments); out.writeInt(s.presentations); out.writeInt(s.streak);
                out.writeLong(s.firstCorrectRound); out.writeLong(s.lastCorrectRound); out.writeLong(s.lastPresentedRound);
                out.writeBoolean(s.mastered); writeStrings(out, new ArrayList<>(new TreeSet<>(s.correctIds)));
            }
            out.writeInt(itemPresentations.size());
            for (Map.Entry<String, Integer> e : new TreeMap<>(itemPresentations).entrySet()) {
                out.writeUTF(e.getKey()); out.writeInt(e.getValue());
            }
            writeStrings(out, new ArrayList<>(new TreeSet<>(encountered)));
            out.writeUTF(pendingKey); out.writeUTF(pendingId); out.writeUTF(pendingReason);
            out.writeBoolean(pendingEligible); out.writeBoolean(pendingAnswered);
            out.writeBoolean(cachedPlan != null);
            if (cachedPlan != null) writeStrings(out, cachedPlan);
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) { throw new IllegalStateException("cannot encode assessment history", e); }
    }

    /** Invalid nonempty state is an explicit error: never silently reset mastered points. */
    public static QuizProgress restore(String encoded) {
        QuizProgress p = new QuizProgress();
        if (encoded == null || encoded.isEmpty()) return p;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            if (in.readInt() != VERSION) throw new IOException("unsupported assessment history version");
            p.token = in.readUTF(); p.round = in.readLong();
            int count = count(in);
            for (int i = 0; i < count; i++) {
                String key = in.readUTF(); Stats s = new Stats();
                s.assessments = in.readInt(); s.presentations = in.readInt(); s.streak = in.readInt();
                s.firstCorrectRound = in.readLong(); s.lastCorrectRound = in.readLong(); s.lastPresentedRound = in.readLong();
                s.mastered = in.readBoolean(); s.correctIds.addAll(readStrings(in)); p.points.put(key, s);
            }
            count = count(in);
            for (int i = 0; i < count; i++) p.itemPresentations.put(in.readUTF(), in.readInt());
            p.encountered.addAll(readStrings(in));
            p.pendingKey = in.readUTF(); p.pendingId = in.readUTF(); p.pendingReason = in.readUTF();
            p.pendingEligible = in.readBoolean(); p.pendingAnswered = in.readBoolean();
            if (in.readBoolean()) p.cachedPlan = readStrings(in);
            if (in.available() != 0) throw new IOException("trailing assessment history data");
            return p;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid assessment history; retain stored data for recovery", e);
        }
    }

    private static int count(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 100000) throw new IOException("invalid collection size");
        return n;
    }
    private static void writeStrings(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size()); for (String s : values) out.writeUTF(s);
    }
    private static List<String> readStrings(DataInputStream in) throws IOException {
        int n = count(in); List<String> result = new ArrayList<>();
        for (int i = 0; i < n; i++) result.add(in.readUTF());
        return result;
    }
}
