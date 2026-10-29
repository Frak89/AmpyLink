/*
 * Canon EOS Wi-Fi link (EOS R6 Mark II and other recent bodies).
 *  - PTP/IP : ISO 15740 over TCP 15740 (CIPA DC-005), the transport Canon Camera Connect uses
 *             (UPnP discovery, then PTP/IP Init with the phone name + a persistent GUID that the
 *             camera stores at pairing). Canon EOS remote/event mode, ProbeRequest answering.
 *  - CCAPI  : Canon Camera Control API (HTTP/JSON, port 8080), after the free activation.
 * Derived from AmpyRaw's CanonClient. GPL v3.
 */
package com.ampylink.app;

import android.net.Network;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

abstract class CanonClient {

    static final class Item {
        final String name; final String path; final int handle; long size;
        long time;          // capture time, epoch ms (0 = unknown)
        int w, h;           // pixel size if the camera reports it
        String folder = "";
        Item(String name, String path, int handle, long size) { this.name = name; this.path = path; this.handle = handle; this.size = size; }
        String ext() { int i = name.lastIndexOf('.'); return i < 0 ? "" : name.substring(i + 1).toUpperCase(Locale.US); }
        boolean isRaw() { String e = ext(); return e.equals("CR3") || e.equals("CR2") || e.equals("DNG"); }
        boolean isJpeg() { String e = ext(); return e.equals("JPG") || e.equals("JPEG"); }
        boolean isHeif() { String e = ext(); return e.equals("HIF") || e.equals("HEIF") || e.equals("HEIC"); }
        String base() { int i = name.lastIndexOf('.'); return (folder + "/" + (i < 0 ? name : name.substring(0, i))).toUpperCase(Locale.US); }
        String key() { return folder + "/" + name; }
    }

    interface Progress { void on(long done, long total); }

    /** Called for every file as soon as the card scan finds it (progressive gallery). */
    interface Sink { void on(Item it); }
    volatile Sink onItem;

    protected final String host;
    protected final Network network; // the camera's Wi-Fi network (no internet) – may be null
    String cameraName = "";

    CanonClient(String host, Network network) { this.host = host; this.network = network; }

    abstract String mode();
    abstract void connect() throws IOException;
    abstract List<Item> list(Progress p) throws IOException;
    abstract byte[] thumbnail(Item it) throws IOException;
    /** First bytes of the file (EXIF + embedded preview), or null if unsupported. */
    abstract byte[] head(Item it, int bytes) throws IOException;
    /** Display-size JPEG if the protocol offers one (CCAPI), else null. */
    byte[] display(Item it) throws IOException { return null; }
    abstract void download(Item it, File out, Progress p) throws IOException;
    abstract void close();
    /** Periodic keep-alive while idle; may refresh nothing. */
    void keepAlive() {}

