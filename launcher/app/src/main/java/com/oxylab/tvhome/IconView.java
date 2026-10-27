package com.oxylab.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Small hand-drawn icons for the quick-settings panel.
 *
 * Canvas primitives rather than vector-drawable path data: it is easy to
 * get an SVG-style path wrong without being able to preview it, and these
 * only need to read clearly at tile size next to a text label - they do
 * not have to be pixel-perfect Material glyphs.
 */
class IconView extends View {

    static final int WIFI = 0;
    static final int BLUETOOTH = 1;
    static final int NETWORK = 2;
    static final int NOTIFICATIONS = 3;
    static final int SETTINGS = 4;
    static final int PICTURE = 5;
    static final int SOUND = 6;
    static final int ACCESSIBILITY = 7;
    static final int INPUT = 8;
    static final int APPS = 9;
    static final int WALLPAPER = 10;
    static final int MEDIA_PREV = 11;
    static final int MEDIA_PLAY = 12;
    static final int MEDIA_PAUSE = 13;
    static final int MEDIA_NEXT = 14;

    private int kind = WIFI;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    IconView(Context c, int kind) {
        super(c);
        this.kind = kind;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(0xFFF0F0F0);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFF0F0F0);
    }

    void setTint(int color) {
        stroke.setColor(color);
        fill.setColor(color);
        invalidate();
    }

    /** Swaps the glyph in place - used by the media row's play/pause button. */
    void setKind(int k) {
        kind = k;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;
        stroke.setStrokeWidth(s * 0.09f);
        path.reset();

        switch (kind) {
            case WIFI: {
                float baseY = cy + s * 0.22f;
                for (int i = 0; i < 3; i++) {
                    float r = s * (0.14f + i * 0.17f);
                    rect.set(cx - r, baseY - r, cx + r, baseY + r);
                    c.drawArc(rect, 215, 110, false, stroke);
                }
                c.drawCircle(cx, baseY, s * 0.045f, fill);
                break;
            }
            case BLUETOOTH: {
                float top = cy - s * 0.32f, bottom = cy + s * 0.32f;
                float qh = (bottom - top) * 0.25f, aw = s * 0.16f;
                path.moveTo(cx, top);
                path.lineTo(cx, bottom);
                c.drawPath(path, stroke);
                path.reset();
                path.moveTo(cx - aw, top + qh);
                path.lineTo(cx + aw, bottom - qh);
                c.drawPath(path, stroke);
                path.reset();
                path.moveTo(cx + aw, top + qh);
                path.lineTo(cx - aw, bottom - qh);
                c.drawPath(path, stroke);
                break;
            }
            case NETWORK: {
                float r = s * 0.32f;
                c.drawCircle(cx, cy, r, stroke);
                rect.set(cx - r * 0.42f, cy - r, cx + r * 0.42f, cy + r);
                c.drawOval(rect, stroke);
                c.drawLine(cx - r, cy, cx + r, cy, stroke);
                break;
            }
            case NOTIFICATIONS: {
                float r = s * 0.24f;
                float domeCx = cx, domeCy = cy - s * 0.06f;
                rect.set(domeCx - r, domeCy - r, domeCx + r, domeCy + r);
                c.drawArc(rect, 180, 180, false, stroke);
                float bottomY = domeCy + s * 0.26f;
                c.drawLine(domeCx - r, domeCy, domeCx - r * 1.3f, bottomY, stroke);
                c.drawLine(domeCx + r, domeCy, domeCx + r * 1.3f, bottomY, stroke);
                c.drawLine(domeCx - r * 1.3f, bottomY, domeCx + r * 1.3f, bottomY, stroke);
                c.drawCircle(domeCx, domeCy - r - s * 0.03f, s * 0.035f, fill);
                c.drawCircle(domeCx, bottomY + s * 0.06f, s * 0.045f, fill);
                break;
            }
            case SETTINGS: {
                float ringR = s * 0.2f;
                c.drawCircle(cx, cy, ringR, stroke);
                int teeth = 8;
                float toothLen = s * 0.14f, toothW = s * 0.07f;
                for (int i = 0; i < teeth; i++) {
                    c.save();
                    c.rotate(i * 360f / teeth, cx, cy);
                    c.drawRoundRect(cx - toothW / 2, cy - ringR - toothLen,
                            cx + toothW / 2, cy - ringR, toothW / 2, toothW / 2, fill);
                    c.restore();
                }
                break;
            }
            case PICTURE: {
                float[] ys = { cy - s * 0.2f, cy, cy + s * 0.2f };
                float[] knobX = { 0.32f, 0.62f, 0.45f };
                float x0 = cx - s * 0.32f, x1 = cx + s * 0.32f;
                for (int i = 0; i < 3; i++) {
                    c.drawLine(x0, ys[i], x1, ys[i], stroke);
                    c.drawCircle(x0 + (x1 - x0) * knobX[i], ys[i], s * 0.06f, fill);
                }
                break;
            }
            case SOUND: {
                float bx = cx - s * 0.28f, by = cy;
                float boxW = s * 0.14f, boxH = s * 0.18f;
                path.moveTo(bx - boxW, by - boxH / 2);
                path.lineTo(bx, by - boxH / 2);
                path.lineTo(bx + s * 0.18f, by - s * 0.26f);
                path.lineTo(bx + s * 0.18f, by + s * 0.26f);
                path.lineTo(bx, by + boxH / 2);
                path.lineTo(bx - boxW, by + boxH / 2);
                path.close();
                c.drawPath(path, fill);
                for (int i = 0; i < 2; i++) {
                    float r = s * (0.14f + i * 0.12f);
                    rect.set(bx + s * 0.1f - r, cy - r, bx + s * 0.1f + r, cy + r);
                    c.drawArc(rect, -45, 90, false, stroke);
                }
                break;
            }
            case ACCESSIBILITY: {
                c.drawCircle(cx, cy, s * 0.36f, stroke);
                c.drawCircle(cx, cy - s * 0.18f, s * 0.07f, fill);
                c.drawLine(cx - s * 0.18f, cy - s * 0.03f, cx + s * 0.18f, cy - s * 0.03f, stroke);
                c.drawLine(cx, cy - s * 0.03f, cx, cy + s * 0.14f, stroke);
                c.drawLine(cx, cy + s * 0.14f, cx - s * 0.14f, cy + s * 0.3f, stroke);
                c.drawLine(cx, cy + s * 0.14f, cx + s * 0.14f, cy + s * 0.3f, stroke);
                break;
            }
            case INPUT: {
                // A signal arrow entering a rectangle and stopping inside
                // it - the actual remote glyph, not a bracket it passes
                // through and out the other side of.
                float rw = s * 0.34f, rh = s * 0.36f;
                float boxLeft = cx - rw * 0.1f, boxRight = boxLeft + rw;
                rect.set(boxLeft, cy - rh / 2, boxRight, cy + rh / 2);
                c.drawRoundRect(rect, s * 0.04f, s * 0.04f, stroke);

                float shaftStart = cx - s * 0.34f;
                float shaftEnd = boxLeft + rw * 0.3f;
                c.drawLine(shaftStart, cy, shaftEnd, cy, stroke);
                float head = s * 0.11f;
                c.drawLine(shaftEnd, cy, shaftEnd - head, cy - head, stroke);
                c.drawLine(shaftEnd, cy, shaftEnd - head, cy + head, stroke);
                break;
            }
            case APPS: {
                float cell = s * 0.22f, gap = s * 0.1f;
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 2; col++) {
                        float x = cx + (col == 0 ? -(gap / 2 + cell) : gap / 2);
                        float y = cy + (row == 0 ? -(gap / 2 + cell) : gap / 2);
                        c.drawRoundRect(x, y, x + cell, y + cell, s * 0.04f, s * 0.04f, fill);
                    }
                }
                break;
            }
            case WALLPAPER: {
                float fw = s * 0.34f, fh = s * 0.24f;
                c.drawRoundRect(cx - fw, cy - fh, cx + fw, cy + fh, s * 0.03f, s * 0.03f, stroke);
                c.drawCircle(cx - fw * 0.45f, cy - fh * 0.4f, s * 0.045f, fill);
                path.moveTo(cx - fw, cy + fh * 0.5f);
                path.lineTo(cx - fw * 0.2f, cy - fh * 0.15f);
                path.lineTo(cx + fw * 0.15f, cy + fh * 0.15f);
                path.lineTo(cx + fw * 0.45f, cy - fh * 0.35f);
                path.lineTo(cx + fw, cy + fh * 0.5f);
                path.close();
                c.save();
                c.clipRect(cx - fw, cy - fh, cx + fw, cy + fh);
                c.drawPath(path, fill);
                c.restore();
                break;
            }
            case MEDIA_PLAY: {
                float r = s * 0.3f;
                path.moveTo(cx - r * 0.55f, cy - r);
                path.lineTo(cx - r * 0.55f, cy + r);
                path.lineTo(cx + r * 0.85f, cy);
                path.close();
                c.drawPath(path, fill);
                break;
            }
            case MEDIA_PAUSE: {
                float barW = s * 0.12f, half = s * 0.34f, gap = s * 0.11f;
                float r = s * 0.03f;
                c.drawRoundRect(cx - gap - barW, cy - half, cx - gap, cy + half, r, r, fill);
                c.drawRoundRect(cx + gap, cy - half, cx + gap + barW, cy + half, r, r, fill);
                break;
            }
            case MEDIA_NEXT: {
                float r = s * 0.26f;
                path.moveTo(cx - r * 0.95f, cy - r);
                path.lineTo(cx - r * 0.95f, cy + r);
                path.lineTo(cx + r * 0.45f, cy);
                path.close();
                c.drawPath(path, fill);
                c.drawRoundRect(cx + r * 0.62f, cy - r,
                        cx + r * 0.62f + s * 0.1f, cy + r, s * 0.03f, s * 0.03f, fill);
                break;
            }
            case MEDIA_PREV: {
                float r = s * 0.26f;
                path.moveTo(cx + r * 0.95f, cy - r);
                path.lineTo(cx + r * 0.95f, cy + r);
                path.lineTo(cx - r * 0.45f, cy);
                path.close();
                c.drawPath(path, fill);
                c.drawRoundRect(cx - r * 0.62f - s * 0.1f, cy - r,
                        cx - r * 0.62f, cy + r, s * 0.03f, s * 0.03f, fill);
                break;
            }
        }
    }
}
