package com.oxylab.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * A small hand-drawn weather glyph - sun, cloud, rain, snow, storm or fog.
 * Drawn in code rather than shipped as drawables so one view can switch
 * between conditions, and tinted to match the time widget's text so the
 * whole block reads as a single piece.
 */
class WeatherGlyphView extends View {

    static final int SUN = 0, PARTLY = 1, CLOUD = 2, FOG = 3, RAIN = 4, SNOW = 5, THUNDER = 6;

    private int kind = SUN;
    private int color = 0xFFF0F0F0;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    WeatherGlyphView(Context ctx) {
        super(ctx);
    }

    void setKind(int k) {
        if (k != kind) { kind = k; invalidate(); }
    }

    void setColor(int c) {
        if (c != color) { color = c; invalidate(); }
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);

        switch (kind) {
            case SUN:
                drawSun(cv, cx, cy, s * 0.24f, s);
                break;
            case PARTLY:
                drawSun(cv, cx + s * 0.15f, cy - s * 0.15f, s * 0.15f, s * 0.72f);
                drawCloud(cv, cx - s * 0.04f, cy + s * 0.10f, s * 0.82f);
                break;
            case CLOUD:
                drawCloud(cv, cx, cy, s);
                break;
            case FOG:
                drawCloud(cv, cx, cy - s * 0.12f, s * 0.9f);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.05f);
                cv.drawLine(cx - s * 0.30f, cy + s * 0.24f, cx + s * 0.24f, cy + s * 0.24f, p);
                cv.drawLine(cx - s * 0.20f, cy + s * 0.38f, cx + s * 0.32f, cy + s * 0.38f, p);
                break;
            case RAIN:
                drawCloud(cv, cx, cy - s * 0.12f, s * 0.9f);
                drawDrops(cv, cx, cy, s);
                break;
            case SNOW:
                drawCloud(cv, cx, cy - s * 0.12f, s * 0.9f);
                drawFlakes(cv, cx, cy, s);
                break;
            case THUNDER:
                drawCloud(cv, cx, cy - s * 0.12f, s * 0.9f);
                drawBolt(cv, cx, cy, s);
                break;
        }
    }

    private void drawSun(Canvas cv, float cx, float cy, float r, float s) {
        p.setStyle(Paint.Style.FILL);
        cv.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.045f);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            cv.drawLine(cx + cos * r * 1.45f, cy + sin * r * 1.45f,
                        cx + cos * r * 2.05f, cy + sin * r * 2.05f, p);
        }
    }

    /** Cloud as a base bar plus three overlapping bumps. Same-colour fills
     *  union visually, so no path ops are needed. */
    private void drawCloud(Canvas cv, float cx, float cy, float s) {
        p.setStyle(Paint.Style.FILL);
        RectF base = new RectF(cx - s * 0.34f, cy - s * 0.02f, cx + s * 0.34f, cy + s * 0.20f);
        cv.drawRoundRect(base, s * 0.11f, s * 0.11f, p);
        cv.drawCircle(cx - s * 0.15f, cy - s * 0.02f, s * 0.16f, p);
        cv.drawCircle(cx + s * 0.02f, cy - s * 0.11f, s * 0.20f, p);
        cv.drawCircle(cx + s * 0.20f, cy + s * 0.01f, s * 0.14f, p);
    }

    private void drawDrops(Canvas cv, float cx, float cy, float s) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.055f);
        float y0 = cy + s * 0.27f, y1 = cy + s * 0.45f;
        cv.drawLine(cx - s * 0.20f, y0, cx - s * 0.26f, y1, p);
        cv.drawLine(cx + s * 0.00f, y0, cx - s * 0.06f, y1, p);
        cv.drawLine(cx + s * 0.20f, y0, cx + s * 0.14f, y1, p);
    }

    private void drawFlakes(Canvas cv, float cx, float cy, float s) {
        p.setStyle(Paint.Style.FILL);
        cv.drawCircle(cx - s * 0.20f, cy + s * 0.32f, s * 0.055f, p);
        cv.drawCircle(cx + s * 0.00f, cy + s * 0.40f, s * 0.055f, p);
        cv.drawCircle(cx + s * 0.20f, cy + s * 0.32f, s * 0.055f, p);
    }

    private void drawBolt(Canvas cv, float cx, float cy, float s) {
        p.setStyle(Paint.Style.FILL);
        Path path = new Path();
        path.moveTo(cx + s * 0.07f, cy + s * 0.20f);
        path.lineTo(cx - s * 0.12f, cy + s * 0.50f);
        path.lineTo(cx + s * 0.01f, cy + s * 0.50f);
        path.lineTo(cx - s * 0.05f, cy + s * 0.72f);
        path.lineTo(cx + s * 0.17f, cy + s * 0.42f);
        path.lineTo(cx + s * 0.04f, cy + s * 0.42f);
        path.close();
        cv.drawPath(path, p);
    }
}
