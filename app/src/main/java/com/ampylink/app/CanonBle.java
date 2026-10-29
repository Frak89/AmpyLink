/*
 * Canon EOS Bluetooth LE link, as Camera Connect does it (EOS R6 Mark II and other BLE bodies):
 *  1. pairing      0001-service: write 00010006 = {0x01, phone name}; camera asks to confirm;
 *                  indication 00010006[0] == 0x02 → paired.
 *  2. identity     write 0001000a = {0x03, GUID(LE)}, {0x04, nick name}, {0x05, 0x02 (Android)}.
 *                  The GUID is the same one AmpyLink sends in the PTP/IP Init, so the camera
 *                  trusts the Wi-Fi session without asking again.
 *  3. AP config    write 00020002 = {0x0A}; notification 00020002 = TLV {0x10 IP, 0x02 BSSID};
 *                  read 00020004 SSID, 00020006 password, 00020005 security types;
 *                  then 0001000a = {0x01} (setup complete).
 *  4. handover     write 00020002 = {0x01} START; indication 00020003 = {1,3} accepted →
 *                  the camera powers its Wi-Fi access point; we join it and open PTP/IP.
 * GPL v3.
 */
package com.ampylink.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.ParcelUuid;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@SuppressLint("MissingPermission")
final class CanonBle {
    static final UUID SVC_SYSTEM = u("00010000"), CH_RESPONSE = u("00010005"), CH_PAIR = u("00010006"),
        CH_COMMAND = u("0001000a"), CH_INIT = u("0001000b"), CH_INFO = u("0001000c");
    static final UUID SVC_WAP = u("00020000"), CH_WAP_CP = u("00020002"), CH_WAP_STATUS = u("00020003"),
        CH_SSID = u("00020004"), CH_SECURITY = u("00020005"), CH_PASSWORD = u("00020006");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static UUID u(String head) { return UUID.fromString(head + "-0000-1000-0000-d8492fffa821"); }

    interface Status { void on(String s); }

    /** What the camera tells us about its Wi-Fi access point. */
    static final class Ap {
        String ssid = "", password = "", ip = "192.168.1.2", bssid = "";
        List<Integer> security = new ArrayList<>();
    }

    // ------------------------------------------------------------------ scanning

    interface Found { void on(BluetoothDevice d, String name, int rssi); }

