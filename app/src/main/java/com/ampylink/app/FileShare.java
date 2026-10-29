/*
 * Read-only provider for files in our cache (camera downloads, print cards, renders) so the KODAK
 * app and external RAW editors (Lightroom, Snapseed, AmpyRaw…) can open them. GPL v3.
 */
package com.ampylink.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

public final class FileShare extends ContentProvider {
    static final String AUTH = "com.ampylink.app.files";
    private static final String[] DIRS = {"print", "eos", "edit"};

    static Uri uriFor(File f) {
        return Uri.parse("content://" + AUTH + "/" + f.getParentFile().getName() + "/" + Uri.encode(f.getName()));
    }

    static String mime(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".cr3")) return "image/x-canon-cr3";
        if (n.endsWith(".cr2")) return "image/x-canon-cr2";
        if (n.endsWith(".dng")) return "image/x-adobe-dng";
        if (n.endsWith(".hif") || n.endsWith(".heif") || n.endsWith(".heic")) return "image/heif";
        if (n.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private File fileFor(Uri u) throws FileNotFoundException {
        java.util.List<String> seg = u.getPathSegments();
        if (seg.size() != 2) throw new FileNotFoundException();
        String dir = seg.get(0), n = seg.get(1);
        boolean ok = false;
        for (String d : DIRS) ok |= d.equals(dir);
        if (!ok || n.contains("/") || n.contains("..")) throw new FileNotFoundException();
        File f = new File(new File(getContext().getCacheDir(), dir), n);
        if (!f.exists()) throw new FileNotFoundException(n);
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] proj, String sel, String[] args, String sort) {
        try {
            File f = fileFor(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, "mime_type"});
            c.addRow(new Object[]{f.getName(), f.length(), mime(f.getName())});
            return c;
        } catch (FileNotFoundException e) { return null; }
    }

    @Override public String getType(Uri uri) {
        java.util.List<String> seg = uri.getPathSegments();
        return mime(seg.isEmpty() ? "" : seg.get(seg.size() - 1));
    }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
