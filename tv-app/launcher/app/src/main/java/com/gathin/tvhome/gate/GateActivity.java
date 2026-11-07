package com.gathin.tvhome.gate;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
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

/** Full-screen gate: quiz (one star per question to unlock), eye rest, daily limit, parent PIN. Remote-control only.
 * Each quiz round picks a theme at random (GatePalette); artwork backgrounds come from assets/theme/. */
public final class GateActivity extends Activity {
    public static final String EXTRA_MODE = "mode";
    static volatile GateRules.Screen visibleMode;
    /** Goodbye page shown until this elapsedRealtime (set by GateService when watching just ended). */
    static volatile long byeUntil;
    static final long BYE_MS = 60_000;
    private static final long QUIET_AFTER_MS = 60_000;
    private static final int QUIET_BACKGROUND = 0xFF07070C;


    /** Survives the activity being recreated or re-shown during one quiz. */
    private static final class Progress {
        final QuizIntro intro = new QuizIntro();
        final String roundToken = java.util.UUID.randomUUID().toString();
        List<String> plan;
        int stars, slot, wrongInRow;
        Bank.Item current;
    }
    private static Progress progress;
    private static Typeface kaiFont, roundFont;
    private static Bitmap bgBitmap;
    private static String bgKey;

    private final Handler h = new Handler(Looper.getMainLooper());
    private GateRules.Screen mode = GateRules.Screen.QUIZ;
    private Bank bank;
    private FrameLayout root;

