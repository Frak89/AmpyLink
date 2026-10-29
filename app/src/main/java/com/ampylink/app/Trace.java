package com.ampylink.app;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Connection log shown by the LOG button (last 300 lines). GPL v3. */
final class Trace {
    private Trace() {}
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    static void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + "  " + s;
        synchronized (LOG) { LOG.addLast(line); while (LOG.size() > 300) LOG.removeFirst(); }
        try { android.util.Log.i("AmpyLink", s); } catch (Throwable ignored) {}
    }

    static String text() { synchronized (LOG) { return String.join("\n", LOG); } }
}
