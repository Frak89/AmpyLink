/*
 * Live camera link indicator for the top bar: three neon LEDs BT · WI-FI · PTP (off / linking
 * blink / on glow / error) plus camera name and time since the last answer. GPL v3.
 */
package com.ampylink.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

final class LinkStatusView extends View {
    static final int OFF = 0, LINKING = 1, ON = 2, ERROR = 3;

    interface Source { int bt(); int wifi(); int ptp(); String label(); }

    private final Source src;
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), lbl = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    LinkStatusView(Context c, Source src) {
        super(c);
        this.src = src;
        dp = c.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        txt.setTypeface(Cyber.MONO); txt.setTextSize(8 * dp); txt.setLetterSpacing(0.1f);
        lbl.setTypeface(Cyber.MONO); lbl.setTextSize(8 * dp); lbl.setColor(Cyber.TEXT); lbl.setLetterSpacing(0.06f);
    }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), (int) (30 * dp)); }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); tick.run(); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(tick); }

    private final Runnable tick = new Runnable() { @Override public void run() { invalidate(); postDelayed(this, 250); } };

    @Override protected void onDraw(Canvas c) {
        String[] names = {"BT", "WI-FI", "PTP"};
        int[] st = {src.bt(), src.wifi(), src.ptp()};
        boolean blink = (System.currentTimeMillis() / 350) % 2 == 0;
        float x = 8 * dp, top = 2 * dp, h = 13 * dp;
        for (int i = 0; i < 3; i++) {
            float w = txt.measureText(names[i]) + 18 * dp;
            int col = st[i] == ON ? Cyber.CYAN : st[i] == LINKING ? Cyber.YELLOW : st[i] == ERROR ? Cyber.MAGENTA : Cyber.DIM;
            boolean lit = st[i] == ON || st[i] == ERROR || (st[i] == LINKING && blink);
            RectF r = new RectF(x, top, x + w, top + h);
            pill.setStyle(Paint.Style.FILL);
            pill.setColor(lit ? Cyber.alpha(col, 0x33) : 0x1452606E);
            pill.setShadowLayer(0, 0, 0, 0);
            c.drawRoundRect(r, h / 2, h / 2, pill);
            pill.setStyle(Paint.Style.STROKE); pill.setStrokeWidth(dp); pill.setColor(lit ? col : 0x5552606E);
            c.drawRoundRect(r, h / 2, h / 2, pill);
            // LED
            pill.setStyle(Paint.Style.FILL);
            pill.setColor(lit ? col : 0x5552606E);
            if (lit) pill.setShadowLayer(5 * dp, 0, 0, col);
            c.drawCircle(x + 7 * dp, top + h / 2, 2.6f * dp, pill);
            pill.setShadowLayer(0, 0, 0, 0);
            txt.setColor(lit ? col : Cyber.DIM);
            c.drawText(names[i], x + 12 * dp, top + h / 2 + 3 * dp, txt);
            x += w + 5 * dp;
        }
        String l = src.label();
        if (l != null) {
            while (l.length() > 3 && lbl.measureText(l) > getWidth() - 8 * dp) l = l.substring(0, l.length() - 2);
            c.drawText(l, 8 * dp, top + h + 11 * dp, lbl);
        }
    }
}
