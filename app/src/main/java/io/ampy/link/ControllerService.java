package io.ampy.link;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import java.util.LinkedHashMap;

/** Tracks controller windows on the main thread while their sockets are alive. */
public final class ControllerService extends Service {
    private static final int ID = 1381;
    private static final String CHANNEL = "orb_controller";
    private static final LinkedHashMap<MainActivity,String> sessions = new LinkedHashMap<>();
    private static ControllerService running;

    static void begin(MainActivity owner, String ip) {
        sessions.put(owner, "Connessione a " + ip);
        try {
            owner.startForegroundService(new Intent(owner, ControllerService.class));
        } catch (RuntimeException ex) {
            sessions.remove(owner);
            throw ex;
        }
    }

    static void connected(MainActivity owner, String ip) {
        if (!sessions.containsKey(owner)) return;
        sessions.put(owner, "Connesso a " + ip);
        if (running != null) running.refresh();
    }

    static void remove(MainActivity owner) {
        if (sessions.remove(owner) != null && running != null) running.refresh();
    }

    private Notification notification() {
        Intent resume = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent show = PendingIntent.getActivity(this, ID, resume, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, ID,
                new Intent(this, ControllerService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        String state = sessions.isEmpty() ? "Chiusura sessione" : String.join(" · ", sessions.values());
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_share).setContentTitle("AmpyOrb · Controller")
                .setContentText(state).setStyle(new Notification.BigTextStyle().bigText(state))
                .setOngoing(true).setContentIntent(show)
                .addAction(new Notification.Action.Builder(null,"Disconnetti tutti",stop).build()).build();
    }

    private void refresh() {
        if (sessions.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } else {
            getSystemService(NotificationManager.class).notify(ID, notification());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        running = this;
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Connessioni AmpyOrb", NotificationManager.IMPORTANCE_DEFAULT));
        startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        if (intent != null && "STOP".equals(intent.getAction())) {
            for (MainActivity owner : sessions.keySet().toArray(new MainActivity[0])) owner.disconnect();
        }
        refresh();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { if (running == this) running = null; super.onDestroy(); }
}
