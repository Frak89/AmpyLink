/*
 * AmpyLink — extra cyberpunk widgets on top of AmpyRaw's Cyber palette. GPL v3.
 */
package com.ampylink.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class Neon {
    private Neon() {}

    /** Small dim caption without glow. */
    static TextView caption(Context c, String s, float sp) {
        TextView t = Cyber.label(c, s, sp, Cyber.DIM);
        t.setShadowLayer(0, 0, 0, 0);
        t.setLineSpacing(0, 1.25f);
        return t;
    }

    /** Toggle chip: selected = filled + bright, otherwise dim outline. */
    static TextView chip(Context c, String s, int color) {
        TextView t = Cyber.label(c, s, 10, color);
        t.setGravity(Gravity.CENTER);
        int p = Cyber.dp(c, 8);
        t.setPadding(p + 2, p - 1, p + 2, p - 1);
        t.setClickable(true);
        styleChip(t, false, color);
        return t;
    }

    static void styleChip(TextView t, boolean sel, int color) {
        Context c = t.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setColor(sel ? Cyber.alpha(color, 0x33) : 0x10000000);
        g.setStroke(Cyber.dp(c, 1), sel ? color : 0x4452606E);
        float r = Cyber.dp(c, 6);
        g.setCornerRadii(new float[]{r, r, 0, 0, r, r, 0, 0});
        t.setBackground(g);
        t.setTextColor(sel ? color : Cyber.TEXT);
    }

    static LinearLayout.LayoutParams wrap(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = leftMargin;
        return lp;
    }

    static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        return v;
    }

    /** Thin neon rule with a gradient. */
    static View rule(Context c) {
        View v = new View(c) {
            final Paint p = new Paint();
            @Override protected void onDraw(Canvas cv) {
                p.setShader(new LinearGradient(0, 0, getWidth(), 0, Cyber.CYAN, 0x00FF2BD6, Shader.TileMode.CLAMP));
                cv.drawRect(0, 0, getWidth(), getHeight(), p);
            }
        };
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, Cyber.dp(c, 1))));
        return v;
    }

    /** Segmented neon progress bar, value 0..1 (negative = indeterminate sweep). */
    static final class Bar extends View {
        private float value = 0f;
        private final Paint seg = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long t0 = System.currentTimeMillis();

        Bar(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            off.setColor(0x2252606E);
        }

        void set(float v) { value = v; invalidate(); }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Cyber.dp(getContext(), 10)); }

        @Override protected void onDraw(Canvas c) {
            int n = 32;
            float gap = Cyber.dp(getContext(), 2), w = (getWidth() - gap * (n - 1)) / n, h = getHeight();
            int lit = value < 0 ? -1 : Math.round(value * n);
            int sweep = (int) (((System.currentTimeMillis() - t0) / 45) % (n + 6));
            for (int i = 0; i < n; i++) {
                float x = i * (w + gap);
                boolean on = lit >= 0 ? i < lit : (i > sweep - 6 && i <= sweep);
                if (on) {
                    int col = blend(Cyber.CYAN, Cyber.MAGENTA, i / (float) (n - 1));
                    seg.setColor(col);
                    seg.setShadowLayer(Cyber.dp(getContext(), 4), 0, 0, col);
                    c.drawRect(x, 0, x + w, h, seg);
                } else c.drawRect(x, 0, x + w, h, off);
            }
            if (value < 0) postInvalidateDelayed(40);
        }
    }

    static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    /** A panel-styled dialog; returns {dialog, contentLayout}. */
    static Object[] dialog(Activity a, String title, boolean scroll) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Cyber.panelBg(a));
        int pad = Cyber.dp(a, 16);
        root.setPadding(pad, pad, pad, pad);
        if (title != null) {
            root.addView(Cyber.label(a, title, 13, Cyber.CYAN));
            View r = rule(a);
            ((LinearLayout.LayoutParams) r.getLayoutParams()).topMargin = pad / 2;
            ((LinearLayout.LayoutParams) r.getLayoutParams()).bottomMargin = pad / 2;
            root.addView(r);
        }
        if (scroll) {
            ScrollView sv = new ScrollView(a);
            sv.addView(root);
            d.setContentView(sv);
        } else d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            d.getWindow().setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels - pad * 2, Cyber.dp(a, 440)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return new Object[]{d, root};
    }
}
