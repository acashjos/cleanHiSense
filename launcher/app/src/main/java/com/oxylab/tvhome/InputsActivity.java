package com.oxylab.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * A bare host so a remote key mapped to "inputs" can show the same glass
 * dropdown the Inputs pill shows, from anywhere rather than only on Home.
 */
public class InputsActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        final View anchor = new View(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1, 1);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = Math.round(90 * getResources().getDisplayMetrics().density);
        lp.rightMargin = Math.round(120 * getResources().getDisplayMetrics().density);
        root.addView(anchor, lp);
        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.graphics.Typeface tf = null;
        try { tf = getResources().getFont(R.font.outfit); } catch (Throwable ignored) {}
        final android.graphics.Typeface font = tf;
        anchor.post(() -> InputsPopup.show(this, anchor, font));
    }
}
