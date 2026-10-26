package com.oxylab.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The Home weather block, sitting where the three top-bar buttons used to.
 * Same typographic voice as the time widget opposite it - a quiet city
 * line, one big number, a quiet status line - with a drawn condition glyph
 * beside the temperature:
 *
 *        MONTREAL
 *   [sun]  24°C
 *   Sunny day ahead
 *
 * Kept deliberately dumb: it only renders a Weather.Data, and setTheme()
 * flips it between the light and dark wallpaper palettes.
 */
class WeatherView extends LinearLayout {

    private final TextView cityView, tempView, nextView;
    private final WeatherGlyphView glyph;
    private final float density;

    WeatherView(Context ctx, Typeface tf) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        // Center every row within the card so the shorter lines (city,
        // status) get equal space on both sides instead of hugging the right.
        setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        setClipChildren(false);

        cityView = new TextView(ctx);
        style(cityView, 14f, 0xB3FFFFFF, 500, tf);
        // Explicit WRAP_CONTENT: a bare addView() in a VERTICAL LinearLayout
        // defaults to MATCH_PARENT, which would stop this column sizing to
        // its widest child. HomeActivity's time widget hit the clipping bug
        // this causes; keep every child here WRAP_CONTENT for the same
        // "widget covers the longest line" rule.
        addView(cityView, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        LinearLayout mid = new LinearLayout(ctx);
        mid.setOrientation(HORIZONTAL);
        mid.setGravity(Gravity.CENTER);
        mid.setClipChildren(false);

        glyph = new WeatherGlyphView(ctx);
        int gs = dp(34);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(gs, gs);
        glp.rightMargin = dp(12);
        mid.addView(glyph, glp);

        tempView = new TextView(ctx);
        style(tempView, 46f, 0xFFFFFFFF, 300, tf);
        tempView.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(2);
        mid.addView(tempView, tlp);
        addView(mid, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        nextView = new TextView(ctx);
        style(nextView, 13f, 0x8CFFFFFF, 400, tf);
        addView(nextView, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // Placeholder until the first reading lands (or if it never does).
        cityView.setText("Weather");
        tempView.setText("\u2014");
    }

    void setData(Weather.Data d) {
        if (d == null) {
            cityView.setText("Weather");
            tempView.setText("\u2014");
            nextView.setText("Unavailable");
            return;
        }
        cityView.setText(d.city);
        String unit = d.fahrenheit ? "F" : "C";
        String txt = Math.round(d.temp) + "\u00B0" + unit;
        SpannableString span = new SpannableString(txt);
        // The unit stays small so the number itself dominates the line.
        span.setSpan(new RelativeSizeSpan(0.5f), txt.length() - 1, txt.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tempView.setText(span);
        glyph.setKind(Weather.glyphKind(d.code));
        nextView.setText(Weather.phrase(d.nextCode, d.nextIsDay));
    }

    /** Network failure: keep the city we still know from the location
     *  cache, blank the temperature, and say why. */
    void setError(String city) {
        cityView.setText(city == null || city.isEmpty() ? "Weather" : city);
        tempView.setText("--");
        nextView.setText("No network");
        glyph.setKind(WeatherGlyphView.CLOUD);
    }

    /** Light or dark, to match whatever theme Home's overlay is using. */
    void setTheme(boolean dark) {
        cityView.setTextColor(dark ? 0xB3000000 : 0xB3FFFFFF);
        tempView.setTextColor(dark ? 0xFF000000 : 0xFFFFFFFF);
        nextView.setTextColor(dark ? 0x8C000000 : 0x8CFFFFFF);
        glyph.setColor(dark ? 0xFF1A1A1A : 0xFFF0F0F0);
    }

    private void style(TextView t, float sp, int color, int weight, Typeface tf) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        try { t.setFontVariationSettings("'wght' " + weight); } catch (Throwable ignored) {}
        t.setIncludeFontPadding(false);
    }

    private int dp(int v) { return Math.round(v * density); }
}
