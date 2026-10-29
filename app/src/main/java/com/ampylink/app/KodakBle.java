/*
 * KODAK Mini 2 Retro over Bluetooth LE ("Kodak Instant-XXXX" units), interoperable with the
 * KODAK Photo Printer app's BLE transport (z3.b):
 *   service 9877e0cb-a4d5-4860-a50b-ac10d83b6967, MTU 512, high connection priority
 *   characteristic[0] write (no response):
 *       job header  [len>>16, len>>8, len, 0, 0, 0x00, copies, 0, 0, 0]
 *       data chunk  [off>>16, off>>8, off, n>>8, n, data(n ≤ 200)…]   (next chunk on write callback)
 *   characteristic[1] status, polled every 600 ms:
 *       [1] state: 3/4/5 ready · 1 printing ([2] pass Y/M/C/OC, [10]+1 copy, [11] copies) · 0 idle
 *       [3] error (0 ok, 1 paper, 2 cartridge empty, 3 cartridge/cover, 4 jam, 6 paper error,
 *           9 battery, 10 cooling) · [6..7] temperature (≥55 cooling)
 * The JPEG card is zero-padded to ≥ 33 792 bytes as for SPP. GPL v3.
 */
package com.ampylink.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@SuppressLint("MissingPermission")
final class KodakBle implements PrinterLink {
    static final UUID SERVICE = UUID.fromString("9877e0cb-a4d5-4860-a50b-ac10d83b6967");
    static final UUID INFO_A = UUID.fromString("9877e0ce-a4d5-4860-a50b-ac10d83b6967");
    static final UUID INFO_B = UUID.fromString("9877e0cf-a4d5-4860-a50b-ac10d83b6967");
    static final int CHUNK = 200;

    private final BluetoothGatt gatt;
    private BluetoothGattCharacteristic wr, st;
    private final LinkedBlockingQueue<Object[]> ops;
    private volatile boolean closed;

    private KodakBle(BluetoothGatt g, LinkedBlockingQueue<Object[]> ops) { gatt = g; this.ops = ops; }