    private boolean answered;
    private Runnable ticker;
    private LinearLayout bottomRow;
    private Runnable pinBack = this::render;
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
        bgBitmap = null; bgKey = null; kaiFont = null; roundFont = null;
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
            else renderHelp("当前没有可用题目，请家长检查题库。缺少题目不算知识点已通过。");
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
            else renderHelp("暂时没有可用题目，请家长检查题库。已通过的知识点不会为凑题重新出现。");
            return;
        }
        Bank.Item it = progress.current;
        answered = false;

        LinearLayout page = column(48, 28);
        page.addView(topBar(starLine(), "集齐 " + stars() + " 颗星星，就能看电视啦"));

        // left quarter: the theme artwork's character lives there, so nothing is drawn over it
        LinearLayout body = row();
        View art = new View(this);
        int artW = Math.max(0, Math.round(getResources().getDisplayMetrics().widthPixels * 0.25f) - dp(48));
        body.addView(art, new LinearLayout.LayoutParams(artW, 1));
        LinearLayout right = column(0, 0);

        LinearLayout middle = row();
        middle.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout card = column(32, 24);
        card.setBackground(round(GatePalette.withAlpha(GatePalette.PANEL, 0xEB), 36, GatePalette.withAlpha(GatePalette.BORDER, 0x66), 1));
        TextView tag = text(it.subject.equals("英语") ? "English" : it.subject, 20, GatePalette.LABEL_TEXT, true);
        tag.setBackground(round(it.subject.equals("数学") ? GatePalette.SKY : it.subject.equals("英语") ? GatePalette.MINT : GatePalette.AMBER, 20, 0, 0));
        tag.setPadding(dp(14), dp(4), dp(14), dp(4));
        LinearLayout tagRow = row();
        tagRow.addView(tag);
        card.addView(tagRow);
        TextView q = textKai(QuizText.breakChinese(it.question, 16), 36, GatePalette.TEXT);
        q.setLineSpacing(0, 1.12f);
        q.setPadding(0, dp(14), 0, dp(6));
        card.addView(q);
        TextView feedback = text("", 26, GatePalette.TEXT, true);
        feedback.setVisibility(View.GONE);
        card.addView(feedback);
        TextView expl = textKai("", 24, GatePalette.MUTED);
        expl.setVisibility(View.GONE);
        card.addView(expl);
        middle.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        right.addView(middle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout opts = row();
        opts.setGravity(Gravity.CENTER);
        Button[] bs = new Button[it.options.length];
        for (int k = 0; k < it.options.length; k++) {
            final int idx = k;
            Button btn = bigButton("ABC".charAt(k) + "   " + it.options[k], GatePalette.BUTTON, GatePalette.PINK, 30);
            btn.setTypeface(kai());
            bs[k] = btn;
            btn.setOnClickListener(v -> onAnswer(idx, bs, feedback, expl));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(96), 1);
            lp.setMargins(dp(12), 0, dp(12), 0);
            opts.addView(btn, lp);
        }
        right.addView(opts);

        LinearLayout bottom = row();
        bottomRow = bottom;
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(0, dp(18), 0, 0);
        right.addView(bottom);
        body.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setPage(page, "quiz");
        bs[0].requestFocus();
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

    private void renderQuizIntro() {
        final Progress round = progress;
        round.intro.start(SystemClock.elapsedRealtime());
        LinearLayout page = column(48, 28);
        page.addView(topBar("📺", "答题小提醒"));
        LinearLayout center = column(0, 0);
        center.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM); // artwork fills the upper half
        center.setPadding(0, 0, 0, dp(20));
        center.addView(text(GateStore.childName(this) + "，", 30, GatePalette.TEXT, true));
        center.addView(text("现在是答题看电视时间", 40, GatePalette.PINK, true));
        TextView subtitle = text("集齐 " + stars() + " 颗星星，就能看电视啦", 24, GatePalette.TEXT, false);
        subtitle.setPadding(0, dp(14), 0, dp(10));
        center.addView(subtitle);
        TextView countdown = text("", 20, GatePalette.MUTED, false);
        center.addView(countdown);
        for (int n = 0; n < center.getChildCount(); n++)
            ((TextView) center.getChildAt(n)).setGravity(Gravity.CENTER);
        page.addView(center, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER);
        Button start = smallButton("准备好啦，开始答题");
        start.setOnClickListener(v -> {
            if (progress != round) return;
            round.intro.complete();
            render();
        });
        actions.addView(start);
        page.addView(actions);
        setPage(page, "intro");
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

    private void onAnswer(int idx, Button[] bs, TextView feedback, TextView expl) {
        if (answered || mode != GateRules.Screen.QUIZ || progress == null || progress.current == null) return;
        answered = true;
        final Progress round = progress;
        Bank.Item it = progress.current;
        boolean ok = idx == it.answer;
        bank.record(it, ok);
        try {
            GateStore.log(this, new JSONObject().put("ev", "answer").put("id", it.id).put("kp", it.kp)
                    .put("subject", it.subject).put("round_id", round.roundToken)
                    .put("ok", ok).put("chosen", it.options[idx]));
        } catch (Exception ignored) {
        }
        bs[it.answer].setBackground(buttonBg(GatePalette.SUCCESS_SURFACE, GatePalette.MINT));
        feedback.setVisibility(View.VISIBLE);
        if (ok) {
            progress.stars++;
            progress.slot++;
            progress.wrongInRow = 0;
            progress.current = null;
            feedback.setText("答对啦！⭐ 真棒");
            feedback.setTextColor(GatePalette.MINT);
            if (progress.stars >= stars()) {
                h.postDelayed(() -> {
                    if (progress == round && mode == GateRules.Screen.QUIZ && !isFinishing()) celebrate();
                }, 1200);
            } else {
                h.postDelayed(this::render, 1500);
            }
            return;
        }
        bs[idx].setBackground(buttonBg(GatePalette.ERROR_SURFACE, GatePalette.ERROR_TEXT));
        progress.wrongInRow++;
        feedback.setText("差一点点～我们看看怎么想 💡");
        feedback.setTextColor(GatePalette.ERROR_TEXT);
        expl.setVisibility(View.VISIBLE);
        expl.setText(it.explanation);
        Bank.Item next = bank.similar(it);
        progress.current = next;
        for (Button b : bs) b.setFocusable(false);
        // read the explanation for at least 4 s before anything else can be pressed
        h.postDelayed(() -> {
            if (progress.wrongInRow >= 3) renderHelp(it.explanation);
            else {
                Button go = smallButton("下一题 ➜");
                go.setOnClickListener(v -> render());
                bottomRow.addView(go);
                go.requestFocus();
            }
        }, 4000);
    }

    private void celebrate() {
        celebrate(false);
    }

    private void celebrate(boolean masteredExemption) {
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
        LinearLayout page = column(48, 48);
        page.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        page.setPadding(dp(48), dp(48), dp(48), dp(110));
        page.addView(text(masteredExemption ? "这些知识点已通过啦" : "太棒啦！星星集齐了", 44, GatePalette.PINK, true));
        if (masteredExemption)
            page.addView(text("这轮不用重复答题啦", 26, GatePalette.TEXT, false));
        long left = GateStore.rules(this).watchLeftMs(System.currentTimeMillis(), GateStore.weekend());
        long mins = Math.max(1, (left + GateRules.MIN - 1) / GateRules.MIN);
        page.addView(text("去看电视吧，这一次可以看 " + mins + " 分钟哦", 28, GatePalette.TEXT, false));
        setPage(page, "intro");
        try {
            GateStore.log(this, new JSONObject().put("ev", "unlock").put("mastery_exempt", masteredExemption));
        } catch (Exception ignored) {
        }
        progress = null;
        h.postDelayed(this::leave, 2500);
    }

    private void renderHelp(String lastExplanation) {
        LinearLayout page = column(48, 40);
        page.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        page.setPadding(dp(48), dp(40), dp(48), dp(56));
        page.addView(text("这几道题有点难", 44, GatePalette.PINK, true));
        page.addView(text("叫爸爸妈妈来一起看看吧", 32, GatePalette.TEXT, false));
        TextView ex = textKai(lastExplanation, 24, GatePalette.MUTED);
        ex.setPadding(0, dp(16), 0, dp(16));
        page.addView(ex);
        LinearLayout btns = row();
        btns.setGravity(Gravity.CENTER);
        Button retry = smallButton("我再试试（60）");
        retry.setEnabled(false);
        retry.setOnClickListener(v -> {
            progress.wrongInRow = 0;
            render();
        });
        Button parent = smallButton("家长");
        parent.setOnClickListener(v -> { pinBack = () -> renderHelp(lastExplanation); renderPin(); });
        btns.addView(retry);
        btns.addView(parent);
        page.addView(btns);
        setPage(page, "intro");
        parent.requestFocus();
        final int[] left = {60};
        ticker = new Runnable() {
            @Override
            public void run() {
                left[0]--;
                if (left[0] <= 0) {
                    retry.setText("我再试试");
                    retry.setEnabled(true);
                    retry.requestFocus();
                } else {
                    retry.setText("我再试试（" + left[0] + "）");
                    h.postDelayed(this, 1000);
                }
            }
        };
        h.postDelayed(ticker, 1000);
    }

    private void renderRest() {
        LinearLayout page = column(48, 40);
        page.addView(topBar("👀", "让眼睛休息一下"));
        LinearLayout c = column(0, 0);
        c.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM); // artwork fills the upper half
        c.setPadding(0, 0, 0, dp(12));
        TextView left = text("", 56, GatePalette.PINK, true);
        c.addView(left);
        c.addView(text("去窗边看看远处的树  ·  喝口水  ·  跳一跳", 28, GatePalette.TEXT, false));
        c.addView(text("休息好了，答完题就能接着看", 24, GatePalette.MUTED, false));
        for (int i = 0; i < c.getChildCount(); i++) ((TextView) c.getChildAt(i)).setGravity(Gravity.CENTER);
        page.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        page.addView(parentRow());
        setPage(page, "rest");
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
        LinearLayout page = column(48, 40);
        page.addView(topBar("🌙", "今天的电视时间"));
        LinearLayout c = column(0, 0);
        c.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        c.setPadding(0, 0, 0, dp(12));
        c.addView(text("今天的电视时间用完啦", 48, GatePalette.PINK, true));
        c.addView(text("明天再见～接下来想做什么？", 30, GatePalette.TEXT, false));
        for (int i = 0; i < c.getChildCount(); i++) ((TextView) c.getChildAt(i)).setGravity(Gravity.CENTER);
        page.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        page.addView(choices(c, "明天见～"));
        page.addView(parentRow());
        setPage(page, "limit");
        focusFirstChoice(page);
        h.postDelayed(() -> renderQuiet("明天见～"), QUIET_AFTER_MS);
    }

    /** Right after watching ends: wave goodbye, stretch, breathe, then the wait page. */
    private void renderBye() {
        LinearLayout page = column(48, 40);
        page.addView(topBar("👋", "和电视说再见"));
        LinearLayout c = column(0, 0);
        c.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        c.setPadding(0, 0, 0, dp(12));
        c.addView(text("电视要休息啦", 48, GatePalette.PINK, true));
        TextView step = text("", 36, GatePalette.TEXT, false);
        step.setPadding(0, dp(10), 0, dp(6));
        c.addView(step);
        TextView left = text("", 22, GatePalette.MUTED, false);
        c.addView(left);
        for (int i = 0; i < c.getChildCount(); i++) ((TextView) c.getChildAt(i)).setGravity(Gravity.CENTER);
        page.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        page.addView(parentRow());
        setPage(page, "intro");
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
    private void renderQuiet(String line) {
        h.removeCallbacksAndMessages(null);
        LinearLayout page = column(48, 40);
        LinearLayout c = column(0, 0);
        c.setGravity(Gravity.CENTER);
        TextView t = text(line, 26, GatePalette.withAlpha(GatePalette.MUTED, 0x99), false);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        page.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        View p = parentRow();
        p.setAlpha(0.5f);
        page.addView(p);
        setPage(page, null);
        root.setBackgroundColor(QUIET_BACKGROUND);
    }

    /** Three things to do next; picking one gives her the say, and the log tells the parents. */
    private View choices(LinearLayout msgColumn, String quietLine) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER);
        r.setPadding(0, dp(8), 0, dp(8));
        for (String pick : new String[]{"🎨 画画", "🧱 搭积木", "🏃 出去玩"}) {
            Button b = smallButton(pick);
            b.setOnClickListener(v -> {
                String what = pick.substring(pick.indexOf(' ') + 1);
                try {
                    GateStore.log(this, new JSONObject().put("ev", "next_activity").put("pick", what));
                } catch (Exception ignored) {
                }
                h.removeCallbacksAndMessages(null);
                msgColumn.removeAllViews();
                TextView ok = text("好主意！去" + what + "吧～", 44, GatePalette.PINK, true);
                ok.setGravity(Gravity.CENTER);
                msgColumn.addView(ok);
                r.setVisibility(View.GONE);
                h.postDelayed(() -> renderQuiet(quietLine), 5000);
            });
            r.addView(b);
        }
        return r;
    }

    private void focusFirstChoice(View page) {
        View v = page.findFocus();
        LinearLayout l = (LinearLayout) page;
        for (int i = 0; i < l.getChildCount(); i++) {
            View ch = l.getChildAt(i);
            if (ch instanceof LinearLayout && ((LinearLayout) ch).getChildCount() == 3
                    && ((LinearLayout) ch).getChildAt(0) instanceof Button) {
                ((LinearLayout) ch).getChildAt(0).requestFocus();
                return;
            }
        }
        if (v != null) v.requestFocus();
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

    /** Between slots, or this slot's minutes are used up: say when the next slot starts. */
    private void renderWait() {
        LinearLayout page = column(48, 40);
        page.addView(topBar("🌙", "电视时间"));
        LinearLayout c = column(0, 0);
        c.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        c.setPadding(0, 0, 0, dp(12));
        String next = GateStore.nextSlotToday(this);
        String quiet;
        if (next != null) {
            c.addView(text("这一段电视时间结束啦", 48, GatePalette.PINK, true));
            c.addView(text("下一次：" + partOfDay(next) + " " + next + " 可以看", 34, GatePalette.TEXT, false));
            c.addView(text("接下来想做什么？", 26, GatePalette.MUTED, false));
            quiet = partOfDay(next) + " " + next + " 再见～";
        } else {
            c.addView(text("今天的电视时间用完啦", 48, GatePalette.PINK, true));
            c.addView(text("明天再见～接下来想做什么？", 30, GatePalette.TEXT, false));
            quiet = "明天见～";
        }
        for (int i = 0; i < c.getChildCount(); i++) ((TextView) c.getChildAt(i)).setGravity(Gravity.CENTER);
        page.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        page.addView(choices(c, quiet));
        page.addView(parentRow());
        setPage(page, "limit");
        focusFirstChoice(page);
        final String q = quiet;
        h.postDelayed(() -> renderQuiet(q), QUIET_AFTER_MS);
    }

    private static String partOfDay(String hhmm) {
        int h = Integer.parseInt(hhmm.split(":")[0]);
        return h < 12 ? "上午" : h < 18 ? "下午" : "晚上";
    }

    private View parentRow() {
        LinearLayout r = row();
        r.setGravity(Gravity.END);
        Button p = smallButton("家长");
        p.setOnClickListener(v -> renderPin());
        r.addView(p);
        p.requestFocus();
        return r;
    }

    // ---------------------------------------------------------------- parent

    private void renderPin() {
        stopIntroAudio();
        h.removeCallbacksAndMessages(null);
        LinearLayout page = column(48, 30);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.addView(text("家长密码", 40, GatePalette.TEXT, true));
        TextView dots = text("○ ○ ○ ○", 40, GatePalette.PINK, true);
        page.addView(dots);
        final StringBuilder entered = new StringBuilder();
        GridLayout g = new GridLayout(this);
        g.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "删除", "0", "返回"};
        Button first = null;
        for (String k : keys) {
            Button b = bigButton(k, GatePalette.BUTTON, GatePalette.PINK, 28);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(150);
            lp.height = dp(72);
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
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        page.addView(g, glp);
        setPage(page, null);
        first.requestFocus();
    }

    private void renderParentMenu() {
        LinearLayout page = column(48, 40);
        page.setGravity(Gravity.CENTER);
        page.addView(text("家长", 40, GatePalette.TEXT, true));
        Button watch = bigButton("大人看电视（待机前不限时、不计时）", GatePalette.BUTTON, GatePalette.PINK, 26);
        watch.setOnClickListener(v -> {
            GateStore.rules(this).enterParentMode();
            GateStore.save(this);
            logEv("parent_mode");
            progress = null;
            leave();
        });
        Button more = bigButton("给" + GateStore.childName(this) + "加 20 分钟", GatePalette.BUTTON, GatePalette.PINK, 26);
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
        Button back = bigButton("返回", GatePalette.BUTTON, GatePalette.PINK, 26);
        back.setOnClickListener(v -> render());
        for (Button b : new Button[]{watch, more, back}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(640), dp(84));
            lp.setMargins(0, dp(10), 0, dp(10));
            page.addView(b, lp);
        }
        setPage(page, null);
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

    private String starLine() {
        StringBuilder s = new StringBuilder();
        int have = progress == null ? 0 : progress.stars;
        for (int i = 0; i < stars(); i++) s.append(i < have ? "⭐" : "☆").append(' ');
        return s.toString().trim();
    }

    private View topBar(String left, String caption) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(left, 40, GatePalette.STAR, true);
        bar.addView(l);
        TextView c = text(caption, 24, GatePalette.TEXT, false);
        c.setPadding(dp(20), 0, 0, 0);
        bar.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (mode == GateRules.Screen.QUIZ) {
            Button p = smallButton("家长");
            p.setFocusable(true);
            p.setOnClickListener(v -> renderPin());
            bar.addView(p);
        }
        bar.setPadding(0, 0, 0, dp(16));
        return bar;
    }

    /** bg: quiz / intro / rest / limit artwork of the round's theme, or null for a plain page (parent PIN). */
    private void setPage(View page, String bg) {
        if (page instanceof LinearLayout && (((LinearLayout) page).getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL) {
            LinearLayout l = (LinearLayout) page;
            for (int i = 0; i < l.getChildCount(); i++)
                if (l.getChildAt(i) instanceof TextView && !(l.getChildAt(i) instanceof Button)) ((TextView) l.getChildAt(i)).setGravity(Gravity.CENTER);
        }
        root.removeAllViews();
        root.setBackgroundColor(GatePalette.BACKGROUND);
        Bitmap art = bg == null ? null : background(bg);
        if (art != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(art);
            root.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (bg.equals("rest")) { // resting means looking away: keep the rest page dim
                View dim = new View(this);
                dim.setBackgroundColor(0x4D000000);
                root.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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

    private Typeface kai() {
        if (kaiFont == null) {
            try { kaiFont = Typeface.createFromAsset(getAssets(), "fonts/quiz_kai.ttf"); }
            catch (Exception e) { kaiFont = Typeface.DEFAULT; }
        }
        return kaiFont;
    }

    private Typeface roundFace() {
        if (roundFont == null) {
            try { roundFont = Typeface.createFromAsset(getAssets(), "fonts/quiz_round.ttf"); }
            catch (Exception e) { roundFont = Typeface.DEFAULT_BOLD; }
        }
        return roundFont;
    }

    /** Question / options / explanations: textbook-like kaishu so character shapes match what she writes. */
    private TextView textKai(String s, int sp, int color) {
        TextView t = text(s, sp, color, false);
        t.setTypeface(kai());
        return t;
    }

    private LinearLayout column(int padH, int padV) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setTypeface(roundFace()); // single-weight rounded face; "bold" callers already get its heavy strokes
        return t;
    }

    private Button bigButton(String s, int fill, int accent, int sp) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTextColor(GatePalette.TEXT);
        b.setTypeface(roundFace());
        b.setBackgroundTintList(null);
        b.setBackground(buttonBg(fill, accent));
        b.setFocusable(true);
        b.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(120).start());
        return b;
    }

    private Button smallButton(String s) {
        Button b = bigButton(s, GatePalette.BUTTON, GatePalette.SKY, 22);
        b.setPadding(dp(24), dp(8), dp(24), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(64));
        lp.setMargins(dp(12), 0, dp(12), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private StateListDrawable buttonBg(int fill, int accent) {
        StateListDrawable s = new StateListDrawable();
        // remote focus: a clear but soft ring, no glow, so focus never looks like "this is the right answer"
        s.addState(new int[]{android.R.attr.state_focused}, round(GatePalette.FOCUS, 28, accent, 4));
        s.addState(new int[]{android.R.attr.state_pressed}, round(GatePalette.FOCUS, 28, accent, 4));
        s.addState(new int[]{}, round(fill, 28, GatePalette.BORDER, 2));
        return s;
    }

    private GradientDrawable round(int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
