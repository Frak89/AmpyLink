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
        sp = getSharedPreferences("eos", MODE_PRIVATE);
        cols = sp.getInt("cols", 3);
        buildUi();
        startThumbWorker();
        ui.postDelayed(keepAlive, 8000);
        rebuild();
        if (Session.client == null && Session.shots.isEmpty()) showEmpty();
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

    private void setStatus(String s) { ui.post(() -> statusLine.setText(s)); }

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
        rows.clear();
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
        adapter.notifyDataSetChanged();
        updateStatusIdle();
        if (visible.isEmpty()) showEmpty(); else emptyView.setVisibility(View.GONE);
        updateSelBar();
    }

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
        iv.setImageBitmap(bm);
        if (bm == null) requestThumb(s);
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
        }
        thumbQueue.addFirst(s);
    }

    private long lastThumbRefresh;

    private void startThumbWorker() {
        thumbWorker = new Thread(() -> {
            while (alive) {
                try {
                    Session.Shot s = thumbQueue.takeFirst();
                    if (Session.thumbs.get(s.key()) == null) Session.thumbnail(this, s);
                    synchronized (queued) { queued.remove(s.key()); }
                    long now = System.currentTimeMillis();
                    if (now - lastThumbRefresh > 120 || thumbQueue.isEmpty()) { lastThumbRefresh = now; ui.post(adapter::notifyDataSetChanged); }
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
        Object[] dv = Neon.dialog(this, "EOS // WIFI LINK", true);
        Dialog d = (Dialog) dv[0];
        LinearLayout root = (LinearLayout) dv[1];
        int pad = pad();
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
        root.addView(Neon.caption(this, "Nome telefono per l'associazione: " + Session.friendlyName(), 8));
        scan.setOnClickListener(v -> {
            found.removeAllViews();
            TextView s = Neon.caption(this, "RICERCA UPnP/SSDP…", 9);
            s.setPadding(0, pad / 2, 0, 0);
            found.addView(s);
            new Thread(() -> {
                List<Discovery.Found> fl = Discovery.scan(this, wifiNetwork(), 3500);
                ui.post(() -> {
                    found.removeAllViews();
                    if (fl.isEmpty()) { TextView t = Neon.caption(this, "Nessuna Canon trovata: verifica il Wi-Fi o inserisci l'IP.", 9); t.setPadding(0, pad / 2, 0, 0); found.addView(t); }
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

    private static boolean portOpen(Network n, String host, int port) {
        try (Socket s = n != null ? n.getSocketFactory().createSocket() : new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) { return false; }
    }

    private void link(String host, int mode) {
        if (linking) return;
        linking = true;
        phoneTab = false;
        linkBtn.setText("…");
        setStatus("◌ LINK " + host + " …");
        rebuild();
        Session.camIo.execute(() -> {
            try {
                Network n = wifiNetwork();
                int m = mode;
                String h = host;
                if (m == 0) {
                    setStatus("◌ RICERCA FOTOCAMERA (UPnP)…");
                    List<Discovery.Found> fl = Discovery.scan(this, n, 2500);
                    for (Discovery.Found f : fl) if (f.ip.equals(h) || fl.size() == 1) { h = f.ip; m = f.ccapi && !f.ptpip ? 2 : 1; break; }
                    if (m == 0) m = portOpen(n, h, 15740) ? 1 : portOpen(n, h, 8080) ? 2 : 1;
                }
                CanonClient c;
                if (m == 2) c = new CanonClient.Ccapi(h, n);
                else {
                    CanonClient.PtpIp p = new CanonClient.PtpIp(h, n, Session.guid(this), Session.friendlyName());
                    p.onAwaitingPairing = () -> setStatus("◌ CONFERMA «" + Session.friendlyName() + "» SUL DISPLAY DELLA FOTOCAMERA…");
                    c = p;
                }
                setStatus("◌ " + c.mode() + " · " + h + " · HANDSHAKE…");
                c.connect();
                Session.client = c;
                setStatus("◉ " + c.cameraName + " · LETTURA SCHEDA…");
                List<CanonClient.Item> items = c.list((done, tot) -> setStatus("◉ " + c.cameraName + " · LETTURA SCHEDA · " + done + " FILE"));
                List<Session.Shot> shots = Session.group(items);
                synchronized (Session.shots) { Session.shots.clear(); Session.shots.addAll(shots); }
                linking = false;
                ui.post(this::rebuild);
                if (c instanceof CanonClient.Ccapi) fillCcapiDates((CanonClient.Ccapi) c, items);
            } catch (Exception e) {
                Session.disconnect();
                linking = false;
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                ui.post(() -> { rebuild(); statusLine.setText("✕ LINK FALLITO: " + msg); statusLine.setTextColor(Cyber.MAGENTA); });
            }
        });
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
