package com.gathin.tvhome.gate;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

/** Full-screen gate: quiz (5 stars to unlock), eye rest, daily limit, parent PIN. Remote-control only. */
public final class GateActivity extends Activity {
    public static final String EXTRA_MODE = "mode";
    static volatile GateRules.Screen visibleMode;

    private static final int PINK = 0xFFFF7FA8, PINK_SOFT = 0xFFFFE3EC, INK = 0xFF4A3B52, MINT = 0xFF3DBE8B, SKY = 0xFF6FA8FF;

    /** Survives the activity being recreated or re-shown during one quiz. */
    private static final class Progress {
        List<String> plan;
        int stars, slot, wrongInRow;
        Bank.Item current;
    }
    private static Progress progress;

    private final Handler h = new Handler(Looper.getMainLooper());
    private GateRules.Screen mode = GateRules.Screen.QUIZ;
    private Bank bank;
    private FrameLayout root;
    private MediaPlayer player;
    private boolean answered;
    private Runnable ticker;
    private LinearLayout bottomRow;
    private Runnable pinBack = this::render;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        GateStore.mix(this); // picks up a changed custom-bank.json
        bank = new Bank(this);
        root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFFF0F6, 0xFFEAF4FF, 0xFFF3FFF0});
        root.setBackground(bg);
        setContentView(root);
        readMode(getIntent());
        render();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        visibleMode = mode;
    }

    @Override
    protected void onPause() {
        visibleMode = null;
        stopAudio();
        super.onPause();
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
        h.removeCallbacksAndMessages(null);
        stopAudio();
        visibleMode = mode;
        switch (mode) {
            case REST: renderRest(); break;
            case LIMIT: renderLimit(); break;
            default: renderQuiz();
        }
    }

    private void renderQuiz() {
        if (progress == null) {
            progress = new Progress();
            progress.plan = bank.plan(GateStore.mix(this));
        }
        if (progress.current == null) progress.current = bank.next(progress.plan.get(progress.slot % progress.plan.size()));
        Bank.Item it = progress.current;
        answered = false;

        LinearLayout page = column(48, 28);
        page.addView(topBar(starLine(), "集齐 " + stars() + " 颗星星，就能看电视啦"));

        LinearLayout middle = row();
        middle.setGravity(Gravity.CENTER_VERTICAL);
        TextView mascot = text(mascotFor(it.subject), 96, INK, false);
        mascot.setIncludeFontPadding(true);
        mascot.setPadding(0, dp(24), dp(24), dp(12));
        middle.addView(mascot);

        LinearLayout card = column(32, 24);
        card.setBackground(round(Color.WHITE, 36, 0, 0));
        card.setElevation(dp(6));
        TextView tag = text(it.subject.equals("英语") ? "English" : it.subject, 20, Color.WHITE, true);
        tag.setBackground(round(it.subject.equals("数学") ? SKY : it.subject.equals("英语") ? MINT : 0xFFFFA94D, 20, 0, 0));
        tag.setPadding(dp(14), dp(4), dp(14), dp(4));
        LinearLayout tagRow = row();
        tagRow.addView(tag);
        card.addView(tagRow);
        TextView q = text(it.question, 34, INK, true);
        q.setPadding(0, dp(14), 0, dp(6));
        card.addView(q);
        TextView feedback = text("", 26, INK, true);
        feedback.setVisibility(View.GONE);
        card.addView(feedback);
        TextView expl = text("", 24, 0xFF6B5E73, false);
        expl.setVisibility(View.GONE);
        card.addView(expl);
        middle.addView(card, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(middle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout opts = row();
        opts.setGravity(Gravity.CENTER);
        Button[] bs = new Button[it.options.length];
        for (int k = 0; k < it.options.length; k++) {
            final int idx = k;
            Button btn = bigButton("ABC".charAt(k) + "   " + it.options[k], 0xFFFFFFFF, PINK, 30);
            bs[k] = btn;
            btn.setOnClickListener(v -> onAnswer(idx, bs, feedback, expl));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(96), 1);
            lp.setMargins(dp(12), 0, dp(12), 0);
            opts.addView(btn, lp);
        }
        page.addView(opts);

        LinearLayout bottom = row();
        bottomRow = bottom;
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(0, dp(18), 0, 0);
        Button listen = smallButton("🔊 再听一遍");
        listen.setOnClickListener(v -> play(it.id));
        bottom.addView(listen);
        page.addView(bottom);

        setPage(page);
        bs[0].requestFocus();
        h.postDelayed(() -> play(it.id), 400);
    }

    private void onAnswer(int idx, Button[] bs, TextView feedback, TextView expl) {
        if (answered) return;
        answered = true;
        Bank.Item it = progress.current;
        boolean ok = idx == it.answer;
        bank.record(it, ok);
        try {
            GateStore.log(this, new JSONObject().put("ev", "answer").put("id", it.id).put("kp", it.kp)
                    .put("ok", ok).put("chosen", it.options[idx]));
        } catch (Exception ignored) {
        }
        stopAudio();
        bs[it.answer].setBackground(buttonBg(0xFFDDF7EA, MINT));
        feedback.setVisibility(View.VISIBLE);
        if (ok) {
            progress.stars++;
            progress.slot++;
            progress.wrongInRow = 0;
            progress.current = null;
            feedback.setText("答对啦！⭐ 真棒");
            feedback.setTextColor(MINT);
            if (progress.stars >= stars()) {
                h.postDelayed(this::celebrate, 1200);
            } else {
                h.postDelayed(this::render, 1500);
            }
            return;
        }
        bs[idx].setBackground(buttonBg(0xFFFFE1E1, 0xFFFF6B6B));
        progress.wrongInRow++;
        feedback.setText("差一点点～我们看看怎么想 💡");
        feedback.setTextColor(0xFFE0675F);
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
        LinearLayout page = column(48, 48);
        page.setGravity(Gravity.CENTER);
        page.addView(text("🎉", 120, INK, false));
        page.addView(text("太棒啦！星星集齐了", 44, PINK, true));
        long mins = GateStore.rules(this).sessionMs / GateRules.MIN;
        page.addView(text("去看电视吧，" + mins + " 分钟后让眼睛休息一下哦", 28, INK, false));
        setPage(page);
        GateRules r = GateStore.rules(this);
        r.unlock(System.currentTimeMillis());
        GateStore.save(this);
        try {
            GateStore.log(this, new JSONObject().put("ev", "unlock"));
        } catch (Exception ignored) {
        }
        progress = null;
        h.postDelayed(this::leave, 2500);
    }

    private void renderHelp(String lastExplanation) {
        LinearLayout page = column(48, 40);
        page.setGravity(Gravity.CENTER);
        page.addView(text("🧸", 110, INK, false));
        page.addView(text("这几道题有点难", 44, PINK, true));
        page.addView(text("叫爸爸妈妈来一起看看吧", 32, INK, false));
        TextView ex = text(lastExplanation, 24, 0xFF6B5E73, false);
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
        setPage(page);
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
        c.setGravity(Gravity.CENTER);
        c.addView(text("🌳", 110, INK, false));
        TextView left = text("", 56, PINK, true);
        c.addView(left);
        c.addView(text("去窗边看看远处的树  ·  喝口水  ·  跳一跳", 28, INK, false));
        c.addView(text("休息好了，答完题就能接着看", 24, 0xFF6B5E73, false));
        for (int i = 0; i < c.getChildCount(); i++) ((TextView) c.getChildAt(i)).setGravity(Gravity.CENTER);
        page.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        page.addView(parentRow());
        setPage(page);
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
        c.setGravity(Gravity.CENTER);
        c.addView(text("🌙", 110, INK, false));
        c.addView(text("今天的电视时间用完啦", 48, PINK, true));
        c.addView(text("明天再见～去玩玩别的吧", 30, INK, false));
        for (int i = 0; i < c.getChildCount(); i++) ((TextView) c.getChildAt(i)).setGravity(Gravity.CENTER);
        page.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        page.addView(parentRow());
        setPage(page);
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
        h.removeCallbacksAndMessages(null);
        stopAudio();
        LinearLayout page = column(48, 30);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.addView(text("家长密码", 40, INK, true));
        TextView dots = text("○ ○ ○ ○", 40, PINK, true);
        page.addView(dots);
        final StringBuilder entered = new StringBuilder();
        GridLayout g = new GridLayout(this);
        g.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "删除", "0", "返回"};
        Button first = null;
        for (String k : keys) {
            Button b = bigButton(k, Color.WHITE, PINK, 28);
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
        setPage(page);
        first.requestFocus();
    }

    private void renderParentMenu() {
        LinearLayout page = column(48, 40);
        page.setGravity(Gravity.CENTER);
        page.addView(text("家长", 40, INK, true));
        Button watch = bigButton("大人看电视（待机前不限时、不计时）", Color.WHITE, PINK, 26);
        watch.setOnClickListener(v -> {
            GateStore.rules(this).enterParentMode();
            GateStore.save(this);
            logEv("parent_mode");
            progress = null;
            leave();
        });
        Button more = bigButton("给" + GateStore.childName(this) + "加 20 分钟", Color.WHITE, PINK, 26);
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
        Button back = bigButton("返回", Color.WHITE, PINK, 26);
        back.setOnClickListener(v -> render());
        for (Button b : new Button[]{watch, more, back}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(640), dp(84));
            lp.setMargins(0, dp(10), 0, dp(10));
            page.addView(b, lp);
        }
        setPage(page);
        watch.requestFocus();
    }

    private void logEv(String ev) {
        try {
            GateStore.log(this, new JSONObject().put("ev", ev));
        } catch (Exception ignored) {
        }
    }

    private void leave() {
        visibleMode = null;
        finish();
        overridePendingTransition(0, 0);
    }

    // ---------------------------------------------------------------- audio

    private void play(String id) {
        stopAudio();
        try {
            player = new MediaPlayer();
            java.io.File ext = getExternalFilesDir(null);
            java.io.File custom = ext == null ? null : new java.io.File(ext, "audio/" + id + ".mp3");
            if (custom != null && custom.exists()) {
                player.setDataSource(custom.getAbsolutePath());
            } else {
                AssetFileDescriptor fd = getAssets().openFd("quiz/audio/" + id + ".mp3");
                player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                fd.close();
            }
            player.setOnCompletionListener(MediaPlayer::release);
            player.prepare();
            player.start();
        } catch (Exception e) {
            player = null;
        }
    }

    private void stopAudio() {
        if (player != null) {
            try {
                player.stop();
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    // ---------------------------------------------------------------- view helpers

    private static String mascotFor(String subject) {
        switch (subject) {
            case "数学": return "🐰";
            case "英语": return "🐱";
            case "语文": return "🐼";
            default: return "🦊";
        }
    }

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
        TextView l = text(left, 40, 0xFFFFB800, true);
        bar.addView(l);
        TextView c = text(caption, 24, INK, false);
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

    private void setPage(View page) {
        if (page instanceof LinearLayout && (((LinearLayout) page).getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL) {
            LinearLayout l = (LinearLayout) page;
            for (int i = 0; i < l.getChildCount(); i++)
                if (l.getChildAt(i) instanceof TextView && !(l.getChildAt(i) instanceof Button)) ((TextView) l.getChildAt(i)).setGravity(Gravity.CENTER);
        }
        root.removeAllViews();
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button bigButton(String s, int fill, int accent, int sp) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTextColor(INK);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(buttonBg(fill, accent));
        b.setFocusable(true);
        b.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(120).start());
        return b;
    }

    private Button smallButton(String s) {
        Button b = bigButton(s, Color.WHITE, SKY, 22);
        b.setPadding(dp(24), dp(8), dp(24), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(64));
        lp.setMargins(dp(12), 0, dp(12), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private StateListDrawable buttonBg(int fill, int accent) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(PINK_SOFT, 28, accent, 6));
        s.addState(new int[]{android.R.attr.state_pressed}, round(PINK_SOFT, 28, accent, 6));
        s.addState(new int[]{}, round(fill, 28, 0x33FF7FA8, 2));
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
