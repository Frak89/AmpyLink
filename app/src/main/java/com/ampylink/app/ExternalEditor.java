/*
 * "Apri con editor RAW": lists the photo/RAW editors installed on the phone (Lightroom, Snapseed,
 * AmpyRaw, Photoshop Express, …), RAW-capable ones first, remembers the last choice, and hands
 * the camera file over through our read-only provider. GPL v3.
 */
package com.ampylink.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ExternalEditor {
    private ExternalEditor() {}

    /** Known editors that develop RAW files (CR3/DNG). */
    static final List<String> RAW_EDITORS = Arrays.asList(
        "com.ampyraw.app", "com.adobe.lrmobile", "com.niksoftware.snapseed", "com.adobe.psmobile",
        "com.cyberlink.photodirector", "com.vsco.cam", "com.photoroom.app", "com.google.android.apps.photos",
        "pl.mobileexperts.raw", "com.polarr.photoeditor", "com.lightricks.photoleap");

    static final class App { String pkg, activity, label; Drawable icon; boolean raw; String action; }

    static List<App> query(Context c, String mime) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> map = new LinkedHashMap<>();
        String[] actions = {Intent.ACTION_EDIT, Intent.ACTION_VIEW};
        String[] types = mime.equals("image/jpeg") ? new String[]{mime} : new String[]{mime, "image/*"};
        Uri probe = Uri.parse("content://" + FileShare.AUTH + "/eos/probe");
        for (String action : actions) for (String t : types) {
            List<ResolveInfo> l = pm.queryIntentActivities(new Intent(action).setDataAndType(probe, t), PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo ri : l) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || map.containsKey(pkg)) continue;
                App a = new App();
                a.pkg = pkg; a.activity = ri.activityInfo.name; a.action = action;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.icon = ri.loadIcon(pm);
                a.raw = RAW_EDITORS.contains(pkg) || !t.equals("image/*");
                map.put(pkg, a);
            }
        }
        List<App> r = new ArrayList<>(map.values());
        r.sort((x, y) -> x.raw != y.raw ? (x.raw ? -1 : 1) : Integer.compare(rank(x.pkg), rank(y.pkg)));
        return r;
    }

    private static int rank(String pkg) { int i = RAW_EDITORS.indexOf(pkg); return i < 0 ? 999 : i; }

    static void open(Activity act, File f) {
        String mime = FileShare.mime(f.getName());
        List<App> apps = query(act, mime);
        SharedPreferences sp = act.getSharedPreferences("editor", Context.MODE_PRIVATE);
        Object[] dv = Neon.dialog(act, "APRI CON EDITOR // " + f.getName(), true);
        Dialog d = (Dialog) dv[0];
        LinearLayout root = (LinearLayout) dv[1];
        root.addView(Neon.caption(act, mime + " · " + (f.length() >> 20) + " MB · gli editor RAW sono in alto", 9));
        if (apps.isEmpty()) {
            TextView t = Cyber.label(act, "Nessun editor installato.\nInstalla Lightroom, Snapseed o AmpyRaw.", 11, Cyber.YELLOW);
            t.setPadding(0, Cyber.dp(act, 12), 0, 0);
            root.addView(t);
        }
        String last = sp.getString("last", null);
        for (App a : apps) {
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int p = Cyber.dp(act, 8);
            row.setPadding(p, p, p, p);
            row.setBackground(Cyber.panelBg(act));
            ImageView iv = new ImageView(act);
            iv.setImageDrawable(a.icon);
            row.addView(iv, new LinearLayout.LayoutParams(Cyber.dp(act, 36), Cyber.dp(act, 36)));
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(p, 0, 0, 0);
            col.addView(Cyber.label(act, a.label, 12, a.pkg.equals(last) ? Cyber.MAGENTA : Cyber.TEXT));
            col.addView(Neon.caption(act, (a.raw ? "RAW ▲ " : "") + a.pkg, 8));
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            if (a.raw) row.addView(Cyber.label(act, "RAW", 9, Cyber.MAGENTA));
            row.setOnClickListener(v -> { sp.edit().putString("last", a.pkg).apply(); d.dismiss(); launch(act, f, a, mime); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Cyber.dp(act, 6);
            if (a.pkg.equals(last)) root.addView(row, 3, lp); else root.addView(row, lp);
        }
        TextView sys = Cyber.button(act, "SCELTA DI SISTEMA…", Cyber.DIM);
        sys.setTextColor(Cyber.TEXT);
        sys.setOnClickListener(v -> {
            d.dismiss();
            Uri u = FileShare.uriFor(f);
            Intent i = new Intent(Intent.ACTION_EDIT).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(ClipData.newRawUri(f.getName(), u));
            try { act.startActivity(Intent.createChooser(i, "Modifica con")); }
            catch (ActivityNotFoundException e) { Toast.makeText(act, "Nessuna app", Toast.LENGTH_SHORT).show(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Cyber.dp(act, 10);
        root.addView(sys, lp);
        d.show();
    }

    private static void launch(Activity act, File f, App a, String mime) {
        Uri u = FileShare.uriFor(f);
        String type = mime;
        // use the precise mime only if the app declares it, else image/*
        if (act.getPackageManager().queryIntentActivities(new Intent(a.action).setDataAndType(u, mime).setPackage(a.pkg), 0).isEmpty()) type = "image/*";
        Intent i = new Intent(a.action).setDataAndType(u, type).setClassName(a.pkg, a.activity)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(ClipData.newRawUri(f.getName(), u));
        i.putExtra(Intent.EXTRA_STREAM, u);
        try { act.startActivity(i); }
        catch (Exception e) {
            Intent s = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, u).setPackage(a.pkg).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            s.setClipData(ClipData.newRawUri(f.getName(), u));
            try { act.startActivity(s); } catch (Exception e2) { Toast.makeText(act, "Impossibile aprire " + a.label, Toast.LENGTH_LONG).show(); }
        }
    }
}
