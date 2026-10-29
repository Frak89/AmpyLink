/*
 * Custom neon fast-scroller for the gallery: a thin rail that widens while you scroll; press and
 * drag it to fly through the card, with a bubble that shows the shooting date (Matrix decode +
 * glitch entrance) and the range of pictures on screen. GPL v3.
 */
package com.ampylink.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

final class NeonScroller extends View {
    interface Model {
        int rowCount();
        int firstRow();
        int visibleRows();
        String dateLabel(int row);   // big line
        String timeLabel(int row);   // small line under the date
        String rangeLabel();         // "FOTO 120–147 / 3015"
        void scrollToRow(int row);
    }

    private final Model m;
    private final Paint rail = new Paint(Paint.ANTI_ALIAS_FLAG), thumb = new Paint(Paint.ANTI_ALIAS_FLAG),
        bubble = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
        big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random r = new Random();
    private final Fx.Rain rain;
    private boolean dragging;
    private long lastScroll, pressAt;
    private float width = 0f;          // animated thumb width (dp units scaled 0..1)
    private String lastDate = "";
    private long dateChangedAt;
    private final float dp;

    NeonScroller(Context c, Model m) {
        super(c);
        this.m = m;
        dp = c.getResources().getDisplayMetrics().density;
        rail.setColor(0x2200F0FF);
        bubble.setColor(0xF20B0B14);
        edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(dp); edge.setColor(Cyber.CYAN);
        big.setTypeface(Cyber.MONO); big.setTextSize(20 * dp); big.setColor(Cyber.CYAN); big.setLetterSpacing(0.08f);
        small.setTypeface(Cyber.MONO); small.setTextSize(10 * dp); small.setColor(Cyber.TEXT); small.setLetterSpacing(0.1f);
        rain = new Fx.Rain(9 * dp);
    }

    /** Call from the list's OnScrollListener. */
    void onScrolled() { lastScroll = System.currentTimeMillis(); invalidate(); }

    boolean isDragging() { return dragging; }

    private float frac() {
        int n = m.rowCount(), v = Math.max(1, m.visibleRows());
        if (n <= v) return 0f;
        return Math.max(0f, Math.min(1f, m.firstRow() / (float) (n - v)));
    }

    private float thumbH() {
        int n = Math.max(1, m.rowCount());
        return Math.max(46 * dp, Math.min(getHeight() * 0.5f, getHeight() * m.visibleRows() / (float) n));
    }

    private float pad() { return 6 * dp; }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (m.rowCount() <= m.visibleRows() + 1) return false;
        float zone = 36 * dp;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (e.getX() < getWidth() - zone) return false; // not on the rail: let the list scroll
                dragging = true;
                pressAt = System.currentTimeMillis();
                getParent().requestDisallowInterceptTouchEvent(true);
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                jump(e.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) jump(e.getY());
                return dragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                lastScroll = System.currentTimeMillis();
                invalidate();
                return true;
        }
        return false;
    }

    private void jump(float y) {
        float th = thumbH(), usable = getHeight() - 2 * pad() - th;
        float f = Math.max(0f, Math.min(1f, (y - pad() - th / 2) / Math.max(1, usable)));
        int n = m.rowCount(), v = Math.max(1, m.visibleRows());
        int row = Math.round(f * Math.max(0, n - v));
        m.scrollToRow(row);
        lastScroll = System.currentTimeMillis();
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        int n = m.rowCount();
        if (n <= m.visibleRows() + 1) return;
        long now = System.currentTimeMillis();
        boolean active = dragging || now - lastScroll < 1400;
        float target = dragging ? 1f : active ? 0.45f : 0f;
        width += (target - width) * 0.25f;
        float W = getWidth(), H = getHeight();
        float tw = (3 + 11 * width) * dp, th = thumbH();
        float x1 = W - 4 * dp, x0 = x1 - tw;
        float top = pad() + frac() * (H - 2 * pad() - th);
        rail.setAlpha((int) (40 + 120 * width));
        c.drawRect(x1 - 2 * dp, pad(), x1, H - pad(), rail);
        thumb.setShader(new LinearGradient(0, top, 0, top + th, Cyber.CYAN, Cyber.MAGENTA, Shader.TileMode.CLAMP));
        thumb.setAlpha((int) (110 + 145 * Math.max(width, active ? 0.6f : 0f)));
        RectF tr = new RectF(x0, top, x1, top + th);
        c.drawRoundRect(tr, 3 * dp, 3 * dp, thumb);
        if (width > 0.5f) { // grip ticks
            Paint g = new Paint(); g.setColor(0x9907070C); g.setStrokeWidth(dp);
            for (int i = -1; i <= 1; i++) c.drawLine(x0 + 3 * dp, top + th / 2 + i * 5 * dp, x1 - 3 * dp, top + th / 2 + i * 5 * dp, g);
        }
        if (dragging) drawBubble(c, x0, top + th / 2, now);
        if (Math.abs(target - width) > 0.01f || active) postInvalidateOnAnimation();
    }

    private void drawBubble(Canvas c, float right, float cy, long now) {
        int row = Math.max(0, Math.min(m.rowCount() - 1, m.firstRow()));
        String date = m.dateLabel(row), time = m.timeLabel(row), range = m.rangeLabel();
        if (!date.equals(lastDate)) { lastDate = date; dateChangedAt = now; }
        float tIn = Math.min(1f, (now - pressAt) / 380f);            // bubble entrance
        float tDec = Math.min(1f, (now - dateChangedAt) / 420f);     // date decode on change
        float bw = 210 * dp, bh = 78 * dp;
        float l = right - 14 * dp - bw * (0.6f + 0.4f * ease(tIn)), t = cy - bh / 2;
        t = Math.max(4 * dp, Math.min(getHeight() - bh - 4 * dp, t));
        RectF b = new RectF(l, t, l + bw, t + bh);
        c.save();
        c.clipRect(b);
        bubble.setAlpha((int) (240 * ease(tIn)));
        c.drawRect(b, bubble);
        if (tIn < 1f || tDec < 1f) { c.save(); c.translate(l, t); rain.draw(c, (int) bw, (int) bh, 0.55f * (1f - Math.min(tIn, tDec) * 0.6f)); c.restore(); }
        float k = (1f - tDec) * 0.9f + (1f - tIn) * 0.6f + (r.nextFloat() < 0.04f ? 0.4f : 0f);
        Fx.glitchText(c, Fx.decode(date, tDec, r), l + 12 * dp, t + 30 * dp, big, Math.min(1f, k), r);
        small.setColor(Cyber.MAGENTA);
        Fx.glitchText(c, Fx.decode(time, tDec, r), l + 12 * dp, t + 47 * dp, small, Math.min(1f, k * 0.6f), r);
        small.setColor(Cyber.TEXT);
        c.drawText(range, l + 12 * dp, t + 66 * dp, small);
        c.restore();
        edge.setShader(new LinearGradient(b.left, 0, b.right, 0, Cyber.CYAN, Cyber.MAGENTA, Shader.TileMode.CLAMP));
        c.drawRect(b, edge);
        // pointer to the thumb
        c.drawLine(b.right, cy, right - 2 * dp, cy, edge);
    }

    private static float ease(float t) { return 1f - (1f - t) * (1f - t); }
}
