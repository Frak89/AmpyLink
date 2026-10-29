/*
 * Cyber effects shared by splash, scroller bubble and viewer: Matrix rain, Matrix "decode" text,
 * RGB-split glitch text and glitch slices for bitmaps. GPL v3.
 */
package com.ampylink.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;

import java.util.Random;

final class Fx {
    private Fx() {}

    static final String GLYPHS = "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン0123456789ABCDEF▲◢◣";
    static final Random R = new Random();

    static char glyph(Random r) { return GLYPHS.charAt(r.nextInt(GLYPHS.length())); }

    /** Characters lock in left→right as t goes 0→1; the rest flicker as Matrix glyphs. */
    static String decode(String target, float t, Random r) {
        if (t >= 1f) return target;
        StringBuilder b = new StringBuilder(target.length());
        for (int i = 0; i < target.length(); i++) {
            char ch = target.charAt(i);
            float at = 0.15f + 0.75f * i / Math.max(1, target.length());
            b.append(ch == ' ' || t > at ? ch : glyph(r));
        }
        return b.toString();
    }

    /** Text with chromatic aberration + horizontal slice jitter; intensity 0 = clean. */
    static void glitchText(Canvas c, String s, float x, float y, Paint p, float k, Random r) {
        int col = p.getColor();
        float dx = k * p.getTextSize() * 0.12f;
        if (k > 0.01f) {
            int a = p.getAlpha();
            p.setColor(Cyber.MAGENTA); p.setAlpha((int) (a * 0.85f));
            c.drawText(s, x - dx - r.nextFloat() * dx, y, p);
            p.setColor(Cyber.CYAN); p.setAlpha((int) (a * 0.85f));
            c.drawText(s, x + dx + r.nextFloat() * dx, y, p);
            p.setColor(col);
        }
        if (k > 0.2f && r.nextFloat() < k) {
            // one displaced band
            float h = p.getTextSize();
            float top = y - h + r.nextFloat() * h, bh = h * (0.12f + r.nextFloat() * 0.25f);
            c.save();
            c.clipOutRect(-10000, top, 10000, top + bh);
            c.drawText(s, x, y, p);
            c.restore();
            c.save();
            c.clipRect(-10000, top, 10000, top + bh);
            c.drawText(s, x + (r.nextFloat() - 0.5f) * h * k, y, p);
            c.restore();
        } else c.drawText(s, x, y, p);
    }

    // ------------------------------------------------------------------ Matrix rain

    static final class Rain {
        private float[] y, speed;
        private char[][] cols;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float cell;
        private int w, h;

        Rain(float cellPx) {
            cell = cellPx;
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(cellPx * 0.9f);
        }

        private void init(int w, int h) {
            this.w = w; this.h = h;
            int n = Math.max(1, (int) (w / cell));
            int rows = Math.max(1, (int) (h / cell) + 2);
            y = new float[n]; speed = new float[n]; cols = new char[n][rows];
            for (int i = 0; i < n; i++) {
                y[i] = -R.nextFloat() * h;
                speed[i] = cell * (0.25f + R.nextFloat() * 0.6f);
                for (int j = 0; j < rows; j++) cols[i][j] = glyph(R);
            }
        }

        /** One frame; alpha 0..1. */
        void draw(Canvas c, int width, int height, float alpha) {
            if (y == null || width != w || height != h) init(width, height);
            int trail = 14;
            for (int i = 0; i < y.length; i++) {
                y[i] += speed[i];
                if (y[i] - trail * cell > h) { y[i] = -R.nextFloat() * h * 0.3f; speed[i] = cell * (0.25f + R.nextFloat() * 0.6f); }
                float x = i * cell;
                int head = (int) (y[i] / cell);
                for (int t = 0; t < trail; t++) {
                    int row = head - t;
                    if (row < 0 || row >= cols[i].length) continue;
                    if (R.nextFloat() < 0.04f) cols[i][row] = glyph(R);
                    float a = alpha * (1f - t / (float) trail);
                    int color = t == 0 ? 0xFFE8FFFF : (i % 7 == 0 ? Cyber.MAGENTA : Cyber.CYAN);
                    p.setColor(color);
                    p.setAlpha((int) (255 * a * (t == 0 ? 1f : 0.55f)));
                    c.drawText(String.valueOf(cols[i][row]), x, row * cell, p);
                }
            }
        }
    }

    // ------------------------------------------------------------------ bitmap glitch

    private static final Paint RED = new Paint(Paint.FILTER_BITMAP_FLAG), GB = new Paint(Paint.FILTER_BITMAP_FLAG), PLAIN = new Paint(Paint.FILTER_BITMAP_FLAG);
    static {
        RED.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0})));
        GB.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0})));
        RED.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        GB.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    /**
     * Draws bm with matrix m, glitched with intensity k (0..1): RGB split (red ⟷ green/blue),
     * displaced horizontal slices and scanlines. k == 0 draws it normally.
     */
    static void glitchBitmap(Canvas c, Bitmap bm, Matrix m, float k, int viewW, int viewH, Random r) {
        if (k <= 0.01f) { c.drawBitmap(bm, m, PLAIN); return; }
        float split = k * viewW * 0.025f;
        c.save();
        c.clipRect(0, 0, viewW, viewH);
        c.drawColor(0xFF000000);
        Matrix mr = new Matrix(m), mg = new Matrix(m);
        mr.postTranslate(-split, 0); mg.postTranslate(split, 0);
        c.drawBitmap(bm, mr, RED);
        c.drawBitmap(bm, mg, GB);
        int bands = 2 + (int) (k * 7);
        for (int i = 0; i < bands; i++) {
            float top = r.nextFloat() * viewH, h = viewH * (0.01f + r.nextFloat() * 0.06f * k);
            float off = (r.nextFloat() - 0.5f) * viewW * 0.18f * k;
            c.save();
            c.clipRect(0, top, viewW, top + h);
            c.drawColor(0xFF000000);
            Matrix ms = new Matrix(m); ms.postTranslate(off, 0);
            c.drawBitmap(bm, ms, PLAIN);
            if (r.nextBoolean()) c.drawColor(r.nextBoolean() ? 0x3300F0FF : 0x33FF2BD6);
            c.restore();
        }
        Paint sl = new Paint();
        sl.setColor(0x22000000);
        for (float yy = 0; yy < viewH; yy += 4) c.drawRect(0, yy, viewW, yy + 2, sl);
        c.restore();
    }
}