    static KodakBle open(Context ctx, BluetoothDevice dev) throws IOException {
        final LinkedBlockingQueue<Object[]> q = new LinkedBlockingQueue<>();
        BluetoothGattCallback cb = new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                Trace.log("KODAK BLE stato connessione=" + state + " status=" + status);
                q.offer(new Object[]{"conn", status, state});
            }
            @Override public void onServicesDiscovered(BluetoothGatt g, int status) { q.offer(new Object[]{"svc", status}); }
            @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) { q.offer(new Object[]{"mtu", mtu}); }
            @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                q.offer(new Object[]{"read", status, c.getValue() != null ? c.getValue().clone() : new byte[0]});
            }
            @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) { q.offer(new Object[]{"write", status}); }
        };
        BluetoothGatt g = dev.connectGatt(ctx.getApplicationContext(), false, cb, BluetoothDevice.TRANSPORT_LE);
        KodakBle k = new KodakBle(g, q);
        try {
            Object[] c = k.await("conn", 15000);
            if ((Integer) c[2] != BluetoothProfile.STATE_CONNECTED) throw new IOException("BLE: connessione rifiutata (" + c[1] + ")");
            sleep(300);
            if (!g.discoverServices()) throw new IOException("BLE: discovery fallita");
            k.await("svc", 15000);
            g.requestMtu(512);
            try { k.await("mtu", 4000); } catch (IOException ignored) {}
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            BluetoothGattService s = g.getService(SERVICE);
            if (s == null) throw new IOException("BLE: servizio stampante KODAK non trovato");
            List<BluetoothGattCharacteristic> cs = s.getCharacteristics();
            // same choice as the KODAK app: [0] = write, [1] = status; fall back on properties
            if (cs.size() >= 2) { k.wr = cs.get(0); k.st = cs.get(1); }
            for (BluetoothGattCharacteristic x : cs) {
                int p = x.getProperties();
                if (k.wr == null && (p & (BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE | BluetoothGattCharacteristic.PROPERTY_WRITE)) != 0) k.wr = x;
                if (k.st == null && (p & BluetoothGattCharacteristic.PROPERTY_READ) != 0 && x != k.wr && !INFO_A.equals(x.getUuid()) && !INFO_B.equals(x.getUuid())) k.st = x;
            }
            if (k.wr == null || k.st == null) throw new IOException("BLE: caratteristiche stampante mancanti");
            k.bonded = dev.getBondState() == BluetoothDevice.BOND_BONDED;
            try { k.name = dev.getName() != null ? dev.getName() : k.name; } catch (SecurityException ignored) {}
            StringBuilder sb = new StringBuilder("KODAK BLE servizio OK · bond=" + dev.getBondState());
            for (BluetoothGattCharacteristic x : cs) sb.append(" · ").append(x.getUuid().toString(), 0, 8).append("/p").append(x.getProperties());
            sb.append(" · write=").append(k.wr.getUuid().toString(), 0, 8).append(" status=").append(k.st.getUuid().toString(), 0, 8);
            Trace.log(sb.toString());
            sleep(500);
            return k;
        } catch (IOException e) { k.close(); throw e; }
    }

    private Object[] await(String kind, long ms) throws IOException {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) throw new IOException("BLE: timeout (" + kind + ")");
            Object[] r;
            try { r = ops.poll(left, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { throw new IOException("Annullato"); }
            if (r == null) continue;
            if ("conn".equals(r[0]) && !"conn".equals(kind) && (Integer) r[2] == BluetoothProfile.STATE_DISCONNECTED)
                throw new IOException("La stampante si è disconnessa");
            if (kind.equals(r[0])) return r;
        }
    }

    private byte[] status() throws IOException {
        IOException last = null;
        for (int i = 0; i < 4; i++) {
            if (gatt.readCharacteristic(st)) {
                try {
                    Object[] r = await("read", 4000);
                    int code = (Integer) r[1];
                    if (code == BluetoothGatt.GATT_SUCCESS) return (byte[]) r[2];
                    Trace.log("KODAK BLE lettura stato → GATT " + code);
                    if (code == 5 || code == 15) throw new IOException("La stampante richiede un collegamento non cifrato: dissociala in Impostazioni › Bluetooth e riprova");
                } catch (IOException e) {
                    last = e;
                    Trace.log("KODAK BLE lettura stato: " + e.getMessage());
                    if (e.getMessage() != null && (e.getMessage().contains("disconnessa") || e.getMessage().contains("dissociala"))) throw e;
                }
            } else Trace.log("KODAK BLE readCharacteristic rifiutata (operazione in corso)");
            sleep(400);
        }
        throw new IOException(bonded ? "La stampante non risponde: è associata in Android. Vai in Impostazioni › Bluetooth, dissocia «" + name + "» e riprova (non va associata)"
            : "BLE: stato stampante non leggibile" + (last != null ? " (" + last.getMessage() + ")" : ""));
    }

    private boolean bonded;
    private String name = "Kodak";

    private void write(byte[] v) throws IOException {
        wr.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        wr.setValue(v);
        if (!gatt.writeCharacteristic(wr)) {
            sleep(1000);
            if (!gatt.writeCharacteristic(wr)) throw new IOException("BLE: scrittura rifiutata");
        }
        Object[] r = await("write", 8000);
        if ((Integer) r[1] != BluetoothGatt.GATT_SUCCESS) throw new IOException("BLE: scrittura fallita (" + r[1] + ")");
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    static String errorText(int code) {
        switch (code) {
            case 1: return "controlla la carta: assente o non alimentata";
            case 2: return "cartuccia esaurita: sostituiscila";
            case 3: return "sportello aperto o cartuccia non inserita";
            case 4: return "carta inceppata: riavvia la stampante e rimuovi il foglio";
            case 5: return "cartuccia non compatibile";
            case 6: return "errore carta: reinserisci la cartuccia";
            case 9: return "batteria scarica: ricarica la stampante";
            case 10: return "stampante in raffreddamento";
            default: return "errore stampante";
        }
    }

    @Override public void print(byte[] jpeg, int copies, KodakBt.Listener l) throws IOException {
        byte[] job = KodakBt.padJob(jpeg);
        int n = job.length;
        copies = Math.max(1, Math.min(9, copies));
        // 1 · wait until ready (state 3/4/5), report errors / cooling
        l.onState("BLE · ATTESA STAMPANTE…", -1, -1);
        long end = System.currentTimeMillis() + 170000;
        long idle0 = 0;
        while (true) {
            byte[] k = status();
            int state = k.length > 1 ? k[1] & 255 : -1, err = k.length > 3 ? k[3] & 255 : 0;
            int temp = k.length > 7 ? ((k[6] & 255) << 8) | (k[7] & 255) : 0;
            if (state == 3 || state == 4 || state == 5) {
                if (temp >= 55 && k.length > 11 && k[10] > 0 && k[10] != k[11]) l.onState("STAMPANTE IN RAFFREDDAMENTO… ATTENDI", -1, -1);
                else break;
            } else if (state == 0 && err != 0 && err != 10) {
                throw new IOException("Errore stampante " + err + ": " + errorText(err));
            } else if (state == 0 && err == 0) {
                if (idle0 == 0) idle0 = System.currentTimeMillis();
                else if (System.currentTimeMillis() - idle0 > 10000) break; // idle without errors: go
            } else if (err == 10) l.onState("STAMPANTE IN RAFFREDDAMENTO… ATTENDI", -1, -1);
            if (System.currentTimeMillis() > end) throw new IOException("La stampante non è pronta");
            sleep(600);
        }
        // 2 · job header + data
        write(new byte[]{(byte) (n >> 16), (byte) (n >> 8), (byte) n, 0, 0, 0x00, (byte) copies, 0, 0, 0});
        for (int off = 0; off < n; off += CHUNK) {
            int len = Math.min(CHUNK, n - off);
            byte[] p = new byte[5 + len];
            p[0] = (byte) (off >> 16); p[1] = (byte) (off >> 8); p[2] = (byte) off;
            p[3] = (byte) (len >> 8); p[4] = (byte) len;
            System.arraycopy(job, off, p, 5, len);
            write(p);
            if ((off / CHUNK) % 20 == 0 || off + len >= n)
                l.onState("TRASFERIMENTO " + ((off + len) * 100L / n) + "%  ·  " + ((off + len) >> 10) + " / " + (n >> 10) + " KB", (off + len) / (float) n, -1);
        }
        // 3 · follow the 4 passes until the printer is idle again
        boolean printing = false;
        long idleSince = 0, start = System.currentTimeMillis();
        while (true) {
            sleep(600);
            byte[] k = status();
            int state = k.length > 1 ? k[1] & 255 : -1, err = k.length > 3 ? k[3] & 255 : 0;
            if (state == 1) {
                printing = true; idleSince = 0;
                int pass = k.length > 2 ? k[2] & 255 : -1;
                int copy = k.length > 11 ? (k[10] & 255) + 1 : 1, tot = k.length > 11 ? k[11] & 255 : copies;
                l.onState("STAMPA 4PASS" + (pass < 4 ? " · " + KodakBt.PASSES[pass] : "") + (tot > 1 ? " · COPIA " + copy + "/" + tot : ""), 1f, pass < 4 ? pass : 3);
                continue;
            }
            if (err != 0 && err != 10) throw new IOException("Errore stampante " + err + ": " + errorText(err));
            if (printing && (state == 0 || state == 3 || state == 4 || state == 5)) {
                l.onState("STAMPA COMPLETATA ✓", 1f, 3);
                return;
            }
            if (!printing) {
                if (idleSince == 0) idleSince = System.currentTimeMillis();
                l.onState("ELABORAZIONE IN STAMPANTE…", -1, -1);
                if (System.currentTimeMillis() - start > 120000) throw new IOException("La stampante non ha avviato la stampa");
            }
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { gatt.disconnect(); } catch (Exception ignored) {}
        try { gatt.close(); } catch (Exception ignored) {}
    }
}
