package com.ampylink.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tone curves: master (RGB) + R, G, B. Monotone cubic (Fritsch–Carlson) like RawTherapee's "flexible" curves. */
final class Curves {
    static final int MASTER = 0, RED = 1, GREEN = 2, BLUE = 3;

    static final class Pt implements Comparable<Pt> {
        float x, y;
        Pt(float x, float y) { this.x = x; this.y = y; }
        @Override public int compareTo(Pt o) { return Float.compare(x, o.x); }
    }

    @SuppressWarnings("unchecked")
    final List<Pt>[] ch = new List[4];

    Curves() { for (int i = 0; i < 4; i++) { ch[i] = new ArrayList<>(); reset(i); } }

    void reset(int c) { ch[c].clear(); ch[c].add(new Pt(0, 0)); ch[c].add(new Pt(1, 1)); }
    void resetAll() { for (int i = 0; i < 4; i++) reset(i); }

    boolean isIdentity(int c) {
        for (Pt p : ch[c]) if (Math.abs(p.x - p.y) > 1e-3f) return false;
        return true;
    }

    boolean isIdentity() { for (int i = 0; i < 4; i++) if (!isIdentity(i)) return false; return true; }

    void sort(int c) { Collections.sort(ch[c]); }

    Curves copy() {
        Curves k = new Curves();
        for (int i = 0; i < 4; i++) { k.ch[i].clear(); for (Pt p : ch[i]) k.ch[i].add(new Pt(p.x, p.y)); }
        return k;
    }

    /** this = a + t*(b - identity) evaluated on the curve values (used for preset strength). */
    static Curves blend(Curves base, Curves look, float t) {
        Curves r = new Curves();
        for (int c = 0; c < 4; c++) {
            r.ch[c].clear();
            for (int i = 0; i <= 16; i++) {
                float x = i / 16f;
                float y = base.eval(c, x) + t * (look.eval(c, x) - x);
                r.ch[c].add(new Pt(x, Math.max(0, Math.min(1, y))));
            }
        }
        return r;
    }

    /** Monotone cubic Hermite interpolation. */
    float eval(int c, float x) {
        List<Pt> p = ch[c];
        int n = p.size();
        if (n == 0) return x;
        if (x <= p.get(0).x) return p.get(0).y;
        if (x >= p.get(n - 1).x) return p.get(n - 1).y;
        float[] xs = new float[n], ys = new float[n], m = new float[n], d = new float[n - 1];
        for (int i = 0; i < n; i++) { xs[i] = p.get(i).x; ys[i] = p.get(i).y; }
        for (int i = 0; i < n - 1; i++) d[i] = (ys[i + 1] - ys[i]) / Math.max(1e-6f, xs[i + 1] - xs[i]);
        m[0] = d[0]; m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) m[i] = (d[i - 1] * d[i] <= 0) ? 0 : (d[i - 1] + d[i]) / 2;
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0) { m[i] = 0; m[i + 1] = 0; continue; }
            float a = m[i] / d[i], b = m[i + 1] / d[i], s = a * a + b * b;
            if (s > 9) { float t = 3f / (float) Math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]; }
        }
        int k = 0;
        while (k < n - 2 && x > xs[k + 1]) k++;
        float h = xs[k + 1] - xs[k], t = (x - xs[k]) / h, t2 = t * t, t3 = t2 * t;
        float y = (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * m[k] + (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h * m[k + 1];
        return Math.max(0, Math.min(1, y));
    }

    /** 256x1 RGBA8 LUT: r,g,b = per-channel, a = master. */
    byte[] lut() {
        byte[] b = new byte[256 * 4];
        int[] map = {RED, GREEN, BLUE, MASTER};
        for (int c = 0; c < 4; c++) {
            for (int i = 0; i < 256; i++) b[i * 4 + c] = (byte) Math.round(eval(map[c], i / 255f) * 255f);
        }
        return b;
    }

    static Curves of(float[] master, float[] r, float[] g, float[] bl) {
        Curves k = new Curves();
        float[][] src = {master, r, g, bl};
        for (int c = 0; c < 4; c++) if (src[c] != null) {
            k.ch[c].clear();
            for (int i = 0; i + 1 < src[c].length; i += 2) k.ch[c].add(new Pt(src[c][i], src[c][i + 1]));
            k.sort(c);
        }
        return k;
    }
}
