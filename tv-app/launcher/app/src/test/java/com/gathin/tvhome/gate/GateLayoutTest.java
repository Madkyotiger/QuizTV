package com.gathin.tvhome.gate;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Looper;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * Lays the gate pages out on the JVM at the TV's size (1920x1080, density 320) with the real fonts and artwork,
 * so text that is cut off, re-wrapped or pushed off the screen is found without a TV and without a child waiting.
 * <p>
 * QUIZTV_EXTRA_BANK=/path/custom-bank.json also checks a family's own questions.
 * QUIZTV_PREVIEW=/path/dir writes a picture of every page in every theme (scripts/preview_ui.sh).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GateLayoutTest {
    private static final int W = 1920, H = 1080;
    private Application app;
    private GateActivity activity;
    private List<Bank.Item> items;

    @Before public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        File dir = app.getExternalFilesDir(null);
        Files.write(new File(dir, "gate.json").toPath(),
                "{\"enabled\":true,\"child_name\":\"宝贝\",\"mix\":{\"数学\":4,\"英语\":3,\"语文\":3}}".getBytes(StandardCharsets.UTF_8));
        String extra = System.getenv("QUIZTV_EXTRA_BANK");
        if (extra != null && new File(extra).isFile())
            Files.copy(new File(extra).toPath(), new File(dir, "custom-bank.json").toPath(), StandardCopyOption.REPLACE_EXISTING);
        Bank.invalidate();
        GateActivity.progress = null;
        new android.app.Instrumentation().setInTouchMode(false); // a TV: the remote moves a focus, nothing is touched
        activity = Robolectric.buildActivity(GateActivity.class,
                new Intent(app, GateActivity.class).putExtra(GateActivity.EXTRA_MODE, "QUIZ")).setup().get();
        items = Bank.all(app);
        assertTrue("bank loaded", items.size() > 100);
    }

    /** Every question, waiting for an answer and after a wrong one (the fullest state): nothing may be cut off. */
    @Test public void everyQuestionFitsTheScreen() throws Exception {
        List<String> problems = new ArrayList<>();
        Map<String, Integer> layouts = new TreeMap<>();
        for (Bank.Item it : items) {
            int wrong = it.answer == 0 ? 1 : 0;
            for (int chosen : new int[]{-1, wrong, it.answer}) {
                quiz(it, chosen == it.answer ? 4 : 3, chosen == wrong ? 1 : 0);
                activity.showQuestion(it, chosen);
                layout();
                String state = chosen < 0 ? "open" : chosen == wrong ? "wrong" : "right";
                layouts.merge(state + " " + activity.layoutNote, 1, Integer::sum);
                List<String> found = new ArrayList<>();
                if (activity.layoutNote.contains("CLIPPED")) found.add("card does not fit above the answers");
                check(root(), found);
                for (String f : found) problems.add(it.id + " [" + state + "] " + f + " | " + it.question);
            }
        }
        StringBuilder stats = new StringBuilder(items.size() + " questions\n");
        for (Map.Entry<String, Integer> e : layouts.entrySet()) stats.append(e.getValue()).append("  ").append(e.getKey()).append('\n');
        File out = new File("build/gate-preview");
        out.mkdirs();
        Files.write(new File(out, "layout-stats.txt").toPath(), stats.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(problems.size() + " layout problems, first ones:\n"
                + String.join("\n", problems.subList(0, Math.min(15, problems.size()))), problems.isEmpty());
    }

    /** The other pages, in every theme: same checks. */
    @Test public void everyPageFitsInEveryTheme() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String theme : GatePalette.THEMES) {
            for (Page p : pages(theme)) {
                p.show.run();
                layout();
                List<String> found = new ArrayList<>();
                check(root(), found);
                for (String f : found) problems.add(theme + " " + p.name + ": " + f);
            }
        }
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    @Test public void everyThemeHasItsFourArtworks() throws Exception {
        for (String theme : GatePalette.THEMES) {
            for (String page : new String[]{"quiz", "intro", "rest", "limit"}) {
                try (InputStream in = app.getAssets().open("theme/" + theme + "/" + page + ".webp")) {
                    Bitmap b = BitmapFactory.decodeStream(in);
                    assertNotNull(theme + "/" + page, b);
                    assertEquals(theme + "/" + page, W, b.getWidth());
                    assertEquals(theme + "/" + page, H, b.getHeight());
                }
            }
        }
    }

    /** Opt-in: pictures of every page for a human (or a vision model) to look at. */
    @Test public void drawsEveryPage() throws Exception {
        String dir = System.getenv("QUIZTV_PREVIEW");
        Assume.assumeTrue("set QUIZTV_PREVIEW=<dir> to draw the pages", dir != null && !dir.isEmpty());
        File out = new File(dir);
        out.mkdirs();
        for (String theme : GatePalette.THEMES) {
            int n = 0;
            for (Page p : pages(theme)) {
                p.show.run();
                layout();
                Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                activity.getWindow().getDecorView().draw(new Canvas(b));
                try (FileOutputStream f = new FileOutputStream(new File(out, String.format("%s-%02d-%s.jpg", theme, ++n, p.name)))) {
                    b.compress(Bitmap.CompressFormat.JPEG, 88, f);
                }
            }
        }
    }

    // ---------------------------------------------------------------- pages

    private static final class Page {
        final String name;
        final Runnable show;
        Page(String name, Runnable show) { this.name = name; this.show = show; }
    }

    private List<Page> pages(String theme) {
        Bank.Item sum = pick("数学", 4, 9), story = longest("数学"), english = containing("句中"), sentence = widestAnswer();
        Bank.Item passage = longestQuestion();
        List<Page> p = new ArrayList<>();
        p.add(new Page("intro", () -> { use(theme); quiz(sum, 0, 0); GateActivity.progress = fresh(); activity.renderQuizIntro(); idle(); }));
        p.add(new Page("quiz-short", () -> { use(theme); quiz(sum, 0, 0); activity.showQuestion(sum, -1); }));
        p.add(new Page("quiz-story", () -> { use(theme); quiz(story, 3, 0); activity.showQuestion(story, -1); }));
        p.add(new Page("quiz-english", () -> { use(theme); quiz(english, 6, 0); activity.showQuestion(english, -1); }));
        p.add(new Page("quiz-passage", () -> { use(theme); quiz(passage, 2, 0); activity.showQuestion(passage, -1); }));
        p.add(new Page("quiz-long-answers", () -> { use(theme); quiz(sentence, 8, 0); activity.showQuestion(sentence, -1); }));
        p.add(new Page("answer-right", () -> { use(theme); quiz(story, 4, 0); activity.showQuestion(story, story.answer); settle(); }));
        p.add(new Page("answer-wrong", () -> { use(theme); quiz(story, 3, 1); activity.showQuestion(story, other(story)); }));
        p.add(new Page("answer-wrong-english", () -> { use(theme); quiz(english, 6, 1); activity.showQuestion(english, other(english)); }));
        p.add(new Page("answer-wrong-long-answers", () -> { use(theme); quiz(sentence, 8, 2); activity.showQuestion(sentence, other(sentence)); }));
        p.add(new Page("help", () -> { use(theme); quiz(story, 3, 3); activity.renderHelp(story.explanation); }));
        p.add(new Page("unlocked", () -> { use(theme); quiz(sum, 10, 0); GateStore.rules(app).restUntil = 0; activity.celebrate(false); }));
        p.add(new Page("rest", () -> { use(theme); GateStore.rules(app).restUntil = System.currentTimeMillis() + 598_000; activity.renderRest(); idle(); GateStore.rules(app).restUntil = 0; }));
        p.add(new Page("bye", () -> { use(theme); GateActivity.byeUntil = android.os.SystemClock.elapsedRealtime() + 52_000; activity.renderBye(); idle(); GateActivity.byeUntil = 0; }));
        p.add(new Page("wait", () -> { use(theme); activity.renderDone("电视时间", "13:00"); }));
        p.add(new Page("limit", () -> { use(theme); activity.renderDone("今天的电视时间", null); }));
        p.add(new Page("quiet", () -> { use(theme); activity.renderQuiet("下午 1:00 再见～"); }));
        p.add(new Page("pin", () -> { use(theme); activity.renderPin(); }));
        p.add(new Page("parent-menu", () -> { use(theme); activity.renderParentMenu(); }));
        return p;
    }

    private void use(String theme) {
        GateStore.prefs(app).edit().putString("theme", theme).commit();
        GatePalette.use(theme);
    }

    private static GateActivity.Progress fresh() {
        GateActivity.Progress p = new GateActivity.Progress();
        p.plan = Collections.nCopies(10, "数学");
        return p;
    }

    /** A round in progress: stars earned so far, wrong answers in a row, the question on screen. */
    private void quiz(Bank.Item current, int stars, int wrongInRow) {
        GateActivity.Progress p = fresh();
        p.stars = stars;
        p.wrongInRow = wrongInRow;
        p.current = current;
        p.intro.complete();
        GateActivity.progress = p;
    }

    private static int other(Bank.Item it) {
        return it.answer == 0 ? 1 : 0;
    }

    private Bank.Item pick(String subject, int min, int max) {
        for (Bank.Item it : items) if (it.subject.equals(subject) && it.question.length() >= min && it.question.length() <= max) return it;
        return items.get(0);
    }

    private Bank.Item longest(String subject) {
        Bank.Item best = null;
        for (Bank.Item it : items) if (it.subject.equals(subject) && (best == null || it.question.length() > best.question.length())) best = it;
        return best == null ? items.get(0) : best;
    }

    private Bank.Item longestQuestion() {
        Bank.Item best = items.get(0);
        for (Bank.Item it : items) if (it.question.length() > best.question.length()) best = it;
        return best;
    }

    private Bank.Item containing(String part) {
        for (Bank.Item it : items) if (it.question.contains(part)) return it;
        return longest("英语");
    }

    private Bank.Item widestAnswer() {
        Bank.Item best = items.get(0);
        int width = 0;
        for (Bank.Item it : items) for (String o : it.options) if (o.length() > width) { width = o.length(); best = it; }
        return best;
    }

    // ---------------------------------------------------------------- checks

    private void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Lets a short animation finish (the newest star popping in). */
    private void settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
    }

    private ViewGroup root() {
        return (ViewGroup) activity.getWindow().getDecorView();
    }

    private void layout() {
        View root = root();
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
    }

    private static void check(View v, List<String> out) {
        if (v.getVisibility() != View.VISIBLE) return;
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        if (v.getWidth() > 0 && v.getHeight() > 0
                && (at[0] < 0 || at[1] < 0 || at[0] + v.getWidth() > W || at[1] + v.getHeight() > H))
            out.add("off screen: " + describe(v));
        if (v instanceof TextView && ((TextView) v).getText().length() > 0) {
            TextView t = (TextView) v;
            String text = t.getText().toString();
            Layout l = t.getLayout();
            if (l == null) { out.add("not laid out: " + text); return; }
            if (l.getLineCount() != QuizText.lineCount(text))
                out.add("re-wrapped into " + l.getLineCount() + " lines: " + text.replace('\n', '/'));
            if (l.getLineCount() > 4) out.add("more than 4 lines: " + text.replace('\n', '/'));
            if (l.getHeight() > t.getHeight() - t.getCompoundPaddingTop() - t.getCompoundPaddingBottom() + 1)
                out.add("cut off at the bottom: " + text.replace('\n', '/'));
            int room = t.getWidth() - t.getCompoundPaddingLeft() - t.getCompoundPaddingRight();
            for (int i = 0; i < l.getLineCount(); i++) {
                if (l.getLineMax(i) > room + 1) out.add(String.format("cut off at the side (line %.0f px, room %d px, text %.0f px): %s",
                        l.getLineMax(i), room, t.getTextSize(), text.replace('\n', '/')));
                if (l.getEllipsisCount(i) > 0) out.add("ellipsis: " + text.replace('\n', '/'));
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.getVisibility() == View.VISIBLE && c.getHeight() > 0
                        && (c.getBottom() > g.getHeight() || c.getRight() > g.getWidth() || c.getTop() < 0 || c.getLeft() < 0))
                    out.add("sticks out of its container: " + describe(c));
                check(c, out);
            }
        }
    }

    private static String describe(View v) {
        return v instanceof TextView ? "\"" + ((TextView) v).getText().toString().replace('\n', '/') + "\"" : v.getClass().getSimpleName();
    }
}
