package com.gathin.tvhome.gate;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

/**
 * Full-screen gate: quiz (one star per question to unlock), eye rest, daily limit, parent PIN. Remote-control only.
 * Each quiz round picks a theme (GatePalette); artwork backgrounds come from assets/theme/. All pages are built in
 * code: GateUi holds the type scale and buttons, QuizText breaks the lines, and GateLayoutTest lays every bundled
 * question out at the TV's size to prove nothing is cut off.
 */
public final class GateActivity extends Activity {
    public static final String EXTRA_MODE = "mode";
    static volatile GateRules.Screen visibleMode;
    /** Goodbye page shown until this elapsedRealtime (set by GateService when watching just ended). */
    static volatile long byeUntil;
    static final long BYE_MS = 60_000;
    private static final long QUIET_AFTER_MS = 60_000;
    private static final int QUIET_BACKGROUND = 0xFF07070C;
    /** Page margins in dp. */
    private static final int PAD_H = 48, PAD_TOP = 24, PAD_BOTTOM = 28;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** How one answer is drawn: still open, the right one, the wrong one that was chosen, or the remaining one. */
    private static final int OPEN = 0, RIGHT = 1, WRONG = 2, OTHER = 3;

    /** Survives the activity being recreated or re-shown during one quiz. */
    static final class Progress {
        final QuizIntro intro = new QuizIntro();
        final String roundToken = java.util.UUID.randomUUID().toString();
        List<String> plan;
        int stars, slot, wrongInRow;
        Bank.Item current;
    }
    static Progress progress; // package-private, like the page methods below, for GateLayoutTest
    private static Bitmap bgBitmap;
    private static String bgKey;

    private final Handler h = new Handler(Looper.getMainLooper());
    private GateRules.Screen mode = GateRules.Screen.QUIZ;
    private Bank bank;
    private FrameLayout root;