    static boolean isImage(String n) {
        String u = n.toUpperCase(Locale.US);
        return u.endsWith(".JPG") || u.endsWith(".JPEG") || u.endsWith(".CR3") || u.endsWith(".CR2") || u.endsWith(".HEIF") || u.endsWith(".HIF") || u.endsWith(".DNG");
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[1 << 16]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    // =====================================================================  CCAPI

    static final class Ccapi extends CanonClient {
        private String contents; // e.g. http://ip:8080/ccapi/ver120/contents
        private String base;
        Ccapi(String host, Network n) { super(host, n); }

        @Override String mode() { return "CCAPI"; }

        private HttpURLConnection open(String url) throws IOException {
            URL u = new URL(url);
            HttpURLConnection c = (HttpURLConnection) (network != null ? network.openConnection(u) : u.openConnection());
            c.setConnectTimeout(6000); c.setReadTimeout(20000);
            return c;
        }

        private JSONObject getJson(String url) throws IOException {
            HttpURLConnection c = open(url);
            try {
                if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode() + " " + url);
                return new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
            } catch (org.json.JSONException e) { throw new IOException(e); } finally { c.disconnect(); }
        }

        private static List<String> paths(JSONObject o) {
            List<String> r = new ArrayList<>();
            JSONArray a = o.optJSONArray("path");
            if (a != null) for (int i = 0; i < a.length(); i++) r.add(a.optString(i));
            return r;
        }

        @Override void connect() throws IOException {
            base = "http://" + host + ":8080";
            JSONObject root = getJson(base + "/ccapi");
            String best = null, devinfo = null;
            for (Iterator<String> k = root.keys(); k.hasNext(); ) {
                JSONArray apis = root.optJSONArray(k.next());
                if (apis == null) continue;
                for (int i = 0; i < apis.length(); i++) {
                    String p = apis.optJSONObject(i) != null ? apis.optJSONObject(i).optString("path") : "";
                    if (p.endsWith("/contents") && (best == null || p.compareTo(best) > 0)) best = p;
                    if (p.endsWith("/deviceinformation") && devinfo == null) devinfo = p;
                }
            }
            if (best == null) best = "/ccapi/ver100/contents";
            contents = abs(best);
            try {
                JSONObject di = getJson(abs(devinfo != null ? devinfo : "/ccapi/ver100/deviceinformation"));
                cameraName = di.optString("productname", "");
            } catch (Exception ignored) {}
        }

        private String abs(String p) { return p.startsWith("http") ? p : base + p; }

        @Override List<Item> list(Progress prog) throws IOException {
            List<Item> out = new ArrayList<>();
            for (String storage : paths(getJson(contents))) {
                for (String dir : paths(getJson(abs(storage)))) {
                    String folder = dir.substring(dir.lastIndexOf('/') + 1);
                    JSONObject num = getJson(abs(dir) + "?kind=number");
                    int pages = Math.max(1, num.optInt("pagenumber", 1));
                    for (int p = 1; p <= pages; p++) {
                        for (String f : paths(getJson(abs(dir) + "?page=" + p))) {
                            String name = f.substring(f.lastIndexOf('/') + 1);
                            if (!isImage(name)) continue;
                            Item it = new Item(name, abs(f), 0, -1);
                            it.folder = folder;
                            out.add(it);
                            Sink sk = onItem; if (sk != null) sk.on(it);
                        }
                        if (prog != null) prog.on(out.size(), -1);
                    }
                }
            }
            return out;
        }

        /** kind=info → size + last-modified date (fills Item). */
        void info(Item it) {
            try {
                JSONObject o = getJson(it.path + "?kind=info");
                it.size = o.optLong("filesize", it.size);
                String d = o.optString("lastmodifieddate", "");
                if (!d.isEmpty()) {
                    try { it.time = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.US).parse(d).getTime(); }
                    catch (ParseException ignored) {}
                }
            } catch (Exception ignored) {}
        }

