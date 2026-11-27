/*
 * Splash: Matrix rain + glitching AMPY▲LINK logo that "decodes" in, AmpyRaw style. Tap to skip.
 * GPL v3.
 */
package com.ampylink.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import java.util.Random;

public final class SplashActivity extends Activity {
    private static final long DURATION = 2200;
    private boolean done;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!isTaskRoot()) { go(); return; } // returning to a running app: no splash
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(new SplashView(this));
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) { c.hide(WindowInsets.Type.systemBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        getWindow().getDecorView().postDelayed(this::go, DURATION);
    }

    private void go() {
        if (done) return;
        done = true;
        startActivity(new Intent(this, DeviceHubActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private final class SplashView extends View {
        private final long t0 = System.currentTimeMillis();
        private final Fx.Rain rain;
        private final Paint logo = new Paint(Paint.ANTI_ALIAS_FLAG), sub = new Paint(Paint.ANTI_ALIAS_FLAG),
            tri = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), dim = new Paint();
        private final Random r = new Random(7);

        SplashView(Context c) {
            super(c);
            rain = new Fx.Rain(Cyber.dp(c, 14));
            logo.setTypeface(Cyber.MONO); logo.setTextSize(Cyber.dp(c, 40)); logo.setLetterSpacing(0.12f); logo.setColor(Cyber.CYAN);
            logo.setTextAlign(Paint.Align.CENTER);
            logo.setShadowLayer(Cyber.dp(c, 14), 0, 0, 0xCC00F0FF);
            sub.setTypeface(Cyber.MONO); sub.setTextSize(Cyber.dp(c, 10)); sub.setLetterSpacing(0.2f); sub.setColor(Cyber.DIM);
            sub.setTextAlign(Paint.Align.CENTER);
            tri.setStyle(Paint.Style.STROKE); tri.setStrokeWidth(Cyber.dp(c, 3)); tri.setColor(Cyber.CYAN);
            tri.setShadowLayer(Cyber.dp(c, 10), 0, 0, Cyber.CYAN);
            line.setStrokeWidth(Cyber.dp(c, 2));
            dim.setColor(0xCC07070C);
            setLayerType(LAYER_TYPE_SOFTWARE, null); // shadow-layer glow on text
        }

        @Override public boolean onTouchEvent(MotionEvent e) { if (e.getAction() == MotionEvent.ACTION_UP) go(); return true; }

        @Override protected void onDraw(Canvas c) {
            float t = (System.currentTimeMillis() - t0) / (float) DURATION;
            int w = getWidth(), h = getHeight();
            c.drawColor(Cyber.BG);
            rain.draw(c, w, h, Math.max(0.15f, 1f - t * 0.9f));
            float cx = w / 2f, cy = h / 2f;
            // dark plate behind the logo
            c.drawRect(0, cy - Cyber.dp(getContext(), 110), w, cy + Cyber.dp(getContext(), 80), dim);
            // aperture triangle draws itself
            float s = Cyber.dp(getContext(), 46), k = Math.min(1f, t * 2.2f);
            Path p = new Path();
            float ty = cy - Cyber.dp(getContext(), 60);
            p.moveTo(cx, ty - s * 0.6f); p.lineTo(cx + s * 0.55f, ty + s * 0.45f); p.lineTo(cx - s * 0.55f, ty + s * 0.45f); p.close();
            android.graphics.PathMeasure pm = new android.graphics.PathMeasure(p, true);
            Path seg = new Path();
            pm.getSegment(0, pm.getLength() * k, seg, true);
            c.drawPath(seg, tri);
            // logo: Matrix decode + glitch decaying
            float dec = Math.min(1f, Math.max(0f, (t - 0.12f) / 0.55f));
            float glitch = t < 0.85f ? (1f - dec) * 0.9f + (r.nextFloat() < 0.12f ? 0.6f : 0f) : (r.nextFloat() < 0.03f ? 0.5f : 0f);
            Fx.glitchText(c, Fx.decode("AMPY▲LINK", dec, r), cx, cy + Cyber.dp(getContext(), 14), logo, glitch, r);
            // neon rule + subtitle
            line.setShader(new LinearGradient(cx - w * 0.3f, 0, cx + w * 0.3f, 0, Cyber.CYAN, Cyber.MAGENTA, Shader.TileMode.CLAMP));
            float lw = w * 0.3f * Math.min(1f, Math.max(0f, (t - 0.35f) * 3f));
            c.drawLine(cx - lw, cy + Cyber.dp(getContext(), 30), cx + lw, cy + Cyber.dp(getContext(), 30), line);
            float subT = Math.min(1f, Math.max(0f, (t - 0.45f) / 0.4f));
            sub.setAlpha((int) (255 * subT));
            c.drawText(Fx.decode("CANON EOS · KODAK · AMPYORB LAN", subT, r), cx, cy + Cyber.dp(getContext(), 52), sub);
            sub.setColor(Cyber.MAGENTA); sub.setAlpha((int) (200 * subT));
            c.drawText("POWERED BY AMPY▲RAW GPU ENGINE", cx, cy + Cyber.dp(getContext(), 70), sub);
            sub.setColor(Cyber.DIM);
            if (!done) postInvalidateOnAnimation();
        }
    }
}
