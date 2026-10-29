/*
 * Single-picture view: zoomable preview, live histogram, EXIF shooting data, swipe between shots,
 * and the four actions: print on the Mini 2 Retro, edit in-app (with print frame), open in an
 * installed RAW editor, save to the phone. GPL v3.
 */
package com.ampylink.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ViewerActivity extends Activity {
    static List<Session.Shot> list = new ArrayList<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int index;
    private Session.Shot shot;
    private ZoomView image;
    private TextView title, sub, counter, status, useRaw, useJpg;
    private LinearLayout exifBox;
    private UiViews.HistogramView hist;
    private Neon.Bar bar;
    private Bitmap previewBm;
    private boolean preferRaw = true;
    private int generation;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        index = getIntent().getIntExtra("index", 0);
        if (list.isEmpty()) { finish(); return; }
        index = Math.max(0, Math.min(list.size() - 1, index));
        build();
        show(index);
    }

    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); build(); show(index); }

    private void build() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int pad = Cyber.dp(this, 10);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(Cyber.BG);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(Cyber.panelBg(this));
        top.setPadding(pad, pad, pad, pad);
        TextView back = Cyber.button(this, "◀", Cyber.CYAN);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setPadding(pad, 0, 0, 0);
        title = Cyber.label(this, "", 12, Cyber.TEXT);
        title.setSingleLine();
        sub = Neon.caption(this, "", 8);
        sub.setSingleLine();
        tcol.addView(title); tcol.addView(sub);
        top.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1));
        counter = Cyber.label(this, "", 10, Cyber.YELLOW);
        top.addView(counter);
        left.addView(top);

        FrameLayout imgBox = new FrameLayout(this);
        image = new ZoomView(this);
        image.onSwipe = dir -> { int n = index + dir; if (n >= 0 && n < list.size()) show(n); };
        imgBox.addView(image, new FrameLayout.LayoutParams(-1, -1));
        hist = new UiViews.HistogramView(this);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(Cyber.dp(this, 140), Cyber.dp(this, 56), Gravity.TOP | Gravity.END);
        hlp.setMargins(pad, pad, pad, pad);
        imgBox.addView(hist, hlp);
        left.addView(imgBox, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Cyber.panelBg(this));
        panel.setPadding(pad, pad, pad, pad);
        bar = new Neon.Bar(this);
        panel.addView(bar);
        status = Cyber.label(this, "", 9, Cyber.YELLOW);
        status.setPadding(0, pad / 3, 0, pad / 2);
        panel.addView(status);

        LinearLayout src = new LinearLayout(this);
        src.setGravity(Gravity.CENTER_VERTICAL);
        src.addView(Neon.caption(this, "FILE DA MODIFICARE ", 8));
        useRaw = Neon.chip(this, "RAW", Cyber.MAGENTA);
        useJpg = Neon.chip(this, "JPG", Cyber.CYAN);
        useRaw.setOnClickListener(v -> { preferRaw = true; styleSrc(); });
        useJpg.setOnClickListener(v -> { preferRaw = false; styleSrc(); });
        src.addView(useRaw, Neon.wrap(pad / 3));
        src.addView(useJpg, Neon.wrap(pad / 3));
        panel.addView(src);

        LinearLayout acts = new LinearLayout(this);
        acts.setPadding(0, pad, 0, 0);
        TextView print = Cyber.button(this, "STAMPA", Cyber.MAGENTA);
        TextView edit = Cyber.button(this, "MODIFICA", Cyber.CYAN);
        TextView ext = Cyber.button(this, "EDITOR RAW", Cyber.CYAN);
        TextView save = Cyber.button(this, "SALVA", Cyber.YELLOW);
        print.setOnClickListener(v -> print());
        edit.setOnClickListener(v -> editInApp());
        ext.setOnClickListener(v -> editExternal());
        save.setOnClickListener(v -> save());
        TextView[] ab = {print, edit, ext, save};
        for (int i = 0; i < ab.length; i++) {
            ab[i].setTextSize(10);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = pad / 3;
            acts.addView(ab[i], lp);
        }
        panel.addView(acts);

        exifBox = new LinearLayout(this);
        exifBox.setOrientation(LinearLayout.VERTICAL);
        exifBox.setPadding(0, pad, 0, 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(exifBox);
        panel.addView(sv, new LinearLayout.LayoutParams(-1, land ? 0 : Cyber.dp(this, 190), land ? 1 : 0));

        if (land) {
            root.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(panel, new LinearLayout.LayoutParams(Cyber.dp(this, 330), -1));
        } else {
            root.addView(left, new LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        }
        setContentView(root);
        getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets i = ins.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
    }

    private void styleSrc() {
        boolean pair = shot != null && shot.raw != null && (shot.jpg != null || shot.heif != null);
        useRaw.setVisibility(pair ? View.VISIBLE : View.GONE);
        useJpg.setVisibility(pair ? View.VISIBLE : View.GONE);
        ((View) useRaw.getParent()).setVisibility(pair ? View.VISIBLE : View.GONE);
        Neon.styleChip(useRaw, preferRaw, Cyber.MAGENTA);
        Neon.styleChip(useJpg, !preferRaw, Cyber.CYAN);
    }

    private void setStatus(String s, float p) { ui.post(() -> { status.setText(s.isEmpty() ? "" : "> " + s); bar.set(p); }); }

    private void show(int i) {
        index = i;
        shot = list.get(i);
        final int gen = ++generation;
        title.setText(shot.name());
        counter.setText((i + 1) + "/" + list.size());
        StringBuilder sb = new StringBuilder(shot.badge());
        if (!shot.isLocal()) {
            long size = 0; for (CanonClient.Item it : new CanonClient.Item[]{shot.jpg, shot.heif, shot.raw}) if (it != null && it.size > 0) size += it.size;
            if (size > 0) sb.append(String.format(Locale.US, " · %.1f MB", size / 1048576.0));
            if (shot.raw != null && shot.jpg != null) sb.append(" · ").append(shot.raw.name).append(" + ").append(shot.jpg.ext());
        }
        sub.setText(sb.toString());
        styleSrc();
        previewBm = null;
        Bitmap th = Session.thumbs.get(shot.key());
        image.setBitmap(th);
        hist.set(null, null, null, null);
        exifBox.removeAllViews();
        if (shot.exif != null) fillExif(shot.exif); else exifBox.addView(Neon.caption(this, "LETTURA EXIF…", 9));
        setStatus("CARICAMENTO ANTEPRIMA…", -1);
        final Session.Shot s = shot;
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            try {
                Exif.Info e = Session.exif(ctx, s);
                ui.post(() -> { if (gen == generation) fillExif(e); });
                Bitmap bm = Session.preview(ctx, s, 2048, (d, t) -> { if (gen == generation) setStatus("DOWNLOAD " + (t > 0 ? 100 * d / t + "%" : (d >> 10) + " KB"), t > 0 ? d / (float) t : -1); });
                int[][] h = histogram(bm);
                ui.post(() -> {
                    if (gen != generation) return;
                    previewBm = bm;
                    image.setBitmap(bm);
                    hist.set(h[0], h[1], h[2], h[3]);
                    setStatus(bm.getWidth() + "×" + bm.getHeight() + " ANTEPRIMA · PRONTA", 1f);
                });
            } catch (Exception e) {
                if (gen == generation) setStatus("✕ " + e.getMessage(), 0);
            }
        }).start();
    }

    private void fillExif(Exif.Info e) {
        exifBox.removeAllViews();
        List<String[]> rows = e.rows();
        if (rows.isEmpty()) { exifBox.addView(Neon.caption(this, "NESSUN DATO EXIF", 9)); return; }
        String line = e.exposureLine();
        if (!line.isEmpty()) {
            TextView big = Cyber.label(this, line, 13, Cyber.CYAN);
            big.setPadding(0, 0, 0, Cyber.dp(this, 6));
            exifBox.addView(big);
        }
        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, Cyber.dp(this, 2), 0, Cyber.dp(this, 2));
            TextView k = Neon.caption(this, r[0], 9);
            row.addView(k, new LinearLayout.LayoutParams(Cyber.dp(this, 84), -2));
            TextView v = Cyber.label(this, r[1], 10, Cyber.TEXT);
            v.setShadowLayer(0, 0, 0, 0);
            row.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
            exifBox.addView(row);
        }
    }

    static int[][] histogram(Bitmap b) {
        int w = Math.min(256, b.getWidth()), h = Math.max(1, Math.round(w * b.getHeight() / (float) b.getWidth()));
        Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
        int[] px = new int[w * h];
        s.getPixels(px, 0, w, 0, 0, w, h);
        int[] r = new int[256], g = new int[256], bl = new int[256], l = new int[256];
        for (int c : px) {
            int rr = (c >> 16) & 255, gg = (c >> 8) & 255, bb = c & 255;
            r[rr]++; g[gg]++; bl[bb]++;
            l[Math.min(255, (rr * 54 + gg * 183 + bb * 19) >> 8)]++;
        }
        return new int[][]{r, g, bl, l};
    }

    // ------------------------------------------------------------------ actions

    private CanonClient.Item editTarget() {
        if (shot.isLocal()) return null;
        if (shot.raw != null && (preferRaw || (shot.jpg == null && shot.heif == null))) return shot.raw;
        return shot.primary();
    }

    private interface FileCb { void on(File f); }

    private void fetch(CanonClient.Item it, FileCb cb) {
        final Session.Shot s = shot;
        final Context ctx = getApplicationContext();
        setStatus("DOWNLOAD " + (it != null ? it.name : s.name()) + "…", -1);
        Session.camIo.execute(() -> {
            try {
                File f = Session.download(ctx, s, it, (d, t) -> setStatus("DOWNLOAD " + (t > 0 ? 100 * d / t + "%" : "") + "  " + (d >> 20) + " MB", t > 0 ? d / (float) t : -1));
                ui.post(() -> { setStatus(f.getName() + " · " + (f.length() >> 20) + " MB · PRONTO", 1f); cb.on(f); });
            } catch (Exception e) { setStatus("✕ " + e.getMessage(), 0); }
        });
    }

    private Frames.Meta meta() { return Frames.Meta.of(shot.exif, shot.name()); }

    private void print() {
        if (previewBm == null) { Toast.makeText(this, "Attendi il caricamento dell'anteprima", Toast.LENGTH_SHORT).show(); return; }
        PrintSheet.showSingle(this, previewBm, meta(), null);
    }

    private void editInApp() {
        fetch(editTarget(), f -> {
            Frames.Meta m = meta();
            startActivity(new Intent(this, EditorActivity.class)
                .putExtra(EditorActivity.EXTRA_PATH, f.getAbsolutePath())
                .putExtra("m_date", m.date).putExtra("m_time", m.time).putExtra("m_camera", m.camera)
                .putExtra("m_exposure", m.exposure).putExtra("m_title", m.title)
                .putExtra(EditorActivity.EXTRA_FRAME, getSharedPreferences("print", MODE_PRIVATE).getInt("layout", Frames.Layout.RETRO.ordinal())));
        });
    }

    private void editExternal() { fetch(editTarget(), f -> ExternalEditor.open(this, f)); }

    private void save() {
        if (shot.isLocal()) { Toast.makeText(this, "È già sul telefono", Toast.LENGTH_SHORT).show(); return; }
        final Session.Shot s = shot;
        final Context ctx = getApplicationContext();
        Session.camIo.execute(() -> {
            int ok = 0;
            for (CanonClient.Item it : new CanonClient.Item[]{s.jpg, s.heif, s.raw}) {
                if (it == null) continue;
                try {
                    File f = Session.download(ctx, s, it, (d, t) -> setStatus("SALVATAGGIO " + it.name + " " + (t > 0 ? 100 * d / t + "%" : ""), t > 0 ? d / (float) t : -1));
                    GalleryActivity.saveToPhone(ctx, f);
                    ok++;
                } catch (Exception e) { setStatus("✕ " + e.getMessage(), 0); }
            }
            final int n = ok;
            setStatus(n + " FILE SALVATI › DCIM / Download / AmpyLink", 1f);
        });
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == PrintSheet.REQ_BT) Toast.makeText(this, "Permesso aggiornato: tocca di nuovo STAMPA DIRETTA", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ zoom view

    /** Fit / pinch / pan / double-tap 2.5×; horizontal fling at fit = next/previous shot. */
    static final class ZoomView extends View {
        interface Swipe { void on(int dir); }
        Swipe onSwipe;
        private Bitmap bm;
        private final Matrix m = new Matrix();
        private float scale = 1, tx, ty, fit = 1;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final ScaleGestureDetector sg;
        private final GestureDetector gd;

        ZoomView(Context c) {
            super(c);
            sg = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) { zoom(d.getScaleFactor(), d.getFocusX(), d.getFocusY()); return true; }
            });
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (scale > fit * 1.05f) reset(); else zoom(2.5f, e.getX(), e.getY());
                    return true;
                }
                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    if (scale > fit * 1.02f) { tx -= dx; ty -= dy; clamp(); invalidate(); }
                    return true;
                }
                @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    if (scale <= fit * 1.02f && Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(vx) > 800 && onSwipe != null) onSwipe.on(vx < 0 ? 1 : -1);
                    return true;
                }
            });
        }

        void setBitmap(Bitmap b) {
            boolean keep = bm != null && b != null && scale > fit * 1.02f;
            float rel = keep ? b.getWidth() / (float) bm.getWidth() : 1;
            bm = b;
            if (keep) { computeFit(); scale /= rel; clamp(); invalidate(); } else reset();
        }

        private void computeFit() {
            if (bm == null || getWidth() == 0) return;
            fit = Math.min(getWidth() / (float) bm.getWidth(), getHeight() / (float) bm.getHeight());
        }

        void reset() {
            computeFit();
            scale = fit;
            if (bm != null) { tx = (getWidth() - bm.getWidth() * scale) / 2; ty = (getHeight() - bm.getHeight() * scale) / 2; }
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { reset(); }

        private void zoom(float f, float fx, float fy) {
            if (bm == null) return;
            float ns = Math.max(fit, Math.min(scale * f, fit * 8));
            float k = ns / scale;
            tx = fx - (fx - tx) * k; ty = fy - (fy - ty) * k;
            scale = ns;
            clamp();
            invalidate();
        }

        private void clamp() {
            if (bm == null) return;
            float w = bm.getWidth() * scale, h = bm.getHeight() * scale;
            tx = w <= getWidth() ? (getWidth() - w) / 2 : Math.min(0, Math.max(getWidth() - w, tx));
            ty = h <= getHeight() ? (getHeight() - h) / 2 : Math.min(0, Math.max(getHeight() - h, ty));
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            sg.onTouchEvent(e);
            gd.onTouchEvent(e);
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            if (bm == null) return;
            m.reset(); m.postScale(scale, scale); m.postTranslate(tx, ty);
            c.drawBitmap(bm, m, p);
            Paint o = new Paint(); o.setStyle(Paint.Style.STROKE); o.setColor(Cyber.LINE);
            c.drawRect(new RectF(tx, ty, tx + bm.getWidth() * scale, ty + bm.getHeight() * scale), o);
        }
    }
}
