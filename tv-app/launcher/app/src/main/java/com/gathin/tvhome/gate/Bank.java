package com.gathin.tvhome.gate;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Question loading and Android persistence adapter for the opt-in quiz policy. */
public final class Bank {
    public static final class Item extends QuizPolicy.Question {
        public String question, explanation;
        public String[] options;
        public int answer;
    }

    private static List<Item> items;
    private final Random rnd = new Random();
    private final Context ctx;
    private final Map<String, Integer> weight = new HashMap<>();
    private final LinkedList<String> recent = new LinkedList<>();
    private final boolean masteryEnabled;
    private final boolean historyHealthy;
    private final QuizProgress progress;
    private final QuizPolicy policy;
    private QuizProgress.Assessment lastAssessment;

    public Bank(Context c) { this(c, false); }

    public Bank(Context c, boolean masteryEnabled) {
        ctx = c.getApplicationContext();
        load(ctx);
        SharedPreferences p = GateStore.prefs(ctx);
        QuizProgress restored;
        boolean healthy = true;
        try {
            restored = QuizProgress.restore(p.getString(QuizProgress.PREF_KEY, ""));
        } catch (IllegalArgumentException invalid) {
            // Retain the stored bytes for recovery; keep the ordinary quiz and parent entry usable.
            restored = new QuizProgress();
            healthy = false;
            try { GateStore.log(ctx, new JSONObject().put("ev", "assessment_history_invalid")); }
            catch (Exception ignored) { }
        }
        historyHealthy = healthy;
        this.masteryEnabled = masteryEnabled && healthy;
        progress = restored;
        policy = new QuizPolicy(progress, this.masteryEnabled, rnd);
        try {
            JSONObject w = new JSONObject(p.getString("kpWeight", "{}"));
            for (java.util.Iterator<String> it = w.keys(); it.hasNext(); ) {
                String k = it.next();
                weight.put(k, w.getInt(k));
            }
            JSONArray r = new JSONArray(p.getString("recent", "[]"));
            for (int i = 0; i < r.length(); i++) recent.add(r.getString(i));
        } catch (Exception ignored) {
        }
    }

    private static synchronized void load(Context c) {
        if (items != null) return;
        items = new ArrayList<>();
        try (InputStream in = c.getAssets().open("quiz/bank.json")) {
            addAll(read(in));
        } catch (Exception e) {
            throw new IllegalStateException("quiz bank missing", e);
        }
        // Family-added questions: <external files>/custom-bank.json, same item format; pushed without rebuilding.
        java.io.File dir = c.getExternalFilesDir(null);
        java.io.File custom = dir == null ? null : new java.io.File(dir, "custom-bank.json");
        if (custom != null && custom.exists()) {
            try (InputStream in = new java.io.FileInputStream(custom)) {
                addAll(read(in));
            } catch (Exception ignored) {
                // a broken custom file must not stop the gate; the built-in bank still works
            }
        }
    }

    private static JSONArray read(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8)).getJSONArray("items");
    }

    private static void addAll(JSONArray arr) throws Exception {
        {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.id = o.getString("id");
                it.subject = o.getString("subject");
                it.kp = o.getString("kp");
                it.ref = o.optString("mistake_ref");
                it.question = o.getString("question");
                it.explanation = o.optString("explanation");
                JSONArray op = o.getJSONArray("options");
                it.options = new String[op.length()];
                for (int k = 0; k < op.length(); k++) it.options[k] = op.getString(k);
                it.answer = o.getInt("answer_index");
                if (it.options.length < 2 || it.answer < 0 || it.answer >= it.options.length) continue;
                items.add(it);
            }
        }
    }

    /** Reload on next use (custom-bank.json changed). */
    public static synchronized void invalidate() {
        items = null;
    }

    /** Explicit quiz-session boundary. Reuse the same token on every Activity recreation. */
    public void beginRound(String token) {
        if (progress.beginRound(token)) saveProgress();
    }

    /** Legacy non-balanced mix remains the default. */
    public List<String> plan(Map<String, Integer> mix) { return plan(mix, false); }

    public List<String> plan(Map<String, Integer> mix, boolean balanced) {
        List<String> result = policy.plan(items, mix, balanced && historyHealthy);
        if (progress.active()) saveProgress();
        return result;
    }

    /** Null means this subject currently has no selectable points; never index an empty pool. */
    public Item next(String subject) {
        Item result = policy.next(items, subject, recent, weight);
        if (masteryEnabled) saveProgress();
        return result;
    }

    public Item similar(Item wrong) {
        Item result = policy.similar(items, wrong, recent, weight);
        if (masteryEnabled) saveProgress();
        return result;
    }

    public boolean isMastered(Item item) {
        return masteryEnabled && progress.isMastered(item.subject, item.kp);
    }

    /** Positive exemption check; a missing configured subject/broken bank is NOT evidence of mastery. */
    public boolean allMastered(Map<String, Integer> mix) {
        return masteryEnabled && policy.allMastered(items, mix);
    }

    public boolean wasLastAssessmentEligible() { return lastAssessment != null && lastAssessment.eligible; }
    public QuizProgress.Assessment lastAssessment() { return lastAssessment; }

    private void saveProgress() {
        if (!historyHealthy) return;
        // Synchronous: a process stop must not invent a fresh round or lose a just-earned mastery latch.
        if (!GateStore.prefs(ctx).edit().putString(QuizProgress.PREF_KEY, progress.encode())
                .putString("recent", new JSONArray(recent).toString()).commit()) {
            throw new IllegalStateException("assessment history could not be saved");
        }
    }

    private int w(String kp) {
        Integer v = weight.get(kp);
        return v == null ? 0 : v;
    }

    public void record(Item it, boolean ok) {
        lastAssessment = masteryEnabled ? progress.record(it.subject, it.kp, it.id, ok) : null;
        if (masteryEnabled) saveProgress();
        try {
            JSONObject event = new JSONObject().put("ev", "assessment").put("version", 1)
                    .put("subject", it.subject).put("kp", it.kp).put("id", it.id).put("ok", ok)
                    .put("eligible", wasLastAssessmentEligible()).put("mastery_enabled", masteryEnabled);
            if (lastAssessment != null) {
                event.put("round", lastAssessment.round).put("reason", lastAssessment.reason)
                        .put("streak", lastAssessment.streak).put("assessments", lastAssessment.assessments)
                        .put("distinct_ids", lastAssessment.distinctIds).put("mastered", lastAssessment.mastered);
            } else event.put("reason", "mastery_disabled");
            GateStore.log(ctx, event);
        } catch (Exception ignored) { }
        int v = w(it.kp);
        weight.put(it.kp, ok ? Math.max(0, v - 1) : Math.min(6, v + 2));
        try {
            JSONObject wj = new JSONObject();
            for (Map.Entry<String, Integer> e : weight.entrySet()) if (e.getValue() > 0) wj.put(e.getKey(), e.getValue());
            GateStore.prefs(ctx).edit().putString("kpWeight", wj.toString()).putString("recent", new JSONArray(recent).toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
