package io.ampy.link;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Toast;
import java.net.Inet4Address;
import java.util.LinkedHashMap;

final class OrbLan implements AutoCloseable {
    private static final String TYPE = "_ampyorb._tcp.";
    private final NsdManager manager;
    private NsdManager.RegistrationListener registration;

    OrbLan(Context context) {
        manager = context.getSystemService(NsdManager.class);
    }

    void advertise() {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("Orb · " + Build.MODEL);
        info.setServiceType(TYPE);
        info.setPort(Link.PORT);
        registration = new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo service) {}
            public void onRegistrationFailed(NsdServiceInfo service, int error) {}
            public void onServiceUnregistered(NsdServiceInfo service) {}
            public void onUnregistrationFailed(NsdServiceInfo service, int error) {}
        };
        try { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration); }
        catch (RuntimeException unavailable) { registration = null; }
    }

    public void close() {
        if (registration == null) return;
        try { manager.unregisterService(registration); }
        catch (IllegalArgumentException ignored) {}
        registration = null;
    }

    static void pick(Activity owner, EditText address) {
        NsdManager nsd = owner.getSystemService(NsdManager.class);
        LinkedHashMap<String,NsdServiceInfo> hosts = new LinkedHashMap<>();
        ArrayAdapter<String> names = new ArrayAdapter<>(owner, android.R.layout.simple_list_item_1);
        AlertDialog dialog = new AlertDialog.Builder(owner)
                .setTitle("Ricerca host Orb in LAN…")
                .setAdapter(names, (d, index) -> {
                    NsdServiceInfo selected = hosts.get(names.getItem(index));
                    if (selected == null) return;
                    nsd.resolveService(selected, new NsdManager.ResolveListener() {
                        public void onResolveFailed(NsdServiceInfo service, int error) {
                            owner.runOnUiThread(() -> Toast.makeText(owner, "Host irraggiungibile", Toast.LENGTH_SHORT).show());
                        }
                        public void onServiceResolved(NsdServiceInfo service) {
                            owner.runOnUiThread(() -> {
                                if (owner.isDestroyed()) return;
                                if (service.getPort() == Link.PORT && service.getHost() instanceof Inet4Address)
                                    address.setText(service.getHost().getHostAddress());
                                else Toast.makeText(owner, "Usa l’indirizzo IPv4 mostrato dall’host", Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                })
                .setNegativeButton("Indirizzo manuale", null).create();
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            void update(NsdServiceInfo service, boolean found) {
                owner.runOnUiThread(() -> {
                    if (!dialog.isShowing()) return;
                    if (found) hosts.put(service.getServiceName(), service);
                    else hosts.remove(service.getServiceName());
                    names.clear();
                    names.addAll(hosts.keySet());
                    dialog.setTitle(hosts.isEmpty() ? "Ricerca host Orb in LAN…" : "Scegli un host Orb");
                });
            }
            public void onServiceFound(NsdServiceInfo service) { update(service, true); }
            public void onServiceLost(NsdServiceInfo service) { update(service, false); }
            public void onDiscoveryStarted(String type) {}
            public void onDiscoveryStopped(String type) {}
            public void onStopDiscoveryFailed(String type, int error) {}
            public void onStartDiscoveryFailed(String type, int error) {
                owner.runOnUiThread(() -> dialog.setTitle("Ricerca LAN non disponibile"));
            }
        };
        dialog.setOnDismissListener(d -> {
            try { nsd.stopServiceDiscovery(listener); } catch (IllegalArgumentException ignored) {}
        });
        dialog.show();
        try { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener); }
        catch (RuntimeException unavailable) { dialog.setTitle("Ricerca LAN non disponibile"); }
    }
}