    /** BLE scan for Canon cameras (they advertise the 00010000 service). Returns a stopper. */
    static Runnable scan(Context c, Found cb) {
        BluetoothAdapter a = KodakBt.adapter(c);
        if (a == null || a.getBluetoothLeScanner() == null) return () -> {};
        final BluetoothLeScanner sc = a.getBluetoothLeScanner();
        final ScanCallback scb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                String n = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                if (n == null) try { n = r.getDevice().getName(); } catch (SecurityException ignored) {}
                cb.on(r.getDevice(), n != null ? n : "Canon EOS", r.getRssi());
            }
        };
        List<ScanFilter> f = Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC_SYSTEM)).build());
        try { sc.startScan(f, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scb); }
        catch (SecurityException e) { return () -> {}; }
        return () -> { try { sc.stopScan(scb); } catch (Exception ignored) {} };
    }

    // ------------------------------------------------------------------ GATT plumbing (one op at a time)

    private final Context ctx;
    private final BluetoothDevice dev;
    private BluetoothGatt gatt;
    private final LinkedBlockingQueue<Object[]> ops = new LinkedBlockingQueue<>();     // op completions
    private final LinkedBlockingQueue<Object[]> events = new LinkedBlockingQueue<>();  // notifications
    private volatile int connState = -1;
    /** Ask the camera to register AmpyLink even if the phone is already bonded (one confirmation). */
    volatile boolean forcePair;

    CanonBle(Context ctx, BluetoothDevice dev) { this.ctx = ctx.getApplicationContext(); this.dev = dev; }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            connState = state;
            ops.offer(new Object[]{"conn", status, state});
            if (state == BluetoothProfile.STATE_DISCONNECTED) events.offer(new Object[]{"disc"});
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) { ops.offer(new Object[]{"svc", status}); }
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) { ops.offer(new Object[]{"mtu", status}); }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            ops.offer(new Object[]{"read", status, c.getValue() != null ? c.getValue().clone() : new byte[0]});
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) { ops.offer(new Object[]{"write", status}); }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { ops.offer(new Object[]{"desc", status}); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            events.offer(new Object[]{c.getUuid(), c.getValue() != null ? c.getValue().clone() : new byte[0]});
        }
    };

    private Object[] await(String kind, long ms) throws IOException {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) throw new IOException("Bluetooth: timeout (" + kind + ")");
            Object[] r;
            try { r = ops.poll(left, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { throw new IOException("Annullato"); }
            if (r == null) continue;
            if ("conn".equals(r[0]) && (Integer) r[2] == BluetoothProfile.STATE_DISCONNECTED && !"conn".equals(kind))
                throw new IOException("Bluetooth: la fotocamera si è disconnessa");
            if (kind.equals(r[0])) return r;
        }
    }

    private BluetoothGattCharacteristic ch(UUID svc, UUID c) throws IOException {
        BluetoothGattService s = gatt.getService(svc);
        BluetoothGattCharacteristic x = s != null ? s.getCharacteristic(c) : null;
        if (x == null) throw new IOException("Bluetooth: servizio Canon non trovato (" + c.toString().substring(0, 8) + ")");
        return x;
    }

    private BluetoothGattCharacteristic opt(UUID svc, UUID c) {
        BluetoothGattService s = gatt.getService(svc);
        return s != null ? s.getCharacteristic(c) : null;
    }

    private byte[] read(BluetoothGattCharacteristic c) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!gatt.readCharacteristic(c)) { sleep(300); continue; }
            Object[] r = await("read", 8000);
            int st = (Integer) r[1];
            if (st == BluetoothGatt.GATT_SUCCESS) return (byte[]) r[2];
            if (st == 5 || st == 15) { bond(); continue; } // insufficient authentication / encryption
            throw new IOException("Bluetooth: lettura fallita (" + st + ")");
        }
        throw new IOException("Bluetooth: lettura non riuscita");
    }

    private void write(BluetoothGattCharacteristic c, byte[] v) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            c.setValue(v);
            if (!gatt.writeCharacteristic(c)) { sleep(300); continue; }
            Object[] r = await("write", 8000);
            int st = (Integer) r[1];
            if (st == BluetoothGatt.GATT_SUCCESS) return;
            if (st == 5 || st == 15) { bond(); continue; }
            throw new IOException("Bluetooth: scrittura fallita (" + st + ")");
        }
        throw new IOException("Bluetooth: scrittura non riuscita");
    }

    /** Enable notifications or indications, whichever the characteristic offers. */
    private void subscribe(BluetoothGattCharacteristic c) throws IOException {
        if (c == null) return;
        int p = c.getProperties();
        boolean ind = (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0, not = (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
        if (!ind && !not) return;
        gatt.setCharacteristicNotification(c, true);
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) return;
        d.setValue(ind ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!gatt.writeDescriptor(d)) { sleep(300); continue; }
            int st = (Integer) await("desc", 8000)[1];
            if (st == BluetoothGatt.GATT_SUCCESS) return;
            if (st == 5 || st == 15) { bond(); continue; }
            return;
        }
    }

    private byte[] event(UUID c, long ms) throws IOException {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return null;
            Object[] e;
            try { e = events.poll(left, TimeUnit.MILLISECONDS); } catch (InterruptedException x) { throw new IOException("Annullato"); }
            if (e == null) return null;
            if ("disc".equals(e[0])) throw new IOException("Bluetooth: la fotocamera si è disconnessa");
            if (c.equals(e[0])) return (byte[]) e[1];
        }
    }

    private void bond() throws IOException {
        if (dev.getBondState() == BluetoothDevice.BOND_BONDED) { sleep(500); return; }
        if (dev.getBondState() != BluetoothDevice.BOND_BONDING) dev.createBond();
        long end = System.currentTimeMillis() + 30000;
        while (dev.getBondState() != BluetoothDevice.BOND_BONDED) {
            if (System.currentTimeMillis() > end) throw new IOException("Bluetooth: associazione non completata");
            sleep(250);
        }
        sleep(600);
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    // ------------------------------------------------------------------ sequence

    /** Blocking. Pairs if needed, reads the AP config and asks the camera to start its Wi-Fi. */
    Ap connectAndStartWifi(boolean firstPairing, UUID guid, String phoneName, Status st) throws IOException {
        connect(firstPairing, guid, phoneName, st);
        return requestWifi(st);
    }

    boolean isConnected() { return gatt != null && connState == BluetoothProfile.STATE_CONNECTED; }
    String address() { return dev.getAddress(); }

    /** Blocks until the link drops (background service keeps the camera "attached"). */
    void awaitDisconnect() throws InterruptedException {
        while (isConnected()) Thread.sleep(1000);
    }

    /** Connect, (pair), register the phone — the part the background service keeps alive. */
    synchronized void connect(boolean firstPairing, UUID guid, String phoneName, Status st) throws IOException {
        st.on("BLUETOOTH · CONNESSIONE A " + safeName() + "…");
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        Object[] c;
        try {
            c = await("conn", 12000);
            if ((Integer) c[2] != BluetoothProfile.STATE_CONNECTED) throw new IOException("stato " + c[1]);
        } catch (IOException first) {
            // camera off / asleep: it only advertises now and then — let Android wait for it (autoConnect)
            Trace.log("BLE connessione diretta fallita (" + first.getMessage() + "): attesa con autoConnect");
            try { gatt.close(); } catch (Exception ignored) {}
            ops.clear();
            st.on("BLUETOOTH · ATTESA FOTOCAMERA (anche spenta, con Bluetooth attivo)…");
            gatt = dev.connectGatt(ctx, true, cb, BluetoothDevice.TRANSPORT_LE);
            c = await("conn", 60000);
            if ((Integer) c[2] != BluetoothProfile.STATE_CONNECTED) throw new IOException("Bluetooth: la fotocamera non risponde (" + c[1] + ")");
        }
        Trace.log("BLE connesso a " + safeName() + " · bond=" + dev.getBondState());
        sleep(400);
        gatt.requestMtu(185);
        try { await("mtu", 3000); } catch (IOException ignored) {}
        if (!gatt.discoverServices()) throw new IOException("Bluetooth: discovery servizi fallita");
        await("svc", 15000);
        if (gatt.getService(SVC_SYSTEM) == null) throw new IOException("Non è una fotocamera Canon compatibile (servizio BLE assente)");
        final boolean wasBonded = dev.getBondState() == BluetoothDevice.BOND_BONDED;
        if (!wasBonded) { st.on("BLUETOOTH · ASSOCIAZIONE ANDROID…"); bond(); }

        BluetoothGattCharacteristic pair = opt(SVC_SYSTEM, CH_PAIR), cmd = ch(SVC_SYSTEM, CH_COMMAND);
        subscribe(opt(SVC_SYSTEM, CH_RESPONSE));
        subscribe(pair);
        subscribe(opt(SVC_SYSTEM, CH_INFO));
        subscribe(opt(SVC_WAP, CH_WAP_CP));
        subscribe(opt(SVC_WAP, CH_WAP_STATUS));

        if (firstPairing && wasBonded && !forcePair) Trace.log("BLE: già associata al telefono → nessuna richiesta di conferma, passo al Wi-Fi");
        if (forcePair) Trace.log("BLE: associazione AmpyLink richiesta (fotocamera su «Aggiungi dispositivo»)");
        if (pair != null && ((firstPairing && !wasBonded) || forcePair)) {
            st.on("CONFERMA «" + phoneName + "» SUL DISPLAY DELLA FOTOCAMERA…");
            boolean ok = false;
            try {
                write(pair, concat(new byte[]{1}, ascii(phoneName)));
                // already bonded (e.g. by Camera Connect): don't block on a prompt that may never come
                byte[] r = event(CH_PAIR, wasBonded && !forcePair ? 12000 : 90000);
                Trace.log("BLE pairing → " + (r == null ? "nessuna risposta" : "risposta " + (r.length > 0 ? r[0] : -1)));
                if (r != null && r.length > 0 && r[0] == 2) ok = true;
                else if (!wasBonded || forcePair) throw new IOException(r == null ? "Associazione non confermata sulla fotocamera" : "Associazione rifiutata dalla fotocamera (" + (r.length > 0 ? r[0] : -1) + ")");
            } catch (IOException e) {
                if (!wasBonded || forcePair) throw e;
                Trace.log("BLE pairing saltato (già associata): " + e.getMessage());
            }
            if (!ok) st.on("BLUETOOTH · FOTOCAMERA GIÀ ASSOCIATA, PROSEGUO…");
        }
        BluetoothGattCharacteristic init = opt(SVC_SYSTEM, CH_INIT);
        if (init != null && (init.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) try { read(init); } catch (IOException ignored) {}
        if (firstPairing) {
            st.on("BLUETOOTH · REGISTRAZIONE TELEFONO…");
            byte[] g = guidBytes(guid);
            reverse(g);
            try {
                write(cmd, concat(new byte[]{3}, g));
                write(cmd, concat(new byte[]{4}, ascii(phoneName)));
                write(cmd, new byte[]{5, 2});
            } catch (IOException e) {
                if (!wasBonded) throw e;
                Trace.log("BLE identità non accettata: " + e.getMessage());
            }
        }

    }

    /** Ask the camera for its access point and power it on (handover). */
    synchronized Ap requestWifi(Status st) throws IOException {
        if (!isConnected()) throw new IOException("Bluetooth: fotocamera non collegata");
        BluetoothGattCharacteristic cmd = ch(SVC_SYSTEM, CH_COMMAND);
        events.clear();
        st.on("BLUETOOTH · RICHIESTA CONFIGURAZIONE WI-FI…");
        Ap ap = new Ap();
        BluetoothGattCharacteristic wap = ch(SVC_WAP, CH_WAP_CP);
        write(wap, new byte[]{0x0A});
        byte[] cfg = event(CH_WAP_CP, 8000);
        if (cfg != null) parseTlv(cfg, ap);
        ap.ssid = trim(read(ch(SVC_WAP, CH_SSID)));
        ap.password = trim(read(ch(SVC_WAP, CH_PASSWORD)));
        BluetoothGattCharacteristic sec = opt(SVC_WAP, CH_SECURITY);
        if (sec != null) try {
            byte[] s = read(sec);
            reverse(s);
            for (int i = s.length / 4 - 1; i >= 0; i--)
                ap.security.add(((s[i * 4] & 255) << 24) | ((s[i * 4 + 1] & 255) << 16) | ((s[i * 4 + 2] & 255) << 8) | (s[i * 4 + 3] & 255));
        } catch (IOException ignored) {}
        write(cmd, new byte[]{1}); // setup sequence complete
        if (ap.ssid.isEmpty()) throw new IOException("La fotocamera non ha inviato l'SSID Wi-Fi");

        st.on("BLUETOOTH · AVVIO WI-FI FOTOCAMERA (" + ap.ssid + ")…");
        write(wap, new byte[]{1}); // START handover
        long end = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < end) {
            byte[] s = event(CH_WAP_STATUS, end - System.currentTimeMillis());
            if (s == null || s.length < 2) break;
            if ((s[0] == 1 || s[0] == 2) && s[1] == 3) return ap;          // accepted
            if (s[0] == 1 && s[1] >= 4 && s[1] <= 9) throw new IOException("La fotocamera ha rifiutato l'avvio del Wi-Fi (codice " + s[1] + "): chiudi il menu e riprova");
        }
        return ap; // some bodies start the AP without a status indication
    }

    /** Keeps the BLE link (camera stays awake); call when unlinking. */
    void close() {
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (Exception ignored) {}
        gatt = null;
    }

    private String safeName() { try { String n = dev.getName(); return n != null ? n : dev.getAddress(); } catch (SecurityException e) { return dev.getAddress(); } }

    static void parseTlv(byte[] v, Ap ap) {
        int i = 2;
        while (i + 2 <= v.length) {
            int t = v[i] & 255, n = v[i + 1] & 255;
            if (i + 2 + n > v.length) break;
            byte[] d = java.util.Arrays.copyOfRange(v, i + 2, i + 2 + n);
            if (t == 0x10 && n == 4) { reverse(d); ap.ip = (d[0] & 255) + "." + (d[1] & 255) + "." + (d[2] & 255) + "." + (d[3] & 255); }
            else if (t == 0x02 && n == 6) { reverse(d); ap.bssid = String.format(Locale.US, "%02x:%02x:%02x:%02x:%02x:%02x", d[0], d[1], d[2], d[3], d[4], d[5]); }
            i += 2 + n;
        }
    }

    static byte[] guidBytes(UUID g) {
        return java.nio.ByteBuffer.allocate(16).putLong(g.getMostSignificantBits()).putLong(g.getLeastSignificantBits()).array();
    }

    static void reverse(byte[] b) { for (int i = 0, j = b.length - 1; i < j; i++, j--) { byte t = b[i]; b[i] = b[j]; b[j] = t; } }
    private static byte[] ascii(String s) { return s.replaceAll("[^\\x20-\\x7E]", "_").getBytes(StandardCharsets.US_ASCII); }
    private static String trim(byte[] b) { int n = 0; while (n < b.length && b[n] != 0) n++; return new String(b, 0, n, StandardCharsets.US_ASCII).trim(); }
    private static byte[] concat(byte[] a, byte[] b) { byte[] r = new byte[a.length + b.length]; System.arraycopy(a, 0, r, 0, a.length); System.arraycopy(b, 0, r, a.length, b.length); return r; }

    // ------------------------------------------------------------------ joining the camera access point

    interface Joined { void on(Network n, String error); }

    /** Ask Android to join the camera AP (system "Connect to EOS…?" prompt). Keep the callback to stay joined. */
    static ConnectivityManager.NetworkCallback joinWifi(Context c, Ap ap, boolean wpa3, Joined done) {
        WifiNetworkSpecifier.Builder b = new WifiNetworkSpecifier.Builder().setSsid(ap.ssid);
        if (!ap.password.isEmpty()) {
            if (wpa3) b.setWpa3Passphrase(ap.password); else b.setWpa2Passphrase(ap.password);
        }
        NetworkRequest req = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(b.build())
            .build();
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        final boolean[] fired = {false};
        ConnectivityManager.NetworkCallback ncb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { if (!fired[0]) { fired[0] = true; done.on(n, null); } }
            @Override public void onUnavailable() { if (!fired[0]) { fired[0] = true; done.on(null, "Connessione al Wi-Fi " + ap.ssid + " non riuscita o annullata"); } }
        };
        cm.requestNetwork(req, ncb, 60000);
        return ncb;
    }
}
