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
        boolean ccapi, ptpip;
        String label() { return name + " · " + ip + " · " + (ccapi ? "CCAPI" : "PTP/IP"); }
    }

    private static final String[] TARGETS = {
        "urn:schemas-canon-com:service:ICPO-SmartPhoneEOSSystemService:1",
        "urn:schemas-canon-com:service:CameraControlAPIService:1",
        "urn:schemas-canon-com:service:ICPO-WFTEOSSystemService:1",
        "upnp:rootdevice",
    };

    static List<Found> scan(Context ctx, Network net, int millis) {
        Map<String, Found> found = new LinkedHashMap<>();
        WifiManager wm = ctx.getApplicationContext().getSystemService(WifiManager.class);
        WifiManager.MulticastLock lock = null;
        try {
            if (wm != null) { lock = wm.createMulticastLock("ampylink-ssdp"); lock.setReferenceCounted(false); lock.acquire(); }
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(0));
            if (net != null) try { net.bindSocket(s); } catch (Exception ignored) {}
            s.setSoTimeout(300);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            long end = System.currentTimeMillis() + millis;
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
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    String low = msg.toLowerCase(Locale.US);
                    if (!low.contains("canon")) continue;
                    String ip = p.getAddress().getHostAddress();
                    Found f = found.get(ip);
                    if (f == null) { f = new Found(); f.ip = ip; found.put(ip, f); }
                    if (low.contains("cameracontrolapi") || low.contains(":8080/")) f.ccapi = true;
                    if (low.contains("smartphoneeos") || low.contains("wfteos")) f.ptpip = true;
                    String loc = header(msg, "LOCATION");
                    if (loc != null && f.location == null) f.location = loc;
                } catch (SocketTimeoutException ignored) {}
            }
            s.close();
        } catch (Exception ignored) {
        } finally { if (lock != null) try { lock.release(); } catch (Exception ignored) {} }
        List<Found> out = new ArrayList<>(found.values());
        for (Found f : out) if (f.location != null) friendlyName(f, net);
        return out;
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
