package com.gathin.tvhome.gate;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Question bank from assets/quiz/bank.json (built by build_bank_v2.py). Recently missed knowledge points come up more. */
public final class Bank {
    public static final class Item {
        public String id, subject, kp, ref, question, explanation;
        public String[] options;
        public int answer;
    }

    private static List<Item> items;
    private final Random rnd = new Random();
    private final Context ctx;
    private final Map<String, Integer> weight = new HashMap<>();
    private final LinkedList<String> recent = new LinkedList<>();

    public Bank(Context c) {
        ctx = c.getApplicationContext();
        load(ctx);
        SharedPreferences p = GateStore.prefs(ctx);
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

    /** Subject plan for one unlock from gate.json "mix" (default 数学 3 + 英语 2), shuffled, so no subject can be skipped. */
    public List<String> plan(Map<String, Integer> mix) {
        List<String> s = new ArrayList<>();
        for (Map.Entry<String, Integer> e : mix.entrySet()) {
            boolean any = false;
            for (Item it : items) if (it.subject.equals(e.getKey())) { any = true; break; }
            if (!any) continue; // a subject with no questions yet is skipped
            for (int i = 0; i < e.getValue(); i++) s.add(e.getKey());
        }
        if (s.isEmpty()) { s.add("数学"); s.add("数学"); s.add("数学"); s.add("英语"); s.add("英语"); }
        Collections.shuffle(s, rnd);
        return s;
    }

    public Item next(String subject) {
        // weighted pick of a knowledge point within the subject, then an item not asked recently
        Map<String, List<Item>> byKp = new HashMap<>();
        for (Item it : items) if (it.subject.equals(subject)) byKp.computeIfAbsent(it.kp, k -> new ArrayList<>()).add(it);
        List<String> kps = new ArrayList<>(byKp.keySet());
        int total = 0;
        for (String k : kps) total += 1 + 3 * w(k);
        int r = rnd.nextInt(Math.max(total, 1));
        String kp = kps.get(0);
        for (String k : kps) {
            r -= 1 + 3 * w(k);
            if (r < 0) { kp = k; break; }
        }
        return fresh(byKp.get(kp), subject);
    }

    /** After a wrong answer: another item on the same knowledge point (or same notebook entry, or same subject). */
    public Item similar(Item wrong) {
        List<Item> same = new ArrayList<>();
        for (Item it : items) if (it.kp.equals(wrong.kp) && !it.id.equals(wrong.id)) same.add(it);
        if (same.isEmpty()) for (Item it : items) if (it.ref.equals(wrong.ref) && it.subject.equals(wrong.subject) && !it.id.equals(wrong.id)) same.add(it);
        if (same.isEmpty()) return next(wrong.subject);
        return fresh(same, wrong.subject);
    }

    private Item fresh(List<Item> pool, String subject) {
        List<Item> cands = new ArrayList<>();
        for (Item it : pool) if (!recent.contains(it.id)) cands.add(it);
        if (cands.isEmpty()) cands = pool;
        Item pick = cands.get(rnd.nextInt(cands.size()));
        recent.add(pick.id);
        while (recent.size() > 120) recent.removeFirst();
        return pick;
    }

    private int w(String kp) {
        Integer v = weight.get(kp);
        return v == null ? 0 : v;
    }

    public void record(Item it, boolean ok) {
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
