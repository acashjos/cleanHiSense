package com.oxylab.tvhome;

import android.content.Context;

/**
 * Remembers which of the clock's two shapes Home is showing. Tapping the
 * time widget flips between them; the first press writes an explicit
 * choice, and until then the clock follows the system's own 12/24-hour
 * setting. Same SharedPreferences pattern as the other config classes.
 */
final class ClockConfig {
    private static final String PREFS = "clock_config";
    private static final String KEY_24H = "hour24";

    private ClockConfig() {}

    /** True = "22:34", false = "10:34 PM". */
    static boolean is24Hour(Context ctx) {
        return prefs(ctx).getBoolean(KEY_24H,
                android.text.format.DateFormat.is24HourFormat(ctx));
    }

    static void toggle24Hour(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_24H, !is24Hour(ctx)).apply();
    }

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
