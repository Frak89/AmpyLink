/*
 * Minimal EXIF reader for camera files fetched over Wi-Fi: JPEG (APP1), Canon CR3 (CMT1/CMT2
 * boxes inside the ISO-BMFF header) and TIFF-based raws (CR2/DNG). Works on the first bytes of
 * the file (GetPartialObject), so the gallery can show shooting data without a full download.
 * Also extracts the embedded preview JPEG (CR3 PRVW, or the largest JPEG found). GPL v3.
 */
package com.ampylink.app;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Exif {
    private Exif() {}

    static final class Info {
        String make = "", model = "", lens = "", date = "", serial = "";
        String exposure = "", fnumber = "", iso = "", focal = "", bias = "", program = "", metering = "", wb = "", flash = "";
        int width, height, orientation = 1;
        double shutterSec, aperture, focalMm; int isoValue;

        boolean isEmpty() { return model.isEmpty() && exposure.isEmpty() && date.isEmpty(); }

        String camera() {
            String m = model.trim();
            if (m.isEmpty()) return make.trim();
            if (!make.isEmpty() && !m.toUpperCase(Locale.US).startsWith(make.trim().split(" ")[0].toUpperCase(Locale.US))) m = make.trim() + " " + m;
            return m;
        }

        /** "1/250 · f/2.8 · ISO 400 · 50mm" */
        String exposureLine() {
            StringBuilder b = new StringBuilder();
            for (String s : new String[]{exposure, fnumber, iso.isEmpty() ? "" : "ISO " + iso, focal}) {
                if (s.isEmpty()) continue;
                if (b.length() > 0) b.append(" · ");
                b.append(s);
            }
            return b.toString();
        }

        /** "2026.10.29" from "2026:10:29 14:02:11" */
        String dateShort() { return date.length() >= 10 ? date.substring(0, 10).replace(':', '.') : date; }
        String timeShort() { return date.length() >= 16 ? date.substring(11, 16) : ""; }

        List<String[]> rows() {
            List<String[]> r = new ArrayList<>();
            add(r, "FOTOCAMERA", camera());
            add(r, "OBIETTIVO", lens);
            add(r, "DATA", date.length() >= 19 ? date.substring(0, 10).replace(':', '.') + "  " + date.substring(11, 19) : date);
            add(r, "TEMPO", exposure);
            add(r, "DIAFRAMMA", fnumber);
            add(r, "ISO", iso);
            add(r, "FOCALE", focal);
            add(r, "COMP. EV", bias);
            add(r, "MODALITÀ", program);
            add(r, "ESPOSIMETRO", metering);
            add(r, "BIL. BIANCO", wb);
            add(r, "FLASH", flash);
            if (width > 0 && height > 0) add(r, "PIXEL", width + "×" + height + String.format(Locale.US, "  %.1f MP", width * (double) height / 1e6));
            add(r, "SERIALE", serial);
            return r;
        }

        private static void add(List<String[]> r, String k, String v) { if (v != null && !v.trim().isEmpty()) r.add(new String[]{k, v.trim()}); }
    }

    // ------------------------------------------------------------------ entry points

    static Info parse(byte[] d) {
        Info info = new Info();
        if (d == null || d.length < 16) return info;
        try {
            if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) parseJpeg(d, info);
            else if (isCr3(d)) parseCr3(d, info);
            else if (isTiffAt(d, 0)) new Tiff(d, 0, d.length).read(info, true);
        } catch (Exception ignored) {}
        return info;
    }

    static boolean isCr3(byte[] d) {
        return d.length > 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p' && d[8] == 'c' && d[9] == 'r' && d[10] == 'x';
    }

    private static boolean isTiffAt(byte[] d, int o) {
        if (o + 8 > d.length) return false;
        return (d[o] == 'I' && d[o + 1] == 'I' && d[o + 2] == 42 && d[o + 3] == 0) || (d[o] == 'M' && d[o + 1] == 'M' && d[o + 2] == 0 && d[o + 3] == 42);
    }

    private static void parseJpeg(byte[] d, Info info) {
        int o = 2;
        while (o + 4 < d.length) {
            if ((d[o] & 0xFF) != 0xFF) { o++; continue; }
            int m = d[o + 1] & 0xFF;
            if (m == 0xD8 || (m >= 0xD0 && m <= 0xD7) || m == 0x01) { o += 2; continue; }
            if (m == 0xDA || m == 0xD9) break;
            int len = ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
            if (m == 0xE1 && o + 10 < d.length && d[o + 4] == 'E' && d[o + 5] == 'x' && d[o + 6] == 'i' && d[o + 7] == 'f') {
                int t = o + 10;
                new Tiff(d, t, Math.min(d.length, o + 2 + len)).read(info, true);
            }
            if ((m == 0xC0 || m == 0xC2) && info.width == 0 && o + 9 < d.length) {
                info.height = ((d[o + 5] & 0xFF) << 8) | (d[o + 6] & 0xFF);
                info.width = ((d[o + 7] & 0xFF) << 8) | (d[o + 8] & 0xFF);
            }
            o += 2 + len;
        }
    }

    private static void parseCr3(byte[] d, Info info) {
        int c1 = find(d, "CMT1", 0, Math.min(d.length, 1 << 20));
        if (c1 >= 4) {
            int t = c1 + 4;
            if (isTiffAt(d, t)) new Tiff(d, t, Math.min(d.length, t + boxLen(d, c1 - 4))).read(info, true);
        }
        int c2 = find(d, "CMT2", 0, Math.min(d.length, 1 << 20));
        if (c2 >= 4) {
            int t = c2 + 4;
            if (isTiffAt(d, t)) new Tiff(d, t, Math.min(d.length, t + boxLen(d, c2 - 4))).read(info, false);
        }
    }

    private static int boxLen(byte[] d, int o) {
        int n = ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
        return n <= 8 ? d.length : n - 8;
    }

    static int find(byte[] d, String tag, int from, int to) {
        byte[] t = tag.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = Math.max(0, from); i + t.length <= to; i++) {
            for (int k = 0; k < t.length; k++) if (d[i + k] != t[k]) continue outer;
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ previews

    /** Best embedded JPEG: CR3 PRVW (≈1620×1080), otherwise the largest complete JPEG in the bytes. */
    static byte[] previewJpeg(byte[] d) {
        if (d == null) return null;
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            int end = jpegEnd(d, 0);
            if (end > 0) return java.util.Arrays.copyOfRange(d, 0, end);
        }
        if (isCr3(d)) {
            int p = find(d, "PRVW", 0, d.length);
            if (p > 0) {
                int soi = findSoi(d, p, Math.min(d.length, p + 256));
                if (soi > 0) { int end = jpegEnd(d, soi); if (end > soi) return java.util.Arrays.copyOfRange(d, soi, end); }
            }
        }
        int best = -1, bestEnd = -1;
        for (int i = 2; i + 3 < d.length; i++) {
            if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xD8 && (d[i + 2] & 0xFF) == 0xFF) {
                int end = jpegEnd(d, i);
                if (end > i && end - i > bestEnd - best) { best = i; bestEnd = end; }
                if (end > i) i = end - 1;
            }
        }
        return best >= 0 && bestEnd - best > 2048 ? java.util.Arrays.copyOfRange(d, best, bestEnd) : null;
    }

    private static int findSoi(byte[] d, int from, int to) {
        for (int i = from; i + 2 < to; i++) if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xD8 && (d[i + 2] & 0xFF) == 0xFF) return i;
        return -1;
    }

    /** End offset (exclusive) of the JPEG starting at soi, or -1 if truncated. */
    static int jpegEnd(byte[] d, int soi) {
        int o = soi + 2;
        while (o + 4 <= d.length) {
            if ((d[o] & 0xFF) != 0xFF) return -1;
            int m = d[o + 1] & 0xFF;
            if (m == 0xFF) { o++; continue; }
            if (m == 0xD9) return o + 2;
            if ((m >= 0xD0 && m <= 0xD7) || m == 0x01) { o += 2; continue; }
            int len = ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
            if (m == 0xDA) {
                o += 2 + len;
                // entropy-coded data: scan for a marker that is not a stuffed byte or RSTn
                while (o + 1 < d.length) {
                    if ((d[o] & 0xFF) == 0xFF) {
                        int n = d[o + 1] & 0xFF;
                        if (n == 0x00 || (n >= 0xD0 && n <= 0xD7) || n == 0xFF) { o += (n == 0xFF ? 1 : 2); continue; }
                        if (n == 0xD9) return o + 2;
                        break; // another segment (progressive JPEG) follows
                    }
                    o++;
                }
                continue;
            }
            o += 2 + len;
        }
        return -1;
    }

    // ------------------------------------------------------------------ TIFF / EXIF IFDs

    private static final class Tiff {
        final byte[] d; final int base, end; final boolean le;

        Tiff(byte[] d, int base, int end) { this.d = d; this.base = base; this.end = end; le = d[base] == 'I'; }

        int u16(int o) { if (o < 0 || o + 2 > end) return 0; return le ? (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) : ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF); }
        long u32(int o) {
            if (o < 0 || o + 4 > end) return 0;
            long v = le ? (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((long) (d[o + 3] & 0xFF) << 24)
                : ((long) (d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
            return v & 0xFFFFFFFFL;
        }

        void read(Info info, boolean ifd0) {
            int first = (int) u32(base + 4);
            readIfd(info, base + first, 0);
        }

        private void readIfd(Info info, int at, int depth) {
            if (depth > 3 || at < base || at + 2 > end) return;
            int n = u16(at);
            if (n <= 0 || n > 512) return;
            for (int i = 0; i < n; i++) {
                int e = at + 2 + i * 12;
                if (e + 12 > end) break;
                int tag = u16(e), type = u16(e + 2);
                long count = u32(e + 4);
                int size = typeSize(type) * (int) Math.min(count, 1 << 20);
                int val = size <= 4 ? e + 8 : base + (int) u32(e + 8);
                handle(info, tag, type, (int) count, val, depth);
            }
        }

        private static int typeSize(int t) {
            switch (t) { case 3: case 8: return 2; case 4: case 9: case 11: return 4; case 5: case 10: case 12: return 8; default: return 1; }
        }

        private String ascii(int o, int n) {
            if (o < 0 || o >= end) return "";
            int len = Math.min(n, end - o);
            int z = 0; while (z < len && d[o + z] != 0) z++;
            return new String(d, o, z, StandardCharsets.ISO_8859_1).trim();
        }

        private double rational(int o, boolean signed) {
            long a = u32(o), b = u32(o + 4);
            if (signed) { a = (int) a; b = (int) b; }
            return b == 0 ? 0 : a / (double) b;
        }

        private int num(int type, int o) { return type == 3 ? u16(o) : (int) u32(o); }

        private void handle(Info f, int tag, int type, int count, int v, int depth) {
            switch (tag) {
                case 0x010F: f.make = ascii(v, count); break;
                case 0x0110: f.model = ascii(v, count); break;
                case 0x0112: f.orientation = num(type, v); break;
                case 0x0132: if (f.date.isEmpty()) f.date = ascii(v, count); break;
                case 0x8769: readIfd(f, base + num(type, v), depth + 1); break; // Exif IFD
                case 0x829A: { double s = rational(v, false); f.shutterSec = s; f.exposure = shutter(s); break; }
                case 0x829D: { double a = rational(v, false); f.aperture = a; f.fnumber = a > 0 ? String.format(Locale.US, a < 10 ? "f/%.1f" : "f/%.0f", a) : ""; break; }
                case 0x8822: f.program = program(num(type, v)); break;
                case 0x8827: { int iso = num(type, v); if (iso > 0 && iso < 65535) { f.isoValue = iso; f.iso = String.valueOf(iso); } break; }
                case 0x8832: { int iso = num(type, v); if (iso > 0) { f.isoValue = iso; f.iso = String.valueOf(iso); } break; } // RecommendedExposureIndex (ISO > 65535)
                case 0x9003: f.date = ascii(v, count); break;
                case 0x9204: { double b = rational(v, true); f.bias = Math.abs(b) < 0.01 ? "±0 EV" : String.format(Locale.US, "%+.1f EV", b); break; }
                case 0x9207: f.metering = metering(num(type, v)); break;
                case 0x9209: { int fl = num(type, v); f.flash = (fl & 1) != 0 ? "SCATTATO" : "NO"; break; }
                case 0x920A: { double mm = rational(v, false); f.focalMm = mm; f.focal = mm > 0 ? String.format(Locale.US, "%.0fmm", mm) : ""; break; }
                case 0xA002: if (f.width == 0) f.width = num(type, v); break;
                case 0xA003: if (f.height == 0) f.height = num(type, v); break;
                case 0xA403: f.wb = num(type, v) == 0 ? "AUTO" : "MANUALE"; break;
                case 0xA431: f.serial = ascii(v, count); break;
                case 0xA434: f.lens = ascii(v, count); break;
                default:
            }
        }
    }

    static String shutter(double s) {
        if (s <= 0) return "";
        if (s >= 0.3) return (s == Math.floor(s) ? String.valueOf((int) s) : String.format(Locale.US, "%.1f", s)) + "\"";
        return "1/" + Math.round(1 / s);
    }

    private static String program(int p) {
        switch (p) {
            case 1: return "M"; case 2: return "P"; case 3: return "Av"; case 4: return "Tv";
            case 5: return "CREATIVO"; case 6: return "AZIONE"; case 7: return "RITRATTO"; case 8: return "PAESAGGIO";
            default: return p == 0 ? "" : "#" + p;
        }
    }

    private static String metering(int m) {
        switch (m) {
            case 1: return "MEDIA"; case 2: return "MEDIA PESATA AL CENTRO"; case 3: return "SPOT"; case 4: return "MULTI-SPOT";
            case 5: return "VALUTATIVA"; case 6: return "PARZIALE"; default: return "";
        }
    }
}
