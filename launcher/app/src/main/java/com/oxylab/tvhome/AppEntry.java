package com.oxylab.tvhome;

import android.content.Intent;
import android.graphics.drawable.Drawable;

/** One launchable app: its artwork, its name, and how to start it. */
class AppEntry {
    final String pkg;
    final String label;
    final Drawable art;
    final Intent launch;
    /** true when the art is a 16:9 leanback banner, false for a square icon. */
    final boolean wide;
    /** Dominant colour sampled from the art, used for the ambient glow. */
    int accent = 0xFF8899AA;

    AppEntry(String pkg, String label, Drawable art, Intent launch, boolean wide) {
        this.pkg = pkg;
        this.label = label;
        this.art = art;
        this.launch = launch;
        this.wide = wide;
    }
}
