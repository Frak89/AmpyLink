/*
 * Finds Canon cameras on the Wi-Fi network the way Camera Connect does: UPnP/SSDP.
 * Smartphone-mode EOS bodies answer as ICPO-SmartPhoneEOSSystemService (→ PTP/IP);
 * CCAPI-enabled bodies answer as CameraControlAPIService on port 8080. GPL v3.
 */
package com.ampylink.app;

import android.content.Context;
import android.net.Network;
import android.net.wifi.WifiManager;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Discovery {
    private Discovery() {}

    static final class Found {
        String ip, location, name = "Canon EOS";
        boolean ccapi, ptpip, probed, port15740, port8080, upnp;
        String label() {
            String l = name + " · " + ip + " · " + (ccapi && !ptpip ? "CCAPI" : "PTP/IP");
            if (probed) l += "\nporte: 15740 " + (port15740 ? "✓" : "✗") + " · 8080 " + (port8080 ? "✓" : "✗") + " · UPnP " + (upnp ? "✓" : "✗");
            return l;
        }
    }

    private static final String[] TARGETS = {
        "urn:schemas-canon-com:service:ICPO-SmartPhoneEOSSystemService:1",
        "urn:schemas-canon-com:service:CameraControlAPIService:1",
        "urn:schemas-canon-com:service:ICPO-WFTEOSSystemService:1",
        "upnp:rootdevice",
    };

    /**
     * Three sources in parallel: unicast replies to M-SEARCH, NOTIFY adverts on 239.255.255.250:1900
     * (Canon bodies in smartphone mode mostly just advertise), and a direct probe of the Wi-Fi
     * gateway — with the camera's own hotspot the camera IS the gateway (usually 192.168.1.2).
     */
    static List<Found> scan(Context ctx, Network net, int millis, String... hints) {
        final Map<String, Found> found = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
        WifiManager wm = ctx.getApplicationContext().getSystemService(WifiManager.class);
        WifiManager.MulticastLock lock = null;
        List<Thread> workers = new ArrayList<>();
        final long end = System.currentTimeMillis() + millis;
        try {
            if (wm != null) { lock = wm.createMulticastLock("ampylink-ssdp"); lock.setReferenceCounted(false); lock.acquire(); }
            // A · M-SEARCH, unicast replies
            workers.add(start(() -> {
                try (DatagramSocket s = new DatagramSocket(null)) {
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(0));
                    if (net != null) try { net.bindSocket(s); } catch (Exception ignored) {}
                    s.setSoTimeout(300);
                    InetAddress group = InetAddress.getByName("239.255.255.250");
                    int round = 0;
                    byte[] buf = new byte[4096];
                    while (System.currentTimeMillis() < end) {
                        if (round < 3) {
                            for (String st : TARGETS) {
                                byte[] q = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                                try { s.send(new DatagramPacket(q, q.length, group, 1900)); } catch (Exception ignored) {}
                            }
                            round++;
                        }
                        try {
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            s.receive(p);
                            onSsdp(found, p);
                        } catch (SocketTimeoutException ignored) {}
                    }
                } catch (Exception ignored) {}
            }));
            // B · NOTIFY listener on the multicast group
            workers.add(start(() -> {
                try (java.net.MulticastSocket ms = new java.net.MulticastSocket(null)) {
                    ms.setReuseAddress(true);
                    ms.bind(new InetSocketAddress(1900));
                    if (net != null) try { net.bindSocket(ms); } catch (Exception ignored) {}
                    java.net.NetworkInterface nif = wifiInterface(ctx, net);
                    InetSocketAddress grp = new InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900);
                    if (nif != null) ms.joinGroup(grp, nif); else ms.joinGroup(grp.getAddress());
                    ms.setSoTimeout(300);
                    byte[] buf = new byte[4096];
                    while (System.currentTimeMillis() < end) {
                        try {
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            ms.receive(p);
                            onSsdp(found, p);
                        } catch (SocketTimeoutException ignored) {}
                    }
                } catch (Exception ignored) {}
            }));
            // C · direct probe of the gateway and of the IP typed by the user
            java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
            String gw = gateway(ctx, net);
            if (gw != null) targets.add(gw);
            for (String h : hints) if (h != null && !h.trim().isEmpty()) targets.add(h.trim());
            for (String ip : targets) workers.add(start(() -> probe(found, net, ip)));
            for (Thread t : workers) t.join(Math.max(1, end - System.currentTimeMillis() + 2500));
        } catch (Exception ignored) {
        } finally { if (lock != null) try { lock.release(); } catch (Exception ignored) {} }
        List<Found> out;
        synchronized (found) { out = new ArrayList<>(found.values()); }
        for (Found f : out) if (f.location != null) friendlyName(f, net);
        return out;
    }

    private static Thread start(Runnable r) { Thread t = new Thread(r, "discovery"); t.setDaemon(true); t.start(); return t; }

    /** Any SSDP packet from a camera-looking device (Canon headers do not always say "Canon"). */
    private static void onSsdp(Map<String, Found> found, DatagramPacket p) {
        String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
        String low = msg.toLowerCase(Locale.US);
        boolean camera = low.contains("canon") || low.contains("camera") || low.contains("icpo") || low.contains("eos")
            || low.contains("cameradevdesc") || low.contains("ccapi");
        if (!camera) return;
        String ip = p.getAddress().getHostAddress();
        Found f;
        synchronized (found) {
            f = found.get(ip);
            if (f == null) { f = new Found(); f.ip = ip; found.put(ip, f); }
        }
        if (low.contains("cameracontrolapi") || low.contains(":8080/")) f.ccapi = true;
        if (low.contains("smartphoneeos") || low.contains("wfteos") || low.contains("icpo")) f.ptpip = true;
        String loc = header(msg, "LOCATION");
        if (loc != null && f.location == null) f.location = loc;
    }

    private static void probe(Map<String, Found> found, Network net, String ip) {
        // never open/close a bare TCP connection on 15740: Canon bodies take it as a failed phone
        // session, show "cannot connect" and leave the waiting screen. Only harmless HTTP probes.
        boolean ptp = false, cc = httpOk(net, "http://" + ip + ":8080/ccapi");
        String loc = null;
        if (!ptp && !cc) {
            // camera UPnP description (port 49152 on Canon bodies)
            for (String u : new String[]{"http://" + ip + ":49152/upnp/CameraDevDesc.xml", "http://" + ip + ":49152/description.xml"})
                if (httpOk(net, u)) { loc = u; break; }
            if (loc == null) return;
        }
        Found f;
        synchronized (found) {
            f = found.get(ip);
            if (f == null) { f = new Found(); f.ip = ip; found.put(ip, f); }
        }
        f.ptpip |= ptp || loc != null;
        f.ccapi |= cc;
        f.probed = true; f.port15740 = ptp; f.port8080 = cc; f.upnp = loc != null;
        if (f.location == null) f.location = loc != null ? loc : "http://" + ip + ":49152/upnp/CameraDevDesc.xml";
        if (cc && f.name.equals("Canon EOS")) f.name = "Canon EOS (CCAPI)";
    }

    static boolean portOpen(Network n, String host, int port, int ms) {
        try (java.net.Socket s = n != null ? n.getSocketFactory().createSocket() : new java.net.Socket()) {
            s.connect(new InetSocketAddress(host, port), ms);
            return true;
        } catch (Exception e) { return false; }
    }

    private static boolean httpOk(Network net, String url) {
        try {
            URL u = new URL(url);
            HttpURLConnection c = (HttpURLConnection) (net != null ? net.openConnection(u) : u.openConnection());
            c.setConnectTimeout(1500); c.setReadTimeout(1500);
            try { return c.getResponseCode() / 100 == 2; } finally { c.disconnect(); }
        } catch (Exception e) { return false; }
    }

    private static java.net.NetworkInterface wifiInterface(Context ctx, Network net) {
        try {
            android.net.LinkProperties lp = ctx.getSystemService(android.net.ConnectivityManager.class).getLinkProperties(net);
            return lp != null && lp.getInterfaceName() != null ? java.net.NetworkInterface.getByName(lp.getInterfaceName()) : null;
        } catch (Exception e) { return null; }
    }

    static String gateway(Context ctx, Network net) {
        try {
            android.net.LinkProperties lp = net == null ? null : ctx.getSystemService(android.net.ConnectivityManager.class).getLinkProperties(net);
            if (lp != null) for (android.net.RouteInfo r : lp.getRoutes())
                if (r.isDefaultRoute() && r.getGateway() instanceof java.net.Inet4Address) return r.getGateway().getHostAddress();
        } catch (Exception ignored) {}
        return null;
    }

    private static String header(String msg, String name) {
        for (String line : msg.split("\r?\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) return line.substring(c + 1).trim();
        }
        return null;
    }

    private static void friendlyName(Found f, Network net) {
        try {
            URL u = new URL(f.location);
            HttpURLConnection c = (HttpURLConnection) (net != null ? net.openConnection(u) : u.openConnection());
            c.setConnectTimeout(1500); c.setReadTimeout(2000);
            try (InputStream in = c.getInputStream()) {
                String xml = new String(CanonClient.readAll(in), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("<modelName>([^<]+)</modelName>").matcher(xml);
                if (m.find()) f.name = m.group(1).trim();
                else { m = Pattern.compile("<friendlyName>([^<]+)</friendlyName>").matcher(xml); if (m.find()) f.name = m.group(1).trim(); }
                if (xml.contains("CameraControlAPI") || xml.contains("ccapi")) f.ccapi = true;
            } finally { c.disconnect(); }
        } catch (Exception ignored) {}
    }
}
