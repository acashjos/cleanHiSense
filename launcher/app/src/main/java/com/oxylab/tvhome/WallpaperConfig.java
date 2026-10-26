package com.oxylab.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;

import org.json.JSONArray;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Everything the wallpaper feature needs to remember: whether it's on,
 * which of the three mutually-exclusive sources is active, and each
 * source's own state. Same SharedPreferences pattern as KeyMap/DockConfig.
 *
 * Solid color and Custom image are now multi-select: the stored list is
 * the rotation set, and a separate "active" value is whichever member is
 * on screen right now. The refresh schedule advances that active value
 * (or downloads a fresh Unsplash photo). Switching source never discards
 * the other two sources' settings.
 */
final class WallpaperConfig {
    private static final String PREFS = "wallpaper_config";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MODE = "mode";
    private static final String KEY_SCHEDULE_DAYS = "schedule_days";
    private static final String KEY_COLORS = "colors";              // JSON int array
    private static final String KEY_ACTIVE_COLOR = "active_color";  // int
    private static final String KEY_ACTIVE_FILE = "active_file";    // string
    private static final String KEY_CACHED_IMAGE = "cached_image";
    private static final String KEY_LAST_REFRESH = "last_refresh";
    private static final String KEY_LAST_REFRESH_DAY = "last_refresh_day";
    private static final String KEY_CREDIT = "credit";

    // Single-value key from before multi-select existed; migrated on first
    // read so an existing install keeps the color it had chosen.
    private static final String LEGACY_COLOR = "color";

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

    static final String MODE_UNSPLASH = "unsplash";
    static final String MODE_FILE = "file";
    static final String MODE_COLOR = "color";

    /** A dark, legible-with-white-text default - shown when Solid color is
     *  active but nothing has been ticked yet. */
    static final int DEFAULT_COLOR = 0xFF14213D;

    private WallpaperConfig() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    private static final String KEY_DARK_TEXT = "dark_text";

    /** Whether Home's overlay text (clock, greeting, dock title) should
     *  be dark instead of the default light/white - for bright
     *  wallpapers. A manual toggle in the Wallpaper panel, not
     *  auto-detected from the image: simpler, predictable, and the user
     *  already knows which one they need. */
    static boolean isDarkText(Context ctx) {
        return prefs(ctx).getBoolean(KEY_DARK_TEXT, false);
    }

    static void setDarkText(Context ctx, boolean dark) {
        prefs(ctx).edit().putBoolean(KEY_DARK_TEXT, dark).apply();
    }

    static String getMode(Context ctx) {
        return prefs(ctx).getString(KEY_MODE, MODE_UNSPLASH);
    }

    static void setMode(Context ctx, String mode) {
        prefs(ctx).edit().putString(KEY_MODE, mode).apply();
    }

    /** 0 means "never auto-refresh"; otherwise 1-364 days. */
    static int getScheduleDays(Context ctx) {
        return prefs(ctx).getInt(KEY_SCHEDULE_DAYS, 1);
    }

    static void setScheduleDays(Context ctx, int days) {
        prefs(ctx).edit().putInt(KEY_SCHEDULE_DAYS, days).apply();
    }

