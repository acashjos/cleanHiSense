package com.oxylab.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One app tile: artwork, plus its name underneath that only appears while
 * the tile has focus.  The focus ring itself is NOT drawn here - a single
 * shared glass plate behind the grid does that, so the highlight can
 * travel between tiles instead of blinking on and off.
 */
class Tile extends LinearLayout {

    final AppEntry entry;
    private final ImageView art;
    private final TextView name;

    Tile(Context ctx, AppEntry entry, int artW, int artH, Typeface tf) {
        super(ctx);
        this.entry = entry;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setClipChildren(false);
        setClipToPadding(false);

        art = new ImageView(ctx);
        art.setLayoutParams(new LayoutParams(artW, artH));
        art.setBackgroundResource(R.drawable.tile_bg);
        art.setClipToOutline(true);
        Drawable d = entry.art;
        if (d != null) art.setImageDrawable(d);
        // A 16:9 banner fills the tile; a square icon sits inside it.
        if (entry.wide) {
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = Math.round(artH * 0.20f);
            art.setPadding(pad, pad, pad, pad);
        }
        addView(art);

        name = new TextView(ctx);
        name.setText(entry.label);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setTextColor(0xFFE8E8E8);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        if (tf != null) name.setTypeface(tf);
        // At this size, plain weight reads as almost invisible over a
        // busy wallpaper - a touch bolder keeps it legible in both themes.
        try { name.setFontVariationSettings("'wght' 600"); } catch (Throwable ignored) {}
        name.setAlpha(0f);
        LayoutParams lp = new LayoutParams(artW, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        name.setLayoutParams(lp);
        addView(name);
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        float s = gained ? 1.08f : 1f;
        art.animate().scaleX(s).scaleY(s)
           .setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
        name.animate().alpha(gained ? 1f : 0f).setDuration(160).start();
        if (gained) {
            // Keep the focused tile above its neighbours while it is scaled.
            setZ(8f);
        } else {
            setZ(0f);
        }
    }

    /** Bounds of the artwork only, in this tile's coordinates. */
    View artView() { return art; }

    /** Light or dark, to match whatever theme Home's overlay text is
     *  currently using - set at creation and again live if the theme
     *  changes while this tile already exists. */
    void setNameColor(int color) {
        name.setTextColor(color);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
