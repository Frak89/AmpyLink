/*
 * PRINT // KODAK MINI 2 RETRO — frame picker, drag-to-reframe preview, copies, printer picker,
 * direct Bluetooth printing (KodakBt) with live 4PASS progress, and hand-off to the KODAK app
 * as a fallback. Prints one or several pictures in sequence. GPL v3.
 */
package com.ampylink.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

@SuppressLint("MissingPermission")
final class PrintSheet {
    static final String KODAK_PKG = "com.prinics.kodak.photoprinter";
    static final String KODAK_ACTIVITY = "com.prinics.kodak.photoprinter.ui.activities.MainActivity";
    static final int REQ_BT = 4711;

    /** Pictures to print. load() runs on a worker thread. */
    interface Source {
        int count();
        Bitmap load(int i) throws Exception;
        Frames.Meta meta(int i);
    }

    private final Activity a;
    private final Source src;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences sp;
    private Dialog dialog;
    private ImageView preview;
    private TextView status, printerLabel, copiesLabel, counter, sendBtn;
    private final TextView[] passViews = new TextView[4];
    private Neon.Bar bar;
    private Frames.Layout layout;
    private boolean dye, stamp;
    private int copies = 1;
    private float ox, oy, zoom = 1f;
    private int index;
    private Bitmap current;
    private volatile boolean printing;
    private KodakBt link;

    PrintSheet(Activity a, Source src, Frames.Layout initial) {
        this.a = a;
        this.src = src;
        sp = a.getSharedPreferences("print", Context.MODE_PRIVATE);
        layout = initial != null ? initial : Frames.Layout.values()[Math.min(Frames.Layout.values().length - 1, sp.getInt("layout", 0))];
        dye = sp.getBoolean("dye", true);
        stamp = sp.getBoolean("stamp", true);
    }

    static void show(Activity a, Source src, Frames.Layout initial) { new PrintSheet(a, src, initial).build(); }

    static void showSingle(Activity a, Bitmap bm, Frames.Meta meta, Frames.Layout initial) {
        show(a, new Source() {
            @Override public int count() { return 1; }
            @Override public Bitmap load(int i) { return bm; }
            @Override public Frames.Meta meta(int i) { return meta; }
        }, initial);
    }

    // ------------------------------------------------------------------ UI

    private void build() {
        Object[] dv = Neon.dialog(a, "PRINT // KODAK MINI 2 RETRO", true);
        dialog = (Dialog) dv[0];
        LinearLayout root = (LinearLayout) dv[1];
        int pad = Cyber.dp(a, 16);
        TextView sub = Neon.caption(a, "2.1×3.4\" · 640×1024 · 4PASS DYE-SUB · BLUETOOTH DIRETTO", 9);
        root.addView(sub);
        counter = Cyber.label(a, "", 10, Cyber.YELLOW);
        counter.setPadding(0, pad / 3, 0, 0);
        root.addView(counter);

        preview = new ImageView(a);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, Cyber.dp(a, 300));
        plp.topMargin = pad / 2;
        root.addView(preview, plp);
        attachReframe(preview);
        TextView hint = Neon.caption(a, "TRASCINA = RIQUADRA · PIZZICA = ZOOM · DOPPIO TAP = CENTRA", 7);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        // frame chips
        LinearLayout frames = new LinearLayout(a);
        final List<TextView> fchips = new ArrayList<>();
        for (Frames.Layout l : Frames.Layout.values()) {
            TextView c = Neon.chip(a, l.label, Cyber.CYAN);
            c.setOnClickListener(v -> {
                layout = l; sp.edit().putInt("layout", l.ordinal()).apply();
                for (int i = 0; i < fchips.size(); i++) Neon.styleChip(fchips.get(i), Frames.Layout.values()[i] == layout, Cyber.CYAN);
                refresh();
            });
            fchips.add(c);
            frames.addView(c, Neon.wrap(fchips.size() == 1 ? 0 : pad / 3));
        }
        for (int i = 0; i < fchips.size(); i++) Neon.styleChip(fchips.get(i), Frames.Layout.values()[i] == layout, Cyber.CYAN);
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setPadding(0, pad / 2, 0, 0);
        hs.addView(frames);
        root.addView(hs);

