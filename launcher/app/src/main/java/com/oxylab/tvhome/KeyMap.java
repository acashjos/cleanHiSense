package com.oxylab.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Which app each remote button launches.
 *
 * Defaults come from res/values/config.xml; anything the user picks in the
 * quick-settings panel is stored in SharedPreferences and wins. Stored as
 * plain keycode -> package so KeyService can read it with no parsing cost.
 */
final class KeyMap {

    static final String PREFS = "remote_keys";
    /** Stored against a keycode to mean "deliberately unmapped". */
    static final String NONE = "";

    /** One physical button on the remote. */
    static class Button {
        final int keyCode;
        final String label;
        Button(int keyCode, String label) { this.keyCode = keyCode; this.label = label; }
    }

    private KeyMap() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The buttons this TV's remote has, from config.xml. */
    static List<Button> buttons(Context c) {
        List<Button> out = new ArrayList<>();
        for (String row : c.getResources().getStringArray(R.array.remote_buttons)) {
            int bar = row.indexOf('|');
            if (bar <= 0) continue;
            try {
                out.add(new Button(Integer.parseInt(row.substring(0, bar).trim()),
                                   row.substring(bar + 1).trim()));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /**
     * Package for a keycode, or null if the button should do nothing.
     * A stored empty string means the user explicitly cleared it, which is
     * why that case is distinguished from "nothing stored".
     */
    static String target(Context c, int keyCode) {
        SharedPreferences p = prefs(c);
        String key = String.valueOf(keyCode);
        if (p.contains(key)) {
            String v = p.getString(key, NONE);
            return (v == null || v.isEmpty()) ? null : v;
        }
        String v = KeyConfig.from(c).appKeys.get(keyCode);
        return (v == null || v.isEmpty()) ? null : v;
    }

    static void setTarget(Context c, int keyCode, String pkgOrNull) {
        prefs(c).edit()
                .putString(String.valueOf(keyCode), pkgOrNull == null ? NONE : pkgOrNull)
                .apply();
    }
}
