/*
 * AMPY▲LINK — main screen. Links to the Canon EOS R6 Mark II over Wi-Fi (PTP/IP like Camera
 * Connect, or CCAPI), shows the card as a date-grouped gallery with RAW+JPG pairing, filters and
 * multi-select, plus pictures picked from the phone. GPL v3.
 */
package com.ampylink.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

public final class GalleryActivity extends Activity {
    static final int REQ_LOCAL = 11;
    private static final int F_ALL = 0, F_JPG = 1, F_RAW = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private TextView statusLine, linkBtn, srcCam, srcPhone, selInfo, emptyView, gridBtn, sortBtn;
    private final TextView[] filterChips = new TextView[3];
    private LinearLayout selBar;
    private ListView list;
    private RowAdapter adapter;
    private int filter = F_ALL, cols = 3;
    private boolean phoneTab, newestFirst = true, selecting;
    private volatile boolean linking;
    private final List<Session.Shot> visible = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();
    private final LinkedBlockingDeque<Session.Shot> thumbQueue = new LinkedBlockingDeque<>();
    private final Set<String> queued = new HashSet<>();
    private Thread thumbWorker;
    private volatile boolean alive = true;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Session.appCtx = getApplicationContext();
        sp = getSharedPreferences("eos", MODE_PRIVATE);
        cols = sp.getInt("cols", 3);
        buildUi();
        startThumbWorker();
        ui.postDelayed(keepAlive, 8000);
        rebuild();
        if (Session.client == null && Session.shots.isEmpty()) showEmpty();
        if (CameraLinkService.enabled(this)) CameraLinkService.start(this);
        ui.postDelayed(this::autoConnect, 600);
    }

    // ------------------------------------------------------------------ UI

    private int pad() { return Cyber.dp(this, 10); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Cyber.BG);
        int pad = pad();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackground(Cyber.panelBg(this));
        top.setPadding(pad, pad, pad, pad);
        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = Cyber.label(this, "AMPY▲LINK", 16, Cyber.CYAN);
        row1.addView(logo);
        row1.addView(Neon.spacer(this));
        TextView prn = Cyber.button(this, "PRN", Cyber.YELLOW);
        prn.setOnClickListener(v -> printSelectionOrHint());
        row1.addView(prn, Neon.wrap(pad / 2));
        linkBtn = Cyber.button(this, "LINK", Cyber.MAGENTA);
        linkBtn.setOnClickListener(v -> showLinkDialog());
        linkBtn.setOnLongClickListener(v -> { showLog(); return true; });
        row1.addView(linkBtn, Neon.wrap(pad / 2));
        top.addView(row1);
        statusLine = Neon.caption(this, "", 9);
        statusLine.setSingleLine();
        statusLine.setPadding(0, pad / 2, 0, 0);
        top.addView(statusLine);

        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setPadding(0, pad, 0, 0);
        srcCam = Neon.chip(this, "◉ EOS", Cyber.MAGENTA);
        srcPhone = Neon.chip(this, "▤ TELEFONO", Cyber.MAGENTA);
        srcCam.setOnClickListener(v -> { phoneTab = false; rebuild(); });
        srcPhone.setOnClickListener(v -> { if (phoneTab) pickLocal(); phoneTab = true; rebuild(); });
        row2.addView(srcCam);
        row2.addView(srcPhone, Neon.wrap(pad / 3));
        row2.addView(Neon.spacer(this));
        String[] fn = {"TUTTE", "JPG", "RAW"};
        for (int i = 0; i < 3; i++) {
            filterChips[i] = Neon.chip(this, fn[i], Cyber.CYAN);
            final int k = i;
            filterChips[i].setOnClickListener(v -> { filter = k; rebuild(); });
            row2.addView(filterChips[i], Neon.wrap(pad / 3));
        }
        top.addView(row2);
        LinearLayout row3 = new LinearLayout(this);
        row3.setGravity(Gravity.CENTER_VERTICAL);
        row3.setPadding(0, pad / 2, 0, 0);
        TextView sel = Neon.chip(this, "SELEZIONA", Cyber.YELLOW);
        sel.setOnClickListener(v -> { selecting = !selecting; if (!selecting) clearSelection(); Neon.styleChip(sel, selecting, Cyber.YELLOW); updateSelBar(); });
        row3.addView(sel);
        row3.addView(Neon.spacer(this));
        sortBtn = Neon.chip(this, "", Cyber.CYAN);
        sortBtn.setOnClickListener(v -> { newestFirst = !newestFirst; rebuild(); });
        row3.addView(sortBtn);
        gridBtn = Neon.chip(this, "", Cyber.CYAN);
        gridBtn.setOnClickListener(v -> { cols = cols >= 5 ? 2 : cols + 1; sp.edit().putInt("cols", cols).apply(); rebuild(); });
        row3.addView(gridBtn, Neon.wrap(pad / 3));
        TextView refresh = Neon.chip(this, "↻", Cyber.CYAN);
        refresh.setOnClickListener(v -> { if (Session.client != null) relist(); else showLinkDialog(); });
        row3.addView(refresh, Neon.wrap(pad / 3));
        top.addView(row3);
        root.addView(top);

        FrameLayout body = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setPadding(pad / 2, pad / 2, pad / 2, pad * 6);
        list.setClipToPadding(false);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> { if (r - l != or - ol) ui.post(adapter::notifyDataSetChanged); });
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        list.setVerticalScrollBarEnabled(false);
        scroller = new NeonScroller(this, scrollModel);
        body.addView(scroller, new FrameLayout.LayoutParams(-1, -1));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) { scrollState = state; if (state != SCROLL_STATE_IDLE) scroller.onScrolled(); }
            @Override public void onScroll(AbsListView v, int first, int count, int total) { if (scrollState != SCROLL_STATE_IDLE || scroller.isDragging()) scroller.onScrolled(); }
        });
        emptyView = new TextView(this);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTypeface(Cyber.MONO);
        emptyView.setTextColor(Cyber.DIM);
        emptyView.setLineSpacing(0, 1.4f);
        emptyView.setPadding(pad * 2, 0, pad * 2, 0);
        body.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));

        selBar = new LinearLayout(this);
        selBar.setGravity(Gravity.CENTER_VERTICAL);
        selBar.setBackground(Cyber.panelBg(this));
        selBar.setPadding(pad, pad, pad, pad);
        selInfo = Cyber.label(this, "", 11, Cyber.TEXT);
        selBar.addView(selInfo, new LinearLayout.LayoutParams(0, -2, 1));
        TextView sPrint = Cyber.button(this, "STAMPA", Cyber.MAGENTA);
        sPrint.setOnClickListener(v -> printShots(selected()));
        TextView sSave = Cyber.button(this, "SALVA", Cyber.CYAN);
        sSave.setOnClickListener(v -> saveShots(selected()));
        TextView sClear = Cyber.button(this, "✕", Cyber.DIM);
        sClear.setTextColor(Cyber.TEXT);
        sClear.setOnClickListener(v -> { clearSelection(); selecting = false; Neon.styleChip(sel, false, Cyber.YELLOW); updateSelBar(); });
        selBar.addView(sPrint);
        selBar.addView(sSave, Neon.wrap(pad / 2));
        selBar.addView(sClear, Neon.wrap(pad / 2));
        selBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        body.addView(selBar, slp);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets i = ins.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, 0);
            list.setPadding(pad / 2, pad / 2, pad / 2, pad * 6 + i.bottom);
            selBar.setPadding(pad, pad, pad, pad + i.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
        getWindow().setDecorFitsSystemWindows(false);
    }

    private void setStatus(String s) { Session.log(s); ui.post(() -> statusLine.setText(s)); }

    /** LOG: the full connection sequence, copyable, to see exactly where it stops. */
    private void showLog() {
        Object[] dv = Neon.dialog(this, "LOG // CONNESSIONE", true);
        Dialog d = (Dialog) dv[0];
        LinearLayout root = (LinearLayout) dv[1];
        String txt = Session.logText();
        TextView t = Cyber.label(this, txt.isEmpty() ? "(vuoto)" : txt, 8, Cyber.TEXT);
        t.setShadowLayer(0, 0, 0, 0);
        t.setTextIsSelectable(true);
        t.setLineSpacing(0, 1.2f);
        root.addView(t);
        TextView copy = Cyber.button(this, "COPIA LOG", Cyber.MAGENTA);
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("AmpyLink log", txt));
            Toast.makeText(this, "Log copiato: incollalo nella chat", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Cyber.dp(this, 10);
        root.addView(copy, lp);
        d.show();
    }

    /** Like Camera Connect: on start, wake the paired camera over Bluetooth and bring up its Wi-Fi. */
    private void autoConnect() {
        if (Session.client != null || linking || !sp.getBoolean("ble_auto", true)) return;
        String mac = sp.getString("ble_mac", null), name = sp.getString("ble_name", "EOS");
        if (mac == null || sp.getString("ble_paired", null) == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        android.bluetooth.BluetoothAdapter ad = KodakBt.adapter(this);
        if (ad == null || !ad.isEnabled()) return;
        Session.log("AUTO-COLLEGA via Bluetooth → " + name + " " + mac);
        try { bleLink(ad.getRemoteDevice(mac), name); } catch (Exception e) { Session.log("auto-collega: " + e.getMessage()); }
    }

    private void updateStatusIdle() {
        CanonClient c = Session.client;
        if (c == null) { statusLine.setText("◌ OFFLINE · TOCCA LINK PER COLLEGARE LA EOS"); statusLine.setTextColor(Cyber.DIM); linkBtn.setText("LINK"); }
        else {
            statusLine.setText("◉ " + (c.cameraName.isEmpty() ? "CANON EOS" : c.cameraName.toUpperCase(Locale.US)) + " · " + c.host + " · " + c.mode() + " · " + Session.shots.size() + " SCATTI");
            statusLine.setTextColor(Cyber.CYAN);
            linkBtn.setText("UNLINK");
        }
    }

    private void showEmpty() {
        emptyView.setVisibility(View.VISIBLE);
        if (phoneTab) {
            emptyView.setText("TELEFONO\n\nTocca ▤ TELEFONO di nuovo per scegliere foto o RAW\ndal telefono da stampare o modificare.");
        } else if (linking) {
            emptyView.setText("LINK IN CORSO…\n\n" + statusLine.getText());
        } else {
            emptyView.setText("AMPY▲LINK\n\nCanon EOS R6 Mark II › Wi-Fi\nKODAK Mini 2 Retro › Bluetooth\n\nTocca LINK per collegare la fotocamera");
        }
    }

    // ------------------------------------------------------------------ list model

    private void rebuild() {
        Neon.styleChip(srcCam, !phoneTab, Cyber.MAGENTA);
        Neon.styleChip(srcPhone, phoneTab, Cyber.MAGENTA);
        for (int i = 0; i < 3; i++) Neon.styleChip(filterChips[i], filter == i, Cyber.CYAN);
        sortBtn.setText(newestFirst ? "↓ RECENTI" : "↑ VECCHIE");
        gridBtn.setText("▦ " + cols);
        visible.clear();
        List<Session.Shot> src;
        synchronized (Session.shots) { src = new ArrayList<>(phoneTab ? Session.localShots : Session.shots); }
        for (Session.Shot s : src) {
            if (filter == F_RAW && !s.hasRaw()) continue;
            if (filter == F_JPG && s.hasRaw() && (s.isLocal() || (s.jpg == null && s.heif == null))) continue;
            visible.add(s);
        }
        Session.sortShots(visible, newestFirst);
        // remember what the user is looking at, unless they are at the very top
        String anchorKey = null; int anchorTop = 0;
        int fv = list.getFirstVisiblePosition();
        View fvView = list.getChildCount() > 0 ? list.getChildAt(0) : null;
        boolean atTop = fv == 0 && (fvView == null || fvView.getTop() >= list.getPaddingTop() - Cyber.dp(this, 4));
        if (!atTop && fv < rows.size()) {
            for (int i = fv; i < rows.size() && anchorKey == null; i++) if (rows.get(i) instanceof Session.Shot[]) {
                anchorKey = ((Session.Shot[]) rows.get(i))[0].key();
                View cv = list.getChildAt(i - fv);
                anchorTop = cv != null ? cv.getTop() : 0;
            }
        }
        rows.clear();
        rowShot.clear();
        String day = null;
        List<Session.Shot> cur = new ArrayList<>();
        int dayCount = 0;
        int headerIndex = -1;
        for (Session.Shot s : visible) {
            String d = s.dayKey();
            if (!d.equals(day)) {
                flush(cur);
                if (headerIndex >= 0) rows.set(headerIndex, new Header(day, dayCount));
                day = d; dayCount = 0;
                headerIndex = rows.size();
                rows.add(new Header(d, 0));
            }
            dayCount++;
            cur.add(s);
            if (cur.size() == cols) flush(cur);
        }
        flush(cur);
        if (headerIndex >= 0) rows.set(headerIndex, new Header(day, dayCount));
        // row → index of its first picture in `visible` (headers point at the next one)
        int idx = 0;
        for (int i = 0; i < rows.size(); i++) rowShot.add(0);
        for (int i = 0; i < rows.size(); i++) {
            rowShot.set(i, Math.min(idx, Math.max(0, visible.size() - 1)));
            if (rows.get(i) instanceof Session.Shot[]) idx += ((Session.Shot[]) rows.get(i)).length;
        }
        adapter.notifyDataSetChanged();
        if (anchorKey != null) {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i) instanceof Session.Shot[]) {
                boolean hit = false;
                for (Session.Shot x : (Session.Shot[]) rows.get(i)) if (x.key().equals(anchorKey)) { hit = true; break; }
                if (hit) { list.setSelectionFromTop(i, anchorTop); break; }
            }
        }
        if (scroller != null) scroller.invalidate();
        updateStatusIdle();
        if (visible.isEmpty()) showEmpty(); else emptyView.setVisibility(View.GONE);
        emptyView.setClickable(false);
        updateSelBar();
    }

    private NeonScroller scroller;
    private volatile int scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
    private final List<Integer> rowShot = new ArrayList<>();

    private Session.Shot shotAtRow(int row) {
        if (row < 0 || row >= rowShot.size() || visible.isEmpty()) return null;
        return visible.get(Math.min(visible.size() - 1, rowShot.get(row)));
    }

    private final NeonScroller.Model scrollModel = new NeonScroller.Model() {
        @Override public int rowCount() { return rows.size(); }
        @Override public int firstRow() { return list.getFirstVisiblePosition(); }
        @Override public int visibleRows() { return Math.max(1, list.getLastVisiblePosition() - list.getFirstVisiblePosition() + 1); }
        @Override public String dateLabel(int row) {
            Session.Shot s = shotAtRow(row);
            return s == null || s.time == 0 ? "----.--.--" : new SimpleDateFormat("yyyy.MM.dd", Locale.US).format(new Date(s.time));
        }
        @Override public String timeLabel(int row) {
            Session.Shot s = shotAtRow(row);
            if (s == null) return "";
            String day = s.time == 0 ? "" : new SimpleDateFormat("EEE HH:mm", Locale.ITALIAN).format(new Date(s.time)).toUpperCase(Locale.ITALIAN);
            return day + "  " + s.name();
        }
        @Override public String rangeLabel() {
            int a = list.getFirstVisiblePosition(), b = list.getLastVisiblePosition();
            if (rowShot.isEmpty()) return "";
            int from = rowShot.get(Math.max(0, Math.min(rowShot.size() - 1, a))) + 1;
            int to = from;
            for (int i = Math.max(0, a); i <= Math.min(rows.size() - 1, b); i++)
                if (rows.get(i) instanceof Session.Shot[]) to = rowShot.get(i) + ((Session.Shot[]) rows.get(i)).length;
            return "FOTO " + from + "–" + Math.max(from, to) + " / " + visible.size();
        }
        @Override public void scrollToRow(int row) { list.setSelection(Math.max(0, Math.min(rows.size() - 1, row))); }
    };

    private void flush(List<Session.Shot> cur) {
        if (cur.isEmpty()) return;
        rows.add(cur.toArray(new Session.Shot[0]));
        cur.clear();
    }

    private static final class Header {
        final String day; final int n;
        Header(String d, int n) { day = d; this.n = n; }
    }

    private List<Session.Shot> selected() {
        List<Session.Shot> r = new ArrayList<>();
        for (Session.Shot s : visible) if (s.selected) r.add(s);
        return r;
    }

    private void clearSelection() {
        synchronized (Session.shots) { for (Session.Shot s : Session.shots) s.selected = false; }
        synchronized (Session.localShots) { for (Session.Shot s : Session.localShots) s.selected = false; }
        adapter.notifyDataSetChanged();
    }

    private void updateSelBar() {
        int n = selected().size();
        selBar.setVisibility(selecting || n > 0 ? View.VISIBLE : View.GONE);
        selInfo.setText(n + (n == 1 ? " SELEZIONATA" : " SELEZIONATE"));
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return rows.get(i) instanceof Header ? 0 : 1; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            Object o = rows.get(i);
            Context c = GalleryActivity.this;
            if (o instanceof Header) {
                LinearLayout h;
                if (v == null) {
                    h = new LinearLayout(c);
                    h.setOrientation(LinearLayout.VERTICAL);
                    h.setPadding(Cyber.dp(c, 4), Cyber.dp(c, 14), Cyber.dp(c, 4), Cyber.dp(c, 6));
                    LinearLayout line = new LinearLayout(c);
                    line.setGravity(Gravity.CENTER_VERTICAL);
                    line.addView(Cyber.label(c, "", 12, Cyber.CYAN));
                    line.addView(Neon.spacer(c));
                    line.addView(Neon.caption(c, "", 9));
                    h.addView(line);
                    View r = Neon.rule(c);
                    ((LinearLayout.LayoutParams) r.getLayoutParams()).topMargin = Cyber.dp(c, 4);
                    h.addView(r);
                } else h = (LinearLayout) v;
                Header hd = (Header) o;
                LinearLayout line = (LinearLayout) h.getChildAt(0);
                ((TextView) line.getChildAt(0)).setText(dayLabel(hd.day));
                ((TextView) line.getChildAt(2)).setText(hd.n + " SCATTI");
                return h;
            }
            Session.Shot[] cells = (Session.Shot[]) o;
            LinearLayout row;
            int gap = Cyber.dp(c, 3);
            int size = (list.getWidth() - list.getPaddingLeft() - list.getPaddingRight() - gap * (cols - 1)) / Math.max(1, cols);
            if (size <= 0) size = Cyber.dp(c, 110);
            if (v == null || ((LinearLayout) v).getChildCount() != cols) {
                row = new LinearLayout(c);
                row.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
                row.setPadding(0, 0, 0, gap);
                for (int k = 0; k < cols; k++) row.addView(tile(c), new LinearLayout.LayoutParams(size, size));
                for (int k = 1; k < cols; k++) ((LinearLayout.LayoutParams) row.getChildAt(k).getLayoutParams()).leftMargin = gap;
            } else row = (LinearLayout) v;
            for (int k = 0; k < cols; k++) {
                FrameLayout t = (FrameLayout) row.getChildAt(k);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
                if (lp.width != size) { lp.width = size; lp.height = size; t.setLayoutParams(lp); }
                if (k >= cells.length) { t.setVisibility(View.INVISIBLE); continue; }
                t.setVisibility(View.VISIBLE);
                bindTile(t, cells[k]);
            }
            return row;
        }
    }

    private String dayLabel(String d) {
        String today = new SimpleDateFormat("yyyy.MM.dd", Locale.US).format(new Date());
        String yest = new SimpleDateFormat("yyyy.MM.dd", Locale.US).format(new Date(System.currentTimeMillis() - 86400000L));
        if (d.equals(today)) return d + " // OGGI";
        if (d.equals(yest)) return d + " // IERI";
        return d.startsWith("-") ? "DATA SCONOSCIUTA" : d;
    }

    private FrameLayout tile(Context c) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable(); g.setColor(0xFF0B0B14); g.setStroke(Cyber.dp(c, 1), Cyber.LINE);
        f.setBackground(g);
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        TextView badge = Cyber.label(c, "", 7, Cyber.CYAN);
        badge.setBackgroundColor(0xCC07070C);
        badge.setPadding(Cyber.dp(c, 4), Cyber.dp(c, 2), Cyber.dp(c, 4), Cyber.dp(c, 2));
        f.addView(badge, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        TextView time = Cyber.label(c, "", 7, Cyber.TEXT);
        time.setShadowLayer(Cyber.dp(c, 3), 0, 0, 0xFF000000);
        time.setPadding(Cyber.dp(c, 4), 0, 0, Cyber.dp(c, 3));
        f.addView(time, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        TextView check = Cyber.label(c, "✓", 14, Cyber.MAGENTA);
        check.setGravity(Gravity.CENTER);
        f.addView(check, new FrameLayout.LayoutParams(Cyber.dp(c, 26), Cyber.dp(c, 26), Gravity.TOP | Gravity.END));
        return f;
    }

    private void bindTile(FrameLayout t, Session.Shot s) {
        ImageView iv = (ImageView) t.getChildAt(0);
        TextView badge = (TextView) t.getChildAt(1), time = (TextView) t.getChildAt(2), check = (TextView) t.getChildAt(3);
        Bitmap bm = Session.thumbs.get(s.key());
        iv.setTag(s.key());
        iv.setImageBitmap(bm);
        if (bm == null && !Session.thumbGaveUp(s)) requestThumb(s);
        String b = s.badge();
        badge.setText(b);
        badge.setTextColor(b.startsWith("RAW") ? Cyber.MAGENTA : b.equals("PHONE") ? Cyber.YELLOW : Cyber.CYAN);
        time.setText(s.time == 0 ? s.name() : new SimpleDateFormat("HH:mm", Locale.US).format(new Date(s.time)) + "  " + s.name());
        check.setVisibility(s.selected ? View.VISIBLE : View.GONE);
        GradientDrawable g = new GradientDrawable(); g.setColor(0xFF0B0B14);
        g.setStroke(Cyber.dp(this, s.selected ? 2 : 1), s.selected ? Cyber.MAGENTA : Cyber.LINE);
        t.setBackground(g);
        iv.setAlpha(s.selected ? 0.75f : 1f);
        t.setOnClickListener(v -> {
            if (selecting) { s.selected = !s.selected; adapter.notifyDataSetChanged(); updateSelBar(); return; }
            openViewer(s);
        });
        t.setOnLongClickListener(v -> { selecting = true; s.selected = true; adapter.notifyDataSetChanged(); updateSelBar(); return true; });
    }

    private void openViewer(Session.Shot s) {
        ViewerActivity.list = new ArrayList<>(visible);
        startActivity(new Intent(this, ViewerActivity.class).putExtra("index", visible.indexOf(s)));
    }

    // ------------------------------------------------------------------ thumbnails

    private void requestThumb(Session.Shot s) {
        synchronized (queued) {
            if (queued.contains(s.key())) { thumbQueue.remove(s); thumbQueue.addFirst(s); return; }
            queued.add(s.key());
            thumbQueue.addFirst(s);
            // keep only what is (or was just) on screen: drop the oldest requests
            while (thumbQueue.size() > 80) { Session.Shot old = thumbQueue.pollLast(); if (old != null) queued.remove(old.key()); }
        }
    }

    /** Put a freshly loaded thumbnail into the tiles currently showing it — no full list refresh. */
    private void showThumb(String key, Bitmap b) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (!(row instanceof LinearLayout) || ((LinearLayout) row).getOrientation() != LinearLayout.HORIZONTAL) continue;
            LinearLayout r = (LinearLayout) row;
            for (int k = 0; k < r.getChildCount(); k++) {
                View t = r.getChildAt(k);
                if (!(t instanceof FrameLayout)) continue;
                ImageView iv = (ImageView) ((FrameLayout) t).getChildAt(0);
                if (key.equals(iv.getTag())) iv.setImageBitmap(b);
            }
        }
    }

    private long lastThumbRefresh;

    private void startThumbWorker() {
        thumbWorker = new Thread(() -> {
            while (alive) {
                try {
                    Session.Shot s = thumbQueue.takeFirst();
                    synchronized (queued) { queued.remove(s.key()); }
                    Bitmap b = Session.thumbs.get(s.key());
                    if (b == null) b = Session.thumbnail(this, s);
                    final Bitmap fb = b;
                    final String key = s.key();
                    if (fb != null) ui.post(() -> showThumb(key, fb));
                    else {
                        String err = Session.lastThumbError;
                        long now = System.currentTimeMillis();
                        if (err != null && now - lastThumbRefresh > 4000 && !linking) { lastThumbRefresh = now; setStatus("⚠ MINIATURE: " + err); }
                    }
                } catch (InterruptedException e) { return; } catch (Exception ignored) {}
            }
        }, "thumbs");
        thumbWorker.setDaemon(true);
        thumbWorker.start();
    }

    private final Runnable keepAlive = new Runnable() {
        @Override public void run() {
            CanonClient c = Session.client;
            if (c != null && !linking && thumbQueue.isEmpty()) Session.camIo.execute(c::keepAlive);
            if (alive) ui.postDelayed(this, 8000);
        }
    };

    // ------------------------------------------------------------------ link

    private Network wifiNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    /** Camera's own access point → the camera is the Wi-Fi gateway. */
    private String gatewayIp() {
        try {
            Network n = wifiNetwork();
            LinkProperties lp = n != null ? getSystemService(ConnectivityManager.class).getLinkProperties(n) : null;
            if (lp != null) for (RouteInfo r : lp.getRoutes())
                if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) return r.getGateway().getHostAddress();
        } catch (Exception ignored) {}
        return "192.168.1.2";
    }

    private void showLinkDialog() {
        if (Session.client != null) {
            Session.disconnect();
            synchronized (Session.shots) { Session.shots.clear(); }
            Session.thumbs.evictAll();
            rebuild();
            return;
        }
        Object[] dv = Neon.dialog(this, "EOS // LINK", true);
        Dialog d = (Dialog) dv[0];
        LinearLayout root = (LinearLayout) dv[1];
        int pad = pad();
        // ---- Bluetooth (Camera Connect style): pair, wake the camera, start its Wi-Fi, auto-join
        root.addView(Cyber.label(this, "◉ BLUETOOTH", 11, Cyber.MAGENTA));
        root.addView(Neon.caption(this,
            "Come Camera Connect. Sulla R6 II apri il menu della connessione allo smartphone, scegli di aggiungere un dispositivo Android e lascia la fotocamera in attesa. " +
            "AmpyLink associa la fotocamera via Bluetooth, le fa accendere il Wi-Fi e si collega da sola (Android chiede conferma per la rete EOS).", 9));
        String btMac = sp.getString("ble_mac", null), btName = sp.getString("ble_name", "EOS");
        if (btMac != null) {
            TextView again = Cyber.button(this, "▶ RICOLLEGA " + btName + " VIA BLUETOOTH", Cyber.MAGENTA);
            again.setOnClickListener(v -> {
                if (!PrintSheet.ensureBt(this)) return;
                d.dismiss();
                try { bleLink(KodakBt.adapter(this).getRemoteDevice(btMac), btName); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
            });
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
            alp.topMargin = pad / 2;
            root.addView(again, alp);
        }
        TextView auto = Neon.chip(this, "AUTO-COLLEGA ALL'AVVIO (ANCHE A FOTOCAMERA SPENTA)", Cyber.YELLOW);
        Neon.styleChip(auto, sp.getBoolean("ble_auto", true), Cyber.YELLOW);
        auto.setOnClickListener(v -> { boolean on = !sp.getBoolean("ble_auto", true); sp.edit().putBoolean("ble_auto", on).apply(); Neon.styleChip(auto, on, Cyber.YELLOW); });
        LinearLayout.LayoutParams aulp = new LinearLayout.LayoutParams(-1, -2);
        aulp.topMargin = pad / 2;
        root.addView(auto, aulp);
        TextView svcChip = Neon.chip(this, "SERVIZIO IN BACKGROUND (COME CAMERA CONNECT)", Cyber.YELLOW);
        Neon.styleChip(svcChip, CameraLinkService.enabled(this), Cyber.YELLOW);
        svcChip.setOnClickListener(v -> {
            boolean on = !CameraLinkService.enabled(this);
            if (on) {
                if (!PrintSheet.ensureBt(this)) return;
                if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 77);
                if (sp.getString("ble_mac", null) == null) { Toast.makeText(this, "Prima collega la fotocamera via Bluetooth una volta", Toast.LENGTH_LONG).show(); return; }
            }
            sp.edit().putBoolean("svc_on", on).apply();
            Neon.styleChip(svcChip, on, Cyber.YELLOW);
            if (on) CameraLinkService.start(this); else CameraLinkService.stop(this);
            Toast.makeText(this, on ? "Servizio attivo: la fotocamera resta collegata via Bluetooth anche ad app chiusa" : "Servizio fermato", Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, -2);
        svlp.topMargin = pad / 3;
        root.addView(svcChip, svlp);
        root.addView(Neon.caption(this, "⚠ Se sul telefono c'è Canon Camera Connect, chiudilo (Impostazioni › App › Camera Connect › Forza arresto): tiene occupata la fotocamera via Bluetooth e AmpyLink non riesce a collegarsi. " +
            "Sulla R6 II lascia attivo il Bluetooth (Impostazioni wireless › Bluetooth) così si può svegliare anche da spenta.", 8));
        TextView bleScan = Cyber.button(this, "CERCA FOTOCAMERA VIA BLUETOOTH", Cyber.CYAN);
        bleScan.setOnClickListener(v -> { if (PrintSheet.ensureBt(this)) { d.dismiss(); bleScanDialog(); } });
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(-1, -2);
        blp2.topMargin = pad / 2;
        root.addView(bleScan, blp2);
        View sepr = Neon.rule(this);
        ((LinearLayout.LayoutParams) sepr.getLayoutParams()).topMargin = pad;
        ((LinearLayout.LayoutParams) sepr.getLayoutParams()).bottomMargin = pad / 2;
        root.addView(sepr);
        root.addView(Cyber.label(this, "◉ WI-FI", 11, Cyber.CYAN));
        root.addView(Neon.caption(this,
            "EOS R6 Mark II, come Canon Camera Connect:\n" +
            "1 · Sulla fotocamera: MENU › Rete › Connetti a smartphone (Wi-Fi). Se chiede l'app, scegli connessione Wi-Fi senza Bluetooth.\n" +
            "2 · Collega il telefono alla rete Wi-Fi della fotocamera (EOSR6M2-…) oppure alla stessa rete.\n" +
            "3 · Tocca COLLEGA e conferma «" + Session.friendlyName() + "» sul display della fotocamera.\n" +
            "CCAPI: se l'hai attivato, abilitalo nel menu Rete e scegli CCAPI.", 9));
        final int[] mode = {sp.getInt("mode", 0)}; // 0 auto, 1 ptp, 2 ccapi
        LinearLayout modes = new LinearLayout(this);
        modes.setPadding(0, pad, 0, 0);
        String[] mn = {"AUTO", "PTP/IP", "CCAPI"};
        TextView[] mc = new TextView[3];
        for (int i = 0; i < 3; i++) {
            mc[i] = Neon.chip(this, mn[i], Cyber.CYAN);
            final int k = i;
            mc[i].setOnClickListener(v -> { mode[0] = k; for (int j = 0; j < 3; j++) Neon.styleChip(mc[j], j == k, Cyber.CYAN); });
            modes.addView(mc[i], Neon.wrap(i == 0 ? 0 : pad / 3));
            Neon.styleChip(mc[i], i == mode[0], Cyber.CYAN);
        }
        root.addView(modes);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, pad, 0, 0);
        EditText ip = new EditText(this);
        ip.setTypeface(Cyber.MONO);
        ip.setTextColor(Cyber.TEXT);
        ip.setHintTextColor(Cyber.DIM);
        ip.setHint("IP FOTOCAMERA");
        ip.setSingleLine();
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        GradientDrawable g = new GradientDrawable(); g.setStroke(Cyber.dp(this, 1), Cyber.LINE); g.setColor(0xFF0B0B14);
        ip.setBackground(g);
        ip.setPadding(pad, pad, pad, pad);
        String last = sp.getString("ip", null);
        ip.setText(last != null ? last : gatewayIp());
        row.addView(ip, new LinearLayout.LayoutParams(0, -2, 1));
        TextView scan = Cyber.button(this, "CERCA", Cyber.CYAN);
        row.addView(scan, Neon.wrap(pad / 2));
        root.addView(row);
        LinearLayout found = new LinearLayout(this);
        found.setOrientation(LinearLayout.VERTICAL);
        root.addView(found);
        TextView go = Cyber.button(this, "▶ COLLEGA", Cyber.MAGENTA);
        go.setTextSize(14);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = pad;
        root.addView(go, glp);
        root.addView(Neon.caption(this, "Nome telefono per l'associazione: " + Session.friendlyName() +
            "\nDopo un riavvio: sulla fotocamera riapri la connessione allo smartphone e scegli il telefono già registrato, poi tocca COLLEGA.", 8));
        TextView logBtn = Cyber.button(this, "LOG CONNESSIONE", Cyber.YELLOW);
        logBtn.setOnClickListener(v -> showLog());
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = pad / 2;
        root.addView(logBtn, llp);
        TextView reset = Cyber.button(this, "RESET ASSOCIAZIONE", Cyber.DIM);
        reset.setTextColor(Cyber.TEXT);
        reset.setOnClickListener(v -> {
            getSharedPreferences("eos", MODE_PRIVATE).edit().remove("guid").remove("ble_paired").apply();
            Toast.makeText(this, "Nuova identità creata: elimina il telefono dalla fotocamera e ricollega (ti chiederà conferma)", Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = pad / 2;
        root.addView(reset, rlp);
        scan.setOnClickListener(v -> {
            found.removeAllViews();
            TextView s = Neon.caption(this, "RICERCA UPnP/SSDP…", 9);
            s.setPadding(0, pad / 2, 0, 0);
            found.addView(s);
            new Thread(() -> {
                List<Discovery.Found> fl = Discovery.scan(this, wifiNetwork(), 3500, ip.getText().toString());
                ui.post(() -> {
                    found.removeAllViews();
                    if (fl.isEmpty()) { TextView t = Neon.caption(this, "Nessuna risposta dalla fotocamera. Controlla che sul display sia attiva la connessione allo smartphone (in attesa), poi tocca COLLEGA con l'IP " + ip.getText() + " e modalità PTP/IP.", 9); t.setPadding(0, pad / 2, 0, 0); found.addView(t); }
                    for (Discovery.Found f : fl) {
                        TextView t = Cyber.button(this, "▣ " + f.label(), Cyber.MAGENTA);
                        t.setGravity(Gravity.START);
                        t.setOnClickListener(x -> {
                            ip.setText(f.ip);
                            mode[0] = f.ccapi && !f.ptpip ? 2 : 1;
                            for (int j = 0; j < 3; j++) Neon.styleChip(mc[j], j == mode[0], Cyber.CYAN);
                        });
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                        lp.topMargin = pad / 2;
                        found.addView(t, lp);
                    }
                });
            }).start();
        });
        go.setOnClickListener(v -> {
            String host = ip.getText().toString().trim();
            sp.edit().putString("ip", host).putInt("mode", mode[0]).apply();
            d.dismiss();
            link(host, mode[0]);
        });
        d.show();
    }

    // ------------------------------------------------------------------ Bluetooth link

    private void bleScanDialog() {
        Object[] dv = Neon.dialog(this, "EOS // BLUETOOTH", true);
        Dialog d = (Dialog) dv[0];
        LinearLayout root = (LinearLayout) dv[1];
        int pad = pad();
        TextView state = Neon.caption(this, "RICERCA FOTOCAMERE CANON… (fotocamera in attesa di associazione)", 9);
        root.addView(state);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows);
        final java.util.Set<String> seen = new java.util.HashSet<>();
        final Runnable[] stop = {null};
        // cameras already paired with this phone (e.g. by Camera Connect) are listed right away
        try {
            for (android.bluetooth.BluetoothDevice dev : KodakBt.adapter(this).getBondedDevices()) {
                String n = dev.getName();
                if (n == null) continue;
                String u = n.toUpperCase(Locale.US);
                if (!(u.startsWith("EOS") || u.contains("CANON") || u.contains("R6"))) continue;
                if (!seen.add(dev.getAddress())) continue;
                TextView t = Cyber.button(this, "▣ " + n + "\n" + dev.getAddress() + " · GIÀ ASSOCIATA AL TELEFONO", Cyber.MAGENTA);
                t.setGravity(Gravity.START);
                t.setOnClickListener(v -> { if (stop[0] != null) stop[0].run(); d.dismiss(); bleLink(dev, n); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = pad / 2;
                rows.addView(t, lp);
                state.setText("TOCCA LA FOTOCAMERA PER COLLEGARLA");
            }
        } catch (SecurityException ignored) {}
        stop[0] = CanonBle.scan(this, (dev, name, rssi) -> ui.post(() -> {
            if (!seen.add(dev.getAddress())) return;
            TextView t = Cyber.button(this, "▣ " + name + "\n" + dev.getAddress() + " · " + rssi + " dBm", Cyber.MAGENTA);
            t.setGravity(Gravity.START);
            t.setOnClickListener(v -> { if (stop[0] != null) stop[0].run(); d.dismiss(); bleLink(dev, name); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = pad / 2;
            rows.addView(t, lp);
            state.setText("TOCCA LA FOTOCAMERA PER COLLEGARLA");
        }));
        ui.postDelayed(() -> { if (seen.isEmpty() && d.isShowing()) state.setText("Nessuna fotocamera trovata: sulla R6 II avvia l'associazione con lo smartphone (Bluetooth attivo) e resta su quella schermata."); }, 12000);
        d.setOnDismissListener(x -> { if (stop[0] != null) stop[0].run(); });
        d.show();
    }

    /** BLE pairing/handshake → camera starts Wi-Fi AP → Android joins it → PTP/IP over that network. */
    private void bleLink(android.bluetooth.BluetoothDevice dev, String name) {
        if (linking) return;
        linking = true;
        phoneTab = false;
        linkBtn.setText("…");
        rebuild();
        final boolean first = !dev.getAddress().equals(sp.getString("ble_paired", null));
        new Thread(() -> {
            try {
                CanonBle.Ap ap;
                CanonBle svc = CameraLinkService.linked(dev.getAddress());
                if (svc == null && CameraLinkService.enabled(this) && dev.getAddress().equals(sp.getString("ble_mac", null))) {
                    // the background service owns the Bluetooth link: wait for it instead of competing
                    long until = System.currentTimeMillis() + 75000;
                    while ((svc = CameraLinkService.linked(dev.getAddress())) == null && System.currentTimeMillis() < until) {
                        setStatus("◌ SERVIZIO: " + CameraLinkService.state);
                        Thread.sleep(1000);
                    }
                }
                if (svc != null) {
                    setStatus("◌ BLUETOOTH GIÀ COLLEGATO (SERVIZIO) · RICHIESTA WI-FI…");
                    ap = svc.requestWifi(s -> setStatus("◌ " + s));
                } else {
                    CanonBle ble = new CanonBle(this, dev);
                    ap = ble.connectAndStartWifi(first, Session.guid(this), Session.friendlyName(), s -> setStatus("◌ " + s));
                    Session.ble = ble;
                }
                sp.edit().putString("ble_mac", dev.getAddress()).putString("ble_name", name).putString("ble_paired", dev.getAddress())
                    .putString("ip", ap.ip).putInt("mode", 1).apply();
                setStatus("◌ WI-FI " + ap.ssid + " · CONFERMA LA RETE NEL POPUP DI ANDROID…");
                ui.post(() -> joinAndLink(ap, false));
            } catch (Exception e) {
                Session.disconnect();
                linking = false;
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                Session.log("✕ " + msg);
                ui.post(() -> { rebuild(); statusLine.setText("✕ BLUETOOTH: " + msg); statusLine.setTextColor(Cyber.MAGENTA); });
            }
        }, "ble-link").start();
    }

    private void joinAndLink(CanonBle.Ap ap, boolean wpa3) {
        Session.netCb = CanonBle.joinWifi(this, ap, wpa3, (net, err) -> {
            if (net != null) {
                setStatus("◌ WI-FI " + ap.ssid + " OK · PTP/IP " + ap.ip + "…");
                ui.post(() -> { linking = false; link(ap.ip, 1, net); });
            } else if (!wpa3 && ap.security.contains(9)) {
                ui.post(() -> joinAndLink(ap, true));
            } else {
                ui.post(() -> {
                    linking = false;
                    rebuild();
                    statusLine.setText("✕ " + err + " · in alternativa collegati a mano a " + ap.ssid + " (password " + ap.password + ") e usa COLLEGA");
                    statusLine.setTextColor(Cyber.MAGENTA);
                });
            }
        });
    }

    private static boolean portOpen(Network n, String host, int port) {
        try (Socket s = n != null ? n.getSocketFactory().createSocket() : new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) { return false; }
    }

    private void link(String host, int mode) { link(host, mode, null); }

    private void link(String host, int mode, Network forced) {
        if (linking) return;
        linking = true;
        phoneTab = false;
        linkBtn.setText("…");
        setStatus("◌ LINK " + host + " …");
        rebuild();
        Session.camIo.execute(() -> {
            try {
                Network n = forced != null ? forced : wifiNetwork();
                int m = mode;
                String h = host;
                if (m == 0) {
                    setStatus("◌ RICERCA FOTOCAMERA (UPnP)…");
                    List<Discovery.Found> fl = Discovery.scan(this, n, 2500, h);
                    for (Discovery.Found f : fl) if (f.ip.equals(h) || fl.size() == 1) { h = f.ip; m = f.ccapi && !f.ptpip ? 2 : 1; break; }
                    if (m == 0) m = 1; // default PTP/IP (Camera Connect); no port probing, it upsets the camera
                }
                CanonClient c;
                if (m == 2) c = new CanonClient.Ccapi(h, n);
                else {
                    CanonClient.PtpIp p = new CanonClient.PtpIp(h, n, Session.guid(this), Session.friendlyName());
                    p.onAwaitingPairing = () -> setStatus("◌ FOTOCAMERA OCCUPATA, RIPROVO… (se compare una richiesta sul display, accettala)");
                    c = p;
                }
                setStatus("◌ " + c.mode() + " · " + h + " · HANDSHAKE…");
                // right after a Bluetooth handover the camera AP / PTP/IP server may need a few seconds
                long until = System.currentTimeMillis() + (forced != null ? 20000 : 0);
                while (true) {
                    try { c.connect(); break; }
                    catch (java.io.IOException e) {
                        if (System.currentTimeMillis() > until || (e.getMessage() != null && e.getMessage().startsWith("Associazione"))) throw e;
                        setStatus("◌ ATTESA WI-FI FOTOCAMERA…");
                        Thread.sleep(2000);
                        if (m == 2) c = new CanonClient.Ccapi(h, n);
                        else { CanonClient.PtpIp p = new CanonClient.PtpIp(h, n, Session.guid(this), Session.friendlyName()); p.onAwaitingPairing = ((CanonClient.PtpIp) c).onAwaitingPairing; c = p; }
                    }
                }
                final CanonClient cc = c;
                Session.client = cc;
                setStatus("◉ " + cc.cameraName + " · LETTURA SCHEDA…");
                synchronized (scanItems) { scanItems.clear(); }
                cc.onItem = it -> { synchronized (scanItems) { scanItems.add(it); } scheduleRegroup(); };
                List<CanonClient.Item> items = cc.list((done, tot) -> setStatus("◉ " + cc.cameraName + " · LETTURA SCHEDA · " + done + " FILE"));
                cc.onItem = null;
                List<Session.Shot> shots = Session.group(items);
                applyShots(shots);
                linking = false;
                ui.post(this::rebuild);
                if (cc instanceof CanonClient.Ccapi) fillCcapiDates((CanonClient.Ccapi) cc, items);
            } catch (Exception e) {
                Session.disconnect();
                linking = false;
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                Session.log("✕ " + msg);
                ui.post(() -> { rebuild(); statusLine.setText("✕ LINK FALLITO: " + msg); statusLine.setTextColor(Cyber.MAGENTA); });
            }
        });
    }

    // progressive card scan: files show up while the camera is still being read
    private final List<CanonClient.Item> scanItems = new ArrayList<>();
    private volatile boolean regroupPending;

    private void scheduleRegroup() {
        if (regroupPending) return;
        regroupPending = true;
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE || (scroller != null && scroller.isDragging())) { ui.postDelayed(this, 400); return; }
            regroupPending = false;
            List<CanonClient.Item> snap;
            synchronized (scanItems) { snap = new ArrayList<>(scanItems); }
            applyShots(Session.group(snap));
            rebuild();
            if (emptyView.getVisibility() == View.VISIBLE && !visible.isEmpty()) emptyView.setVisibility(View.GONE);
        } }, 600);
    }

    /** Replace the shot list, keeping EXIF / selection / dates of pictures we already know. */
    private void applyShots(List<Session.Shot> shots) {
        synchronized (Session.shots) {
            java.util.Map<String, Session.Shot> old = new java.util.HashMap<>();
            for (Session.Shot o : Session.shots) old.put(o.key(), o);
            for (Session.Shot n : shots) {
                Session.Shot o = old.get(n.key());
                if (o != null) { n.exif = o.exif; n.selected = o.selected; if (n.time == 0) n.time = o.time; }
            }
            Session.shots.clear();
            Session.shots.addAll(shots);
        }
    }

    private void relist() {
        CanonClient c = Session.client;
        if (c == null || linking) return;
        linking = true;
        Session.camIo.execute(() -> {
            try {
                List<CanonClient.Item> items = c.list((done, tot) -> setStatus("◉ AGGIORNAMENTO · " + done + " FILE"));
                List<Session.Shot> shots = Session.group(items);
                synchronized (Session.shots) {
                    // keep loaded EXIF of known shots
                    for (Session.Shot n : shots) for (Session.Shot o : Session.shots) if (o.key().equals(n.key())) { n.exif = o.exif; if (n.time == 0) n.time = o.time; break; }
                    Session.shots.clear(); Session.shots.addAll(shots);
                }
                if (c instanceof CanonClient.Ccapi) fillCcapiDates((CanonClient.Ccapi) c, items);
            } catch (Exception e) {
                setStatus("✕ " + e.getMessage());
            } finally { linking = false; ui.post(this::rebuild); }
        });
    }

    /** CCAPI lists names only: fetch dates in the background, regroup once. */
    private void fillCcapiDates(CanonClient.Ccapi c, List<CanonClient.Item> items) {
        Session.thumbIo.execute(() -> {
            int k = 0;
            for (CanonClient.Item it : items) {
                if (!alive || Session.client != c) return;
                if (it.time == 0) c.info(it);
                if (++k % 25 == 0) setStatus("◉ " + c.cameraName + " · DATE " + k + "/" + items.size());
            }
            List<Session.Shot> shots = Session.group(items);
            synchronized (Session.shots) { Session.shots.clear(); Session.shots.addAll(shots); }
            ui.post(this::rebuild);
        });
    }

    // ------------------------------------------------------------------ local + actions

    private void pickLocal() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/octet-stream"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_LOCAL);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_LOCAL || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        for (Uri u : uris) {
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            boolean dup = false;
            synchronized (Session.localShots) { for (Session.Shot s : Session.localShots) dup |= s.local.equals(u); }
            if (!dup) Session.localShots.add(0, Session.local(getContentResolver(), u));
        }
        phoneTab = true;
        rebuild();
    }

    private void printSelectionOrHint() {
        List<Session.Shot> s = selected();
        if (!s.isEmpty()) { printShots(s); return; }
        Toast.makeText(this, "Apri una foto o selezionane alcune, poi STAMPA", Toast.LENGTH_SHORT).show();
    }

    void printShots(List<Session.Shot> shots) {
        if (shots.isEmpty()) { Toast.makeText(this, "Nessuna foto selezionata", Toast.LENGTH_SHORT).show(); return; }
        final List<Session.Shot> list = new ArrayList<>(shots);
        final Context ctx = getApplicationContext();
        PrintSheet.show(this, new PrintSheet.Source() {
            @Override public int count() { return list.size(); }
            @Override public Bitmap load(int i) throws Exception { Session.exif(ctx, list.get(i)); return Session.preview(ctx, list.get(i), 2048, null); }
            @Override public Frames.Meta meta(int i) { return Frames.Meta.of(list.get(i).exif, list.get(i).name()); }
        }, null);
    }

    private void saveShots(List<Session.Shot> shots) {
        if (shots.isEmpty()) return;
        final Context ctx = getApplicationContext();
        Toast.makeText(this, "SALVATAGGIO " + shots.size() + " FOTO…", Toast.LENGTH_SHORT).show();
        Session.camIo.execute(() -> {
            int ok = 0;
            for (Session.Shot s : shots) {
                if (s.isLocal()) continue;
                for (CanonClient.Item it : new CanonClient.Item[]{s.jpg, s.heif, s.raw}) {
                    if (it == null) continue;
                    try {
                        final int done = ok;
                        File f = Session.download(ctx, s, it, (d, t) -> setStatus("◉ SALVATAGGIO " + it.name + "  " + (t > 0 ? 100 * d / t + "%" : (d >> 10) + " KB") + " · " + done + " OK"));
                        saveToPhone(ctx, f);
                        ok++;
                    } catch (Exception e) { setStatus("✕ " + it.name + ": " + e.getMessage()); }
                }
            }
            final int n = ok;
            ui.post(() -> { Toast.makeText(this, n + " FILE SALVATI › DCIM/AmpyLink", Toast.LENGTH_LONG).show(); updateStatusIdle(); });
        });
    }

    /** JPG/HEIF → DCIM/AmpyLink (gallery), RAW → Download/AmpyLink (any mime accepted). */
    static Uri saveToPhone(Context ctx, File f) throws Exception {
        String mime = FileShare.mime(f.getName());
        boolean raw = !mime.equals("image/jpeg") && !mime.equals("image/heif");
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, raw ? "Download/AmpyLink" : "DCIM/AmpyLink");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri coll = raw ? MediaStore.Downloads.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri u = ctx.getContentResolver().insert(coll, v);
        if (u == null) throw new Exception("MediaStore refused " + f.getName());
        try (InputStream in = new FileInputStream(f); OutputStream o = ctx.getContentResolver().openOutputStream(u)) {
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        v.clear(); v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        ctx.getContentResolver().update(u, v, null, null);
        return u;
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i != null && i.getBooleanExtra("wifi", false)) ui.postDelayed(this::autoConnect, 300);
    }

    @Override protected void onResume() { super.onResume(); if (adapter != null) rebuild(); }

    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); rebuild(); }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == PrintSheet.REQ_BT) Toast.makeText(this, "Permesso Bluetooth aggiornato: tocca di nuovo STAMPA", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        alive = false;
        ui.removeCallbacksAndMessages(null);
        if (thumbWorker != null) thumbWorker.interrupt();
        if (isFinishing()) Session.disconnect();
    }
}
