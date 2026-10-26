package com.oxylab.tvhome;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * The dock's app list and order: defaults from config.xml's dock_apps,
 * overridden once the user pins or reorders anything from the launcher
 * itself. Same pattern as KeyMap for the remote buttons - a persisted
 * choice always wins over the config.xml default, for every app in the
 * list at once rather than per-button.
 */
final class DockConfig {
    private static final String PREFS = "dock_config";
    private static final String KEY_ORDER = "order"; // comma-separated package names

    private DockConfig() {}

    static List<String> load(Context ctx) {
        String stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ORDER, null);
        List<String> out = new ArrayList<>();
        if (stored != null && !stored.trim().isEmpty()) {
            for (String s : stored.split(",")) {
                s = s.trim();
                if (!s.isEmpty()) out.add(s);
            }
            if (!out.isEmpty()) return out;
        }
        for (String s : ctx.getResources().getStringArray(R.array.dock_apps)) out.add(s);
        return out;
    }

    static void save(Context ctx, List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkgs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(pkgs.get(i));
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ORDER, sb.toString()).apply();
    }
}
