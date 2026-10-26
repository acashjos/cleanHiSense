package com.oxylab.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * Fetches current conditions for the Home weather widget. Two free,
 * key-less services, both plain HTTPS JSON:
 *
 *   1. Location: the system provider when the app holds coarse location
 *      (the TV's fused/network fix, reverse-geocoded to a city name), else
 *      a public-IP lookup (ipwho.is, falling back to ipinfo.io) - a good
 *      signal for a device that never moves.
 *   2. Open-Meteo for the actual weather, current + hourly, in the
 *      location's own timezone.
 *
 * The result is cached in SharedPreferences so Home can paint instantly
 * on launch and only pays for a network round trip once the cache is
 * stale. Nothing here touches the UI thread; fetch() posts its callback
 * back to the main looper.
 */
final class Weather {

    private static final String PREFS = "weather";
    private static final String K_CITY = "city";
    private static final String K_TEMP = "temp";
    private static final String K_CODE = "code";
    private static final String K_NEXT_CODE = "next_code";
    private static final String K_NEXT_DAY = "next_day";
    private static final String K_IS_DAY = "is_day";
    private static final String K_FAHRENHEIT = "fahrenheit";
    private static final String K_FETCHED = "fetched_at";
    private static final String K_LAT = "lat_bits";
    private static final String K_LON = "lon_bits";
    private static final String K_COUNTRY = "country";
    private static final String K_UNIT = "unit_override";
    private static final String K_ATTEMPT = "attempted_at";
    private static final String K_LOCATED = "located_at";

    /** How long a fetch is considered good for. */
    static final long FRESH_MILLIS = 30L * 60 * 1000;

    /** How long to wait after a *failed* attempt before the minute tick is
     *  allowed to try again. Without this, a dead network or a rate-limited
     *  server would be hit once a minute, forever, because a failed fetch
     *  never advances fetched_at. Five minutes keeps the worst case to a
     *  handful of calls an hour. */
    static final long RETRY_MILLIS = 5L * 60 * 1000;

    /** Immutable-ish snapshot handed to the widget. */
    static final class Data {
        String city = "";
        double temp;          // already in the unit below
        int code;             // WMO code, current
        int nextCode;         // WMO code, next hour
        boolean nextIsDay = true;
        boolean isDay = true;
        boolean fahrenheit;
        long fetchedAt;
    }

    interface Callback { void onWeather(Data d); }

    private Weather() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Last saved reading, or null if there has never been one. */
    static Data cached(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains(K_FETCHED)) return null;
        Data d = new Data();
        d.city = p.getString(K_CITY, "");
        d.temp = p.getFloat(K_TEMP, 0f);
        d.code = p.getInt(K_CODE, 0);
        d.nextCode = p.getInt(K_NEXT_CODE, d.code);
        d.nextIsDay = p.getBoolean(K_NEXT_DAY, true);
        d.isDay = p.getBoolean(K_IS_DAY, true);
        d.fahrenheit = p.getBoolean(K_FAHRENHEIT, false);
        d.fetchedAt = p.getLong(K_FETCHED, 0);
        return d;
    }

    /** Drops the cached reading (temperature, codes, timestamp) but keeps
     *  the geolocated location, so an expired reading is never shown again
     *  while the next refresh still knows where to look. */
    static void clearReading(Context c) {
        prefs(c).edit()
                .remove(K_TEMP).remove(K_CODE).remove(K_NEXT_CODE)
                .remove(K_NEXT_DAY).remove(K_IS_DAY).remove(K_FAHRENHEIT)
                .remove(K_FETCHED).apply();
    }

    /** The geolocated city, kept even after the reading itself is cleared. */
    static String cachedCity(Context c) {
        return prefs(c).getString(K_CITY, "");
    }

    /** True when the last refresh attempt failed (nothing has succeeded
     *  since), so Home can show the offline state on launch instead of a
     *  blank placeholder while the retry backoff is still running. */
    static boolean lastAttemptFailed(Context c) {
        SharedPreferences p = prefs(c);
        return p.getLong(K_ATTEMPT, 0) > p.getLong(K_FETCHED, 0);
    }

    /** True once enough time has passed since the last attempt (successful
     *  or failed) that another automatic one is allowed. This is the retry
     *  backoff, not the freshness window - isFresh() covers the latter. */
    static boolean canAttempt(Context c) {
        long last = prefs(c).getLong(K_ATTEMPT, 0);
        return System.currentTimeMillis() - last >= RETRY_MILLIS;
    }

    static boolean isFresh(Context c, Data d) {
        if (d == null) return false;
        SharedPreferences p = prefs(c);
        // Without a geolocated country we do not yet know which unit this
        // location uses, and a stored reading in the other unit would be
        // the wrong scale - treat both as stale so the next pass fixes it.
        // An explicit unit choice needs no country.
        if (p.getInt(K_UNIT, UNIT_AUTO) == UNIT_AUTO
                && p.getString(K_COUNTRY, "").isEmpty()) return false;
        if (d.fahrenheit != useFahrenheit(p)) return false;
        return System.currentTimeMillis() - d.fetchedAt < FRESH_MILLIS;
    }

    // ------------------------------------------------------------- units

    /** Manual unit choice: follows the location until the widget is tapped. */
    static final int UNIT_AUTO = 0, UNIT_C = 1, UNIT_F = 2;

    /** Effective unit: the explicit choice if there is one, else the
     *  geolocated country (US/LR/MM are the Fahrenheit holdouts). */
    static boolean useFahrenheit(Context c) { return useFahrenheit(prefs(c)); }

    /** Flips C<->F and pins the choice, so it survives the next refresh. */
    static void toggleUnit(Context c) {
        prefs(c).edit().putInt(K_UNIT, useFahrenheit(c) ? UNIT_C : UNIT_F).apply();
    }

    /** Re-expresses a reading in the currently selected unit and writes it
     *  back, so a tap repaints instantly instead of waiting on the network
     *  (the next fetch then arrives in the same unit). */
    static void convert(Context c, Data d) {
        if (d == null) return;
        boolean f = useFahrenheit(c);
        if (d.fahrenheit == f) return;
        d.temp = f ? d.temp * 9.0 / 5.0 + 32 : (d.temp - 32) * 5.0 / 9.0;
        d.fahrenheit = f;
        prefs(c).edit().putFloat(K_TEMP, (float) d.temp)
                .putBoolean(K_FAHRENHEIT, f).apply();
    }

    /** Runs the whole lookup off the UI thread; cb (if non-null) fires on
     *  the main thread with the new Data, or null if it could not be had. */
    static void fetch(Context ctx, Callback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Data out = null;
            try { out = doFetch(app); } catch (Throwable ignored) {}
            final Data result = out;
            new Handler(Looper.getMainLooper())
                    .post(() -> { if (cb != null) cb.onWeather(result); });
        }).start();
    }

    private static Data doFetch(Context ctx) throws Exception {
        SharedPreferences p = prefs(ctx);
        // Record the attempt before any network work, so a failure (which
        // leaves fetched_at untouched) still starts the backoff window.
        p.edit().putLong(K_ATTEMPT, System.currentTimeMillis()).apply();
        double lat = Double.longBitsToDouble(p.getLong(K_LAT, 0));
        double lon = Double.longBitsToDouble(p.getLong(K_LON, 0));
        String city = p.getString(K_CITY, "");
        boolean hasCoords = lat != 0 || lon != 0;
        // The country decides the unit, so a cached location without one is
        // treated as incomplete even before it ages out.
        long locatedAt = p.getLong(K_LOCATED, 0);
        boolean locationFresh = hasCoords
                && System.currentTimeMillis() - locatedAt < FRESH_MILLIS
                && !p.getString(K_COUNTRY, "").isEmpty();
        if (!locationFresh) {
            if (!resolveLocation(ctx, p) && !hasCoords) {
                // Never located and the lookup failed: nothing to fetch for.
                throw new Exception("no location");
            }
            // On lookup failure fall through with the expired location -
            // it is kept around precisely so the widget survives an outage.
            lat = Double.longBitsToDouble(p.getLong(K_LAT, 0));
            lon = Double.longBitsToDouble(p.getLong(K_LON, 0));
            city = p.getString(K_CITY, "");
        }

        boolean fahrenheit = useFahrenheit(p);
        String url = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day"
                + "&hourly=weather_code,is_day"
                + "&forecast_days=2&timezone=auto"
                + (fahrenheit ? "&temperature_unit=fahrenheit" : "");

        JSONObject o = getJson(url);
        JSONObject cur = o.getJSONObject("current");
        Data d = new Data();
        d.city = city;
        d.temp = cur.getDouble("temperature_2m");
        d.code = cur.optInt("weather_code", 0);
        d.isDay = cur.optInt("is_day", 1) == 1;
        d.fahrenheit = fahrenheit;

        // Next hour = the first hourly slot strictly after the current one.
        JSONObject hourly = o.getJSONObject("hourly");
        JSONArray times = hourly.getJSONArray("time");
        JSONArray codes = hourly.getJSONArray("weather_code");
        JSONArray days = hourly.optJSONArray("is_day");
        String now = cur.optString("time", "");
        int idx = -1;
        for (int i = 0; i < times.length(); i++) {
            if (times.getString(i).compareTo(now) > 0) { idx = i; break; }
        }
        if (idx < 0) idx = Math.min(1, times.length() - 1);
        d.nextCode = codes.optInt(idx, d.code);
        d.nextIsDay = days == null ? d.isDay : days.optInt(idx, d.isDay ? 1 : 0) == 1;

        d.fetchedAt = System.currentTimeMillis();
        p.edit()
                .putString(K_CITY, d.city)
                .putFloat(K_TEMP, (float) d.temp)
                .putInt(K_CODE, d.code)
                .putInt(K_NEXT_CODE, d.nextCode)
                .putBoolean(K_NEXT_DAY, d.nextIsDay)
                .putBoolean(K_IS_DAY, d.isDay)
                .putBoolean(K_FAHRENHEIT, d.fahrenheit)
                .putLong(K_FETCHED, d.fetchedAt)
                .apply();
        return d;
    }

    /** Location lookup: the system provider first (it is usually the same
     *  network fix, but no API call), then the public-IP services. Writes
     *  city + country + coordinates into prefs. */
    private static boolean resolveLocation(Context ctx, SharedPreferences p) {
        return systemLocation(ctx, p) || geolocate(p);
    }

    /** Reads the freshest system fix and reverse-geocodes it to a city.
     *  Returns false when there is no permission, no fix, or no city name -
     *  in which case the caller falls back to the IP lookup. */
    private static boolean systemLocation(Context ctx, SharedPreferences p) {
        if (!hasLocationPermission(ctx)) return false;
        Location best = bestLastKnown(ctx);
        if (best == null) return false;

        String city = p.getString(K_CITY, "");
        String country = p.getString(K_COUNTRY, "");
        try {
            Geocoder gc = new Geocoder(ctx, Locale.getDefault());
            List<Address> found =
                    gc.getFromLocation(best.getLatitude(), best.getLongitude(), 1);
            if (found != null && !found.isEmpty()) {
                Address a = found.get(0);
                String label = a.getLocality();
                if (label == null || label.isEmpty()) label = a.getSubAdminArea();
                if (label == null || label.isEmpty()) label = a.getAdminArea();
                if (label != null && !label.isEmpty()) city = label;
                String cc = a.getCountryCode();
                if (cc != null && !cc.isEmpty()) country = cc;
            }
        } catch (Throwable ignored) {}

        // With no city label the widget would have nothing to show, so let
        // the IP lookup supply one (and its own coordinates) instead.
        if (city == null || city.isEmpty()) return false;
        saveLocation(p, city, country, best.getLatitude(), best.getLongitude());
        return true;
    }

    private static boolean hasLocationPermission(Context ctx) {
        try {
            return ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                    || ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Location bestLastKnown(Context ctx) {
        try {
            LocationManager lm =
                    (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String provider : lm.getAllProviders()) {
                Location l = lm.getLastKnownLocation(provider);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Public-IP lookup; writes city + coordinates into prefs. */
    private static boolean geolocate(SharedPreferences p) {
        try {
            JSONObject g = getJson("https://ipwho.is/");
            if (g.optBoolean("success", false)) {
                String city = g.optString("city", "");
                String country = g.optString("country_code", "");
                double lat = g.optDouble("latitude", 0);
                double lon = g.optDouble("longitude", 0);
                if (!city.isEmpty() && (lat != 0 || lon != 0)) {
                    saveLocation(p, city, country, lat, lon);
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        try {
            JSONObject g = getJson("https://ipinfo.io/json");
            String city = g.optString("city", "");
            String country = g.optString("country", "");
            String loc = g.optString("loc", "");
            int comma = loc.indexOf(',');
            if (!city.isEmpty() && comma > 0) {
                double lat = Double.parseDouble(loc.substring(0, comma));
                double lon = Double.parseDouble(loc.substring(comma + 1));
                saveLocation(p, city, country, lat, lon);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void saveLocation(SharedPreferences p, String city, String country,
                                     double lat, double lon) {
        p.edit()
                .putString(K_CITY, city)
                .putString(K_COUNTRY, country)
                .putLong(K_LAT, Double.doubleToLongBits(lat))
                .putLong(K_LON, Double.doubleToLongBits(lon))
                .putLong(K_LOCATED, System.currentTimeMillis())
                .apply();
    }

    /** Metric everywhere except the three Fahrenheit holdouts, decided from
     *  the geolocated country (falling back to the device locale until the
     *  first lookup lands). */
    private static boolean useFahrenheit(SharedPreferences p) {
        int unit = p.getInt(K_UNIT, UNIT_AUTO);
        if (unit == UNIT_C) return false;
        if (unit == UNIT_F) return true;
        String cc = p.getString(K_COUNTRY, "");
        if (cc == null || cc.isEmpty()) cc = Locale.getDefault().getCountry();
        return "US".equalsIgnoreCase(cc) || "LR".equalsIgnoreCase(cc) || "MM".equalsIgnoreCase(cc);
    }

    private static JSONObject getJson(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", "application/json");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new JSONObject(bos.toString("UTF-8"));
        } finally {
            conn.disconnect();
        }
    }

    /** WMO weather code -> one of WeatherGlyphView's shapes. */
    static int glyphKind(int code) {
        switch (code) {
            case 0: return WeatherGlyphView.SUN;
            case 1: case 2: return WeatherGlyphView.PARTLY;
            case 3: return WeatherGlyphView.CLOUD;
            case 45: case 48: return WeatherGlyphView.FOG;
            case 51: case 53: case 55: case 56: case 57:
            case 61: case 63: case 65: case 66: case 67:
            case 80: case 81: case 82: return WeatherGlyphView.RAIN;
            case 71: case 73: case 75: case 77: case 85: case 86: return WeatherGlyphView.SNOW;
            case 95: case 96: case 99: return WeatherGlyphView.THUNDER;
            default: return WeatherGlyphView.CLOUD;
        }
    }

    /** Short "what's coming" line for the widget's last row. */
    static String phrase(int code, boolean isDay) {
        switch (code) {
            case 0: return isDay ? "Sunny day ahead" : "Clear night ahead";
            case 1: return isDay ? "Mostly sunny ahead" : "Mostly clear ahead";
            case 2: return "Partly cloudy ahead";
            case 3: return "Cloudy ahead";
            case 45: case 48: return "Foggy ahead";
            case 51: case 53: case 55: case 56: case 57: return "Drizzle ahead";
            case 61: case 63: case 65: case 66: case 67:
            case 80: case 81: case 82: return "Rain ahead";
            case 71: case 73: case 75: case 77: case 85: case 86: return "Snow ahead";
            case 95: case 96: case 99: return "Thunderstorms ahead";
            default: return "More weather ahead";
        }
    }
}
