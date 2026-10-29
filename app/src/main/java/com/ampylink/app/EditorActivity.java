/*
 * AmpyLink in-app editor: AmpyRaw's GPU raw developer (derived from RawTherapee 5.13) plus a live
 * KODAK print-frame overlay and direct printing. GPL v3.
 */
package com.ampylink.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EditorActivity extends Activity {
    static final String EXTRA_PATH = "path", EXTRA_FRAME = "frame";
    private static final int REQ_OPEN = 1;
    private FrameOverlay overlay;
    private Frames.Layout frameLayout;
    private Frames.Meta meta = Frames.Meta.of(null, null);
    private TextView frameBtn;
    private static final int TAB_ADJ = 0, TAB_LENS = 1, TAB_CURVE = 2, TAB_LOOKS = 3;
    private static final String[] TAB_NAMES = {"ADJ", "LENS", "CURVE", "LOOKS"};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private EditorView editor;
    private View topBar, panel;
    private TextView empty, beforeTag, afterTag, toolName, toolValue, sourceTag;
    private Cyber.NeonSlider slider;
    private final TextView[] chips = new TextView[Tool.values().length];
    private final TextView[] tabViews = new TextView[4];
    private TextView[] presetTiles;
    private UiViews.HistogramView histView;
    private UiViews.CurveView curveView;
    private int[] lastLumaHist;
    private boolean histVisible = true;

    private int tab = TAB_ADJ;
    private Tool selAdj = Tool.EXPOSURE, selLens = Tool.DISTORTION;
    private final Curves curves = new Curves();

    // looks
    private int activePreset = -1;
    private float presetStrength = 1f;
    private float[] presetBase;
    private Curves presetBaseCurves;

    private int insetL, insetT, insetR, insetB;
    private boolean loading;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setDecorFitsSystemWindows(false);

        root = new FrameLayout(this);
        root.setBackgroundColor(Cyber.BG);
        editor = new EditorView(this);
        root.addView(editor, new FrameLayout.LayoutParams(-1, -1));
        overlay = new FrameOverlay(this, editor);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        editor.onViewChanged = overlay::invalidate;
        readMeta(getIntent());
        editor.onSplitMoved = this::placeSplitTags;
        editor.renderer.setHistogramListener((r, g, bl, l) -> ui.post(() -> {
            lastLumaHist = l;
            if (histView != null) histView.set(r, g, bl, l);
            if (curveView != null) curveView.setHistogram(l);
        }));

        empty = new TextView(this);
        empty.setGravity(Gravity.CENTER);
        empty.setTypeface(Cyber.MONO);
        empty.setLineSpacing(0, 1.4f);
        showEmpty(null);
        root.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        beforeTag = tag("◀ BEFORE", Cyber.CYAN);
        afterTag = tag("AFTER ▶", Cyber.MAGENTA);
        root.addView(beforeTag, new FrameLayout.LayoutParams(-2, -2));
        root.addView(afterTag, new FrameLayout.LayoutParams(-2, -2));

        histView = new UiViews.HistogramView(this);
        histView.setVisibility(View.GONE);
        root.addView(histView, new FrameLayout.LayoutParams(Cyber.dp(this, 150), Cyber.dp(this, 64)));

        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets i = ins.getInsets(WindowInsets.Type.displayCutout() | WindowInsets.Type.systemBars());
            insetL = i.left; insetT = i.top; insetR = i.right; insetB = i.bottom;
            buildChrome();
            return WindowInsets.CONSUMED;
        });
        hideSystemBars();
        buildChrome();
        handleIntent(getIntent());
    }

    private void readMeta(Intent i) {
        if (i == null || !i.hasExtra("m_date")) return;
        Frames.Meta m = new Frames.Meta();
        m.date = str(i, "m_date"); m.time = str(i, "m_time"); m.camera = str(i, "m_camera");
        m.exposure = str(i, "m_exposure"); m.title = str(i, "m_title");
        meta = m;
        int f = i.getIntExtra(EXTRA_FRAME, -1);
        frameLayout = f >= 0 && f < Frames.Layout.values().length ? Frames.Layout.values()[f] : null;
        if (frameLayout == Frames.Layout.FULL) frameLayout = null;
        overlay.layout = frameLayout;
        overlay.meta = meta;
        overlay.invalidate();
    }

    private static String str(Intent i, String k) { String v = i.getStringExtra(k); return v != null ? v : ""; }

    private void cycleFrame() {
        Frames.Layout[] all = Frames.Layout.values();
        if (frameLayout == null) frameLayout = all[1];
        else frameLayout = frameLayout.ordinal() + 1 < all.length ? all[frameLayout.ordinal() + 1] : null;
        overlay.layout = frameLayout;
        overlay.meta = meta;
        overlay.invalidate();
        if (frameBtn != null) { frameBtn.setText(frameLayout == null ? "FRAME" : frameLayout.label); frameBtn.setAlpha(frameLayout == null ? 0.5f : 1f); }
        if (frameLayout != null) getSharedPreferences("print", MODE_PRIVATE).edit().putInt("layout", frameLayout.ordinal()).apply();
        Toast.makeText(this, frameLayout == null ? "FRAME OFF" : "FRAME // " + frameLayout.label, Toast.LENGTH_SHORT).show();
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private TextView tag(String s, int color) {
        TextView t = Cyber.label(this, s, 9, color);
        t.setPadding(Cyber.dp(this, 6), Cyber.dp(this, 3), Cyber.dp(this, 6), Cyber.dp(this, 3));
        t.setBackgroundColor(0xB307070C);
        t.setVisibility(View.GONE);
        return t;
    }

    private void showEmpty(String status) {
        SpannableString s = new SpannableString("AMPY▲LINK\n\n" + (status != null ? status : "APRI UN FILE · O SCEGLI UNA FOTO DALLA EOS") + "\n");
        s.setSpan(new ForegroundColorSpan(Cyber.CYAN), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(Cyber.MAGENTA), 4, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(Cyber.CYAN), 5, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(Cyber.DIM), 9, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new android.text.style.RelativeSizeSpan(2.6f), 0, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        empty.setText(s);
        empty.setTextSize(11);
        empty.setLetterSpacing(0.2f);
        empty.setShadowLayer(Cyber.dp(this, 10), 0, 0, 0x8800F0FF);
        empty.setVisibility(View.VISIBLE);
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        buildChrome(); // portrait <-> landscape without reloading the image or GL context
    }

    // ------------------------------------------------------------------ layout

    private boolean landscape() { return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE; }

    private LinearLayout content; // tab content holder

    private void buildChrome() {
        if (topBar != null) root.removeView(topBar);
        if (panel != null) root.removeView(panel);
        boolean land = landscape();
        int pad = Cyber.dp(this, 10);

        // --- command bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(land ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackground(Cyber.panelBg(this));
        TextView logo = Cyber.label(this, land ? "A▲L" : "AMPY▲LINK", land ? 13 : 14, Cyber.CYAN);
        logo.setOnLongClickListener(v -> { resetAll(); return true; });
        sourceTag = Cyber.label(this, "", 7, Cyber.DIM);
        sourceTag.setShadowLayer(0, 0, 0, 0);
        sourceTag.setSingleLine(!land);
        if (!land) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(logo); col.addView(sourceTag);
            bar.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        } else {
            bar.addView(logo);
            bar.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        }
        String[] labels = {isTaskRoot() ? "OPEN" : "◀", "A|B", "HIST", frameLayout == null ? "FRAME" : frameLayout.label, "PRINT", "SAVE"};
        int[] colors = {Cyber.CYAN, Cyber.YELLOW, Cyber.YELLOW, Cyber.CYAN, Cyber.MAGENTA, Cyber.MAGENTA};
        for (int i = 0; i < labels.length; i++) {
            TextView btn = Cyber.button(this, labels[i], colors[i]);
            btn.setTextSize(10);
            btn.setPadding(Cyber.dp(this, 7), Cyber.dp(this, 7), Cyber.dp(this, 7), Cyber.dp(this, 7));
            final int k = i;
            btn.setOnClickListener(v -> onCommand(k));
            if (i == 2) btn.setAlpha(histVisible ? 1f : 0.4f);
            if (i == 3) { frameBtn = btn; btn.setAlpha(frameLayout == null ? 0.5f : 1f); }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(land ? -1 : -2, -2);
            if (land) lp.topMargin = pad / 2; else lp.leftMargin = pad / 4;
            bar.addView(btn, lp);
        }
        if (land) { bar.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1)); bar.addView(sourceTag); }

        // --- panel: tabs + content
        LinearLayout pnl = new LinearLayout(this);
        pnl.setOrientation(LinearLayout.VERTICAL);
        pnl.setBackground(Cyber.panelBg(this));
        LinearLayout tabs = new LinearLayout(this);
        for (int i = 0; i < 4; i++) {
            TextView t = Cyber.label(this, TAB_NAMES[i], 10, Cyber.TEXT);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Cyber.dp(this, 6), 0, Cyber.dp(this, 6));
            final int k = i;
            t.setOnClickListener(v -> { tab = k; styleTabs(); showTab(); });
            tabViews[i] = t;
            tabs.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        pnl.addView(tabs);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        pnl.addView(content, new LinearLayout.LayoutParams(-1, land ? 0 : -2, land ? 1 : 0));

        FrameLayout.LayoutParams blp, plp;
        int railW = Cyber.dp(this, 78), panelW = Cyber.dp(this, 310);
        if (land) {
            blp = new FrameLayout.LayoutParams(railW, -1, Gravity.START);
            blp.leftMargin = insetL;
            bar.setPadding(pad / 2, pad + insetT, pad / 2, pad + insetB);
            plp = new FrameLayout.LayoutParams(panelW, -1, Gravity.END);
            plp.rightMargin = insetR;
            pnl.setPadding(pad, pad + insetT, pad, pad + insetB);
        } else {
            blp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
            bar.setPadding(pad + insetL, pad + insetT, pad + insetR, pad);
            plp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            pnl.setPadding(pad + insetL, pad / 2, pad + insetR, pad + insetB);
        }
        root.addView(bar, blp);
        root.addView(pnl, plp);
        topBar = bar; panel = pnl;
        styleTabs();
        showTab();
        updateSourceTag();

        View.OnLayoutChangeListener l = (v, a, bb, c, d, e, f, g, h) -> updateSafeArea();
        bar.addOnLayoutChangeListener(l);
        pnl.addOnLayoutChangeListener(l);
    }

    private void styleTabs() {
        for (int i = 0; i < 4; i++) {
            TextView t = tabViews[i];
            if (t == null) continue;
            boolean sel = i == tab;
            t.setTextColor(sel ? Cyber.MAGENTA : Cyber.DIM);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0x00000000);
            if (sel) { g.setColor(0x18FF2BD6); g.setStroke(Cyber.dp(this, 1), 0x66FF2BD6); }
            t.setBackground(g);
        }
    }

    /** Fill the panel for the current tab: chips/presets on top, ONE parameter slider below (or the curve editor). */
    private void showTab() {
        content.removeAllViews();
        editor.renderer.histogramOn = histVisible || tab == TAB_CURVE;
        editor.renderer.invalidateHistogram();
        editor.requestRender();
        for (int i = 0; i < chips.length; i++) chips[i] = null;
        boolean land = landscape();
        int pad = Cyber.dp(this, 10);
        if (tab == TAB_ADJ || tab == TAB_LENS) {
            int which = tab == TAB_ADJ ? Tool.TAB_ADJUST : Tool.TAB_LENS;
            ViewGroup strip = toolsStrip(land, which);
            content.addView(strip, new LinearLayout.LayoutParams(-1, land ? 0 : -2, land ? 1 : 0));
            addSliderRow(pad);
            select(tab == TAB_ADJ ? selAdj : selLens);
        } else if (tab == TAB_CURVE) {
            LinearLayout chRow = new LinearLayout(this);
            chRow.setGravity(Gravity.CENTER_VERTICAL);
            chRow.setPadding(0, pad / 2, 0, pad / 2);
            String[] cn = {"RGB", "R", "G", "B"};
            final TextView[] cb = new TextView[4];
            for (int i = 0; i < 4; i++) {
                cb[i] = Cyber.button(this, cn[i], UiViews.CurveView.color(i));
                final int k = i;
                cb[i].setOnClickListener(v -> { curveView.setChannel(k); for (int j = 0; j < 4; j++) cb[j].setAlpha(j == k ? 1f : 0.35f); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = pad / 2;
                chRow.addView(cb[i], lp);
            }
            chRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            TextView rst = Cyber.button(this, "RESET", Cyber.YELLOW);
            rst.setOnClickListener(v -> { curves.reset(curveView.channel()); curvesChanged(true); curveView.invalidate(); });
            chRow.addView(rst);
            content.addView(chRow);
            int prev = curveView != null ? curveView.channel() : Curves.MASTER;
            curveView = new UiViews.CurveView(this, curves);
            curveView.setChannel(prev);
            for (int j = 0; j < 4; j++) cb[j].setAlpha(j == prev ? 1f : 0.35f);
            curveView.setHistogram(lastLumaHist);
            curveView.setListener(() -> curvesChanged(true));
            content.addView(curveView, land ? new LinearLayout.LayoutParams(-1, 0, 1)
                : new LinearLayout.LayoutParams(-1, Cyber.dp(this, 200)));
            TextView hint = Cyber.label(this, "TAP = ADD · DRAG = MOVE · DOUBLE-TAP / DRAG OUT = DELETE", 7, Cyber.DIM);
            hint.setShadowLayer(0, 0, 0, 0);
            hint.setGravity(Gravity.CENTER);
            content.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        } else { // LOOKS
            ViewGroup strip = presetStrip(land);
            content.addView(strip, new LinearLayout.LayoutParams(-1, land ? 0 : -2, land ? 1 : 0));
            addSliderRow(pad);
            showPresetSlider();
        }
    }

    private void addSliderRow(int pad) {
        LinearLayout head = new LinearLayout(this);
        head.setPadding(Cyber.dp(this, 8), pad, Cyber.dp(this, 8), 0);
        toolName = Cyber.label(this, "", 12, Cyber.TEXT);
        toolValue = Cyber.label(this, "", 12, Cyber.MAGENTA);
        toolValue.setGravity(Gravity.END);
        head.addView(toolName, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(toolValue);
        content.addView(head);
        slider = new Cyber.NeonSlider(this);
        slider.setListener((pos, done) -> {
            if (tab == TAB_LOOKS) {
                if (activePreset <= 0) return;
                presetStrength = pos;
                applyPreset(activePreset, presetStrength);
                toolValue.setText(Math.round(pos * 100) + "%");
            } else {
                Tool t = tab == TAB_ADJ ? selAdj : selLens;
                float v = t.fromPos(pos);
                synchronized (editor.renderer.lock) { editor.renderer.values[t.ordinal()] = v; }
                manualEdit();
                toolValue.setText(t.format(v));
                styleChip(t);
                editor.renderer.invalidateHistogram();
                editor.requestRender();
            }
        });
        content.addView(slider, new LinearLayout.LayoutParams(-1, -2));
    }

    private ViewGroup toolsStrip(boolean land, int which) {
        if (land) {
            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(4);
            for (Tool t : Tool.values()) if (t.tab == which) {
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
                lp.width = 0; lp.setMargins(4, 4, 4, 4);
                grid.addView(chip(t), lp);
            }
            ScrollView sv = new ScrollView(this);
            sv.addView(grid);
            return sv;
        }
        LinearLayout row = new LinearLayout(this);
        for (Tool t : Tool.values()) if (t.tab == which) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = Cyber.dp(this, 5);
            row.addView(chip(t), lp);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setPadding(0, Cyber.dp(this, 6), 0, 0);
        hs.addView(row);
        return hs;
    }

    private ViewGroup presetStrip(boolean land) {
        presetTiles = new TextView[Preset.ALL.length];
        ViewGroup row;
        if (land) { GridLayout g = new GridLayout(this); g.setColumnCount(3); row = g; }
        else row = new LinearLayout(this);
        int sz = Cyber.dp(this, 70);
        for (int i = 0; i < Preset.ALL.length; i++) {
            Preset p = Preset.ALL[i];
            TextView t = Cyber.label(this, p.name, 8, 0xFFFFFFFF);
            t.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            t.setPadding(4, 4, 4, Cyber.dp(this, 5));
            t.setShadowLayer(Cyber.dp(this, 3), 0, 0, 0xFF000000);
            final int k = i;
            t.setOnClickListener(v -> onPreset(k));
            presetTiles[i] = t;
            if (land) {
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
                lp.width = 0; lp.height = sz; lp.setMargins(4, 4, 4, 4);
                row.addView(t, lp);
            } else {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz); lp.rightMargin = Cyber.dp(this, 6);
                row.addView(t, lp);
            }
        }
        stylePresets();
        if (land) { ScrollView sv = new ScrollView(this); sv.addView(row); return sv; }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setPadding(0, Cyber.dp(this, 6), 0, 0);
        hs.addView(row);
        return hs;
    }

    private void stylePresets() {
        if (presetTiles == null) return;
        for (int i = 0; i < presetTiles.length; i++) {
            Preset p = Preset.ALL[i];
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{p.c1, p.c2});
            float r = Cyber.dp(this, 8);
            g.setCornerRadii(new float[]{r, r, 0, 0, r, r, 0, 0});
            g.setStroke(Cyber.dp(this, i == activePreset ? 2 : 1), i == activePreset ? Cyber.CYAN : 0x55FFFFFF);
            presetTiles[i].setBackground(g);
            presetTiles[i].setAlpha(activePreset < 0 || i == activePreset ? 1f : 0.75f);
        }
    }

    private void showPresetSlider() {
        if (activePreset > 0) {
            toolName.setText("LOOK // " + Preset.ALL[activePreset].name);
            toolValue.setText(Math.round(presetStrength * 100) + "%");
            slider.set(presetStrength, 1f);
            slider.setEnabled(true); slider.setAlpha(1f);
        } else {
            toolName.setText(activePreset == 0 ? "LOOK // ORIGINAL" : "TAP A LOOK");
            toolValue.setText("");
            slider.set(1f, 1f);
            slider.setAlpha(0.3f);
        }
    }

    private void updateSafeArea() {
        if (topBar == null || panel == null) return;
        if (landscape()) editor.setSafeArea(topBar.getRight(), insetT, root.getWidth() - panel.getLeft(), insetB);
        else editor.setSafeArea(insetL, topBar.getBottom(), insetR, root.getHeight() - panel.getTop());
        placeSplitTags();
        placeHistogram();
    }

    private void placeHistogram() {
        boolean show = histVisible && editor.renderer.hasImage();
        histView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show || topBar == null) return;
        int m = Cyber.dp(this, 8);
        if (landscape()) { histView.setX(topBar.getRight() + m); histView.setY(insetT + m); }
        else { histView.setX(root.getWidth() - Cyber.dp(this, 150) - m - insetR); histView.setY(topBar.getBottom() + m); }
        histView.bringToFront();
    }

    private void placeSplitTags() {
        boolean show; synchronized (editor.renderer.lock) { show = editor.renderer.showSplit; }
        int vis = show && editor.renderer.hasImage() ? View.VISIBLE : View.GONE;
        beforeTag.setVisibility(vis); afterTag.setVisibility(vis);
        if (vis == View.GONE || topBar == null) return;
        float x = editor.splitX(), gap = Cyber.dp(this, 10);
        float y = (landscape() ? insetT : topBar.getBottom()) + Cyber.dp(this, histVisible ? 80 : 10);
        beforeTag.setX(x - beforeTag.getWidth() - gap); beforeTag.setY(y);
        afterTag.setX(x + gap); afterTag.setY(y);
        float left = landscape() ? topBar.getRight() : 0, right = landscape() ? panel.getLeft() : root.getWidth();
        beforeTag.setAlpha(beforeTag.getX() < left ? 0f : 1f);
        afterTag.setAlpha(afterTag.getX() + afterTag.getWidth() > right ? 0f : 1f);
    }

    private TextView chip(Tool t) {
        TextView c = Cyber.label(this, t.code, 11, Cyber.TEXT);
        c.setGravity(Gravity.CENTER);
        int p = Cyber.dp(this, 9);
        c.setPadding(p + 2, p, p + 2, p);
        c.setMinWidth(Cyber.dp(this, 52));
        c.setOnClickListener(v -> select(t));                               // 1-tap selection
        c.setOnLongClickListener(v -> { setValue(t, t.def); return true; }); // long-press = reset tool
        chips[t.ordinal()] = c;
        styleChip(t);
        return c;
    }

    private Tool selected() { return tab == TAB_LENS ? selLens : selAdj; }

    private void styleChip(Tool t) {
        TextView c = chips[t.ordinal()];
        if (c == null) return;
        boolean sel = t == selected();
        float v; synchronized (editor.renderer.lock) { v = editor.renderer.values[t.ordinal()]; }
        boolean mod = Math.abs(v - t.def) > 1e-4f;
        GradientDrawable g = new GradientDrawable();
        g.setColor(sel ? 0x3300F0FF : 0x10000000);
        g.setStroke(Cyber.dp(this, 1), sel ? Cyber.CYAN : (mod ? 0xAAFF2BD6 : 0x4452606E));
        float r = Cyber.dp(this, 6);
        g.setCornerRadii(new float[]{r, r, 0, 0, r, r, 0, 0});
        c.setBackground(g);
        c.setTextColor(sel ? Cyber.CYAN : (mod ? Cyber.MAGENTA : Cyber.TEXT));
    }

    private void select(Tool t) {
        Tool prev = selected();
        if (t.tab == Tool.TAB_LENS) selLens = t; else selAdj = t;
        styleChip(prev); styleChip(t);
        if (slider == null) return;
        float v; synchronized (editor.renderer.lock) { v = editor.renderer.values[t.ordinal()]; }
        toolName.setText(t.label);
        toolValue.setText(t.format(v));
        slider.set(t.toPos(v), t.toPos(t.def));
        slider.setAlpha(1f);
    }

    private void setValue(Tool t, float v) {
        synchronized (editor.renderer.lock) { editor.renderer.values[t.ordinal()] = v; }
        if (t == selected() && (tab == TAB_ADJ || tab == TAB_LENS)) select(t); else styleChip(t);
        editor.renderer.invalidateHistogram();
        editor.requestRender();
    }

    /** Any manual change ends the current look: next look applies on top of the new state. */
    private void manualEdit() {
        if (activePreset >= 0) { activePreset = -1; presetBase = null; presetBaseCurves = null; stylePresets(); }
    }

    private void curvesChanged(boolean manual) {
        if (manual) manualEdit();
        editor.renderer.setCurves(curves.lut(), !curves.isIdentity());
        editor.requestRender();
    }

    private void resetAll() {
        for (Tool t : Tool.values()) synchronized (editor.renderer.lock) { editor.renderer.values[t.ordinal()] = t.def; }
        curves.resetAll();
        activePreset = -1; presetBase = null; presetBaseCurves = null;
        curvesChanged(false);
        showTab();
        Toast.makeText(this, "RESET // ALL TOOLS", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ looks

    private void onPreset(int i) {
        if (i == 0) { // ORIGINAL
            for (Tool t : Tool.values()) synchronized (editor.renderer.lock) { editor.renderer.values[t.ordinal()] = t.def; }
            curves.resetAll();
            activePreset = 0; presetBase = null; presetBaseCurves = null;
            curvesChanged(false);
        } else {
            if (presetBase == null) { // capture the state the look is applied on
                synchronized (editor.renderer.lock) { presetBase = editor.renderer.values.clone(); }
                presetBaseCurves = curves.copy();
            }
            activePreset = i;
            presetStrength = 1f;
            applyPreset(i, presetStrength);
        }
        stylePresets();
        showPresetSlider();
    }

    private void applyPreset(int i, float k) {
        Preset p = Preset.ALL[i];
        float[] v = presetBase.clone();
        for (java.util.Map.Entry<Tool, Float> e : p.delta.entrySet()) {
            Tool t = e.getKey();
            v[t.ordinal()] = t.clamp(v[t.ordinal()] + k * e.getValue());
        }
        synchronized (editor.renderer.lock) { System.arraycopy(v, 0, editor.renderer.values, 0, v.length); }
        Curves blended = Curves.blend(presetBaseCurves, p.curves != null ? p.curves : new Curves(), k);
        for (int c = 0; c < 4; c++) { curves.ch[c].clear(); curves.ch[c].addAll(blended.ch[c]); }
        // keep the curve editable: if look has no curve keep the user's control points
        if (p.curves == null) for (int c = 0; c < 4; c++) { curves.ch[c].clear(); for (Curves.Pt q : presetBaseCurves.ch[c]) curves.ch[c].add(new Curves.Pt(q.x, q.y)); }
        curvesChanged(false);
        editor.renderer.invalidateHistogram();
    }

    private void updateSourceTag() {
        if (sourceTag == null) return;
        RawDecoder.ImageData d = currentInfo;
        sourceTag.setText(d == null ? "GPU // " + android.os.Build.MODEL.toUpperCase() : (d.source + " · " + d.width() + "×" + d.height()));
    }

    // ------------------------------------------------------------------ commands

    private void onCommand(int k) {
        switch (k) {
            case 0:
                if (isTaskRoot()) {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/octet-stream"});
                    startActivityForResult(i, REQ_OPEN);
                } else finish();
                break;
            case 1:
                synchronized (editor.renderer.lock) { editor.renderer.showSplit = !editor.renderer.showSplit; }
                placeSplitTags(); editor.requestRender();
                break;
            case 2:
                histVisible = !histVisible;
                editor.renderer.histogramOn = histVisible || tab == TAB_CURVE;
                editor.renderer.invalidateHistogram();
                buildChrome(); placeHistogram(); editor.requestRender();
                break;
            case 3: cycleFrame(); break;
            case 4: renderFull(bm -> PrintSheet.showSingle(this, bm, meta, frameLayout != null ? frameLayout
                : Frames.Layout.values()[Math.min(Frames.Layout.values().length - 1, getSharedPreferences("print", MODE_PRIVATE).getInt("layout", 0))])); break;
            case 5: renderFull(this::save); break;
        }
    }

    private void renderFull(GLRenderer.BitmapCallback cb) {
        if (!editor.renderer.hasImage()) { Toast.makeText(this, "NO IMAGE LOADED", Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, "RENDERING FULL RES ON GPU…", Toast.LENGTH_SHORT).show();
        editor.queueEvent(() -> editor.renderer.renderFull(bm -> ui.post(() -> {
            if (bm == null) Toast.makeText(this, "RENDER FAILED", Toast.LENGTH_SHORT).show(); else cb.onBitmap(bm);
        })));
    }

    private void save(Bitmap bm) {
        worker.execute(() -> {
            try {
                String base = currentInfo != null ? currentInfo.name.replaceAll("\\.[^.]*$", "") : "ampylink";
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, base + "_ampylink.jpg");
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/AmpyLink");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                try (OutputStream o = getContentResolver().openOutputStream(u)) { bm.compress(Bitmap.CompressFormat.JPEG, 95, o); }
                v.clear(); v.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(u, v, null, null);
                ui.post(() -> Toast.makeText(this, "SALVATA › DCIM/AmpyLink", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "SAVE FAILED: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ------------------------------------------------------------------ loading

    private volatile RawDecoder.ImageData currentInfo;

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); readMeta(i); handleIntent(i); }

    /** "Open with" / "Share" / "Edit" from Gallery, Files, Google Photos… launches straight into the image. */
    private void handleIntent(Intent i) {
        if (i == null) return;
        Uri u = null;
        String path = i.getStringExtra(EXTRA_PATH);
        if (path != null) { load(Uri.fromFile(new File(path))); return; }
        String a = i.getAction();
        if (Intent.ACTION_SEND.equals(a)) u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        else if (Intent.ACTION_VIEW.equals(a) || Intent.ACTION_EDIT.equals(a)) u = i.getData();
        if (u == null && i.getClipData() != null && i.getClipData().getItemCount() > 0) u = i.getClipData().getItemAt(0).getUri();
        if (u != null) load(u);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_OPEN && data.getData() != null) load(data.getData());
    }

    private String displayName(Uri u) {
        if ("file".equals(u.getScheme())) return new File(u.getPath()).getName();
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return "image";
    }

    private void load(Uri u) {
        if (loading) return;
        loading = true;
        if (!editor.renderer.hasImage()) showEmpty("DECODING ▓▓▓▓▒▒░░");
        Toast.makeText(this, "DECODING…", Toast.LENGTH_SHORT).show();
        final int maxTex = Math.max(4096, editor.renderer.maxTextureSize());
        worker.execute(() -> {
            try {
                RawDecoder.ImageData d = RawDecoder.decode(getContentResolver(), u, displayName(u), maxTex);
                currentInfo = d;
                editor.queueEvent(() -> {
                    editor.renderer.setImage(d);
                    ui.post(() -> {
                        loading = false;
                        empty.setVisibility(View.GONE);
                        updateSourceTag();
                        editor.fit();
                        overlay.invalidate();
                        placeSplitTags();
                        placeHistogram();
                    });
                });
                editor.requestRender();
            } catch (Throwable e) {
                ui.post(() -> {
                    loading = false;
                    if (!editor.renderer.hasImage()) showEmpty("ERROR // " + e.getMessage());
                    Toast.makeText(this, "DECODE FAILED: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == PrintSheet.REQ_BT) Toast.makeText(this, "Permesso aggiornato: tocca di nuovo STAMPA DIRETTA", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onPause() { super.onPause(); editor.onPause(); }
    @Override protected void onResume() { super.onResume(); editor.onResume(); hideSystemBars(); }
}
