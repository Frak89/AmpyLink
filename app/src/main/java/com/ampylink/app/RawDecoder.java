/*
 * AmpyRaw decoder. Uncompressed Bayer DNG (Pixel 7 Pro / Camera2 DngCreator) is parsed here and
 * developed on the GPU. JPEG/HEIC/PNG/compressed DNG go through Android's ImageDecoder.
 * Proprietary raws (Canon CR2/CR3, NEF, ARW...) fall back to their full-size embedded JPEG.
 * GPL v3.
 */
package com.ampylink.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

final class RawDecoder {

    /** Decoded image, either CFA raw (cfa != null) or an 8-bit bitmap. */
    static final class ImageData {
        String name = "";
        String source = "";         // human readable pipeline label
        // raw
        short[] cfa; int rawW, rawH; int[] cfaPattern = {0, 1, 1, 2};
        float[] black = {0, 0, 0, 0}; float white = 65535f;
        float[] wb = {1, 1, 1}; float[] cam2rgb = ColorMath.IDENTITY;
        int orientation = 1; boolean half;
        // bitmap
        Bitmap bitmap;

        int outW() { int w = half ? rawW / 2 : rawW, h = half ? rawH / 2 : rawH; return (orientation == 6 || orientation == 8) ? h : w; }
        int outH() { int w = half ? rawW / 2 : rawW, h = half ? rawH / 2 : rawH; return (orientation == 6 || orientation == 8) ? w : h; }
        int width() { return bitmap != null ? bitmap.getWidth() : outW(); }
        int height() { return bitmap != null ? bitmap.getHeight() : outH(); }
        boolean isRaw() { return cfa != null; }
    }