        // options row
        LinearLayout opts = new LinearLayout(a);
        opts.setGravity(Gravity.CENTER_VERTICAL);
        opts.setPadding(0, pad / 2, 0, 0);
        TextView dyeBtn = Neon.chip(a, "DYE-SUB", Cyber.YELLOW);
        TextView stampBtn = Neon.chip(a, "DATA", Cyber.YELLOW);
        Neon.styleChip(dyeBtn, dye, Cyber.YELLOW);
        Neon.styleChip(stampBtn, stamp, Cyber.YELLOW);
        dyeBtn.setOnClickListener(v -> { dye = !dye; sp.edit().putBoolean("dye", dye).apply(); Neon.styleChip(dyeBtn, dye, Cyber.YELLOW); refresh(); });
        stampBtn.setOnClickListener(v -> { stamp = !stamp; sp.edit().putBoolean("stamp", stamp).apply(); Neon.styleChip(stampBtn, stamp, Cyber.YELLOW); refresh(); });
        opts.addView(dyeBtn);
        opts.addView(stampBtn, Neon.wrap(pad / 3));
        opts.addView(Neon.spacer(a));
        TextView minus = Cyber.button(a, "−", Cyber.CYAN), plus = Cyber.button(a, "+", Cyber.CYAN);
        copiesLabel = Cyber.label(a, "", 11, Cyber.TEXT);
        copiesLabel.setPadding(pad / 2, 0, pad / 2, 0);
        minus.setOnClickListener(v -> { copies = Math.max(1, copies - 1); styleCopies(); });
        plus.setOnClickListener(v -> { copies = Math.min(9, copies + 1); styleCopies(); });
        opts.addView(minus); opts.addView(copiesLabel); opts.addView(plus);
        root.addView(opts);
        styleCopies();

        // printer row
        LinearLayout prow = new LinearLayout(a);
        prow.setGravity(Gravity.CENTER_VERTICAL);
        prow.setPadding(0, pad / 2, 0, 0);
        printerLabel = Cyber.label(a, "", 10, Cyber.TEXT);
        prow.addView(printerLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView pick = Cyber.button(a, "STAMPANTE", Cyber.CYAN);
        pick.setOnClickListener(v -> { if (ensureBt()) pickPrinter(); });
        prow.addView(pick);
        root.addView(prow);
        updatePrinterLabel();

        // status
        LinearLayout passes = new LinearLayout(a);
        passes.setPadding(0, pad / 2, 0, 0);
        String[] pn = {"Y", "M", "C", "OC"};
        int[] pc = {0xFFF5FF3B, 0xFFFF2BD6, 0xFF00F0FF, 0xFFC8F7FF};
        for (int i = 0; i < 4; i++) {
            passViews[i] = Cyber.label(a, "■ " + pn[i], 9, pc[i]);
            passViews[i].setAlpha(0.25f);
            passes.addView(passViews[i], Neon.wrap(i == 0 ? 0 : pad / 2));
        }
        passes.addView(Neon.spacer(a));
        passes.addView(Neon.caption(a, "4PASS", 8));
        root.addView(passes);
        bar = new Neon.Bar(a);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = pad / 3;
        root.addView(bar, blp);
        status = Cyber.label(a, "> PRONTO", 10, Cyber.YELLOW);
        status.setPadding(0, pad / 3, 0, pad / 2);
        root.addView(status);

        sendBtn = Cyber.button(a, "▶ STAMPA DIRETTA", Cyber.MAGENTA);
        sendBtn.setTextSize(14);
        sendBtn.setOnClickListener(v -> startDirect());
        root.addView(sendBtn, new LinearLayout.LayoutParams(-1, -2));
        TextView kodak = Cyber.button(a, "APRI NELL'APP KODAK", Cyber.DIM);
        kodak.setTextColor(Cyber.TEXT);
        kodak.setOnClickListener(v -> sendToKodakApp());
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-1, -2);
        klp.topMargin = pad / 2;
        root.addView(kodak, klp);

