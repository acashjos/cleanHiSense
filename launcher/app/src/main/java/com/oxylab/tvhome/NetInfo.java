package com.oxylab.tvhome;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;

/** The current connection: what pill text and the quick panel's info card show. */
final class NetInfo {

    final boolean connected;
    final boolean ethernet;
    final String name;   // "Ethernet", the Wi-Fi SSID, or "Offline"
    final String ip;     // may be null

    private NetInfo(boolean connected, boolean ethernet, String name, String ip) {
        this.connected = connected;
        this.ethernet = ethernet;
        this.name = name;
        this.ip = ip;
    }

    static NetInfo read(Context ctx) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network net = cm.getActiveNetwork();
            NetworkCapabilities nc = net == null ? null : cm.getNetworkCapabilities(net);
            if (nc == null) return new NetInfo(false, false, "Offline", null);

            String ip = null;
            LinkProperties lp = cm.getLinkProperties(net);
            if (lp != null) {
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address) { ip = a.getHostAddress(); break; }
                }
            }

            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return new NetInfo(true, true, "Ethernet", ip);
            }
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                String ssid = null;
                WifiManager wm = (WifiManager)
                        ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                if (wi != null && wi.getSSID() != null) ssid = wi.getSSID().replace("\"", "");
                boolean known = ssid != null && !ssid.isEmpty() && !ssid.contains("unknown");
                return new NetInfo(true, false, known ? ssid : "Wi-Fi", ip);
            }
            return new NetInfo(true, false, "Connected", ip);
        } catch (Throwable t) {
            return new NetInfo(false, false, "Offline", null);
        }
    }

    static boolean wifiEnabled(Context ctx) {
        try {
            WifiManager wm = (WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Android 12+ requires BLUETOOTH_CONNECT to even query adapter state; a
     * caller without the permission granted gets null here rather than a
     * SecurityException, and should fall back to a status-free tile.
     */
    static Boolean bluetoothEnabled(Context ctx) {
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager)
                    ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter a = bm == null ? null : bm.getAdapter();
            if (a == null) return false;
            return a.isEnabled();
        } catch (SecurityException e) {
            return null;
        } catch (Throwable t) {
            return false;
        }
    }
}
