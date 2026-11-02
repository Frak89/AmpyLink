/*
 * Background camera link, like Canon Camera Connect's service: keeps the paired EOS attached over
 * Bluetooth LE (even when the camera is switched off, as long as its Bluetooth is on), reconnects
 * by itself, and lets AmpyLink ask for the camera's Wi-Fi instantly. Started from the LINK dialog,
 * the notification, or at boot. GPL v3.
 */
package com.ampylink.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

@SuppressLint("MissingPermission")
public final class CameraLinkService extends Service {
    static final String CH = "camera_link", STOP = "com.ampylink.app.STOP_LINK";
    static volatile CanonBle ble;          // connected camera, shared with GalleryActivity
    static volatile String state = "";
    private volatile boolean running;
    private Thread worker;

    /** On by default as soon as a camera has been paired: no manual setup. */
    static boolean enabled(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences("eos", MODE_PRIVATE);
        return sp.getBoolean("svc_on", sp.getString("ble_paired", null) != null);
    }

    static void start(Context c) {
        Intent i = new Intent(c, CameraLinkService.class);
        try { c.startForegroundService(i); } catch (Exception e) { Trace.log("servizio: avvio negato · " + e.getMessage()); }
    }

    static void stop(Context c) { c.stopService(new Intent(c, CameraLinkService.class)); }

    /** The service's live link if it is attached to this camera. */
    static CanonBle linked(String mac) {
        CanonBle b = ble;
        return b != null && b.isConnected() && b.address().equals(mac) ? b : null;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && STOP.equals(intent.getAction())) {
            getSharedPreferences("eos", MODE_PRIVATE).edit().putBoolean("svc_on", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Collegamento fotocamera", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Mantiene la fotocamera Canon collegata via Bluetooth");
            nm.createNotificationChannel(ch);
        }
        Notification n = notification("Avvio collegamento Bluetooth…");
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(42, n);
        if (!running) {
            running = true;
            worker = new Thread(this::loop, "camera-link");
            worker.start();
        }
        return START_STICKY;
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, GalleryActivity.class).putExtra("wifi", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, CameraLinkService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_fg)
            .setColor(Cyber.CYAN)
            .setContentTitle("AMPY▲LINK · EOS")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "APRI GALLERIA", open).build())
            .addAction(new Notification.Action.Builder(null, "STOP", stop).build())
            .build();
    }

    private void post(String text) {
        state = text;
        Trace.log("servizio: " + text);
        try { getSystemService(NotificationManager.class).notify(42, notification(text)); } catch (Exception ignored) {}
    }

    private void loop() {
        SharedPreferences sp = getSharedPreferences("eos", MODE_PRIVATE);
        int backoff = 5000;
        while (running) {
            String mac = sp.getString("ble_mac", null), name = sp.getString("ble_name", "EOS");
            BluetoothAdapter ad = KodakBt.adapter(this);
            if (mac == null) { post("Nessuna fotocamera associata: usa LINK › Bluetooth"); sleep(30000); continue; }
            if (ad == null || !ad.isEnabled()) { post("Bluetooth spento"); sleep(15000); continue; }
            try {
                BluetoothDevice dev = ad.getRemoteDevice(mac);
                CanonBle b = new CanonBle(this, dev);
                post("Ricerca " + name + "…");
                boolean first = !mac.equals(sp.getString("ble_paired", null));
                b.connect(first, Session.guid(this), Session.friendlyName(), s -> state = s);
                sp.edit().putString("ble_paired", mac).apply();
                ble = b;
                backoff = 5000;
                post("◉ " + name + " collegata via Bluetooth · tocca per aprire la galleria");
                b.awaitDisconnect();
                post(name + " disconnessa, riconnessione…");
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                post("In attesa di " + name + " (" + e.getMessage() + ")");
                sleep(backoff);
                backoff = Math.min(60000, backoff * 2);
            } finally {
                CanonBle b = ble;
                if (b != null && !b.isConnected()) { b.close(); ble = null; }
            }
        }
    }

    private void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { running = false; } }

    @Override public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        CanonBle b = ble; ble = null;
        if (b != null) b.close();
        super.onDestroy();
    }

    /** Restart the link after a reboot if the user enabled it. */
    public static final class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) && enabled(c)) start(c);
        }
    }
}
