package com.gathin.tvhome.gate;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.TextView;

/**
 * Typography, buttons and the star row shared by every gate page. Two faces with separate jobs: the rounded
 * display face for headlines, numbers and button labels (a few characters), the textbook-like kai face for
 * everything that is read as a sentence. Sizes are sp/dp; the TV is 1920x1080 at density 2, viewed from a sofa.
 */
final class GateUi {
    private GateUi() {}

    static final int HEADLINE = 46, LEAD = 30, BODY = 24, CAPTION = 21;
    /** Question sizes, largest first: one line, two lines, three lines, anything longer. */
    static final int[] QUESTION_SP = {40, 36, 32, 28};

    private static Typeface kaiFont, roundFont;

    static Typeface kai(Context c) {
        if (kaiFont == null) {
            try { kaiFont = Typeface.createFromAsset(c.getAssets(), "fonts/quiz_kai.ttf"); }
            catch (Exception e) { kaiFont = Typeface.DEFAULT; }
        }
        return kaiFont;
    }

    static Typeface round(Context c) {
        if (roundFont == null) {
            try { roundFont = Typeface.createFromAsset(c.getAssets(), "fonts/quiz_round.ttf"); }
            catch (Exception e) { roundFont = Typeface.DEFAULT_BOLD; }
        }
        return roundFont;
    }

    /** While the child watches, the process should be as small as possible: the next gate page reloads them. */
    static void release() {
        kaiFont = null;
        roundFont = null;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }

    /** Headline, number or label in the rounded display face (single weight: never add fake bold). */
    static TextView display(Context c, String s, int sp, int color) {
        TextView t = plain(c, s, sp, color, round(c));
        t.setLetterSpacing(0.03f);
        return t;
    }

    /** A sentence in the kai face: questions, answers, explanations, hints. */
    static TextView body(Context c, String s, int sp, int color) {
        TextView t = plain(c, s, sp, color, kai(c));
        t.setLineSpacing(0, 1.16f);
        return t;
    }

    private static TextView plain(Context c, String s, int sp, int color, Typeface face) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    /** Measures with exactly the paint body() uses, so QuizText.wrap and the TextView agree on every line. */
    static QuizText.Measurer kaiMeasurer(Context c, int sp) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(kai(c));
        p.setTextSize(sp(c, sp));
        return p::measureText;
    }

    static GradientDrawable box(int fill, float radiusPx, int stroke, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, stroke);
        return g;
    }

    /**
     * Resting and remote-focused look of anything that can be pressed. Focus is a clear but soft ring with no
     * glow, so it never reads as "this is the right answer".
     */
    static StateListDrawable pressable(Context c, int fill, int radiusDp) {
        float r = dp(c, radiusDp);
        StateListDrawable s = new StateListDrawable();
        GradientDrawable focused = box(GatePalette.FOCUS, r, GatePalette.ACCENT, dp(c, 3));
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, focused);
        s.addState(new int[]{}, box(fill, r, GatePalette.withAlpha(GatePalette.BORDER, 0xB0), dp(c, 1.5f)));
        return s;
    }

    static void growOnFocus(View v, float scale) {
        v.setOnFocusChangeListener((view, f) ->
                view.animate().scaleX(f ? scale : 1f).scaleY(f ? scale : 1f).setDuration(120).start());
    }

    /** A pill button: the rounded face, as tall as given, as wide as its label. */
    static Button pill(Context c, String s, int sp, int heightDp) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTextColor(GatePalette.TEXT);
        b.setTypeface(round(c));
        b.setLetterSpacing(0.03f);
        b.setIncludeFontPadding(false);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setHeight(dp(c, heightDp));
        b.setPadding(dp(c, heightDp * 0.5f), 0, dp(c, heightDp * 0.5f), 0);
        b.setBackgroundTintList(null);
        b.setBackground(pressable(c, GatePalette.withAlpha(GatePalette.BUTTON, 0xE6), heightDp / 2));
        b.setFocusable(true);
        growOnFocus(b, 1.04f);
        return b;
    }

    /** The parents' entry: present on every page, but quiet, so it does not compete with the child's buttons. */
    static Button quiet(Context c, String s) {
        Button b = pill(c, s, 18, 40);
        b.setTextColor(GatePalette.MUTED);
        b.setBackground(pressable(c, GatePalette.withAlpha(GatePalette.BUTTON, 0x99), 20));
        return b;
    }

    /** A rounded-rectangle key or menu row (parent pages). */
    static Button tile(Context c, String s, int sp) {
        Button b = pill(c, s, sp, 64);
        b.setBackground(pressable(c, GatePalette.BUTTON, 20));
        return b;
    }

    /** One star per question, drawn (not emoji) so they look the same on every TV; earned ones are filled. */
    static final class Stars extends View {
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG),
                offFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final int total, have;
        private final float size, gap;
        private final boolean popNewest;
        private float pop = 1f;

        Stars(Context c, int total, int have, float sizePx, boolean popNewest) {
            super(c);
            this.total = Math.max(1, total);
            this.have = Math.max(0, Math.min(have, this.total));
            this.size = sizePx;
            this.gap = sizePx * 0.26f;
            this.popNewest = popNewest && this.have > 0;
            float outer = size * 0.5f, inner = outer * 0.54f;
            for (int i = 0; i < 10; i++) {
                double a = -Math.PI / 2 + i * Math.PI / 5;
                float rad = i % 2 == 0 ? outer : inner;
                float x = (float) Math.cos(a) * rad, y = (float) Math.sin(a) * rad;
                if (i == 0) shape.moveTo(x, y); else shape.lineTo(x, y);
            }
            shape.close();
            CornerPathEffect soft = new CornerPathEffect(size * 0.11f);
            on.setStyle(Paint.Style.FILL);
            on.setColor(GatePalette.STAR);
            on.setPathEffect(soft);
            offFill.setStyle(Paint.Style.FILL);
            offFill.setColor(GatePalette.withAlpha(GatePalette.BUTTON, 0xB0));
            offFill.setPathEffect(soft);
            off.setStyle(Paint.Style.STROKE);
            off.setStrokeWidth(Math.max(2f, size * 0.06f));
            off.setColor(GatePalette.withAlpha(GatePalette.BORDER, 0xC0));
            off.setPathEffect(soft);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!popNewest) return;
            ValueAnimator a = ValueAnimator.ofFloat(0.3f, 1f);
            a.setDuration(420);
            a.setInterpolator(new OvershootInterpolator(3f));
            a.addUpdateListener(v -> {
                pop = (float) v.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension((int) Math.ceil(total * size + (total - 1) * gap), (int) Math.ceil(size * 1.04f));
        }

        @Override protected void onDraw(Canvas canvas) {
            for (int i = 0; i < total; i++) {
                canvas.save();
                canvas.translate(i * (size + gap) + size / 2f, size * 0.55f);
                if (i < have) {
                    if (i == have - 1) canvas.scale(pop, pop);
                    canvas.drawPath(shape, on);
                } else {
                    canvas.scale(0.9f, 0.9f);
                    canvas.drawPath(shape, offFill);
                    canvas.drawPath(shape, off);
                }
                canvas.restore();
            }
        }
    }
}
