package com.oxylab.tvhome;

import android.content.Context;

import java.util.Arrays;
import java.util.HashMap;

/** The remote-key mapping from res/values/config.xml, shared by the
 *  launcher and by KeyService. */
final class KeyConfig {

    final int[] settingsKeys;
    final int[] inputKeys;
    final HashMap<Integer, String> appKeys = new HashMap<>();

    private KeyConfig(Context c) {
        settingsKeys = parseInts(c.getString(R.string.settings_key_keycodes));
        inputKeys = parseInts(c.getString(R.string.input_key_keycodes));
        for (String pair : c.getString(R.string.app_keys).split(",")) {
            pair = pair.trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                appKeys.put(Integer.parseInt(pair.substring(0, eq).trim()),
                            pair.substring(eq + 1).trim());
            } catch (NumberFormatException ignored) {}
        }
    }

    static KeyConfig from(Context c) { return new KeyConfig(c); }

    boolean isMapped(int keyCode) {
        return contains(settingsKeys, keyCode)
                || contains(inputKeys, keyCode)
                || appKeys.containsKey(keyCode);
    }

    static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    static int[] parseInts(String csv) {
        if (csv == null || csv.trim().isEmpty()) return new int[0];
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            try { out[n++] = Integer.parseInt(p.trim()); } catch (NumberFormatException ignored) {}
        }
        return Arrays.copyOf(out, n);
    }
}
