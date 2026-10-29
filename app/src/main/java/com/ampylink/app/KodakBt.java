/*
 * Direct Bluetooth printing to KODAK Mini 2 Retro (P210R, 4PASS dye-sub, 2.1"×3.4", 640×1024).
 *
 * Interoperability implementation of the Prinics "PPVP" link used by the KODAK Photo Printer app
 * over classic Bluetooth SPP (RFCOMM, UUID 00001101-…):
 *   every packet = 8-byte header [cmd, p1, p2, p3, length(u32 big-endian)] + payload.
 *   printer → phone                         phone → printer
 *   0x02 hello (p3 = model code)            0x02 p1=1 p2=0x25            (hello ack)
 *   0x64 keep-alive                         0x64 p1=1
 *   0x04/1 ready for a job                  0x05 p1=copies, payload[12] = {jpegLength u32, 0…}
 *   0x08|0x11 data request {off,len}        0x09 p1,p2 echoed, payload = jpeg[off, off+len)
 *   0x04/3 printing (p2 = copy, j[7]=pass)  —
 *   0x04/4 error (p2 = code)                —
 *   0x04/6 job done                         0x01                          (job close)
 *   0x01/5 status poll                      0x07 p1=p3
 *   (silence)                               0x03                          (status request)
 * The image is a baseline JPEG of the card, zero-padded to at least 33 792 bytes.
 * GPL v3.
 */
package com.ampylink.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@SuppressLint("MissingPermission")
final class KodakBt {
    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static final int MIN_JOB = 33792;

    interface Listener {
        /** progress 0..1 (negative = waiting), pass 0..3 while printing (Y, M, C, overcoat) or -1. */
        void onState(String text, float progress, int pass);
    }

    /** Transport abstraction so the state machine can be unit-tested off-device. */
    interface Link {
        InputStream in() throws IOException;
        OutputStream out() throws IOException;
        void close();
    }

    static final class Packet {
        final int cmd, p1, p2, p3; final byte[] data;
        Packet(int cmd, int p1, int p2, int p3, byte[] data) { this.cmd = cmd; this.p1 = p1; this.p2 = p2; this.p3 = p3; this.data = data; }
    }

    private final Link link;
    private final LinkedBlockingQueue<Object> rx = new LinkedBlockingQueue<>();
    private volatile boolean closed;
    private Thread reader;
    int modelCode = -1;
    String deviceName = "", firmware = "";

    KodakBt(Link link) { this.link = link; }

    // ------------------------------------------------------------------ device helpers

    static BluetoothAdapter adapter(Context c) {
        BluetoothManager m = c.getSystemService(BluetoothManager.class);
        return m != null ? m.getAdapter() : null;
    }

    static boolean looksLikeKodak(String n) {
        if (n == null) return false;
        String u = n.toUpperCase(Locale.US);
        return u.contains("KODAK") || u.contains("PICKIT") || u.contains("MINI 2") || u.contains("P210");
    }

    static List<BluetoothDevice> bondedPrinters(Context c) {
        List<BluetoothDevice> r = new ArrayList<>();
        BluetoothAdapter a = adapter(c);
        if (a == null) return r;
        try {
            for (BluetoothDevice d : a.getBondedDevices()) if (looksLikeKodak(d.getName())) r.add(d);
        } catch (SecurityException ignored) {}
        return r;
    }

