package com.ampylink.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

final class UiViews {
    private UiViews() {}

    /** RGB + luminance histogram, neon styled, with clipping warnings. */
    static final class HistogramView extends View {
        private int[] r, g, b, l;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            grid = new Paint(), bg = new Paint(), txt = new Paint(Paint.ANTI_ALIAS_FLAG);

        HistogramView(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_HARDWARE, null);
            fill.setStyle(Paint.Style.FILL);
            fill.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(Cyber.dp(c, 1.2f)); line.setColor(0xDDFFFFFF);
            grid.setColor(0x2200F0FF); grid.setStrokeWidth(1);
            bg.setColor(0xCC07070C);
            txt.setTypeface(Cyber.MONO); txt.setTextSize(Cyber.dp(c, 8)); txt.setColor(Cyber.DIM);
        }

        void set(int[] r, int[] g, int[] b, int[] l) { this.r = r; this.g = g; this.b = b; this.l = l; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawRect(0, 0, w, h, bg);
            for (int i = 1; i < 4; i++) c.drawLine(w * i / 4f, 0, w * i / 4f, h, grid);
            grid.setColor(0x6600F0FF);
            c.drawLine(0, 0, w, 0, grid); c.drawLine(0, h - 1, w, h - 1, grid);
            grid.setColor(0x2200F0FF);
            if (r == null) { c.drawText("HISTOGRAM", Cyber.dp(getContext(), 4), h / 2, txt); return; }
            // shared scale: 99.5th percentile of bins so spikes don't flatten the graph
            int[] all = new int[256 * 3];
            System.arraycopy(r, 0, all, 0, 256); System.arraycopy(g, 0, all, 256, 256); System.arraycopy(b, 0, all, 512, 256);
            java.util.Arrays.sort(all);
            float max = Math.max(1, all[(int) (all.length * 0.995f)]);
            drawCh(c, r, 0x99FF3355, max, w, h);
            drawCh(c, g, 0x9933FF88, max, w, h);
            drawCh(c, b, 0x993377FF, max, w, h);
            Path p = path(l, max, w, h, false);
            c.drawPath(p, line);
            // clipping indicators
            long total = 0; for (int v : l) total += v;
            if (total > 0) {
                if ((r[255] + g[255] + b[255]) > total * 0.003) { txt.setColor(Cyber.MAGENTA); c.drawText("CLIP", w - Cyber.dp(getContext(), 24), Cyber.dp(getContext(), 10), txt); }
                if ((r[0] + g[0] + b[0]) > total * 0.003) { txt.setColor(Cyber.CYAN); c.drawText("CLIP", Cyber.dp(getContext(), 3), Cyber.dp(getContext(), 10), txt); }
                txt.setColor(Cyber.DIM);
            }
        }

        private void drawCh(Canvas c, int[] v, int color, float max, float w, float h) {
            fill.setColor(color);
            c.drawPath(path(v, max, w, h, true), fill);
        }