        dialog.setOnDismissListener(d -> closeLink());
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        loadIndex(0);
    }

    private void styleCopies() { copiesLabel.setText(copies + (copies == 1 ? " COPIA" : " COPIE")); }

    private void setStatus(String s, float p, int pass) {
        ui.post(() -> {
            status.setText("> " + s);
            bar.set(p);
            for (int i = 0; i < 4; i++) passViews[i].setAlpha(pass < 0 ? 0.25f : i < pass ? 0.6f : i == pass ? 1f : 0.25f);
        });
    }

    private void loadIndex(int i) {
        index = i;
        counter.setText(src.count() > 1 ? "FOTO " + (i + 1) + " / " + src.count() : "");
        setStatus("CARICAMENTO IMMAGINE…", -1, -1);
        ox = oy = 0; zoom = 1f;
        new Thread(() -> {
            try {
                Bitmap b = src.load(i);
                ui.post(() -> { current = b; refresh(); if (!printing) setStatus("PRONTO", 0, -1); });
            } catch (Exception e) {
                setStatus("ERRORE: " + e.getMessage(), 0, -1);
            }
        }).start();
    }

    private Frames.Meta meta() {
        Frames.Meta m = src.meta(index);
        if (m == null) m = Frames.Meta.of(null, null);
        m.stamp = stamp;
        return m;
    }

    private void refresh() {
        if (current == null) return;
        preview.setImageBitmap(Frames.compose(current, layout, dye, meta(), ox, oy, zoom, false));
    }

    private Bitmap card() { return Frames.compose(current, layout, dye, meta(), ox, oy, zoom, true); }

    @SuppressLint("ClickableViewAccessibility")
    private void attachReframe(View v) {
        final android.view.ScaleGestureDetector sg = new android.view.ScaleGestureDetector(a, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(android.view.ScaleGestureDetector d) {
                zoom = Math.max(1f, Math.min(4f, zoom * d.getScaleFactor())); refresh(); return true;
            }
        });
        final float[] last = new float[2];
        final long[] lastTap = {0};
        v.setOnTouchListener((view, e) -> {
            if (printing || current == null) return true;
            sg.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    long now = System.currentTimeMillis();
                    if (now - lastTap[0] < 280) { ox = oy = 0; zoom = 1; refresh(); }
                    lastTap[0] = now;
                    last[0] = e.getX(); last[1] = e.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (e.getPointerCount() == 1 && !sg.isInProgress()) {
                        float k = 2.4f / Math.max(1, view.getWidth());
                        boolean land = current.getWidth() > current.getHeight();
                        float dx = (e.getX() - last[0]) * k, dy = (e.getY() - last[1]) * k;
                        // preview shows the card rotated for landscape shots
                        if (land) { ox -= dy; oy += dx; } else { ox -= dx; oy -= dy; }
                        ox = Math.max(-1, Math.min(1, ox)); oy = Math.max(-1, Math.min(1, oy));
                        last[0] = e.getX(); last[1] = e.getY();
                        refresh();
                    }
                    break;
                default:
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ printer selection

    private String savedAddress() { return sp.getString("printer", null); }

    private BluetoothDevice savedDevice() {
        BluetoothAdapter ad = KodakBt.adapter(a);
        String mac = savedAddress();
        if (ad == null) return null;
        if (mac != null) try { return ad.getRemoteDevice(mac); } catch (Exception ignored) {}
        try {
            List<BluetoothDevice> b = KodakBt.bondedPrinters(a);
            if (!b.isEmpty()) { sp.edit().putString("printer", b.get(0).getAddress()).putString("printerName", b.get(0).getName()).apply(); return b.get(0); }
        } catch (SecurityException ignored) {}
        return null;
    }

    private void updatePrinterLabel() {
        String n = sp.getString("printerName", null), mac = savedAddress();
        printerLabel.setText(mac == null ? "◌ NESSUNA STAMPANTE · TOCCA STAMPANTE" : "◉ " + (n != null ? n : "KODAK") + "  " + mac.substring(Math.max(0, mac.length() - 5)));
        printerLabel.setTextColor(mac == null ? Cyber.DIM : Cyber.TEXT);
    }

    private boolean ensureBt() {
        BluetoothAdapter ad = KodakBt.adapter(a);
        if (ad == null) { Toast.makeText(a, "Bluetooth non disponibile", Toast.LENGTH_LONG).show(); return false; }
        if (Build.VERSION.SDK_INT >= 31) {
            if (a.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                || a.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                a.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, REQ_BT);
                return false;
            }
        } else if (a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BT);
            return false;
        }
        if (!ad.isEnabled()) {
            try { a.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception ignored) {}
            return false;
        }
        return true;
    }

    private void pickPrinter() {
        Object[] dv = Neon.dialog(a, "STAMPANTI KODAK", true);
        Dialog d = (Dialog) dv[0];
        LinearLayout list = (LinearLayout) dv[1];
        TextView hint = Neon.caption(a, "Accendi la Mini 2 Retro. Prima volta: associala dalle impostazioni Bluetooth oppure tocca il nome qui sotto quando compare.", 9);
        list.addView(hint);
        final LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        list.addView(rows);
        final List<String> seen = new ArrayList<>();
        final BluetoothAdapter ad = KodakBt.adapter(a);
        java.util.function.Consumer<BluetoothDevice> add = dev -> {
            String name; try { name = dev.getName(); } catch (SecurityException e) { name = null; }
            if (name == null || seen.contains(dev.getAddress())) return;
            boolean kodak = KodakBt.looksLikeKodak(name);
            seen.add(dev.getAddress());
            TextView t = Cyber.button(a, (kodak ? "▣ " : "□ ") + name + "\n" + dev.getAddress() + (dev.getBondState() == BluetoothDevice.BOND_BONDED ? " · ASSOCIATA" : ""), kodak ? Cyber.MAGENTA : Cyber.DIM);
            t.setGravity(Gravity.START);
            final String nm = name;
            t.setOnClickListener(v -> {
                sp.edit().putString("printer", dev.getAddress()).putString("printerName", nm).apply();
                if (dev.getBondState() != BluetoothDevice.BOND_BONDED) try { dev.createBond(); } catch (Exception ignored) {}
                updatePrinterLabel();
                d.dismiss();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Cyber.dp(a, 6);
            if (kodak) rows.addView(t, 0, lp); else rows.addView(t, lp);
        };
        try { for (BluetoothDevice dev : ad.getBondedDevices()) add.accept(dev); } catch (SecurityException ignored) {}
        final TextView scanState = Neon.caption(a, "RICERCA IN CORSO…", 9);
        scanState.setPadding(0, Cyber.dp(a, 8), 0, 0);
        list.addView(scanState);
        BroadcastReceiver rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (BluetoothDevice.ACTION_FOUND.equals(i.getAction())) {
                    BluetoothDevice dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (dev != null) add.accept(dev);
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(i.getAction())) scanState.setText("RICERCA COMPLETATA");
            }
        };
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        a.registerReceiver(rx, f);
        try { ad.startDiscovery(); } catch (SecurityException e) { scanState.setText("PERMESSO BLUETOOTH NEGATO"); }
        d.setOnDismissListener(x -> { try { a.unregisterReceiver(rx); } catch (Exception ignored) {} try { ad.cancelDiscovery(); } catch (Exception ignored) {} });
        d.show();
    }

    // ------------------------------------------------------------------ printing

    private void startDirect() {
        if (printing) return;
        if (current == null) { Toast.makeText(a, "Immagine non ancora pronta", Toast.LENGTH_SHORT).show(); return; }
        if (!ensureBt()) return;
        final BluetoothDevice dev = savedDevice();
        if (dev == null) { pickPrinter(); return; }
        printing = true;
        dialog.setCancelable(false);
        sendBtn.setAlpha(0.4f);
        final Bitmap firstCard = card();
        final int n = src.count();
        new Thread(() -> {
            String err = null;
            try {
                for (int i = index; i < n; i++) {
                    final int k = i;
                    Bitmap cardBm;
                    if (i == index) cardBm = firstCard;
                    else {
                        ui.post(() -> counter.setText("FOTO " + (k + 1) + " / " + n));
                        setStatus("CARICAMENTO FOTO " + (k + 1) + "…", -1, -1);
                        Bitmap b = src.load(i);
                        ui.post(() -> { current = b; ox = oy = 0; zoom = 1; refresh(); });
                        Frames.Meta m = src.meta(i); if (m == null) m = Frames.Meta.of(null, null); m.stamp = stamp;
                        cardBm = Frames.compose(b, layout, dye, m, 0, 0, 1, true);
                    }
                    byte[] jpeg = jpeg(cardBm);
                    if (link == null) {
                        setStatus("CONNESSIONE BLUETOOTH…", -1, -1);
                        link = KodakBt.open(a, dev);
                    }
                    final String prefix = n > 1 ? (k + 1) + "/" + n + " · " : "";
                    try {
                        link.print(jpeg, copies, (t, p, pass) -> setStatus(prefix + t, p, pass));
                    } catch (java.io.IOException e) {
                        // the printer may drop the link between jobs: reconnect once
                        if (e.getMessage() != null && e.getMessage().startsWith("Errore stampante")) throw e;
                        closeLink();
                        setStatus("RICONNESSIONE…", -1, -1);
                        link = KodakBt.open(a, dev);
                        link.print(jpeg, copies, (t, p, pass) -> setStatus(prefix + t, p, pass));
                    }
                    if (i < n - 1) Thread.sleep(1500);
                }
            } catch (Exception e) {
                err = e.getMessage();
                closeLink();
            }
            final String fe = err;
            ui.post(() -> {
                printing = false;
                dialog.setCancelable(true);
                sendBtn.setAlpha(1f);
                if (fe != null) setStatus("✕ " + fe, 0, -1);
                else { setStatus(n > 1 ? "TUTTE LE FOTO STAMPATE ✓" : "STAMPA COMPLETATA ✓", 1f, 3); a.getWindow().getDecorView().performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); }
            });
        }, "kodak-print").start();
    }

    private void closeLink() { KodakBt l = link; link = null; if (l != null) l.close(); }

    /** Baseline JPEG for the printer, long side 1024. */
    static byte[] jpeg(Bitmap card) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(400 << 10);
        card.compress(Bitmap.CompressFormat.JPEG, 92, bo);
        return bo.toByteArray();
    }

    private void sendToKodakApp() {
        if (current == null) return;
        try {
            File dir = Session.cacheDir(a, "print");
            File f = new File(dir, "ampylink_print_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream o = new FileOutputStream(f)) { card().compress(Bitmap.CompressFormat.JPEG, 97, o); }
            Uri uri = FileShare.uriFor(f);
            Intent i = new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(ClipData.newRawUri("print", uri));
            try {
                a.startActivity(new Intent(i).setClassName(KODAK_PKG, KODAK_ACTIVITY));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(a, "App KODAK Photo Printer non installata", Toast.LENGTH_LONG).show();
                try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + KODAK_PKG))); }
                catch (ActivityNotFoundException e2) { a.startActivity(Intent.createChooser(i, "Stampa con")); }
            }
        } catch (Exception e) {
            Toast.makeText(a, "Errore: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    static ViewGroup.LayoutParams match() { return new ViewGroup.LayoutParams(-1, -2); }
}
