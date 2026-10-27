package com.oxylab.tvhome;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A circular icon button for the Home top bar (Inputs/Settings/Wallpaper):
 * just the icon normally, its label fading in underneath on focus - the
 * same "icon now, name on focus" idea Tile already uses for the dock and
 * grid, so three text pills don't sit there competing with the clock for
 * attention all the time.
 *
 * pill_bg's 22dp corner radius is exactly half of this button's 44dp
 * size, so the focused pill drawable renders as a circle here with no
 * new drawable needed. While unfocused the plate is fully transparent,
 * so the top bar shows bare glyphs until one is selected.
 */
class IconButton extends LinearLayout {

    // Exactly one of these is non-null: the hand-drawn IconView glyph, or
    // an ImageView for the few icons (Input) that came from a real SVG
    // asset instead.
    private final IconView icon;
    private final ImageView image;
    private final FrameLayout iconWrap;
    private final TextView label;

    /** drawableRes != 0 uses that drawable (tinted) instead of the
     *  IconView glyph for iconKind. */
    IconButton(Context ctx, int iconKind, int drawableRes, String text, Typeface tf,
               Runnable onFocusGained) {
        this(ctx, iconKind, drawableRes, text, tf, onFocusGained, true);
    }

    /** showLabel=false gives a bare circular glyph with nothing underneath,
     *  for the media row, where the vertical space is tight. */
    IconButton(Context ctx, int iconKind, int drawableRes, String text, Typeface tf,
               Runnable onFocusGained, boolean showLabel) {
        super(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setFocusable(true);
        setClipChildren(false);
        setClipToPadding(false);

        int outer = Math.round(44 * density);
        int inner = Math.round(20 * density);

        iconWrap = new FrameLayout(ctx);
        // The plate's focused state lives on this non-focusable wrapper, so
        // it has to inherit the button's state for the glass circle to
        // appear only while the button is selected.
        iconWrap.setDuplicateParentStateEnabled(true);
        iconWrap.setBackgroundResource(R.drawable.pill_bg);
        View glyph;
        // The glyphs have very different natural extents: the Input SVG
        // fills its box, while the gear and photo-frame strokes only span
        // part of theirs. Draw each into its own box size so all three end
        // up with the same optical height (~30px) inside the circle.
        float glyphScale;
        if (drawableRes != 0) {
            icon = null;
            image = new ImageView(ctx);
            image.setImageResource(drawableRes);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            glyph = image;
            glyphScale = 0.89f;
        } else {
            image = null;
            icon = new IconView(ctx, iconKind);
            glyph = icon;
            glyphScale = iconKind == IconView.WALLPAPER ? 1.36f
                       : iconKind == IconView.SETTINGS  ? 1.15f : 1f;
        }
        int glyphBox = Math.round(inner * glyphScale);
        iconWrap.addView(glyph, new FrameLayout.LayoutParams(glyphBox, glyphBox, Gravity.CENTER));
        addView(iconWrap, new LayoutParams(outer, outer));

        if (showLabel) {
            label = new TextView(ctx);
            label.setText(text);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            label.setTextColor(0xFFF0F0F0);
            if (tf != null) label.setTypeface(tf);
            try { label.setFontVariationSettings("'wght' 600"); } catch (Throwable ignored) {}
            label.setIncludeFontPadding(false);
            label.setAlpha(0f);
            LayoutParams llp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT);
            llp.topMargin = Math.round(6 * density);
            llp.gravity = Gravity.CENTER_HORIZONTAL;
            addView(label, llp);
        } else {
            label = null;
        }

        setOnFocusChangeListener((v, has) -> {
            iconWrap.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f)
                    .setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
            if (label != null) label.animate().alpha(has ? 1f : 0f).setDuration(140).start();
            if (has && onFocusGained != null) onFocusGained.run();
        });
    }

    /** Swaps the hand-drawn glyph (the media row's play/pause button). No
     *  effect on a button built from a drawable resource. */
    void setIcon(int kind) {
        if (icon != null) icon.setKind(kind);
    }

    /** Changes the label that fades in on focus ("Play" / "Pause"). */
    void setLabel(String text) {
        if (label != null) label.setText(text);
    }

    /** Light or dark, to match whatever theme Home's overlay is currently
     *  using - see HomeActivity.applyTextTheme(). */
    void setTheme(boolean dark) {
        iconWrap.setBackgroundResource(dark ? R.drawable.pill_bg : R.drawable.pill_bg_dark);
        int color = dark ? 0xFF1A1A1A : 0xFFF0F0F0;
        if (icon != null) icon.setTint(color);
        if (image != null) image.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (label != null) label.setTextColor(color);
    }
}