    /** RFCOMM to the printer: insecure SPP (as the KODAK app), then secure, then channel 1. */
    static KodakBt open(Context ctx, BluetoothDevice dev) throws IOException {
        BluetoothAdapter a = adapter(ctx);
        if (a != null && a.isDiscovering()) a.cancelDiscovery();
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            BluetoothSocket s = null;
            try {
                if (attempt == 0) s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                else if (attempt == 1) s = dev.createRfcommSocketToServiceRecord(SPP);
                else s = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
                s.connect();
                final BluetoothSocket sock = s;
                KodakBt k = new KodakBt(new Link() {
                    @Override public InputStream in() throws IOException { return sock.getInputStream(); }
                    @Override public OutputStream out() throws IOException { return sock.getOutputStream(); }
                    @Override public void close() { try { sock.close(); } catch (IOException ignored) {} }
                });
                k.deviceName = dev.getName() != null ? dev.getName() : "";
                k.start();
                return k;
            } catch (Exception e) {
                last = e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
                if (s != null) try { s.close(); } catch (IOException ignored) {}
                try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
            }
        }
        throw last != null ? last : new IOException("Connessione Bluetooth non riuscita");
    }

    // ------------------------------------------------------------------ transport

    void start() throws IOException {
        final InputStream in = link.in();
        reader = new Thread(() -> {
            try {
                byte[] h = new byte[8];
                while (!closed) {
                    readFully(in, h, 8);
                    int len = ((h[4] & 0xFF) << 24) | ((h[5] & 0xFF) << 16) | ((h[6] & 0xFF) << 8) | (h[7] & 0xFF);
                    if (len < 0 || len > (4 << 20)) len = 0;
                    byte[] p = new byte[len];
                    if (len > 0) readFully(in, p, len);
                    rx.put(new Packet(h[0] & 0xFF, h[1] & 0xFF, h[2] & 0xFF, h[3] & 0xFF, p));
                }
            } catch (Exception e) {
                if (!closed) rx.offer(e instanceof IOException ? e : new IOException(String.valueOf(e.getMessage())));
            }
        }, "kodak-rx");
        reader.setDaemon(true);
        reader.start();
    }

    private static void readFully(InputStream in, byte[] b, int n) throws IOException {
        int o = 0;
        while (o < n) {
            int r = in.read(b, o, n - o);
            if (r < 0) throw new IOException("Connessione chiusa dalla stampante");
            o += r;
        }
    }

    static byte[] packet(int cmd, int p1, int p2, byte[] payload) {
        int n = payload == null ? 0 : payload.length;
        byte[] b = new byte[8 + n];
        b[0] = (byte) cmd; b[1] = (byte) p1; b[2] = (byte) p2; b[3] = 0;
        b[4] = (byte) (n >> 24); b[5] = (byte) (n >> 16); b[6] = (byte) (n >> 8); b[7] = (byte) n;
        if (n > 0) System.arraycopy(payload, 0, b, 8, n);
        return b;
    }

    private synchronized void send(int cmd, int p1, int p2, byte[] payload) throws IOException {
        byte[] b = packet(cmd, p1, p2, payload);
        OutputStream o = link.out();
        for (int i = 0; i < b.length; i += 2048) { // SPP: 2 KB writes like the KODAK app
            o.write(b, i, Math.min(2048, b.length - i));
            o.flush();
            if (b.length > 2048) try { Thread.sleep(1); } catch (InterruptedException e) { throw new IOException("Interrupted"); }
        }
    }

    void close() {
        closed = true;
        link.close();
        if (reader != null) reader.interrupt();
    }

    static byte[] padJob(byte[] jpeg) {
        if (jpeg.length >= MIN_JOB) return jpeg;
        byte[] b = new byte[MIN_JOB];
        System.arraycopy(jpeg, 0, b, 0, jpeg.length);
        return b;
    }

    static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    // ------------------------------------------------------------------ job

    /** Blocking: sends one card and waits for the printer to finish all copies. */
    void print(byte[] jpeg, int copies, Listener l) throws IOException {
        final byte[] job = padJob(jpeg);
        boolean jobSent = false, dataStarted = false;
        long sent = 0;
        long lastRx = System.currentTimeMillis(), lastPoke = 0;
        int pokes = 0, stage = 0;
        long t0 = System.currentTimeMillis();
        l.onState("HANDSHAKE…", -1, -1);
        while (true) {
            Object o;
            try { o = rx.poll(100, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { throw new IOException("Annullato"); }
            long now = System.currentTimeMillis();
            if (o instanceof IOException) throw (IOException) o;
            if (o == null) {
                long silence = now - lastRx;
                // wake the printer up on a fresh link: keep-alive first, then status request
                if (stage == 0 && now - t0 > 1200) { send(0x64, 1, 0, null); stage = 1; lastPoke = now; }
                else if (stage == 1 && now - lastPoke > 1500) { send(0x03, 0, 0, null); stage = 2; lastPoke = now; }
                long limit = dataStarted ? 45000 : 15000;
                if (silence > 5000 && now - lastPoke > 5000 && pokes < 3) { send(0x03, 0, 0, null); pokes++; lastPoke = now; }
                if (silence > limit) throw new IOException(jobSent ? "La stampante non risponde più" : "La stampante non risponde: è accesa e associata?");
                continue;
            }
            Packet p = (Packet) o;
            lastRx = now; pokes = 0; stage = Math.max(stage, 2);
            switch (p.cmd) {
                case 0x02: // hello / model
                    modelCode = p.p3;
                    send(0x02, 1, 0x25, null);
                    l.onState("STAMPANTE ONLINE", -1, -1);
                    break;
                case 0x64:
                    send(0x64, 1, 0, null);
                    break;
                case 0x01:
                    if (p.p1 == 5) send(0x07, p.p3, 0, null);
                    else if (p.p1 == 0x53 && p.p2 == 0) send(0x54, 0, 0, null);
                    break;
                case 0x04:
                    switch (p.p1) {
                        case 1: // ready: announce the job
                            if (!jobSent) {
                                byte[] hdr = new byte[12];
                                hdr[0] = (byte) (job.length >> 24); hdr[1] = (byte) (job.length >> 16);
                                hdr[2] = (byte) (job.length >> 8); hdr[3] = (byte) job.length;
                                send(0x05, Math.max(1, Math.min(copies, 9)), 0, hdr);
                                jobSent = true;
                                l.onState("INVIO LAVORO…", 0f, -1);
                            }
                            break;
                        case 3: { // printing
                            int pass = p.data.length > 7 ? (p.data[7] & 0xFF) : -1;
                            String copy = copies > 1 ? " · COPIA " + Math.max(1, p.p2) + "/" + copies : "";
                            l.onState("STAMPA 4PASS" + (pass >= 0 && pass < 4 ? " · " + PASSES[pass] : "") + copy, 1f, pass);
                            break;
                        }
                        case 4:
                            throw new IOException(errorText(p.p2, p.p3));
                        case 5:
                            throw new IOException("Avviso stampante (4/5): togli la foto dall'uscita e riprova");
                        case 6:
                            if (jobSent) {
                                send(0x01, 0, 0, null);
                                l.onState("STAMPA COMPLETATA ✓", 1f, 3);
                                try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                                return;
                            }
                            break;
                        case 8:
                            throw new IOException("Stampa annullata dalla stampante");
                        default:
                    }
                    break;
                case 0x08: case 0x11: { // data request
                    if (p.data.length < 8) break;
                    int off = be32(p.data, 0), n = be32(p.data, 4);
                    if (off < 0 || off > job.length) off = job.length;
                    n = Math.max(0, Math.min(n, job.length - off));
                    byte[] chunk = new byte[n];
                    System.arraycopy(job, off, chunk, 0, n);
                    send(0x09, p.p1, p.p2, chunk);
                    dataStarted = true;
                    sent = Math.max(sent, off + n);
                    l.onState("TRASFERIMENTO " + (sent * 100 / job.length) + "%  ·  " + (sent >> 10) + " / " + (job.length >> 10) + " KB", sent / (float) job.length, -1);
                    break;
                }
                case 0x32: // printer state (temperature / cooling)
                    if (p.p1 > 54) l.onState("STAMPANTE IN RAFFREDDAMENTO… ATTENDI", -1, -1);
                    break;
                case 0x70: // device info
                    deviceName = ascii(p.data, 0x35, 32, deviceName);
                    firmware = ascii(p.data, 0x55, 32, firmware);
                    break;
                case 0x65:
                    if (jobSent && dataStarted) { l.onState("STAMPA COMPLETATA ✓", 1f, 3); return; }
                    throw new IOException("La stampante ha chiuso la sessione");
                default:
            }
        }
    }

    static final String[] PASSES = {"GIALLO", "MAGENTA", "CIANO", "LAMINATO"};

    private static String ascii(byte[] d, int o, int max, String def) {
        if (d.length <= o) return def;
        int n = 0; while (n < max && o + n < d.length && d[o + n] > 0) n++;
        return n == 0 ? def : new String(d, o, n, StandardCharsets.US_ASCII);
    }

    static String errorText(int code, int sub) {
        String why;
        switch (code) {
            case 4: why = "sportello aperto o cartuccia non inserita"; break;
            case 20: why = sub == 2 ? "cartuccia esaurita: sostituiscila" : "controlla la carta: assente o non alimentata"; break;
            case 21: why = "cartuccia non riconosciuta (usa cartucce KODAK 2.1×3.4)"; break;
            case 22: why = sub == 2 ? "cartuccia senza inchiostro o carta" : "errore cartuccia: reinseriscila"; break;
            case 23: why = "carta inceppata: riavvia la stampante e rimuovi il foglio"; break;
            case 28: why = "errore carta: reinserisci la cartuccia"; break;
            default: why = "errore stampante";
        }
        return "Errore stampante " + code + (sub != 0 ? "/" + sub : "") + ": " + why;
    }
}