    static ImageData decode(ContentResolver cr, Uri uri, String name, int maxTex) throws IOException {
        byte[] bytes;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open " + uri);
            bytes = readAll(in);
        }
        ImageData d = null;
        String cr3Error = null;
        if (Cr3Decoder.isCr3(bytes)) {
            // Canon CR3: real CRX raw decode (port of RawTherapee's canon_cr3_decoder)
            try { d = Cr3Decoder.decode(bytes, maxTex); }
            catch (Throwable t) { d = null; cr3Error = t.getMessage(); }
        }
        if (d == null && isTiff(bytes)) {
            try { d = parseDng(bytes, maxTex); } catch (Throwable t) { d = null; }
        }
        if (d == null) {
            try {
                d = new ImageData();
                d.bitmap = decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxTex);
                d.source = isTiff(bytes) ? "DNG · SYSTEM DECODER" : "BITMAP · sRGB";
            } catch (Throwable t) {
                d = embeddedJpeg(bytes, maxTex);
                if (d == null) throw new IOException("Unsupported file format");
                d.source = cr3Error != null ? "CR3 · EMBEDDED JPEG (" + cr3Error + ")" : "RAW · EMBEDDED JPEG";
            }
        }
        d.name = name;
        return d;
    }

    static Bitmap decodeBitmap(ImageDecoder.Source src, final int maxTex) throws IOException {
        return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            int m = Math.max(w, h);
            if (m > maxTex) {
                float k = maxTex / (float) m;
                decoder.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
            }
        });
    }

    /** CR2/CR3/NEF/ARW etc: find the largest embedded JPEG (CR3 track 1 is full size). */
    static ImageData embeddedJpeg(byte[] b, int maxTex) {
        int bestOff = -1; long bestPx = 0;
        BitmapFactory.Options o = new BitmapFactory.Options();
        for (int i = 0; i < b.length - 3; i++) {
            if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0xD8 && (b[i + 2] & 0xFF) == 0xFF) {
                o.inJustDecodeBounds = true; o.outWidth = 0;
                BitmapFactory.decodeByteArray(b, i, b.length - i, o);
                long px = (long) o.outWidth * o.outHeight;
                if (px > bestPx) { bestPx = px; bestOff = i; }
            }
        }
        if (bestOff < 0) return null;
        try {
            Bitmap bm = decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(b, bestOff, b.length - bestOff).slice()), maxTex);
            ImageData d = new ImageData();
            d.bitmap = bm;
            return d;
        } catch (Throwable t) { return null; }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 22);
        byte[] buf = new byte[1 << 16]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    static boolean isTiff(byte[] b) {
        return b.length > 8 && ((b[0] == 'I' && b[1] == 'I' && b[2] == 42 && b[3] == 0) || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && b[3] == 42));
    }

    // ---------------------------------------------------------------- TIFF / DNG

    private static final class Entry { int type; long count; int valOff; }

    private static final class Tiff {
        final ByteBuffer bb;
        Tiff(byte[] b) {
            bb = ByteBuffer.wrap(b);
            bb.order(b[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        }
        Map<Integer, Entry> ifd(int off) {
            Map<Integer, Entry> m = new HashMap<>();
            int n = bb.getShort(off) & 0xFFFF;
            for (int i = 0; i < n; i++) {
                int p = off + 2 + i * 12;
                Entry e = new Entry();
                int tag = bb.getShort(p) & 0xFFFF;
                e.type = bb.getShort(p + 2) & 0xFFFF;
                e.count = bb.getInt(p + 4) & 0xFFFFFFFFL;
                long size = e.count * typeSize(e.type);
                e.valOff = size <= 4 ? p + 8 : bb.getInt(p + 8);
                m.put(tag, e);
            }
            return m;
        }
        static int typeSize(int t) {
            switch (t) { case 3: case 8: return 2; case 4: case 9: case 11: return 4; case 5: case 10: case 12: return 8; default: return 1; }
        }
        double num(Entry e, int i) {
            int o = e.valOff + i * typeSize(e.type);
            switch (e.type) {
                case 1: case 7: return bb.get(o) & 0xFF;
                case 6: return bb.get(o);
                case 3: return bb.getShort(o) & 0xFFFF;
                case 8: return bb.getShort(o);
                case 4: return bb.getInt(o) & 0xFFFFFFFFL;
                case 9: return bb.getInt(o);
                case 5: { long a = bb.getInt(o) & 0xFFFFFFFFL, b = bb.getInt(o + 4) & 0xFFFFFFFFL; return b == 0 ? 0 : a / (double) b; }
                case 10: { int a = bb.getInt(o), b = bb.getInt(o + 4); return b == 0 ? 0 : a / (double) b; }
                case 11: return bb.getFloat(o);
                case 12: return bb.getDouble(o);
            }
            return 0;
        }
    }

    static ImageData parseDng(byte[] b, int maxTex) {
        Tiff t = new Tiff(b);
        int ifd0Off = t.bb.getInt(4);
        Map<Integer, Entry> ifd0 = t.ifd(ifd0Off);
        if (!ifd0.containsKey(50706)) return null; // not a DNG (DNGVersion missing)

        Map<Integer, Entry> raw = null;
        if (isCfaRaw(t, ifd0)) raw = ifd0;
        Entry sub = ifd0.get(330);
        if (raw == null && sub != null) {
            for (int i = 0; i < sub.count && raw == null; i++) {
                Map<Integer, Entry> s = t.ifd((int) t.num(sub, i));
                if (isCfaRaw(t, s)) raw = s;
            }
        }
        if (raw == null) return null;
        int comp = (int) val(t, raw, 259, 1);
        int bps = (int) val(t, raw, 258, 16);
        if (comp != 1 || (bps != 16 && bps != 8)) return null; // lossless-JPEG DNG -> system decoder

        ImageData d = new ImageData();
        d.rawW = (int) val(t, raw, 256, 0);
        d.rawH = (int) val(t, raw, 257, 0);
        d.cfa = new short[d.rawW * d.rawH];
        int bpp = bps / 8;

        if (raw.containsKey(324)) { // tiles
            int tw = (int) val(t, raw, 322, 0), th = (int) val(t, raw, 323, 0);
            Entry offs = raw.get(324);
            int across = (d.rawW + tw - 1) / tw;
            for (int i = 0; i < offs.count; i++) {
                int o = (int) t.num(offs, i);
                int tx = (i % across) * tw, ty = (i / across) * th;
                for (int y = 0; y < th && ty + y < d.rawH; y++)
                    for (int x = 0; x < tw && tx + x < d.rawW; x++) {
                        int p = o + (y * tw + x) * bpp;
                        d.cfa[(ty + y) * d.rawW + tx + x] = (short) (bpp == 2 ? t.bb.getShort(p) : (t.bb.get(p) & 0xFF));
                    }
            }
        } else { // strips
            Entry offs = raw.get(273);
            int rps = (int) val(t, raw, 278, d.rawH);
            int idx = 0;
            for (int s = 0; s < offs.count; s++) {
                int o = (int) t.num(offs, s);
                int rows = Math.min(rps, d.rawH - s * rps);
                int n = rows * d.rawW;
                if (bpp == 2) {
                    ByteBuffer v = t.bb.duplicate().order(t.bb.order());
                    v.position(o);
                    v.asShortBuffer().get(d.cfa, idx, n);
                } else for (int i = 0; i < n; i++) d.cfa[idx + i] = (short) (t.bb.get(o + i) & 0xFF);
                idx += n;
            }
        }

        // Linearization table
        Entry lin = raw.get(50712);
        if (lin != null) {
            int n = (int) lin.count; int[] lut = new int[n];
            for (int i = 0; i < n; i++) lut[i] = (int) t.num(lin, i);
            for (int i = 0; i < d.cfa.length; i++) d.cfa[i] = (short) lut[Math.min(d.cfa[i] & 0xFFFF, n - 1)];
        }

        // CFA pattern (2x2)
        Entry pat = raw.get(33422);
        if (pat != null && pat.count >= 4) for (int i = 0; i < 4; i++) d.cfaPattern[i] = (int) t.num(pat, i);

        // black levels
        Entry bl = raw.get(50714);
        if (bl != null) {
            Entry rep = raw.get(50713);
            int rx = rep != null ? (int) t.num(rep, 1) : 1, ry = rep != null ? (int) t.num(rep, 0) : 1;
            for (int i = 0; i < 4; i++) {
                int x = i & 1, y = i >> 1;
                int k = ((y % ry) * rx + (x % rx));
                d.black[i] = (float) t.num(bl, Math.min(k, (int) bl.count - 1));
            }
        }
        d.white = (float) val(t, raw, 50717, (1 << bps) - 1);

        // colour: prefer the D65 calibration matrix
        Map<Integer, Entry> col = ifd0;
        Entry cm1 = col.get(50721), cm2 = col.get(50722);
        int il1 = (int) val(t, col, 50778, 0), il2 = (int) val(t, col, 50779, 0);
        Entry cm = (cm2 != null && il2 == 21) ? cm2 : (cm1 != null && il1 == 21) ? cm1 : (cm2 != null ? cm2 : cm1);
        if (cm != null && cm.count >= 9) {
            double[] m = new double[9];
            for (int i = 0; i < 9; i++) m[i] = t.num(cm, i);
            d.cam2rgb = ColorMath.camToSrgb(m);
        }
        Entry asn = col.get(50728);
        if (asn != null && asn.count >= 3) {
            float g = (float) (1.0 / t.num(asn, 1));
            for (int i = 0; i < 3; i++) d.wb[i] = (float) (1.0 / t.num(asn, i)) / g;
        }
        d.orientation = (int) val(t, ifd0, 274, 1);
        if (d.orientation != 3 && d.orientation != 6 && d.orientation != 8) d.orientation = 1;

        // 50MP Pixel 7 Pro raws: develop at half size with superpixel demosaic to keep GPU memory sane
        d.half = (long) d.rawW * d.rawH > 26_000_000L || Math.max(d.rawW, d.rawH) > maxTex;
        d.source = d.half ? "DNG RAW · GPU SUPERPIXEL" : "DNG RAW · GPU DEMOSAIC";
        return d;
    }

    private static boolean isCfaRaw(Tiff t, Map<Integer, Entry> ifd) {
        return val(t, ifd, 254, 0) == 0 && val(t, ifd, 262, 0) == 32803;
    }

    private static double val(Tiff t, Map<Integer, Entry> ifd, int tag, double def) {
        Entry e = ifd.get(tag);
        return e == null ? def : t.num(e, 0);
    }
}