        private Path path(int[] v, float max, float w, float h, boolean closed) {
            Path p = new Path();
            if (closed) p.moveTo(0, h);
            for (int i = 0; i < 256; i++) {
                float x = w * i / 255f, y = h - Math.min(1f, v[i] / max) * (h - 2);
                if (!closed && i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            if (closed) { p.lineTo(w, h); p.close(); }
            return p;
        }
    }

    /** Tone curve editor: tap to add a point, drag to move, drag out of the box or double-tap to delete. */
    static final class CurveView extends View {
        interface Listener { void onCurveChanged(); }
        private final Curves curves;
        private int channel = Curves.MASTER;
        private Listener listener;
        private Curves.Pt drag;
        private long lastTap; private float lastTapX, lastTapY;
        private int[] hist; // luminance histogram backdrop
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), curve = new Paint(Paint.ANTI_ALIAS_FLAG),
            pt = new Paint(Paint.ANTI_ALIAS_FLAG), ghost = new Paint(Paint.ANTI_ALIAS_FLAG), hp = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint();
        private final float pad;

        CurveView(Context c, Curves curves) {
            super(c);
            this.curves = curves;
            pad = Cyber.dp(c, 10);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            grid.setColor(0x3300F0FF); grid.setStrokeWidth(1);
            curve.setStyle(Paint.Style.STROKE); curve.setStrokeWidth(Cyber.dp(c, 2));
            ghost.setStyle(Paint.Style.STROKE); ghost.setStrokeWidth(Cyber.dp(c, 1)); ghost.setColor(0x44C8F7FF);
            pt.setStyle(Paint.Style.FILL);
            hp.setColor(0x1AC8F7FF);
            bg.setColor(0xFF0B0B14);
        }

        void setListener(Listener l) { listener = l; }
        void setChannel(int c) { channel = c; invalidate(); }
        int channel() { return channel; }
        void setHistogram(int[] l) { hist = l; invalidate(); }

        static int color(int ch) {
            switch (ch) { case Curves.RED: return 0xFFFF3B5C; case Curves.GREEN: return 0xFF3BFF8A; case Curves.BLUE: return 0xFF3B8BFF; default: return Cyber.CYAN; }
        }

        @Override protected void onMeasure(int wm, int hm) {
            int w = MeasureSpec.getSize(wm), h = MeasureSpec.getSize(hm);
            int s = MeasureSpec.getMode(hm) == MeasureSpec.UNSPECIFIED ? w : Math.min(w, h);
            if (MeasureSpec.getMode(hm) == MeasureSpec.UNSPECIFIED) s = Math.min(w, Cyber.dp(getContext(), 220));
            setMeasuredDimension(MeasureSpec.getMode(wm) == MeasureSpec.EXACTLY ? w : s, s);
        }

        private float bx0() { return (getWidth() - side()) / 2f + pad; }
        private float side() { return Math.min(getWidth(), getHeight()); }
        private float size() { return side() - 2 * pad; }
        private float toX(float v) { return bx0() + v * size(); }
        private float toY(float v) { return pad + (1 - v) * size(); }
        private float fromX(float x) { return (x - bx0()) / size(); }
        private float fromY(float y) { return 1 - (y - pad) / size(); }

        @Override protected void onDraw(Canvas c) {
            float s = size(), x0 = bx0(), y0 = pad;
            c.drawRect(x0, y0, x0 + s, y0 + s, bg);
            if (hist != null) {
                int mx = 1; for (int v : hist) mx = Math.max(mx, v);
                Path h = new Path(); h.moveTo(x0, y0 + s);
                for (int i = 0; i < 256; i++) h.lineTo(x0 + s * i / 255f, y0 + s - s * 0.9f * (float) Math.sqrt(hist[i] / (float) mx));
                h.lineTo(x0 + s, y0 + s); h.close();
                c.drawPath(h, hp);
            }
            for (int i = 0; i <= 4; i++) {
                c.drawLine(x0 + s * i / 4, y0, x0 + s * i / 4, y0 + s, grid);
                c.drawLine(x0, y0 + s * i / 4, x0 + s, y0 + s * i / 4, grid);
            }
            c.drawLine(x0, y0 + s, x0 + s, y0, ghost);
            // other channels faint
            for (int ch = 0; ch < 4; ch++) if (ch != channel && !curves.isIdentity(ch)) drawCurve(c, ch, 0x55);
            drawCurve(c, channel, 0xFF);
            int col = color(channel);
            for (Curves.Pt p : curves.ch[channel]) {
                pt.setColor(p == drag ? Cyber.MAGENTA : col);
                pt.setShadowLayer(Cyber.dp(getContext(), 6), 0, 0, pt.getColor());
                float r = Cyber.dp(getContext(), p == drag ? 7 : 5);
                c.drawRect(toX(p.x) - r, toY(p.y) - r, toX(p.x) + r, toY(p.y) + r, pt);
            }
        }

        private void drawCurve(Canvas c, int ch, int alpha) {
            Path p = new Path();
            for (int i = 0; i <= 128; i++) {
                float x = i / 128f, y = curves.eval(ch, x);
                if (i == 0) p.moveTo(toX(x), toY(y)); else p.lineTo(toX(x), toY(y));
            }
            int col = color(ch);
            curve.setColor(Cyber.alpha(col, alpha));
            curve.setShadowLayer(alpha == 0xFF ? Cyber.dp(getContext(), 5) : 0, 0, 0, col);
            c.drawPath(p, curve);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = fromX(e.getX()), y = fromY(e.getY());
            java.util.List<Curves.Pt> pts = curves.ch[channel];
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    float best = Cyber.dp(getContext(), 24) / size();
                    drag = null;
                    for (Curves.Pt p : pts) {
                        float d = (float) Math.hypot(p.x - x, p.y - y);
                        if (d < best) { best = d; drag = p; }
                    }
                    long now = System.currentTimeMillis();
                    boolean dbl = now - lastTap < 300 && Math.hypot(e.getX() - lastTapX, e.getY() - lastTapY) < Cyber.dp(getContext(), 24);
                    lastTap = now; lastTapX = e.getX(); lastTapY = e.getY();
                    if (dbl && drag != null && pts.indexOf(drag) > 0 && pts.indexOf(drag) < pts.size() - 1) {
                        pts.remove(drag); drag = null; changed(); performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        return true;
                    }
                    if (drag == null && x > 0.01f && x < 0.99f && pts.size() < 12) {
                        drag = new Curves.Pt(x, Math.max(0, Math.min(1, y)));
                        pts.add(drag); curves.sort(channel); changed();
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (drag == null) return true;
                    int i = pts.indexOf(drag);
                    boolean endpoint = i == 0 || i == pts.size() - 1;
                    if (!endpoint && (y < -0.15f || y > 1.15f)) { // dragged out -> delete
                        pts.remove(drag); drag = null; changed();
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        return true;
                    }
                    float lo = i > 0 ? pts.get(i - 1).x + 0.01f : 0f, hi = i < pts.size() - 1 ? pts.get(i + 1).x - 0.01f : 1f;
                    if (i == 0) hi = Math.min(hi, 0.95f);
                    if (i == pts.size() - 1) lo = Math.max(lo, 0.05f);
                    drag.x = endpoint && pts.size() == 2 ? Math.max(lo, Math.min(hi, x)) : Math.max(lo, Math.min(hi, x));
                    drag.y = Math.max(0, Math.min(1, y));
                    changed();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drag = null; invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }

        private void changed() { invalidate(); if (listener != null) listener.onCurveChanged(); }
    }
}
