package com.oxylab.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Applies and refreshes the Home screen's background. Two halves:
 *
 *  - apply() is synchronous and cache-only - it never touches the
 *    network, so Home never blocks on a download just to draw itself.
 *    It is safe to call from onCreate()/onResume().
 *  - refreshUnsplash() is the only thing that hits the network, always
 *    on a background thread, callback posted back to the main thread.
 *
 * Per Unsplash's API Guidelines: the downloaded file is cached locally
 * only so the TV can show a wallpaper without live network access (a
 * normal wallpaper app's job), the photographer credit is stored and
 * shown in WallpaperActivity, and the download_location "trigger" is
 * pinged once per photo actually put into use.
 */
final class WallpaperManager {
    private static final String CACHE_FILE = "wallpaper_unsplash.jpg";

    private WallpaperManager() {}

    // ------------------------------------------------------------ apply

    static void apply(Context ctx, View root) {
        if (!WallpaperConfig.isEnabled(ctx)) {
            root.setBackgroundColor(0xFF000000);
            maybeAutoTheme(ctx, "off", 0f);
            return;
        }
        String mode = WallpaperConfig.getMode(ctx);
        if (WallpaperConfig.MODE_COLOR.equals(mode)) {
            int color = WallpaperConfig.getActiveColor(ctx);
            root.setBackgroundColor(color);
            maybeAutoTheme(ctx, "content:" + WallpaperConfig.getLastRefreshMillis(ctx), luminance(color));
            return;
        }
        String path = WallpaperConfig.MODE_FILE.equals(mode)
                ? WallpaperConfig.getActiveFile(ctx)
                : WallpaperConfig.getCachedImagePath(ctx);
        Bitmap bmp = path == null ? null : loadSampledBitmap(ctx, path);
        if (bmp != null) {
            root.setBackground(new BitmapDrawable(ctx.getResources(), bmp));
            maybeAutoTheme(ctx, "content:" + WallpaperConfig.getLastRefreshMillis(ctx), luminance(bmp));
        } else {
            root.setBackgroundColor(0xFF000000);
            maybeAutoTheme(ctx, "off", 0f);
        }
    }

    // ------------------------------------------------------- auto theme

    /** Same idea as ColorSampler's accent-color pick, applied to overall
     *  brightness instead of dominant hue: average perceived luminance
     *  decides whether Home's overlay text should be light or dark.
     *
     *  Runs inside apply() rather than at each place the wallpaper can
     *  change (a color toggled into rotation, a new file, a fresh
     *  Unsplash photo, a scheduled rotation) because apply() is already
     *  the one chokepoint all of those funnel through. identity is
     *  compared against the last call so this only recomputes - and only
     *  overwrites WallpaperConfig's dark-text value - when what's
     *  actually on screen changed; an unrelated repaint (Home resuming,
     *  a setting elsewhere in the panel) leaves a manual override alone.
     *  The dark-text toggle itself never changes identity (it calls
     *  notifyChanged() directly, not through a path that bumps
     *  lastRefreshMillis), so flipping it manually isn't immediately
     *  undone by this on the very next repaint. */
    private static String lastThemeIdentity;
    private static final float LIGHT_THRESHOLD = 0.55f;

    private static void maybeAutoTheme(Context ctx, String identity, float luminance) {
        if (identity.equals(lastThemeIdentity)) return;
        lastThemeIdentity = identity;
        WallpaperConfig.setDarkText(ctx, luminance > LIGHT_THRESHOLD);
    }

    /** Package-visible so WallpaperActivity can use the same contrast math
     *  for the solid-color swatches' own indicator/focus-ring color. */
    static float luminance(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
    }

    /** Stride-sampled so a large photo stays cheap regardless of
     *  resolution - the bitmap handed in is already downsampled to
     *  roughly screen size, so this is at most ~1000 pixel reads. */
    private static float luminance(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w <= 0 || h <= 0) return 0.5f;
        int stepX = Math.max(1, w / 40), stepY = Math.max(1, h / 24);
        long sum = 0; int n = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int p = bmp.getPixel(x, y);
                sum += (int) (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p));
                n++;
            }
        }
        return n == 0 ? 0.5f : (sum / (float) n) / 255f;
    }

    // ------------------------------------------------------- live repaint

    /** Home's root view, so a change made in the Wallpaper panel can repaint
     *  Home immediately instead of waiting for Home to resume when the panel
     *  closes. The panel is translucent and sits on top of Home, so Home is
     *  alive (paused, not stopped) the whole time - but only re-applies its
     *  background in onResume, which is the old after-the-fact behaviour.
     *  Weak so a destroyed Home is never kept alive by this reference. */
    private static WeakReference<View> homeRoot;

    /** Home's own applyTextTheme(), so the dark-text toggle repaints the
     *  overlay text live too - same reasoning as homeRoot, just for text
     *  color instead of the background drawable, since that isn't
     *  something apply() touches. A plain (strong) reference, not weak
     *  like homeRoot: a method reference like HomeActivity::applyTextTheme
     *  has no other owner once attachHome() returns, so wrapping it in a
     *  WeakReference left it eligible for GC almost immediately - it just
     *  relies on detachHome() for cleanup instead, same as homeRoot does
     *  in practice. */
    private static Runnable textThemeCallback;

    /** Called by HomeActivity once its root (and applyTextTheme) exist. */
    static void attachHome(View root, Runnable onTextThemeChanged) {
        homeRoot = new WeakReference<>(root);
        textThemeCallback = onTextThemeChanged;
    }

    /** Called by HomeActivity from onDestroy(). */
    static void detachHome(View root) {
        if (homeRoot != null && homeRoot.get() == root) { homeRoot = null; textThemeCallback = null; }
    }

    /** Re-apply the wallpaper to Home right now, if Home is alive. Called
     *  after every config change in WallpaperActivity. Cheap and cache-only
     *  (never touches the network), so safe to call on every D-pad step. */
    static void notifyChanged() {
        View root = homeRoot == null ? null : homeRoot.get();
        if (root != null) apply(root.getContext(), root);
        if (textThemeCallback != null) textThemeCallback.run();
    }

    /** Downsamples to roughly screen resolution before decoding, and uses
     *  RGB_565 (no alpha needed for a background) - a full-res photo
     *  decoded ARGB_8888 is an easy way to pressure a 1.8 GB TV. Returns
     *  the Bitmap itself (not wrapped in a Drawable) so apply() can also
     *  sample it for auto theme detection without a second decode. */
    private static Bitmap loadSampledBitmap(Context ctx, String path) {
        try {
            int targetW = ctx.getResources().getDisplayMetrics().widthPixels;
            int targetH = ctx.getResources().getDisplayMetrics().heightPixels;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetW
                    && bounds.outHeight / (sample * 2) >= targetH) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    // --------------------------------------------------------- schedule

    /** Fire-and-forget last-updated check, meant to be called every time
     *  Home is loaded (onResume). If the active source's schedule has
     *  elapsed it either downloads a fresh Unsplash photo or advances the
     *  multi-select color/image rotation, then re-applies.
     *
     *  The schedule is counted in local calendar days, not elapsed hours:
     *  with "every day" a change made at 23:59 still advances once the
     *  date rolls over, rather than waiting a full 24 hours.
     *
     *  Deliberately not an AlarmManager/background timer: a TV spends most
     *  of its life with Home in front or one HOME press away, so checking
     *  on load is close enough to a real timer for day-granularity, without
     *  the battery/complexity cost. */
    static void maybeScheduledRefresh(Context ctx, Runnable onApplied) {
        if (!WallpaperConfig.isEnabled(ctx)) return;
        int days = WallpaperConfig.getScheduleDays(ctx);
        if (days <= 0) return;
        String mode = WallpaperConfig.getMode(ctx);
        long lastDay = WallpaperConfig.getLastRefreshDay(ctx);
        if (lastDay <= 0) {
            // Never ticked before. Unsplash with no cached photo still needs
            // its first download, but otherwise just start the clock today so
            // a freshly-picked color/image isn't rotated away immediately.
            boolean unsplashNeedsFirstImage = WallpaperConfig.MODE_UNSPLASH.equals(mode)
                    && WallpaperConfig.getCachedImagePath(ctx) == null;
            if (!unsplashNeedsFirstImage) {
                WallpaperConfig.setLastRefreshMillis(ctx, System.currentTimeMillis());
                return;
            }
        }
        // Whole calendar days since the last refresh, not elapsed millis.
        if (lastDay > 0 && WallpaperConfig.todayEpochDay() - lastDay < days) return;

        final Context app = ctx.getApplicationContext();
        if (WallpaperConfig.MODE_UNSPLASH.equals(mode)) {
            refreshUnsplash(app, (ok, msg) -> { if (ok && onApplied != null) onApplied.run(); });
            return;
        }
        if (WallpaperConfig.MODE_COLOR.equals(mode)) {
            WallpaperConfig.advanceColor(app);
        } else if (WallpaperConfig.MODE_FILE.equals(mode)) {
            WallpaperConfig.advanceFile(app);
        } else {
            return;
        }
        WallpaperConfig.setLastRefreshMillis(app, System.currentTimeMillis());
        if (onApplied != null) onApplied.run();
    }

    // --------------------------------------------------------- download

    interface RefreshCallback { void onResult(boolean ok, String message); }

    static void refreshUnsplash(Context ctx, Runnable onDone) {
        refreshUnsplash(ctx, (ok, msg) -> { if (onDone != null) onDone.run(); });
    }

    static void refreshUnsplash(final Context ctx, final RefreshCallback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String result = download(app);
            if (cb != null) {
                new Handler(Looper.getMainLooper())
                        .post(() -> cb.onResult(result == null, result));
            }
        }).start();
    }

    /** Returns null on success, or a short user-facing error string. */
    private static String download(Context ctx) {
        String key = WallpaperConfig.getEffectiveApiKey(ctx);
        if (key.isEmpty()) {
            return "Add a free Unsplash API key in the Wallpaper screen first";
        }
        String appName = ctx.getString(R.string.wallpaper_app_name);
        String query = WallpaperConfig.getSearchQuery(ctx);
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://api.unsplash.com/photos/random"
                    + "?orientation=landscape&content_filter=high";
            if (!query.isEmpty()) {
                urlStr += "&query=" + java.net.URLEncoder.encode(query, "UTF-8");
            }
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Authorization", "Client-ID " + key);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code == 401 || code == 403) return "Unsplash rejected the API key";
            if (code == 429) return "Unsplash rate limit reached - try later";
            if (code != 200) return "Unsplash error (HTTP " + code + ")";

            JSONObject obj = new JSONObject(readAll(conn.getInputStream()));
            String raw = obj.getJSONObject("urls").getString("raw");
            String imageUrl = raw + (raw.contains("?") ? "&" : "?") + "w=1920&h=1080&fit=crop&q=80";

            JSONObject user = obj.getJSONObject("user");
            String name = user.optString("name", "an Unsplash photographer");
            String credit = "Photo by " + name + " on Unsplash";

            File finalFile = new File(ctx.getFilesDir(), CACHE_FILE);
            File tmp = new File(ctx.getFilesDir(), CACHE_FILE + ".part");
            downloadTo(imageUrl, tmp);
            if (!tmp.renameTo(finalFile)) { tmp.delete(); return "Could not save the photo"; }

            // Download-tracking trigger, per Unsplash API Guidelines -
            // best effort, a failure here shouldn't fail the refresh.
            try {
                String loc = obj.optJSONObject("links") == null ? null
                        : obj.getJSONObject("links").optString("download_location", null);
                if (loc != null) {
                    String sep = loc.contains("?") ? "&" : "?";
                    HttpURLConnection trig = (HttpURLConnection)
                            new URL(loc + sep + "client_id=" + key
                                    + "&utm_source=" + appName + "&utm_medium=referral")
                                    .openConnection();
                    trig.setConnectTimeout(5000);
                    trig.getResponseCode();
                    trig.disconnect();
                }
            } catch (Throwable ignored) {}

            WallpaperConfig.setCachedImagePath(ctx, finalFile.getAbsolutePath());
            WallpaperConfig.setCredit(ctx, credit);
            WallpaperConfig.setLastRefreshMillis(ctx, System.currentTimeMillis());
            return null;
        } catch (Throwable t) {
            return "Couldn't reach Unsplash";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void downloadTo(String urlStr, File out) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        try (InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }
}
