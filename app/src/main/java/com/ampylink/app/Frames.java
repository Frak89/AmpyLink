/*
 * Print frames for the KODAK Mini 2 Retro card (640×1024 px, 2.1"×3.4").
 * The same drawFrame() renders the final print and the live in-app frame overlay in the editor,
 * so what you see around the photo while editing is exactly what comes out of the printer.
 * GPL v3.
 */
package com.ampylink.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

final class Frames {
    private Frames() {}

    static final int CARD_W = 640, CARD_H = 1024;

    enum Layout {
        FULL("FULL"), BORDER("BORDER"), RETRO("RETRO"), POLA("INSTANT"), FILM("FILM"), NEON("NEON");
        final String label;
        Layout(String l) { label = l; }
    }

    /** Text printed on the frame. */
    static final class Meta {
        String date = "", time = "", camera = "", exposure = "", title = "";
        boolean stamp = true;   // orange date stamp on the photo (RETRO/FILM)
        static Meta of(Exif.Info e, String fileName) {
            Meta m = new Meta();
            if (e != null) {
                m.date = e.dateShort(); m.time = e.timeShort();
                m.camera = e.camera().toUpperCase(java.util.Locale.US);
                m.exposure = e.exposureLine();
            }
            m.title = fileName != null ? fileName : "";
            if (m.date.isEmpty()) m.date = new java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.US).format(new java.util.Date());
            return m;
        }
    }

    /** Photo window inside a card of cw×ch. */
    static RectF box(Layout l, int cw, int ch) {
        boolean land = cw > ch;
        switch (l) {
            case BORDER: return new RectF(28, 28, cw - 28, ch - 28);
            case RETRO: { int band = Math.round(ch * (land ? 0.17f : 0.145f)); return new RectF(36, 36, cw - 36, ch - band); }
            case POLA: { int band = Math.round(ch * (land ? 0.24f : 0.21f)); return new RectF(40, 40, cw - 40, ch - band); }
            case FILM: return land ? new RectF(30, 78, cw - 30, ch - 78) : new RectF(78, 30, cw - 78, ch - 30);
            case NEON: { int band = Math.round(ch * (land ? 0.15f : 0.11f)); return new RectF(34, 34, cw - 34, ch - band); }
            default: return new RectF(0, 0, cw, ch);
        }
    }

    private static final Typeface MONO = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private static final Typeface SERIF = Typeface.create(Typeface.SERIF, Typeface.ITALIC);

    /** Draw everything except the photo (outside box), in card coordinates. */
    static void drawFrame(Canvas c, Layout l, int cw, int ch, Meta m) {
        RectF b = box(l, cw, ch);
        boolean land = cw > ch;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.save();
        c.clipOutRect(b);
        switch (l) {
            case FULL: break;
            case BORDER: c.drawColor(0xFFFFFFFF); break;
            case RETRO: {
                c.drawColor(0xFFF4F1EA);
                p.setTypeface(MONO); p.setLetterSpacing(0.15f);
                float y0 = b.bottom;
                float band = ch - y0;
                p.setColor(0xFF1A1A22); p.setTextSize(band * 0.24f);
                c.drawText(trim(m.camera.isEmpty() ? "AMPYLINK" : m.camera, p, cw - 200), 40, y0 + band * 0.42f, p);
                p.setTextSize(band * 0.15f); p.setColor(0xFFB0108F);
                c.drawText(trim(m.date + (m.time.isEmpty() ? "" : " // " + m.time), p, cw - 200), 40, y0 + band * 0.66f, p);
                p.setColor(0xFF505A66); p.setTextSize(band * 0.12f);
                c.drawText(trim(m.exposure, p, cw - 80), 40, y0 + band * 0.86f, p);
                p.setColor(0xFF00A8B5);
                c.drawRect(cw - 120, y0 + band * 0.30f, cw - 40, y0 + band * 0.30f + 6, p);
                p.setColor(0xFFB0108F);
                c.drawRect(cw - 120, y0 + band * 0.30f + 12, cw - 70, y0 + band * 0.30f + 18, p);
                break;
            }
            case POLA: {
                c.drawColor(0xFFFBFAF6);
                float y0 = b.bottom, band = ch - y0;
                p.setTypeface(SERIF); p.setColor(0xFF2A2A30); p.setTextSize(band * 0.2f);
                String t = m.title.replaceAll("\\.[^.]*$", "");
                c.drawText(trim(m.date.isEmpty() ? t : m.date, p, cw - 90), 48, y0 + band * 0.45f, p);
                p.setTypeface(MONO); p.setTextSize(band * 0.1f); p.setColor(0xFF8A8F98); p.setLetterSpacing(0.1f);
                c.drawText(trim(m.exposure, p, cw - 90), 48, y0 + band * 0.68f, p);
                break;
            }
            case FILM: {
                c.drawColor(0xFF111114);
                p.setColor(0xFFE8E2D0);
                float hole = 22, gap = 46;
                if (land) {
                    for (float x = 18; x < cw - hole; x += gap) { c.drawRoundRect(x, 24, x + hole, 24 + 30, 4, 4, p); c.drawRoundRect(x, ch - 54, x + hole, ch - 24, 4, 4, p); }
                } else {
                    for (float y = 18; y < ch - hole; y += gap) { c.drawRoundRect(24, y, 24 + 30, y + hole, 4, 4, p); c.drawRoundRect(cw - 54, y, cw - 24, y + hole, 4, 4, p); }
                }
                p.setColor(0xFFF0A020); p.setTypeface(MONO); p.setTextSize(16); p.setLetterSpacing(0.2f);
                c.save();
                if (land) c.drawText("AMPYLINK 400  ▸ " + m.date, 40, 18, p);
                else { c.rotate(90); c.drawText("AMPYLINK 400  ▸ " + m.date, 40, -60, p); }
                c.restore();
                break;
            }
            case NEON: {
                c.drawColor(0xFF07070C);
                Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
                line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(3);
                line.setShader(new LinearGradient(0, 0, cw, ch, 0xFF00F0FF, 0xFFFF2BD6, Shader.TileMode.CLAMP));
                c.drawRect(b.left - 10, b.top - 10, b.right + 10, b.bottom + 10, line);
                line.setStrokeWidth(1.5f);
                c.drawRect(b.left - 18, b.top - 18, b.right + 18, b.bottom + 18, line);
                float y0 = b.bottom + 18, band = ch - y0;
                p.setTypeface(MONO); p.setLetterSpacing(0.18f);
                p.setColor(0xFF00F0FF); p.setTextSize(Math.min(30, band * 0.34f));
                c.drawText("AMPY▲LINK", b.left, y0 + band * 0.48f, p);
                p.setColor(0xFFFF2BD6); p.setTextSize(Math.min(17, band * 0.2f));
                c.drawText(trim(m.date + "  " + m.exposure, p, cw - 2 * b.left), b.left, y0 + band * 0.78f, p);
                p.setColor(0xFFF5FF3B);
                Paint tri = new Paint(Paint.ANTI_ALIAS_FLAG); tri.setColor(0xFFF5FF3B);
                android.graphics.Path tp = new android.graphics.Path();
                float tx = cw - b.left - 16, ty = y0 + band * 0.48f;
                tp.moveTo(tx, ty - 22); tp.lineTo(tx + 14, ty); tp.lineTo(tx - 14, ty); tp.close();
                c.drawPath(tp, tri);
                break;
            }
            default:
        }
        c.restore();
        // date stamp on the photo itself, like a 90s compact
        if (m.stamp && (l == Layout.RETRO || l == Layout.FILM) && !m.date.isEmpty()) {
            Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
            s.setTypeface(MONO); s.setTextSize(Math.max(18, b.width() * 0.045f)); s.setColor(0xFFFF8A1E);
            s.setShadowLayer(6, 0, 0, 0xCCFF5A00);
            String d = "'" + (m.date.length() >= 10 ? m.date.substring(2).replace('.', ' ') : m.date);
            c.drawText(d, b.right - s.measureText(d) - 18, b.bottom - 18, s);
        }
    }

    private static String trim(String s, Paint p, float max) {
        if (s == null) return "";
        if (p.measureText(s) <= max) return s;
        while (s.length() > 1 && p.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    /** Card (portrait, 640×1024) ready for the printer. ox/oy pan the crop (−1..1), zoom ≥ 1. */
    static Bitmap compose(Bitmap src, Layout l, boolean dyeSub, Meta m, float ox, float oy, float zoom, boolean sharpen) {
        boolean land = src.getWidth() > src.getHeight();
        int cw = land ? CARD_H : CARD_W, ch = land ? CARD_W : CARD_H;
        Bitmap card = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(card);
        c.drawColor(0xFFFFFFFF);
        RectF box = box(l, cw, ch);
        Rect crop = crop(src.getWidth(), src.getHeight(), box.width() / box.height(), ox, oy, zoom);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (dyeSub) p.setColorFilter(dyeSubFilter());
        Bitmap s = Bitmap.createBitmap(src, crop.left, crop.top, crop.width(), crop.height());
        while (s.getWidth() > box.width() * 2.2f) s = Bitmap.createScaledBitmap(s, s.getWidth() / 2, s.getHeight() / 2, true);
        c.drawBitmap(s, null, box, p);
        if (sharpen && dyeSub) sharpen(card, box);
        drawFrame(c, l, cw, ch, m);
        if (!land) return card;
        Matrix r = new Matrix(); r.postRotate(90);
        return Bitmap.createBitmap(card, 0, 0, cw, ch, r, true);
    }

    /** Centre crop with pan/zoom, in source pixels. */
    static Rect crop(int w, int h, float aspect, float ox, float oy, float zoom) {
        float sa = w / (float) h;
        float cw, ch;
        if (sa > aspect) { ch = h; cw = h * aspect; } else { cw = w; ch = w / aspect; }
        zoom = Math.max(1f, zoom);
        cw /= zoom; ch /= zoom;
        float x = (w - cw) / 2f * (1 + Math.max(-1, Math.min(1, ox)));
        float y = (h - ch) / 2f * (1 + Math.max(-1, Math.min(1, oy)));
        int l = Math.max(0, Math.round(x)), t = Math.max(0, Math.round(y));
        return new Rect(l, t, Math.min(w, l + Math.max(1, Math.round(cw))), Math.min(h, t + Math.max(1, Math.round(ch))));
    }

    /** Dye-sub compensation: +8% saturation, slight midtone lift, warm-cast cancel. */
    static ColorMatrixColorFilter dyeSubFilter() {
        ColorMatrix cm = new ColorMatrix(); cm.setSaturation(1.08f);
        cm.postConcat(new ColorMatrix(new float[]{
            1.04f, 0, 0, 0, 6,
            0, 1.04f, 0, 0, 6,
            0, 0, 1.06f, 0, 6,
            0, 0, 0, 1, 0}));
        return new ColorMatrixColorFilter(cm);
    }

    /** Mild 3×3 unsharp on the printed area (dye-sub softens edges). */
    private static void sharpen(Bitmap b, RectF box) {
        int x0 = (int) box.left, y0 = (int) box.top, w = (int) box.width(), h = (int) box.height();
        int[] px = new int[w * h], o = new int[w * h];
        b.getPixels(px, 0, w, x0, y0, w, h);
        final float k = 0.35f;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x;
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) { o[i] = px[i]; continue; }
            int c = px[i], n = px[i - w], s = px[i + w], e = px[i + 1], wv = px[i - 1];
            o[i] = 0xFF000000 | (ch(c, 16, n, s, e, wv, k) << 16) | (ch(c, 8, n, s, e, wv, k) << 8) | ch(c, 0, n, s, e, wv, k);
        }
        b.setPixels(o, 0, w, x0, y0, w, h);
    }

    private static int ch(int c, int sh, int n, int s, int e, int w, float k) {
        int v = (c >> sh) & 255;
        float avg = (((n >> sh) & 255) + ((s >> sh) & 255) + ((e >> sh) & 255) + ((w >> sh) & 255)) / 4f;
        return Math.max(0, Math.min(255, Math.round(v + k * (v - avg))));
    }
}
