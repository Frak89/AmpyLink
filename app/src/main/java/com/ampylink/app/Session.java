/*
 * Shared state between gallery, viewer and editor: the camera link, the shot list (RAW+JPG pairs
 * merged like Camera Connect), thumbnail / EXIF / preview caches and local phone images. GPL v3.
 */
package com.ampylink.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.LruCache;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Session {
    private Session() {}

    /** One picture as the user sees it: a RAW and/or JPG/HEIF of the same frame, or a local file. */
    static final class Shot {
        CanonClient.Item jpg, raw, heif;
        Uri local; String localName; long localSize;
        long time;
        volatile Exif.Info exif;
        boolean selected;

        CanonClient.Item primary() { return jpg != null ? jpg : heif != null ? heif : raw; }
        /** File to edit: RAW when there is one. */
        CanonClient.Item editItem() { return raw != null ? raw : primary(); }
        boolean isLocal() { return local != null; }
        String name() { return isLocal() ? localName : primary().name; }
        String key() { return isLocal() ? local.toString() : primary().key(); }
        boolean hasRaw() { return raw != null || (isLocal() && isRawName(localName)); }
        String badge() {
            if (isLocal()) return isRawName(localName) ? "RAW" : "PHONE";
            if (raw != null && (jpg != null || heif != null)) return "RAW+" + (jpg != null ? "JPG" : "HEIF");
            if (raw != null) return "RAW";
            return heif != null ? "HEIF" : "JPG";
        }
        String dayKey() { return time == 0 ? "----.--.--" : new SimpleDateFormat("yyyy.MM.dd", Locale.US).format(new Date(time)); }
    }

    static boolean isRawName(String n) {
        String u = n == null ? "" : n.toUpperCase(Locale.US);
        return u.endsWith(".CR3") || u.endsWith(".CR2") || u.endsWith(".DNG") || u.endsWith(".NEF") || u.endsWith(".ARW");
    }

    static volatile CanonClient client;
    static final List<Shot> shots = Collections.synchronizedList(new ArrayList<>());
    static final List<Shot> localShots = Collections.synchronizedList(new ArrayList<>());
    static final ExecutorService camIo = Executors.newSingleThreadExecutor();     // full transfers
    static final ExecutorService thumbIo = Executors.newSingleThreadExecutor();   // thumbnails / exif
    static final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(24 << 20) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final Map<String, byte[]> heads = Collections.synchronizedMap(new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) { return size() > 12; }
    });

    static UUID guid(Context c) {
        SharedPreferences sp = c.getSharedPreferences("eos", Context.MODE_PRIVATE);
        String g = sp.getString("guid", null);
        if (g == null) { g = UUID.randomUUID().toString(); sp.edit().putString("guid", g).apply(); }
        return UUID.fromString(g);
    }

    static String friendlyName() {
        String m = android.os.Build.MODEL;
        if (m == null || m.isEmpty()) m = "AmpyLink";
        return m.length() > 24 ? m.substring(0, 24) : m;
    }

    /** Merge RAW + JPG/HEIF of the same frame; newest first. */
    static List<Shot> group(List<CanonClient.Item> items) {
        Map<String, Shot> m = new LinkedHashMap<>();
        for (CanonClient.Item it : items) {
            String k = it.base();
            Shot s = m.get(k);
            if (s == null) { s = new Shot(); m.put(k, s); }
            if (it.isRaw()) s.raw = it; else if (it.isHeif()) s.heif = it; else s.jpg = it;
            if (it.time != 0) s.time = Math.max(s.time, it.time);
        }
        List<Shot> r = new ArrayList<>(m.values());
        sortShots(r, true);
        return r;
    }

    static void sortShots(List<Shot> r, boolean newestFirst) {
        Collections.sort(r, (a, b) -> {
            int c = Long.compare(a.time, b.time);
            if (c == 0) c = a.name().compareTo(b.name());
            return newestFirst ? -c : c;
        });
    }

    static File cacheDir(Context c, String sub) { File d = new File(c.getCacheDir(), sub); d.mkdirs(); return d; }

    // ------------------------------------------------------------------ camera fetches

    static Bitmap thumbnail(Context ctx, Shot s) {
        Bitmap b = thumbs.get(s.key());
        if (b != null) return b;
        try {
            if (s.isLocal()) {
                b = ctx.getContentResolver().loadThumbnail(s.local, new Size(320, 320), null);
            } else {
                CanonClient c = client;
                if (c == null) return null;
                byte[] t = c.thumbnail(s.primary());
                if (c instanceof CanonClient.Ccapi && s.time == 0) s.time = s.primary().time;
                b = BitmapFactory.decodeByteArray(t, 0, t.length);
            }
        } catch (Exception e) { b = null; }
        if (b != null) thumbs.put(s.key(), b);
        return b;
    }

    /** First ~1.5 MB of a camera file (EXIF + CR3 preview), cached. */
    static byte[] head(Context ctx, Shot s, CanonClient.Item it) throws IOException {
        String k = it.key();
        byte[] h = heads.get(k);
        if (h != null) return h;
        File full = new File(cacheDir(ctx, "eos"), it.name);
        if (full.exists() && (it.size <= 0 || full.length() == it.size)) {
            h = readHead(full, 1_600_000);
        } else {
            CanonClient c = client;
            if (c == null) throw new IOException("Not connected");
            h = c.head(it, it.isRaw() ? 1_600_000 : 160_000);
        }
        if (h != null) heads.put(k, h);
        return h;
    }

    private static byte[] readHead(File f, int n) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(n, f.length())];
            int o = 0, r;
            while (o < b.length && (r = in.read(b, o, b.length - o)) > 0) o += r;
            return b;
        }
    }

    static Exif.Info exif(Context ctx, Shot s) {
        if (s.exif != null) return s.exif;
        Exif.Info info = null;
        try {
            if (s.isLocal()) {
                try (InputStream in = ctx.getContentResolver().openInputStream(s.local)) {
                    byte[] b = new byte[1_600_000]; int o = 0, r;
                    while (o < b.length && (r = in.read(b, o, b.length - o)) > 0) o += r;
                    info = Exif.parse(java.util.Arrays.copyOf(b, o));
                }
            } else {
                // RAW carries the full EXIF (CMT1/CMT2) in its first bytes; JPG in APP1
                CanonClient.Item it = s.raw != null ? s.raw : s.primary();
                byte[] h = head(ctx, s, it);
                if (h != null) info = Exif.parse(h);
                if ((info == null || info.isEmpty()) && s.jpg != null && it != s.jpg) {
                    byte[] hj = head(ctx, s, s.jpg);
                    if (hj != null) info = Exif.parse(hj);
                }
            }
        } catch (Exception ignored) {}
        if (info == null) info = new Exif.Info();
        if (info.width == 0 && !s.isLocal() && s.primary().w > 0) { info.width = s.primary().w; info.height = s.primary().h; }
        s.exif = info;
        return info;
    }

    /** Full file in cache/eos (camera) or a copy of a local file in cache/edit. */
    static File download(Context ctx, Shot s, CanonClient.Item it, CanonClient.Progress p) throws IOException {
        if (s.isLocal()) {
            File f = new File(cacheDir(ctx, "edit"), sanitize(s.localName));
            if (f.exists() && f.length() == s.localSize && s.localSize > 0) return f;
            try (InputStream in = ctx.getContentResolver().openInputStream(s.local); FileOutputStream o = new FileOutputStream(f)) {
                if (in == null) throw new IOException("Cannot open " + s.localName);
                byte[] buf = new byte[1 << 16]; int n; long done = 0;
                while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); done += n; if (p != null) p.on(done, s.localSize); }
            }
            return f;
        }
        File f = new File(cacheDir(ctx, "eos"), sanitize(it.name));
        if (f.exists() && it.size > 0 && f.length() == it.size) return f;
        CanonClient c = client;
        if (c == null) throw new IOException("Fotocamera non collegata");
        File tmp = new File(f.getPath() + ".part");
        c.download(it, tmp, p);
        if (!tmp.renameTo(f)) throw new IOException("Cache write failed");
        return f;
    }

    static String sanitize(String n) { return n.replaceAll("[^A-Za-z0-9._-]", "_"); }

    /**
     * Picture for viewing / printing, long side ≤ maxSide. JPG: full file. RAW-only: the embedded
     * preview from the first bytes (no 25 MB transfer). CCAPI: display JPEG.
     */
    static Bitmap preview(Context ctx, Shot s, int maxSide, CanonClient.Progress p) throws IOException {
        byte[] jpeg = null;
        int orientation = 1;
        if (s.isLocal()) {
            if (isRawName(s.localName)) {
                File f = download(ctx, s, null, p);
                byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
                jpeg = Exif.previewJpeg(all);
                orientation = Exif.parse(all).orientation;
            } else {
                try {
                    return android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.getContentResolver(), s.local),
                        (dec, info, src) -> {
                            dec.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                            int w = info.getSize().getWidth(), h = info.getSize().getHeight(), m = Math.max(w, h);
                            if (m > maxSide) dec.setTargetSize(Math.round(w * maxSide / (float) m), Math.round(h * maxSide / (float) m));
                        });
                } catch (Exception e) { throw new IOException(e.getMessage()); }
            }
        } else {
            CanonClient c = client;
            if (s.jpg != null || s.heif != null) {
                CanonClient.Item it = s.jpg != null ? s.jpg : s.heif;
                File f = download(ctx, s, it, p);
                if (it.isHeif()) {
                    try {
                        return android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(f), (dec, info, src) -> {
                            dec.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                            int w = info.getSize().getWidth(), h = info.getSize().getHeight(), m = Math.max(w, h);
                            if (m > maxSide) dec.setTargetSize(Math.round(w * maxSide / (float) m), Math.round(h * maxSide / (float) m));
                        });
                    } catch (Exception e) { throw new IOException(e.getMessage()); }
                }
                return decodeFile(f, maxSide);
            }
            if (c instanceof CanonClient.Ccapi) jpeg = c.display(s.raw);
            if (jpeg == null) {
                byte[] h = head(ctx, s, s.raw);
                jpeg = Exif.previewJpeg(h);
                orientation = h != null ? Exif.parse(h).orientation : 1;
            }
        }
        if (jpeg == null) throw new IOException("Anteprima non disponibile");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        o.inSampleSize = sample(o.outWidth, o.outHeight, maxSide);
        o.inJustDecodeBounds = false;
        Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        if (b == null) throw new IOException("Anteprima non valida");
        return rotate(b, orientation);
    }

    static Bitmap decodeFile(File f, int maxSide) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = sample(o.outWidth, o.outHeight, maxSide);
        o.inJustDecodeBounds = false;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) throw new IOException("Decode failed");
        int orient = 1;
        try { orient = new android.media.ExifInterface(f.getPath()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1); } catch (Exception ignored) {}
        return rotate(b, orient);
    }

    private static int sample(int w, int h, int maxSide) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= maxSide) s *= 2;
        return s;
    }

    static Bitmap rotate(Bitmap b, int orientation) {
        int deg = orientation == 6 ? 90 : orientation == 3 ? 180 : orientation == 8 ? 270 : 0;
        if (deg == 0) return b;
        Matrix m = new Matrix(); m.postRotate(deg);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    // ------------------------------------------------------------------ local images

    static Shot local(ContentResolver cr, Uri u) {
        Shot s = new Shot();
        s.local = u;
        s.localName = "image";
        try (Cursor c = cr.query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int dn = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), sz = c.getColumnIndex(OpenableColumns.SIZE);
                int lm = c.getColumnIndex("last_modified");
                if (dn >= 0 && !c.isNull(dn)) s.localName = c.getString(dn);
                if (sz >= 0 && !c.isNull(sz)) s.localSize = c.getLong(sz);
                if (lm >= 0 && !c.isNull(lm)) s.time = c.getLong(lm);
            }
        } catch (Exception ignored) {}
        if (s.time == 0) s.time = System.currentTimeMillis();
        return s;
    }

    static void disconnect() {
        final CanonClient c = client;
        client = null;
        if (c != null) camIo.execute(c::close);
    }
}