        private byte[] get(String url) throws IOException {
            HttpURLConnection c = open(url);
            try {
                if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode());
                try (InputStream in = c.getInputStream()) { return readAll(in); }
            } finally { c.disconnect(); }
        }

        @Override byte[] thumbnail(Item it) throws IOException {
            if (it.time == 0) info(it);
            return get(it.path + "?kind=thumbnail");
        }

        @Override byte[] display(Item it) throws IOException { return get(it.path + "?kind=display"); }

        @Override byte[] head(Item it, int bytes) throws IOException {
            // CCAPI has no ranged read; the display JPEG carries the EXIF block
            try { return display(it); } catch (IOException e) { return null; }
        }

        @Override void download(Item it, File out, Progress p) throws IOException {
            HttpURLConnection c = open(it.path);
            c.setReadTimeout(60000);
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[1 << 16]; long done = 0; int n;
                while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); done += n; if (p != null) p.on(done, total); }
            } finally { c.disconnect(); }
        }

        @Override void close() {}
    }

    // =====================================================================  PTP/IP

    static final class PtpIp extends CanonClient {
        private static final int PORT = 15740;
        private static final int OK = 0x2001;
        private Socket cmd, evt;
        private DataInputStream in;
        private OutputStream out;
        private int tid = 0;
        private final UUID guid;
        private final String friendly;
        private volatile boolean closed;
        private boolean eos;
        volatile int lastInitFail;
        /** Called while the camera shows its "connect to this device?" prompt. */
        Runnable onAwaitingPairing;

        PtpIp(String host, Network n, UUID guid, String friendlyName) {
            super(host, n);
            this.guid = guid;
            this.friendly = friendlyName;
        }

        @Override String mode() { return "PTP/IP"; }

        private Socket sock() throws IOException {
            Socket s = network != null ? network.getSocketFactory().createSocket() : new Socket();
            s.connect(new InetSocketAddress(host, PORT), 6000);
            s.setSoTimeout(30000);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            return s;
        }

        @Override void connect() throws IOException {
            // The camera answers InitFail while its pairing prompt is on screen: retry for up to 90 s.
            long deadline = System.currentTimeMillis() + 90000;
            Packet ack;
            while (true) {
                cmd = sock();
                in = new DataInputStream(cmd.getInputStream());
                out = cmd.getOutputStream();
                byte[] name = (friendly + "\0").getBytes(StandardCharsets.UTF_16LE);
                ByteBuffer b = le(16 + name.length + 4);
                b.put(CanonBle.guidBytes(guid)).put(name).putInt(0x00010000); // GUID as raw bytes, same as sent over BLE (reversed there)
                send(1, b.array());
                ack = read();
                if (ack.type == 2) break;
                try { cmd.close(); } catch (Exception ignored) {}
                if (ack.type != 5) throw new IOException("Risposta PTP/IP inattesa " + ack.type);
                int reason = ack.payload.length >= 4 ? ack.le().getInt() : 0;
                lastInitFail = reason;
                // PTP/IP InitFail: 1 = initiator rejected, 2 = busy (pairing prompt / previous session), 3 = unspecified
                if (reason == 1)
                    throw new IOException("La fotocamera rifiuta questo telefono (InitFail " + reason + "). Sulla fotocamera elimina il telefono registrato, poi in AmpyLink tocca RESET ASSOCIAZIONE e ricollega");
                if (System.currentTimeMillis() > deadline) throw new IOException("Associazione rifiutata: conferma il telefono sul display della fotocamera");
                if (onAwaitingPairing != null) onAwaitingPairing.run();
                try { Thread.sleep(2000); } catch (InterruptedException e) { throw new IOException("Interrupted"); }
            }
            ByteBuffer ab = ack.le();
            int conn = ab.getInt();
            ab.position(ab.position() + 16); // responder GUID
            StringBuilder sb = new StringBuilder();
            while (ab.remaining() >= 2) { char ch = ab.getChar(); if (ch == 0) break; sb.append(ch); }
            cameraName = sb.toString();
            // event channel
            evt = sock();
            ByteBuffer e = le(4); e.putInt(conn);
            writePacket(evt.getOutputStream(), 3, e.array());
            Packet eack = readFrom(new DataInputStream(evt.getInputStream()));
            if (eack.type != 4) throw new IOException("Canale eventi rifiutato dalla fotocamera");
            startEventPump();
            Response r = op(0x1002, null, 1); // OpenSession
            if (r.code != OK && r.code != 0x201E) throw new IOException("OpenSession 0x" + Integer.toHexString(r.code));
            // Canon EOS: enable remote + event mode (ignored by non-Canon devices)
            try {
                Response rm = op(0x9114, null, 1);
                eos = rm.code == OK;
                op(0x9115, null, 1);
                if (eos) op(0x9116, null); // first GetEvent flushes the property dump
            } catch (IOException ignored) {}
            try {
                Response di = op(0x1001, null); // GetDeviceInfo → model name
                String model = deviceInfoModel(di.data);
                if (model != null && !model.isEmpty()) cameraName = model;
            } catch (IOException ignored) {}
        }

        /** PTP/IP responders send ProbeRequest on the event channel and drop us if unanswered. */
        private void startEventPump() {
            final Socket s = evt;
            Thread t = new Thread(() -> {
                try {
                    DataInputStream ein = new DataInputStream(s.getInputStream());
                    OutputStream eout = s.getOutputStream();
                    s.setSoTimeout(0);
                    while (!closed) {
                        Packet p = readFrom(ein);
                        if (p.type == 13) synchronized (s) { writePacket(eout, 14, new byte[0]); }
                    }
                } catch (Exception ignored) {}
            }, "ptpip-events");
            t.setDaemon(true);
            t.start();
        }

        private static String deviceInfoModel(byte[] d) {
            try {
                ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
                b.getShort(); b.getInt(); b.getShort();
                ptpString(b);           // vendor extension desc
                b.getShort();           // functional mode
                for (int k = 0; k < 5; k++) { int n = b.getInt(); b.position(b.position() + 2 * n); } // ops, events, props, capture fmts, image fmts
                ptpString(b);           // manufacturer
                return ptpString(b);    // model
            } catch (Exception e) { return null; }
        }

        @Override List<Item> list(Progress prog) throws IOException {
            List<Item> cache = new ArrayList<>();
            Response st = op(0x1004, null); // GetStorageIDs
            if (st.code != OK) throw new IOException("GetStorageIDs 0x" + Integer.toHexString(st.code));
            ByteBuffer sb = ByteBuffer.wrap(st.data).order(ByteOrder.LITTLE_ENDIAN);
            int ns = sb.getInt();
            for (int i = 0; i < ns; i++) {
                int sid = sb.getInt();
                if ((sid & 0xFFFF) == 0) continue; // storage not present
                walk(sid, 0xFFFFFFFF, 0, "", cache, prog);
            }
            return cache;
        }

        private void walk(int storage, int parent, int depth, String folder, List<Item> cache, Progress prog) throws IOException {
            if (depth > 4) return;
            Response h = op(0x1007, null, storage, 0, parent); // GetObjectHandles
            if (h.code != OK || h.data.length < 4) return;
            ByteBuffer hb = ByteBuffer.wrap(h.data).order(ByteOrder.LITTLE_ENDIAN);
            int n = hb.getInt();
            for (int i = 0; i < n && hb.remaining() >= 4; i++) {
                int handle = hb.getInt();
                Response info = op(0x1008, null, handle); // GetObjectInfo
                if (info.code != OK || info.data.length < 53) continue;
                ByteBuffer ib = ByteBuffer.wrap(info.data).order(ByteOrder.LITTLE_ENDIAN);
                ib.getInt(); int format = ib.getShort() & 0xFFFF; ib.getShort();
                long size = ib.getInt() & 0xFFFFFFFFL;
                ib.position(26);
                int pw = ib.getInt(), ph = ib.getInt();
                ib.position(52);
                String fname = ptpString(ib);
                String capture = ib.remaining() > 0 ? ptpString(ib) : "";
                String modified = ib.remaining() > 0 ? ptpString(ib) : "";
                if (format == 0x3001) walk(storage, handle, depth + 1, fname, cache, prog);
                else if (isImage(fname)) {
                    Item it = new Item(fname, null, handle, size);
                    it.folder = folder;
                    it.w = pw; it.h = ph;
                    it.time = ptpDate(!capture.isEmpty() ? capture : modified);
                    cache.add(it);
                    Sink sk = onItem; if (sk != null) sk.on(it);
                    if (prog != null && cache.size() % 10 == 0) prog.on(cache.size(), -1);
                }
            }
        }

        static long ptpDate(String s) {
            if (s == null || s.length() < 15) return 0;
            try { return new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).parse(s.substring(0, 15)).getTime(); }
            catch (Exception e) { return 0; }
        }

        @Override byte[] thumbnail(Item it) throws IOException {
            Response r = op(0x100A, null, it.handle);
            if (r.code != OK) throw new IOException("GetThumb 0x" + Integer.toHexString(r.code));
            return r.data;
        }

        @Override byte[] head(Item it, int bytes) throws IOException {
            int n = (int) Math.min(bytes, it.size > 0 ? it.size : bytes);
            Response r = op(0x101B, null, it.handle, 0, n); // GetPartialObject
            if (r.code == OK && r.data.length > 0) return r.data;
            if (eos) {
                r = op(0x9107, null, it.handle, 0, n); // Canon EOS GetPartialObject
                if (r.code == OK && r.data.length > 0) return r.data;
            }
            return null;
        }

        @Override void download(Item it, File f, Progress p) throws IOException {
            Response r;
            try (OutputStream o = new FileOutputStream(f)) {
                r = opStream(0x1009, o, p, it.handle); // GetObject
            }
            if (r.code != OK) { f.delete(); throw new IOException("GetObject 0x" + Integer.toHexString(r.code)); }
        }

        @Override void keepAlive() {
            if (closed) return;
            try { if (eos) op(0x9116, null); else op(0x1014, null, 0x5001); } catch (Exception ignored) {}
        }

        @Override void close() {
            if (closed) return;
            try { op(0x1003, null); } catch (Exception ignored) {}
            closed = true;
            try { if (cmd != null) cmd.close(); } catch (Exception ignored) {}
            try { if (evt != null) evt.close(); } catch (Exception ignored) {}
        }

        // --- transport

        private static final class Packet {
            int type; byte[] payload;
            ByteBuffer le() { return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN); }
        }
        static final class Response { int code; byte[] data = new byte[0]; }

        private static ByteBuffer le(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }

        private void send(int type, byte[] payload) throws IOException { writePacket(out, type, payload); }

        private static void writePacket(OutputStream o, int type, byte[] payload) throws IOException {
            ByteBuffer h = le(8); h.putInt(8 + payload.length).putInt(type);
            o.write(h.array()); o.write(payload); o.flush();
        }

        private Packet read() throws IOException { return readFrom(in); }

        private static Packet readFrom(DataInputStream s) throws IOException {
            byte[] h = new byte[8]; s.readFully(h);
            ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            int len = hb.getInt(); Packet p = new Packet(); p.type = hb.getInt();
            if (len < 8 || len > 64 << 20) throw new IOException("Bad PTP/IP packet");
            p.payload = new byte[len - 8]; s.readFully(p.payload);
            return p;
        }

        Response op(int code, OutputStream sink, int... params) throws IOException {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            Response r = opStream(code, sink != null ? sink : bo, null, params);
            if (sink == null) r.data = bo.toByteArray();
            return r;
        }

        private synchronized Response opStream(int code, OutputStream sink, Progress prog, int... params) throws IOException {
            if (closed) throw new IOException("Fotocamera scollegata");
            int t = ++tid;
            ByteBuffer b = le(4 + 2 + 4 + 4 * params.length);
            b.putInt(1).putShort((short) code).putInt(t);
            for (int p : params) b.putInt(p);
            send(6, b.array());
            long total = -1, done = 0;
            while (true) {
                Packet p = read();
                ByteBuffer pb = p.le();
                switch (p.type) {
                    case 9: pb.getInt(); total = pb.getLong(); break;                       // StartData
                    case 10: case 12: pb.getInt();                                          // Data / EndData
                        sink.write(p.payload, 4, p.payload.length - 4);
                        done += p.payload.length - 4;
                        if (prog != null) prog.on(done, total);
                        break;
                    case 7: { Response r = new Response(); r.code = pb.getShort() & 0xFFFF; return r; } // OperationResponse
                    case 8: case 13: break; // event / probe on command channel – ignore
                    default: throw new IOException("PTP/IP packet type " + p.type);
                }
            }
        }

        private static String ptpString(ByteBuffer b) {
            int n = b.get() & 0xFF;
            if (n == 0) return "";
            char[] c = new char[n];
            for (int i = 0; i < n; i++) c[i] = b.getChar();
            return new String(c, 0, Math.max(0, n - 1));
        }
    }
}