    private boolean answered;
    private Runnable ticker;
    private Runnable pinBack = this::render;
    private View firstOption;
    private Button parentInBar, nextButton;
    /** How the last question was laid out, e.g. "side30 q36 tight0"; read by GateLayoutTest. */
    String layoutNote = "";
    private String optionNote = "", cardNote = "";
    /** The question tightFloor was worked out for. */
    private Bank.Item tightFor;
    private int tightFloor;
    private MediaPlayer introPlayer;
    private AudioManager introAudioManager;
    private AudioFocusRequest introAudioFocus;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        GateStore.mix(this); // picks up a changed custom-bank.json
        bank = new Bank(this, GateStore.masteryEnabled(this));
        GatePalette.use(GateStore.theme(this));
        root = new FrameLayout(this);
        root.setBackgroundColor(GatePalette.BACKGROUND);
        setContentView(root);
        readMode(getIntent());
        render();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        GateRules.Screen old = mode;
        readMode(i);
        if (mode == GateRules.Screen.NONE) { progress = null; leave(); return; }
        if (old != mode) render();
    }

    private void readMode(Intent i) {
        try {
            mode = GateRules.Screen.valueOf(i.getStringExtra(EXTRA_MODE));
        } catch (Exception e) {
            mode = GateRules.Screen.QUIZ;
        }
        if (mode != GateRules.Screen.NONE && GateStore.enabled(this)
                && GateStore.rules(this).mustKeepRest(System.currentTimeMillis())) {
            mode = GateRules.Screen.REST;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        visibleMode = mode;
    }

    @Override
    protected void onPause() {
        stopIntroAudio();
        resumed = false;
        visibleMode = null;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopIntroAudio();
        h.removeCallbacksAndMessages(null);
        // While the child watches, the process should be as small as possible (low-memory killer picks the
        // largest process at a level): drop the ~4 MB background and the two fonts; the next quiz reloads them.
        bgBitmap = null; bgKey = null;
        GateUi.release();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) return true; // no way out by Back
        return super.onKeyDown(code, e);
    }

    @Override
    public void onBackPressed() {
        // swallow
    }

    // ---------------------------------------------------------------- screens

    private void render() {
        stopIntroAudio();
        h.removeCallbacksAndMessages(null);
        if (resumed) visibleMode = mode;
        GatePalette.use(GateStore.theme(this));
        switch (mode) {
            case NONE: leave(); break;
            case REST: renderRest(); break;
            case LIMIT:
            case WAIT:
                if (SystemClock.elapsedRealtime() < byeUntil) renderBye();
                else if (mode == GateRules.Screen.WAIT) renderWait();
                else renderLimit();
                break;
            default: renderQuiz();
        }
    }

    private void renderQuiz() {
        if (progress == null) {
            progress = new Progress();
            bank.beginRound(progress.roundToken);
            progress.plan = bank.plan(GateStore.mix(this), GateStore.balancedQuiz(this));
            GatePalette.use(GateStore.newRoundTheme(this));
        } else {
            bank.beginRound(progress.roundToken);
        }
        if (progress.plan.isEmpty()) {
            if (bank.allMastered(GateStore.mix(this))) celebrate(true);
            else renderNoQuestions("当前没有可用题目，请家长检查题库。缺少题目不算知识点已通过。");
            return;
        }
        if (progress.stars >= progress.plan.size()) {
            celebrate();
            return;
        }
        if (progress.intro.shouldShow(SystemClock.elapsedRealtime())) {
            renderQuizIntro();
            return;
        }
        if (progress.current == null) progress.current = nextUnmasteredForSlot();
        if (progress.current == null) {
            if (bank.allMastered(GateStore.mix(this))) celebrate(true);
            else renderNoQuestions("暂时没有可用题目，请家长检查题库。已通过的知识点不会为凑题重新出现。");
            return;
        }
        answered = false;
        showQuestion(progress.current, -1);
    }

    private Bank.Item nextUnmasteredForSlot() {
        String preferred = progress.plan.get(progress.slot % progress.plan.size());
        Bank.Item it = bank.next(preferred);
        if (it != null) return it;
        // A subject can finish mid-round; use another enabled subject instead of a mastered item.
        for (String subject : GateStore.mix(this).keySet()) {
            if (subject.equals(preferred)) continue;
            it = bank.next(subject);
            if (it != null) return it;
        }
        return null;
    }

    /**
     * The quiz page for one question. chosen &lt; 0: waiting for an answer; otherwise the answer just given, with
     * the answers locked and coloured and the feedback (plus the explanation when wrong) inside the card.
     * The left 26%: the theme artwork's character lives there, so nothing is drawn over it.
     */
    void showQuestion(Bank.Item it, int chosen) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int padH = dp(PAD_H), padTop = dp(PAD_TOP), padBottom = dp(PAD_BOTTOM), gap = dp(14);
        int artW = Math.max(0, Math.round(dm.widthPixels * 0.26f) - padH);
        int colW = dm.widthPixels - 2 * padH - artW;

        LinearLayout page = column();
        page.setPadding(padH, padTop, padH, padBottom);
        View bar = quizBar(chosen >= 0 && chosen == it.answer);
        page.addView(bar);
        bar.measure(exactly(dm.widthPixels - 2 * padH), unspecified());
        int bodyH = dm.heightPixels - padTop - padBottom - bar.getMeasuredHeight() - gap;

        // The card has to fit above the answers, also once the explanation joins it after a wrong answer. If that
        // fullest case needs a smaller question, use the smaller size from the start, so the question does not
        // change size when the answer is given; the lighter steps (compact, no subject pill) are taken per state.
        if (tightFor != it) {
            int worst = 0;
            for (int k = 0; k < it.options.length; k++) {
                if (k != it.answer) worst = Math.max(worst, tightness(it, k, colW, bodyH, 0));
            }
            tightFor = it;
            tightFloor = worst >= 3 ? worst : 0;
        }
        int tight = tightness(it, chosen, colW, bodyH, tightFloor);
        View options = optionsBlock(it, chosen, colW);
        View card = questionCard(it, chosen, colW, tight);
        options.measure(exactly(colW), unspecified());
        card.measure(exactly(colW), unspecified());
        int room = bodyH - options.getMeasuredHeight();
        boolean fits = card.getMeasuredHeight() <= room;
        layoutNote = optionNote + " " + cardNote + " tight" + tight + (fits ? "" : " CLIPPED");

        LinearLayout right = column();
        right.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 3));
        right.addView(card, new LinearLayout.LayoutParams(MATCH, fits ? WRAP : Math.max(room, 0)));
        right.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 2));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(MATCH, WRAP);
        olp.topMargin = gap;
        right.addView(options, olp);

        LinearLayout body = row();
        body.addView(new View(this), new LinearLayout.LayoutParams(artW, 1));
        body.addView(right, new LinearLayout.LayoutParams(0, MATCH, 1));
        page.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1));
        setPage(page, "quiz", false);
        if (chosen < 0 && firstOption != null) firstOption.requestFocus();
    }

    /**
     * The loosest setting (from floor up) at which the card fits above the answers in the given state:
     * 0 = as designed, 1 = compact (less padding, smaller explanation), 2 = also no subject pill,
     * 3 and 4 = question one and two sizes down.
     */
    private int tightness(Bank.Item it, int chosen, int colW, int bodyH, int floor) {
        View options = optionsBlock(it, chosen, colW);
        options.measure(exactly(colW), unspecified());
        int room = bodyH - options.getMeasuredHeight();
        for (int tight = floor; tight < 4; tight++) {
            View card = questionCard(it, chosen, colW, tight);
            card.measure(exactly(colW), unspecified());
            if (card.getMeasuredHeight() <= room) return tight;
        }
        return 4;
    }

    /** Top of the quiz page: one star per question, how many are still missing, and the parents' entry. */
    private View quizBar(boolean popNewest) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, dp(14));
        int total = stars(), have = progress == null ? 0 : Math.min(progress.stars, total);
        float size = Math.min(dp(26), dp(520) / (total * 1.26f));
        bar.addView(new GateUi.Stars(this, total, have, size, popNewest));
        int left = total - have;
        String number = String.valueOf(have == 0 ? total : left);
        String caption = have == 0 ? "集齐 " + number + " 颗星星，就能看电视啦"
                : left > 0 ? "再答对 " + number + " 题，就能看电视啦" : "星星集齐啦";
        TextView c = GateUi.body(this, caption, 22, GatePalette.MUTED);
        int at = caption.indexOf(number);
        if (at >= 0 && left > 0) { // the one number that matters, in the stars' colour
            android.text.SpannableString marked = new android.text.SpannableString(caption);
            marked.setSpan(new android.text.style.ForegroundColorSpan(GatePalette.STAR), at, at + number.length(), 0);
            c.setText(marked);
        }
        c.setMaxLines(1);
        c.setPadding(dp(18), 0, dp(12), 0);
        bar.addView(c, new LinearLayout.LayoutParams(0, WRAP, 1));
        bar.addView(parentButton(true), clearOfCornerArt());
        return bar;
    }

    /** Subject pill, the question, and after an answer the feedback line with the explanation. */
    private View questionCard(Bank.Item it, int chosen, int colW, int tight) {
        boolean compact = tight >= 1;
        int padH = dp(36), padV = dp(compact ? 20 : 26);
        float room = colW - 2 * padH - dp(2);
        LinearLayout card = column();
        card.setPadding(padH, padV, padH, padV);
        card.setBackground(GateUi.box(GatePalette.withAlpha(GatePalette.PANEL, 0xEB), dp(30),
                GatePalette.withAlpha(GatePalette.BORDER, 0x66), dp(1)));
        if (tight < 2) {
            boolean english = it.subject.equals("英语");
            TextView tag = GateUi.body(this, english ? "English" : it.subject, 19, GatePalette.LABEL_TEXT);
            tag.setBackground(GateUi.box(it.subject.equals("数学") ? GatePalette.SKY : english ? GatePalette.MINT
                    : GatePalette.AMBER, dp(15), 0, 0));
            tag.setPadding(dp(13), dp(4), dp(13), dp(4));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(WRAP, WRAP);
            tlp.bottomMargin = dp(12);
            card.addView(tag, tlp);
        }
        // the largest size at which the question is short enough: one line at 40sp, two at 36sp, three at 32sp
        int[] sizes = GateUi.QUESTION_SP;
        int pick = sizes.length - 1;
        for (int i = 0; i < sizes.length - 1; i++) {
            if (QuizText.lineCount(QuizText.wrap(it.question, room, GateUi.kaiMeasurer(this, sizes[i]))) <= i + 1) {
                pick = i;
                break;
            }
        }
        pick = Math.min(sizes.length - 1, pick + Math.max(0, tight - 2));
        cardNote = "q" + sizes[pick];
        card.addView(GateUi.body(this, QuizText.wrap(it.question, room, GateUi.kaiMeasurer(this, sizes[pick])),
                sizes[pick], GatePalette.TEXT));
        nextButton = null;
        if (chosen < 0) return card;

        boolean ok = chosen == it.answer;
        View rule = new View(this);
        rule.setBackgroundColor(GatePalette.withAlpha(GatePalette.BORDER, 0x55));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH, Math.max(1, dp(1)));
        rlp.topMargin = dp(compact ? 10 : 14);
        rlp.bottomMargin = dp(compact ? 6 : 10);
        card.addView(rule, rlp);
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(48));
        line.addView(GateUi.display(this, ok ? "答对啦，真棒！" : "差一点点，我们看看怎么想", 26,
                ok ? GatePalette.MINT : GatePalette.ERROR_TEXT), new LinearLayout.LayoutParams(0, WRAP, 1));
        if (!ok && progress != null && progress.wrongInRow < 3) {
            // visible at once so the card does not jump, usable only after the explanation has been up for 4 s
            nextButton = GateUi.pill(this, "下一题", 22, 48);
            nextButton.setEnabled(false);
            nextButton.setFocusable(false);
            nextButton.setAlpha(0.5f);
            line.addView(nextButton);
        }
        card.addView(line);
        if (!ok && it.explanation != null && !it.explanation.isEmpty()) {
            int sp = compact ? 21 : GateUi.BODY;
            TextView ex = GateUi.body(this, QuizText.wrap(it.explanation, room, GateUi.kaiMeasurer(this, sp)), sp,
                    GatePalette.TEXT);
            ex.setPadding(0, dp(compact ? 4 : 8), 0, 0);
            card.addView(ex);
        }
        return card;
    }

    /**
     * The answers: side by side when each fits on one line there (most do), otherwise one per row, so a longer
     * answer such as a line of a poem or an English sentence is never broken in the middle.
     */
    private View optionsBlock(Bank.Item it, int chosen, int colW) {
        int n = it.options.length, gap = dp(12), badge = dp(32) + dp(12);
        float sideRoom = (colW - (n - 1) * gap) / (float) n - 2 * dp(16) - badge;
        int sp = 0;
        for (int size : new int[]{30, 26}) if (sp == 0 && allFit(it.options, size, sideRoom)) sp = size;
        boolean stacked = sp == 0;
        if (stacked) sp = 28;
        optionNote = (stacked ? "stack" : "side") + sp;
        float stackRoom = colW - 2 * dp(22) - badge;
        LinearLayout block = stacked ? column() : row();
        firstOption = null;
        for (int k = 0; k < n; k++) {
            int state = chosen < 0 ? OPEN : k == it.answer ? RIGHT : k == chosen ? WRONG : OTHER;
            // one per row: once answered, the card needs the room for its feedback, so only the right answer and
            // the one that was chosen stay
            if (stacked && state == OTHER) continue;
            String label = stacked ? QuizText.wrap(it.options[k], stackRoom, GateUi.kaiMeasurer(this, sp)) : it.options[k];
            View v = optionView((char) ('A' + k), label, sp, state, stacked);
            if (chosen < 0) {
                final int idx = k;
                v.setOnClickListener(x -> onAnswer(it, idx));
                if (firstOption == null) firstOption = v;
            }
            LinearLayout.LayoutParams lp = stacked ? new LinearLayout.LayoutParams(MATCH, WRAP)
                    : new LinearLayout.LayoutParams(0, dp(78), 1);
            if (block.getChildCount() > 0) {
                if (stacked) lp.topMargin = dp(10); else lp.leftMargin = gap;
            }
            block.addView(v, lp);
        }
        return block;
    }

    private boolean allFit(String[] options, int sp, float room) {
        QuizText.Measurer m = GateUi.kaiMeasurer(this, sp);
        for (String o : options) if (o.indexOf('\n') >= 0 || m.width(o) > room) return false;
        return true;
    }

    /** One answer: a letter in a ring and the text. A focusable row rather than a Button, so the text is laid out exactly. */
    private View optionView(char letter, String label, int sp, int state, boolean stacked) {
        int radius = stacked ? 22 : 26, padH = dp(stacked ? 22 : 16);
        LinearLayout v = row();
        v.setGravity(stacked ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
        v.setPadding(padH, dp(8), padH, dp(8));
        v.setMinimumHeight(dp(stacked ? 60 : 78));
        // the letter; once answered, a tick on the right answer and a cross on the wrong one that was chosen,
        // so the two are told apart by more than colour
        TextView badge = GateUi.body(this, state == RIGHT ? "✓" : state == WRONG ? "✗" : String.valueOf(letter), 21,
                GatePalette.ACCENT);
        badge.setGravity(Gravity.CENTER);
        if (state == RIGHT || state == WRONG) {
            badge.setTextColor(GatePalette.LABEL_TEXT);
            badge.setBackground(GateUi.box(state == RIGHT ? GatePalette.MINT : GatePalette.ERROR_TEXT, dp(16), 0, 0));
        } else {
            badge.setBackground(GateUi.box(0, dp(16), GatePalette.withAlpha(GatePalette.ACCENT, 0x99), dp(1.5f)));
        }
        v.addView(badge, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(WRAP, WRAP);
        tlp.leftMargin = dp(12);
        v.addView(GateUi.body(this, label, sp, GatePalette.TEXT), tlp);
        int rest = GatePalette.withAlpha(GatePalette.BUTTON, 0xEB);
        if (state == OPEN) {
            v.setBackground(GateUi.pressable(this, rest, radius));
            v.setFocusable(true);
            v.setClickable(true);
            GateUi.growOnFocus(v, stacked ? 1.02f : 1.04f);
        } else if (state == RIGHT) {
            v.setBackground(GateUi.box(GatePalette.SUCCESS_SURFACE, dp(radius), GatePalette.MINT, dp(2)));
        } else if (state == WRONG) {
            v.setBackground(GateUi.box(GatePalette.ERROR_SURFACE, dp(radius), GatePalette.ERROR_TEXT, dp(2)));
        } else {
            v.setBackground(GateUi.box(rest, dp(radius), GatePalette.withAlpha(GatePalette.BORDER, 0xB0), dp(1.5f)));
            v.setAlpha(0.6f);
        }
        return v;
    }

    void renderQuizIntro() {
        final Progress round = progress;
        round.intro.start(SystemClock.elapsedRealtime());
        LinearLayout page = page();
        page.addView(pageBar("答题小提醒"));
        LinearLayout center = lowerColumn();
        center.addView(GateUi.body(this, GateStore.childName(this) + "，", GateUi.LEAD, GatePalette.TEXT), below(0));
        center.addView(GateUi.display(this, "现在是答题看电视时间", GateUi.HEADLINE, GatePalette.ACCENT), below(6));
        center.addView(GateUi.body(this, "集齐 " + stars() + " 颗星星，就能看电视啦", 26, GatePalette.TEXT), below(12));
        TextView countdown = GateUi.body(this, "", GateUi.CAPTION, GatePalette.MUTED);
        center.addView(countdown, below(8));
        Button start = GateUi.pill(this, "准备好啦，开始答题", 24, 58);
        start.setOnClickListener(v -> {
            if (progress != round) return;
            round.intro.complete();
            render();
        });
        page.addView(center, new LinearLayout.LayoutParams(MATCH, 0, 1));
        page.addView(footWithParent(start));
        setPage(page, "intro", true);
        start.requestFocus();
        logEv("quiz_intro");
        playIntroAudio(round);
        ticker = new Runnable() {
            @Override public void run() {
                if (progress != round || mode != GateRules.Screen.QUIZ || isFinishing() || isDestroyed()) return;
                long left = round.intro.remainingMs(SystemClock.elapsedRealtime());
                if (left <= 0) {
                    round.intro.complete();
                    render();
                    return;
                }
                countdown.setText(((left + 999) / 1000) + " 秒后开始答题");
                h.postDelayed(this, Math.min(1000, left));
            }
        };
        h.post(ticker);
    }

    /** A single bundled greeting only; quiz questions remain silent. Never set system volume. */
    private void playIntroAudio(Progress round) {
        if (!round.intro.claimAudio()) return;
        try (AssetFileDescriptor audio = getAssets().openFd(GateStore.audio(this, "welcome"))) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            introAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            introAudioFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(change -> {
                        if (change < 0) stopIntroAudio();
                    }).build();
            if (introAudioManager.requestAudioFocus(introAudioFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                logEv("quiz_intro_audio_unavailable");
                stopIntroAudio();
                return;
            }
            MediaPlayer player = new MediaPlayer();
            introPlayer = player;
            player.setAudioAttributes(attrs);
            player.setDataSource(audio.getFileDescriptor(), audio.getStartOffset(), audio.getLength());
            player.setOnPreparedListener(mp -> {
                if (introPlayer != mp || progress != round || mode != GateRules.Screen.QUIZ
                        || !round.intro.shouldShow(SystemClock.elapsedRealtime()) || isFinishing()) {
                    stopIntroAudio();
                    return;
                }
                mp.start();
                logEv("quiz_intro_audio_start");
            });
            player.setOnCompletionListener(mp -> {
                logEv("quiz_intro_audio_complete");
                stopIntroAudio();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                logEv("quiz_intro_audio_error");
                stopIntroAudio();
                return true;
            });
            player.prepareAsync();
        } catch (Exception ignored) {
            logEv("quiz_intro_audio_error");
            stopIntroAudio(); // missing audio must never block the quiz
        }
    }

    private void stopIntroAudio() {
        MediaPlayer player = introPlayer;
        introPlayer = null;
        if (player != null) {
            player.setOnPreparedListener(null);
            player.setOnCompletionListener(null);
            player.setOnErrorListener(null);
            player.release();
        }
        if (introAudioManager != null && introAudioFocus != null)
            introAudioManager.abandonAudioFocusRequest(introAudioFocus);
        introAudioFocus = null;
        introAudioManager = null;
    }

    private void onAnswer(Bank.Item it, int idx) {
        if (answered || mode != GateRules.Screen.QUIZ || progress == null || progress.current != it) return;
        answered = true;
        final Progress round = progress;
        boolean ok = idx == it.answer;
        bank.record(it, ok);
        try {
            GateStore.log(this, new JSONObject().put("ev", "answer").put("id", it.id).put("kp", it.kp)
                    .put("subject", it.subject).put("round_id", round.roundToken)
                    .put("ok", ok).put("chosen", it.options[idx]));
        } catch (Exception ignored) {
        }
        if (ok) {
            round.stars++;
            round.slot++;
            round.wrongInRow = 0;
            round.current = null;
        } else {
            round.wrongInRow++;
            round.current = bank.similar(it);
        }
        showQuestion(it, idx);
        if (ok) {
            if (round.stars >= stars()) {
                h.postDelayed(() -> {
                    if (progress == round && mode == GateRules.Screen.QUIZ && !isFinishing()) celebrate();
                }, 1200);
            } else {
                h.postDelayed(this::render, 1500);
            }
            return;
        }
        // read the explanation for at least 4 s before anything can be pressed (the parents' button included)
        final Button next = nextButton, parent = parentInBar;
        if (parent != null) parent.setFocusable(false);
        h.postDelayed(() -> {
            if (progress != round || mode != GateRules.Screen.QUIZ || isFinishing()) return;
            if (round.wrongInRow >= 3 || next == null) {
                renderHelp(it.explanation);
                return;
            }
            if (parent != null) parent.setFocusable(true);
            next.setOnClickListener(v -> render());
            next.setEnabled(true);
            next.setFocusable(true);
            next.setAlpha(1f);
            next.requestFocus();
        }, 4000);
    }

    private void celebrate() {
        celebrate(false);
    }

    void celebrate(boolean masteredExemption) {
        if (progress == null || mode != GateRules.Screen.QUIZ || isFinishing() || isDestroyed()) return;
        if (!GateStore.enabled(this)) {
            progress = null;
            mode = GateRules.Screen.NONE;
            leave();
            return;
        }
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (!power.isInteractive()) return;
        long now = System.currentTimeMillis();
        GateRules r = GateStore.rules(this);
        GateStore.prepareSlot(this, now);
        GateRules.Screen barrier = QuizCompletionGuard.required(r, now, GateStore.weekend());
        if (barrier != GateRules.Screen.NONE) {
            progress = null;
            mode = barrier;
            render();
            return;
        }
        if (!r.unlock(now)) {
            progress = null;
            mode = GateRules.Screen.REST;
            render();
            return;
        }
        GateStore.save(this);
        int earned = masteredExemption ? 0 : stars();
        LinearLayout page = page();
        LinearLayout c = lowerColumn();
        c.setPadding(0, 0, 0, dp(64));
        if (earned > 0) c.addView(new GateUi.Stars(this, earned, earned, Math.min(dp(34), dp(640) / (earned * 1.26f)), false), below(0));
        c.addView(GateUi.display(this, masteredExemption ? "这些知识点已通过啦" : "太棒啦！星星集齐了", GateUi.HEADLINE,
                GatePalette.ACCENT), below(14));
        if (masteredExemption)
            c.addView(GateUi.body(this, "这轮不用重复答题啦", 26, GatePalette.TEXT), below(8));
        long left = GateStore.rules(this).watchLeftMs(System.currentTimeMillis(), GateStore.weekend());
        long mins = Math.max(1, (left + GateRules.MIN - 1) / GateRules.MIN);
        c.addView(GateUi.body(this, "去看电视吧，这一次可以看 " + mins + " 分钟哦", GateUi.LEAD, GatePalette.TEXT), below(10));
        page.addView(c, new LinearLayout.LayoutParams(MATCH, 0, 1));
        setPage(page, "intro", true);
        try {
            GateStore.log(this, new JSONObject().put("ev", "unlock").put("mastery_exempt", masteredExemption));
        } catch (Exception ignored) {
        }
        progress = null;
        h.postDelayed(this::leave, 2500);
    }

    /** Three wrong in a row: stop guessing and fetch a parent; "try again" opens after a minute. */
    void renderHelp(String lastExplanation) {
        renderHelp("这几道题有点难", "叫爸爸妈妈来一起看看吧", lastExplanation);
    }

    /** The bank has nothing to ask (broken or empty): a parent has to look, the child cannot fix this. */
    void renderNoQuestions(String why) {
        renderHelp("暂时没有题目", "叫爸爸妈妈来看看吧", why);
    }

    private void renderHelp(String title, String lead, String detail) {
        LinearLayout page = page();
        LinearLayout c = lowerColumn();
        c.addView(GateUi.display(this, title, GateUi.HEADLINE, GatePalette.ACCENT), below(0));
        c.addView(GateUi.body(this, lead, GateUi.LEAD, GatePalette.TEXT), below(8));
        float room = getResources().getDisplayMetrics().widthPixels * 0.66f;
        TextView ex = GateUi.body(this, QuizText.wrap(detail == null ? "" : detail, room,
                GateUi.kaiMeasurer(this, GateUi.BODY)), GateUi.BODY, GatePalette.MUTED);
        ex.setGravity(Gravity.CENTER);
        c.addView(ex, below(14));
        LinearLayout btns = row();
        btns.setGravity(Gravity.CENTER);
        Button retry = GateUi.pill(this, "我再试试（60）", 22, 52);
        retry.setEnabled(false);
        retry.setFocusable(false);
        retry.setAlpha(0.6f);
        retry.setOnClickListener(v -> {
            if (progress != null) progress.wrongInRow = 0;
            render();
        });
        Button parent = GateUi.pill(this, "家长", 22, 52);
        parent.setOnClickListener(v -> { pinBack = () -> renderHelp(title, lead, detail); renderPin(); });
        btns.addView(retry);
        btns.addView(parent, beside(16));
        c.addView(btns, below(18));
        page.addView(c, new LinearLayout.LayoutParams(MATCH, 0, 1));
        setPage(page, "intro", true);
        parent.requestFocus();
        final int[] left = {60};
        ticker = new Runnable() {
            @Override
            public void run() {
                left[0]--;
                if (left[0] <= 0) {
                    retry.setText("我再试试");
                    retry.setEnabled(true);
                    retry.setFocusable(true);
                    retry.setAlpha(1f);
                    retry.requestFocus();
                } else {
                    retry.setText("我再试试（" + left[0] + "）");
                    h.postDelayed(this, 1000);
                }
            }
        };
        h.postDelayed(ticker, 1000);
    }

    void renderRest() {
        LinearLayout page = page();
        page.addView(pageBar("让眼睛休息一下"));
        LinearLayout c = lowerColumn();
        TextView left = GateUi.display(this, "", 54, GatePalette.ACCENT);
        c.addView(left, below(0));
        c.addView(GateUi.body(this, "去窗边看看远处的树  ·  喝口水  ·  跳一跳", 28, GatePalette.TEXT), below(10));
        c.addView(GateUi.body(this, "休息好了，答完题就能接着看", 22, GatePalette.MUTED), below(6));
        page.addView(c, new LinearLayout.LayoutParams(MATCH, 0, 1));
        page.addView(parentRow());
        setPage(page, "rest", true);
        ticker = new Runnable() {
            @Override
            public void run() {
                long ms = GateStore.rules(GateActivity.this).restLeftMs(System.currentTimeMillis());
                long s = (ms + 999) / 1000;
                left.setText(String.format(java.util.Locale.US, "还要休息 %02d:%02d", s / 60, s % 60));
                if (ms > 0) h.postDelayed(this, 1000);
            }
        };
        h.post(ticker);
    }

    private void renderLimit() {
        renderDone("今天的电视时间", null);
    }

    /** Between slots, or this slot's minutes are used up: say when the next slot starts. */
    private void renderWait() {
        renderDone("电视时间", GateStore.nextSlotToday(this));
    }

    /** Watching is over for now (next = "13:00") or for today (next = null): offer three things to do instead. */
    void renderDone(String caption, String next) {
        LinearLayout page = page();
        page.addView(pageBar(caption));
        LinearLayout c = lowerColumn();
        String quiet;
        if (next != null) {
            c.addView(GateUi.display(this, "这一段电视时间结束啦", GateUi.HEADLINE, GatePalette.ACCENT), below(0));
            c.addView(GateUi.body(this, "下一次：" + QuizText.clock(next) + " 可以看", 32, GatePalette.TEXT), below(8));
            c.addView(GateUi.body(this, "接下来想做什么？", GateUi.BODY, GatePalette.MUTED), below(6));
            quiet = QuizText.clock(next) + " 再见～";
        } else {
            c.addView(GateUi.display(this, "今天的电视时间用完啦", GateUi.HEADLINE, GatePalette.ACCENT), below(0));
            c.addView(GateUi.body(this, "明天再见～接下来想做什么？", GateUi.LEAD, GatePalette.TEXT), below(8));
            quiet = "明天见～";
        }
        page.addView(c, new LinearLayout.LayoutParams(MATCH, 0, 1));
        LinearLayout picks = choices(c, quiet);
        page.addView(footWithParent(picks));
        setPage(page, "limit", true);
        picks.getChildAt(0).requestFocus();
        h.postDelayed(() -> renderQuiet(quiet), QUIET_AFTER_MS);
    }

    /** Right after watching ends: wave goodbye, stretch, breathe, then the wait page. */
    void renderBye() {
        LinearLayout page = page();
        page.addView(pageBar("和电视说再见"));
        LinearLayout c = lowerColumn();
        c.addView(GateUi.display(this, "电视要休息啦", GateUi.HEADLINE, GatePalette.ACCENT), below(0));
        TextView step = GateUi.body(this, "", 36, GatePalette.TEXT);
        c.addView(step, below(10));
        TextView left = GateUi.body(this, "", GateUi.CAPTION, GatePalette.MUTED);
        c.addView(left, below(8));
        page.addView(c, new LinearLayout.LayoutParams(MATCH, 0, 1));
        page.addView(parentRow());
        setPage(page, "intro", true);
        playOnce("bye");
        final String[] steps = {"跟我一起，伸个大懒腰～", "慢慢吸气……", "慢慢呼气……", "再吸气……", "再呼气……",
                "眨眨眼睛，看看远处", "转转脖子，扭扭小手", "准备好去玩别的啦"};
        ticker = new Runnable() {
            @Override public void run() {
                long ms = byeUntil - SystemClock.elapsedRealtime();
                if (ms <= 0 || isFinishing() || isDestroyed()) { render(); return; }
                long gone = BYE_MS - ms;
                step.setText(steps[(int) Math.min(steps.length - 1, Math.max(0, gone / (BYE_MS / steps.length)))]);
                left.setText(((ms + 999) / 1000) + " 秒");
                h.postDelayed(this, Math.min(1000, ms));
            }
        };
        h.post(ticker);
    }

    /** After a minute on the wait/limit page the TV goes almost dark: nothing left to look at. */
    void renderQuiet(String line) {
        h.removeCallbacksAndMessages(null);
        LinearLayout page = page();
        LinearLayout c = column();
        c.setGravity(Gravity.CENTER);
        c.addView(GateUi.body(this, line, 26, GatePalette.withAlpha(GatePalette.MUTED, 0x99)), below(0));
        page.addView(c, new LinearLayout.LayoutParams(MATCH, 0, 1));
        View p = parentRow();
        p.setAlpha(0.5f);
        page.addView(p);
        setPage(page, null, false);
        root.setBackgroundColor(QUIET_BACKGROUND);
    }

    /** Three things to do next; picking one gives the child the say, and the log tells the parents. */
    private LinearLayout choices(LinearLayout msgColumn, String quietLine) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER);
        r.setPadding(0, dp(6), 0, dp(4));
        for (String pick : new String[]{"🎨 画画", "🧱 搭积木", "🏃 出去玩"}) {
            Button b = GateUi.pill(this, pick, 24, 58);
            b.setOnClickListener(v -> {
                String what = pick.substring(pick.indexOf(' ') + 1);
                try {
                    GateStore.log(this, new JSONObject().put("ev", "next_activity").put("pick", what));
                } catch (Exception ignored) {
                }
                h.removeCallbacksAndMessages(null);
                msgColumn.removeAllViews();
                msgColumn.addView(GateUi.display(this, "好主意！去" + what + "吧～", GateUi.HEADLINE, GatePalette.ACCENT), below(0));
                r.setVisibility(View.INVISIBLE);
                h.postDelayed(() -> renderQuiet(quietLine), 5000);
            });
            r.addView(b, r.getChildCount() == 0 ? new LinearLayout.LayoutParams(WRAP, WRAP) : beside(18));
        }
        return r;
    }

    /** One bundled clip (the goodbye); same audio rules as the welcome. */
    private void playOnce(String name) {
        stopIntroAudio();
        try (AssetFileDescriptor audio = getAssets().openFd(GateStore.audio(this, name))) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            introAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            introAudioFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs).build();
            if (introAudioManager.requestAudioFocus(introAudioFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                stopIntroAudio();
                return;
            }
            MediaPlayer player = new MediaPlayer();
            introPlayer = player;
            player.setAudioAttributes(attrs);
            player.setDataSource(audio.getFileDescriptor(), audio.getStartOffset(), audio.getLength());
            player.setOnPreparedListener(mp -> { if (introPlayer == mp) mp.start(); });
            player.setOnCompletionListener(mp -> stopIntroAudio());
            player.setOnErrorListener((mp, what, extra) -> { stopIntroAudio(); return true; });
            player.prepareAsync();
        } catch (Exception ignored) {
            stopIntroAudio();
        }
    }

    // ---------------------------------------------------------------- parent

    private Button parentButton(boolean inBar) {
        Button p = GateUi.quiet(this, "家长");
        p.setOnClickListener(v -> renderPin());
        if (inBar) parentInBar = p;
        return p;
    }

    /** Bottom row of a page: its main button(s) in the middle, the parents' entry at the right end of the same row. */
    private View footWithParent(View main) {
        FrameLayout foot = new FrameLayout(this);
        foot.addView(main, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        foot.addView(parentButton(false), new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END | Gravity.CENTER_VERTICAL));
        return foot;
    }

    /** Bottom-right parents' entry of the pages without a quiz bar; takes the focus unless the page moves it. */
    private View parentRow() {
        LinearLayout r = row();
        r.setGravity(Gravity.END);
        Button p = parentButton(false);
        r.addView(p);
        p.requestFocus();
        return r;
    }

    void renderPin() {
        stopIntroAudio();
        h.removeCallbacksAndMessages(null);
        LinearLayout page = page();
        page.setGravity(Gravity.CENTER);
        page.addView(GateUi.display(this, "家长密码", 34, GatePalette.TEXT), below(0));
        TextView dots = GateUi.body(this, "○ ○ ○ ○", 34, GatePalette.ACCENT);
        dots.setGravity(Gravity.CENTER);
        page.addView(dots, below(10));
        final StringBuilder entered = new StringBuilder();
        GridLayout g = new GridLayout(this);
        g.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "删除", "0", "返回"};
        Button first = null;
        for (String k : keys) {
            Button b = GateUi.tile(this, k, 28);
            if (k.length() == 1) b.setTypeface(GateUi.kai(this)); // digits in the plain face: quicker to read
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(150);
            lp.height = dp(70);
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                if (k.equals("返回")) { Runnable back = pinBack; pinBack = this::render; back.run(); return; }
                if (k.equals("删除")) { if (entered.length() > 0) entered.setLength(entered.length() - 1); }
                else if (entered.length() < 4) entered.append(k);
                StringBuilder d = new StringBuilder();
                for (int i = 0; i < 4; i++) d.append(i < entered.length() ? "● " : "○ ");
                dots.setText(d.toString().trim());
                if (entered.length() == 4) {
                    if (GateStore.pinOk(this, entered.toString())) renderParentMenu();
                    else { entered.setLength(0); dots.setText("密码不对"); }
                }
            });
            g.addView(b);
            if (first == null) first = b;
        }
        page.addView(g, below(14));
        setPage(page, null, false);
        first.requestFocus();
    }

    void renderParentMenu() {
        LinearLayout page = page();
        page.setGravity(Gravity.CENTER);
        page.addView(GateUi.display(this, "家长", 34, GatePalette.TEXT), below(0));
        Button watch = GateUi.tile(this, "大人看电视（待机前不限时、不计时）", 26);
        watch.setOnClickListener(v -> {
            GateStore.rules(this).enterParentMode();
            GateStore.save(this);
            logEv("parent_mode");
            progress = null;
            leave();
        });
        Button more = GateUi.tile(this, "给" + GateStore.childName(this) + "加 20 分钟", 26);
        more.setOnClickListener(v -> {
            GateRules r = GateStore.rules(this);
            r.addBonus(20 * GateRules.MIN);
            r.unlock(System.currentTimeMillis());
            r.sessionUsedMs = Math.max(0, r.sessionMs - 20 * GateRules.MIN);
            GateStore.save(this);
            logEv("bonus20");
            progress = null;
            leave();
        });
        Button back = GateUi.tile(this, "返回", 26);
        back.setOnClickListener(v -> render());
        for (Button b : new Button[]{watch, more, back}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(640), dp(78));
            lp.topMargin = dp(18);
            page.addView(b, lp);
        }
        setPage(page, null, false);
        watch.requestFocus();
    }

    private void logEv(String ev) {
        try {
            GateStore.log(this, new JSONObject().put("ev", ev));
        } catch (Exception ignored) {
        }
    }

    private void leave() {
        if (mode != GateRules.Screen.NONE && GateStore.enabled(this)
                && GateStore.rules(this).mustKeepRest(System.currentTimeMillis())) {
            if (mode != GateRules.Screen.REST) {
                mode = GateRules.Screen.REST;
                render();
            }
            return;
        }
        stopIntroAudio();
        visibleMode = null;
        finish();
        overridePendingTransition(0, 0);
    }


    // ---------------------------------------------------------------- view helpers

    private int stars() {
        return progress != null && progress.plan != null ? progress.plan.size() : 5;
    }

    /** A page with the standard margins; children are centred horizontally. */
    private LinearLayout page() {
        LinearLayout l = column();
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(dp(PAD_H), dp(PAD_TOP), dp(PAD_H), dp(PAD_BOTTOM));
        return l;
    }

    /** Top-left caption of the pages that are not the quiz, on a small see-through pill so it reads on any artwork. */
    private View pageBar(String caption) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView c = GateUi.body(this, caption, 22, GatePalette.TEXT);
        c.setPadding(dp(18), dp(7), dp(18), dp(7));
        c.setBackground(GateUi.box(GatePalette.withAlpha(GatePalette.PANEL, 0xB8), dp(22),
                GatePalette.withAlpha(GatePalette.BORDER, 0x55), dp(1)));
        bar.addView(c);
        return bar;
    }

    /** Words of an artwork page: the character fills the upper half, the text sits under it, centred. */
    private LinearLayout lowerColumn() {
        LinearLayout c = column();
        c.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        c.setPadding(0, 0, 0, dp(14));
        return c;
    }

    private LinearLayout.LayoutParams below(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(topDp);
        return lp;
    }

    /** The top-right parents' button moves left of artwork that sits in that corner (the star theme's moon). */
    private LinearLayout.LayoutParams clearOfCornerArt() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.rightMargin = dp(GatePalette.CORNER_DP);
        return lp;
    }

    private LinearLayout.LayoutParams beside(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(leftDp);
        return lp;
    }

    /**
     * bg: quiz / intro / rest / limit artwork of the round's theme, or null for a plain page (parent PIN).
     * scrim: darken the lower half a little, for the pages whose words sit directly on the artwork.
     */
    private void setPage(View page, String bg, boolean scrim) {
        root.removeAllViews();
        root.setBackgroundColor(GatePalette.BACKGROUND);
        Bitmap art = bg == null ? null : background(bg);
        if (art != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(art);
            root.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
            if (bg.equals("rest")) { // resting means looking away: keep the rest page dim
                View dim = new View(this);
                dim.setBackgroundColor(0x4D000000);
                root.addView(dim, new FrameLayout.LayoutParams(MATCH, MATCH));
            }
            if (scrim) {
                View shade = new View(this);
                shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                        GatePalette.withAlpha(GatePalette.BACKGROUND, 0), GatePalette.withAlpha(GatePalette.BACKGROUND, 0x8C),
                        GatePalette.withAlpha(GatePalette.BACKGROUND, 0xA6)}));
                root.addView(shade, new FrameLayout.LayoutParams(MATCH,
                        Math.round(getResources().getDisplayMetrics().heightPixels * 0.58f), Gravity.BOTTOM));
            }
        }
        root.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    /** One decoded background at a time (RGB_565, ~4 MB): the TV has little memory. */
    private Bitmap background(String page) {
        String key = GatePalette.id + "/" + page;
        if (key.equals(bgKey) && bgBitmap != null) return bgBitmap;
        try (java.io.InputStream in = getAssets().open("theme/" + key + ".webp")) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b != null) { bgBitmap = b; bgKey = key; }
            return b;
        } catch (Exception e) {
            return null; // missing artwork must never block the gate
        }
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private int dp(float v) {
        return GateUi.dp(this, v);
    }

    private static int exactly(int px) {
        return View.MeasureSpec.makeMeasureSpec(Math.max(px, 0), View.MeasureSpec.EXACTLY);
    }

    private static int unspecified() {
        return View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
    }
}
