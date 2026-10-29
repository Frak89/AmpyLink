package com.ampylink.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

/** Cyberpunk look: neon palette, mono type, glow. */
final class Cyber {
    private Cyber() {}

    static final int BG = 0xFF07070C, PANEL = 0xE60B0B14, CYAN = 0xFF00F0FF, MAGENTA = 0xFFFF2BD6,
        YELLOW = 0xFFF5FF3B, TEXT = 0xFFC8F7FF, DIM = 0xFF52606E, LINE = 0x3300F0FF;
    static final Typeface MONO = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static TextView label(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(MONO);
        t.setTextColor(color);
        t.setLetterSpacing(0.12f);
        t.setShadowLayer(dp(c, 6), 0, 0, color & 0x99FFFFFF);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Chamfered neon button. */
    static TextView button(Context c, String s, int color) {
        TextView t = label(c, s, 11, color);
        t.setGravity(Gravity.CENTER);
        int ph = dp(c, 10), pv = dp(c, 8);
        t.setPadding(ph, pv, ph, pv);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x14000000 | (color & 0x00FFFFFF));
        g.setStroke(dp(c, 1), color & 0xAAFFFFFF);
        g.setCornerRadii(new float[]{0, 0, dp(c, 8), dp(c, 8), 0, 0, dp(c, 8), dp(c, 8)});
        t.setBackground(g);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    static GradientDrawable panelBg(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(PANEL);
        g.setStroke(dp(c, 1), LINE);
        return g;
    }

    /** One-parameter neon slider. Position 0..1; double-tap resets to default. */
    static final class NeonSlider extends View {
        interface Listener { void onPos(float pos, boolean done); }
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            thumb = new Paint(Paint.ANTI_ALIAS_FLAG), tick = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float pos = 0.5f, defPos = 0.5f;
        private Listener listener;
        private long lastTap;
        private float downX, downPos;
        private boolean wasLeft;

        NeonSlider(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null); // for shadow-layer glow
            track.setColor(0x3352606E); track.setStrokeWidth(dp(c, 2)); track.setStrokeCap(Paint.Cap.ROUND);
            fill.setStrokeWidth(dp(c, 3)); fill.setStrokeCap(Paint.Cap.ROUND);
            fill.setShadowLayer(dp(c, 6), 0, 0, CYAN);
            tick.setColor(DIM); tick.setStrokeWidth(dp(c, 1));
            thumb.setColor(CYAN); thumb.setShadowLayer(dp(c, 10), 0, 0, CYAN);
            glow.setColor(BG);
        }

        void setListener(Listener l) { listener = l; }
        void set(float p, float def) { pos = clamp(p); defPos = clamp(def); invalidate(); }

        private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(getContext(), 44));
        }

        @Override protected void onDraw(Canvas cv) {
            float pad = dp(getContext(), 18);
            float w = getWidth() - 2 * pad, y = getHeight() / 2f;
            float x0 = pad, x1 = pad + w;
            // ticks
            for (int i = 0; i <= 20; i++) {
                float x = x0 + w * i / 20f;
                float l = (i % 5 == 0) ? dp(getContext(), 7) : dp(getContext(), 3);
                cv.drawLine(x, y + dp(getContext(), 9), x, y + dp(getContext(), 9) + l, tick);
            }
            cv.drawLine(x0, y, x1, y, track);
            float xd = x0 + w * defPos, xp = x0 + w * pos;
            fill.setShader(new LinearGradient(x0, 0, x1, 0, CYAN, MAGENTA, Shader.TileMode.CLAMP));
            cv.drawLine(Math.min(xd, xp), y, Math.max(xd, xp), y, fill);
            // default marker
            tick.setColor(YELLOW);
            cv.drawLine(xd, y - dp(getContext(), 8), xd, y - dp(getContext(), 3), tick);
            tick.setColor(DIM);
            // diamond thumb
            float r = dp(getContext(), 9);
            Path p = new Path();
            p.moveTo(xp, y - r); p.lineTo(xp + r, y); p.lineTo(xp, y + r); p.lineTo(xp - r, y); p.close();
            thumb.setColor(Math.abs(pos - defPos) < 0.002f ? CYAN : MAGENTA);
            thumb.setShadowLayer(dp(getContext(), 10), 0, 0, thumb.getColor());
            cv.drawPath(p, thumb);
            float ri = r * 0.4f;
            Path q = new Path();
            q.moveTo(xp, y - ri); q.lineTo(xp + ri, y); q.lineTo(xp, y + ri); q.lineTo(xp - ri, y); q.close();
            cv.drawPath(q, glow);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float pad = dp(getContext(), 18), w = getWidth() - 2 * pad;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    long now = System.currentTimeMillis();
                    if (now - lastTap < 280) { // double tap = reset
                        pos = defPos; invalidate();
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        if (listener != null) listener.onPos(pos, true);
                        lastTap = 0; return true;
                    }
                    lastTap = now;
                    downX = e.getX(); downPos = pos;
                    wasLeft = pos < defPos;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    // relative drag; full width = full range (fine control far from the thumb)
                    float np = clamp(downPos + (e.getX() - downX) / w);
                    boolean left = np < defPos;
                    if (left != wasLeft) { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); wasLeft = left; }
                    // snap to default
                    if (Math.abs(np - defPos) < 0.012f) np = defPos;
                    pos = np; invalidate();
                    if (listener != null) listener.onPos(pos, false);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (listener != null) listener.onPos(pos, true);
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }

    static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }
    static int rgb(int r, int g, int b) { return Color.rgb(r, g, b); }
}