    static long getLastRefreshMillis(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST_REFRESH, 0L);
    }

    /** Local calendar day (epoch day) of the last refresh. The schedule is
     *  compared in whole days, not a rolling 24h window, so this is what
     *  the check actually reads. Falls back to deriving the day from the
     *  older timestamp field for installs that predate this one. */
    static long getLastRefreshDay(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long day = p.getLong(KEY_LAST_REFRESH_DAY, 0L);
        if (day == 0L) {
            long ms = p.getLong(KEY_LAST_REFRESH, 0L);
            if (ms > 0L) day = dayOf(ms);
        }
        return day;
    }

    /** Today as a local epoch day, for comparison against getLastRefreshDay. */
    static long todayEpochDay() {
        return LocalDate.now().toEpochDay();
    }

    static void setLastRefreshMillis(Context ctx, long millis) {
        prefs(ctx).edit()
                .putLong(KEY_LAST_REFRESH, millis)
                .putLong(KEY_LAST_REFRESH_DAY, dayOf(millis))
                .apply();
    }

    private static long dayOf(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                .toLocalDate().toEpochDay();
    }

    // ----------------------------------------------------------- colors

    /** The palette colors the user has included in the rotation, in pick
     *  order. Empty means "nothing chosen yet" (Home falls back to the
     *  default). */
    static List<Integer> getSelectedColors(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String json = p.getString(KEY_COLORS, null);
        if (json == null) {
            List<Integer> migrated = new ArrayList<>();
            if (p.contains(LEGACY_COLOR)) migrated.add(p.getInt(LEGACY_COLOR, DEFAULT_COLOR));
            return migrated;
        }
        List<Integer> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) out.add(a.getInt(i));
        } catch (Throwable ignored) {}
        return out;
    }

    private static void setSelectedColors(Context ctx, List<Integer> colors) {
        JSONArray a = new JSONArray();
        for (Integer c : colors) a.put((int) c);
        prefs(ctx).edit().putString(KEY_COLORS, a.toString()).apply();
    }

    static boolean isColorSelected(Context ctx, int color) {
        return getSelectedColors(ctx).contains(color);
    }

    static void toggleColor(Context ctx, int color) {
        List<Integer> list = getSelectedColors(ctx);
        if (!list.remove((Integer) color)) list.add(color);
        setSelectedColors(ctx, list);
    }

    /** The color Home is showing right now: the stored active one if it's
     *  still in the selection, otherwise the first selected, otherwise the
     *  default. */
    static int getActiveColor(Context ctx) {
        List<Integer> sel = getSelectedColors(ctx);
        if (sel.isEmpty()) return DEFAULT_COLOR;
        int active = prefs(ctx).getInt(KEY_ACTIVE_COLOR, Integer.MIN_VALUE);
        if (sel.contains(active)) return active;
        return sel.get(0);
    }

    static void setActiveColor(Context ctx, int color) {
        prefs(ctx).edit().putInt(KEY_ACTIVE_COLOR, color).apply();
    }

    /** On a scheduled tick, move to the next selected color. No-op with
     *  fewer than two selected. */
    static void advanceColor(Context ctx) {
        List<Integer> sel = getSelectedColors(ctx);
        if (sel.size() < 2) return;
        int idx = sel.indexOf(getActiveColor(ctx));
        setActiveColor(ctx, sel.get((idx + 1) % sel.size()));
    }

    // ------------------------------------------------------------ files

    /** The one place custom images are read from. Static by design: the
     *  user drops pictures into Pictures/Wallpapers on the TV and they show
     *  up here - no in-app picker, no stored file list to keep in sync. */
    static final String FOLDER_NAME = "Wallpapers";

    static File wallpaperFolder(Context ctx) {
        File pics = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES);
        File base = pics != null ? pics
                : new File(android.os.Environment.getExternalStorageDirectory(), "Pictures");
        return new File(base, FOLDER_NAME);
    }

    /** Every large-enough image in Pictures/Wallpapers, recursively, sorted
     *  by path. Empty when the folder is missing or has no big images. */
    static List<String> getFolderImages(Context ctx) {
        return scanImages(ctx, wallpaperFolder(ctx));
    }

    /** The image on screen right now: the remembered one if it still exists
     *  in the folder, otherwise the first. Null only when the folder has no
     *  usable images. */
    static String getActiveFile(Context ctx) {
        String active = prefs(ctx).getString(KEY_ACTIVE_FILE, null);
        if (active != null && new File(active).exists() && isUnder(wallpaperFolder(ctx), active)) {
            return active;
        }
        List<String> files = getFolderImages(ctx);
        return files.isEmpty() ? null : files.get(0);
    }

    static void setActiveFile(Context ctx, String path) {
        prefs(ctx).edit().putString(KEY_ACTIVE_FILE, path).apply();
    }

    /** On a scheduled tick, move to the next image in the folder. No-op with
     *  fewer than two. */
    static void advanceFile(Context ctx) {
        List<String> files = getFolderImages(ctx);
        if (files.size() < 2) return;
        int idx = files.indexOf(getActiveFile(ctx));
        setActiveFile(ctx, files.get((idx + 1) % files.size()));
    }

    private static boolean isUnder(File dir, String path) {
        if (dir == null || path == null) return false;
        try {
            String parent = dir.getCanonicalPath() + File.separator;
            return new File(path).getCanonicalPath().startsWith(parent);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Image files under {@code dir} (recursively), sorted by path, that are
     *  at least half the display resolution - smaller ones look soft as a
     *  full-screen background, so they are skipped rather than upscaled. */
    static List<String> scanImages(Context ctx, File dir) {
        List<String> out = new ArrayList<>();
        collectImages(ctx, dir, out, 0);
        Collections.sort(out);
        return out;
    }

    private static final String[] IMAGE_EXT =
            {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif"};

    private static void collectImages(Context ctx, File dir, List<String> out, int depth) {
        if (dir == null || depth > 6) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        int minW = ctx.getResources().getDisplayMetrics().widthPixels / 2;
        int minH = ctx.getResources().getDisplayMetrics().heightPixels / 2;
        for (File f : kids) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) collectImages(ctx, f, out, depth + 1);
            } else if (isImage(f) && isBigEnough(f, minW, minH)) {
                out.add(f.getAbsolutePath());
            }
        }
    }

    private static boolean isImage(File f) {
        String name = f.getName().toLowerCase(Locale.US);
        for (String ext : IMAGE_EXT) if (name.endsWith(ext)) return true;
        return false;
    }

    /** True when the image's pixel dimensions are both >= the given
     *  minimums. Reads only the header (inJustDecodeBounds), not the
     *  pixels, so it stays cheap even across a big folder. */
    static boolean isBigEnough(File f, int minW, int minH) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        return bounds.outWidth >= minW && bounds.outHeight >= minH;
    }

    // --------------------------------------------------------- unsplash

    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_SEARCH_QUERY = "search_query";

    /** The API key as typed into the Wallpaper screen, or "" if unset. */
    static String getApiKey(Context ctx) {
        return prefs(ctx).getString(KEY_API_KEY, "");
    }

    static void setApiKey(Context ctx, String key) {
        prefs(ctx).edit().putString(KEY_API_KEY, key == null ? "" : key.trim()).apply();
    }

    /** The key the downloader actually uses: the in-app one if set, else
     *  config.xml's unsplash_access_key - kept as a fallback for a key
     *  baked in at build time rather than typed on the TV. */
    static String getEffectiveApiKey(Context ctx) {
        String k = getApiKey(ctx);
        if (!k.isEmpty()) return k;
        try {
            String fromConfig = ctx.getString(R.string.unsplash_access_key);
            return fromConfig == null ? "" : fromConfig.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Free-text search terms sent as the API's `query` param, e.g. "nature
     *  mountains". Empty means no filter - any Unsplash photo. */
    static String getSearchQuery(Context ctx) {
        return prefs(ctx).getString(KEY_SEARCH_QUERY, "");
    }

    static void setSearchQuery(Context ctx, String query) {
        prefs(ctx).edit().putString(KEY_SEARCH_QUERY, query == null ? "" : query.trim()).apply();
    }

    /** Local path to the last successfully downloaded Unsplash photo, or
     *  null if none has ever downloaded. */
    static String getCachedImagePath(Context ctx) {
        return prefs(ctx).getString(KEY_CACHED_IMAGE, null);
    }

    static void setCachedImagePath(Context ctx, String path) {
        prefs(ctx).edit().putString(KEY_CACHED_IMAGE, path).apply();
    }

    /** "Photo by X on Unsplash" - required by the API Guidelines whenever
     *  a downloaded photo is actually in use, not just browsed. */
    static String getCredit(Context ctx) {
        return prefs(ctx).getString(KEY_CREDIT, null);
    }

    static void setCredit(Context ctx, String credit) {
        prefs(ctx).edit().putString(KEY_CREDIT, credit).apply();
    }
}
