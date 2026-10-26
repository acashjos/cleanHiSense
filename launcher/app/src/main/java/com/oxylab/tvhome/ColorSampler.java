package com.oxylab.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

/**
 * Pulls one representative colour out of an app's banner, for the ambient
 * glow behind the dock title.  Deliberately tiny: the banner is rasterised
 * once at 24x14 and scored in a single pass, so this costs well under a
 * millisecond and no bitmap is retained.
 */
final class ColorSampler {
    private ColorSampler() {}

    private static final int W = 24, H = 14;

    static int accentOf(Drawable d) {
        if (d == null) return 0xFF8899AA;
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, W, H);
            d.draw(c);

            float[] hsv = new float[3];
            float bestScore = -1f;
            int best = 0;
            long ar = 0, ag = 0, ab = 0, n = 0;

            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int p = bmp.getPixel(x, y);
                    int a = Color.alpha(p);
                    if (a < 128) continue;
                    ar += Color.red(p); ag += Color.green(p); ab += Color.blue(p); n++;
                    Color.colorToHSV(p, hsv);
                    // Favour saturated pixels of middling brightness: those
                    // read as "the app's colour" rather than its background.
                    float score = hsv[1] * (1f - Math.abs(hsv[2] - 0.65f));
                    if (score > bestScore) { bestScore = score; best = p; }
                }
            }
            if (n == 0) return 0xFF8899AA;
            // A banner with no colour at all (pure greyscale logo) scores
            // near zero everywhere; fall back to its average.
            int picked = bestScore < 0.08f
                    ? Color.rgb((int) (ar / n), (int) (ag / n), (int) (ab / n))
                    : best;
            return boost(picked);
        } catch (Throwable t) {
            return 0xFF8899AA;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /** Lift the colour so the glow reads on a pitch-black background. */
    private static int boost(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(1f, Math.max(hsv[1], 0.45f));
        hsv[2] = Math.min(1f, Math.max(hsv[2], 0.80f));
        return Color.HSVToColor(hsv);
    }
}
