package com.oxylab.tvhome;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;

/**
 * "Button mapping" in the quick-settings panel: pick which app each
 * branded button on the remote launches.
 *
 * Two levels, both plain vertical lists so the D-pad has nowhere
 * surprising to go: the buttons, then the apps.
 */
public class RemoteKeysActivity extends Activity {

    private float density;
    private Typeface outfit;
    private LinearLayout column;
    private TextView title;
    private TextView description;
    private ScrollView scroller;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        try { outfit = getResources().getFont(R.font.outfit); } catch (Throwable ignored) {}

        FrameLayout root = new FrameLayout(this);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundResource(R.drawable.panel_bg);
        shell.setPadding(dp(20), dp(24), dp(20), dp(20));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                dp(400), ViewGroup.LayoutParams.MATCH_PARENT);
        clp.gravity = Gravity.END;
        root.addView(shell, clp);

        title = new TextView(this);
        style(title, 22f, 0xFFFFFFFF, 300);
        title.setPadding(dp(6), 0, 0, dp(6));
        shell.addView(title);

        description = new TextView(this);
        style(description, 13f, 0x99FFFFFF, 400);
        description.setPadding(dp(6), 0, 0, dp(16));
        shell.addView(description);

        scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        shell.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        showButtons();
    }

    // ------------------------------------------------- level 1: buttons

    private void showButtons() {
        title.setText("Button mapping");
        description.setText("Choose which app each branded button on the remote opens.");
        description.setVisibility(View.VISIBLE);
        column.removeAllViews();
        List<KeyMap.Button> buttons = KeyMap.buttons(this);
        if (buttons.isEmpty()) {
            addRow("No remote buttons configured", null, null);
            return;
        }
        for (final KeyMap.Button b : buttons) {
            String pkg = KeyMap.target(this, b.keyCode);
            addRow(b.label, pkg == null ? "Does nothing" : labelOf(pkg),
                   v -> showApps(b));
        }
        column.post(this::focusFirst);
    }

    // ---------------------------------------------------- level 2: apps

    private void showApps(final KeyMap.Button button) {
        title.setText(button.label + " button");
        description.setVisibility(View.GONE);
        column.removeAllViews();
        scroller.scrollTo(0, 0);

        addRow("Do nothing", null, v -> {
            KeyMap.setTarget(this, button.keyCode, null);
            showButtons();
        });

        for (final AppEntry e : AppRepo.load(this, new HashSet<>())) {
            addRow(e.label, null, v -> {
                KeyMap.setTarget(this, button.keyCode, e.pkg);
                showButtons();
            });
        }
        column.post(this::focusFirst);
    }

    // --------------------------------------------------------- plumbing

    private String labelOf(String pkg) {
        try {
            return getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
                    .toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private void addRow(String text, String subtitle, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.panel_item_bg);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        if (onClick != null) {
            row.setFocusable(true);
            row.setOnClickListener(onClick);
        }

        TextView t = new TextView(this);
        t.setText(text);
        style(t, 15f, 0xFFF0F0F0, 500);
        row.addView(t);

        if (subtitle != null) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            style(s, 12f, 0x8CFFFFFF, 400);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(3);
            row.addView(s, slp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(7);
        column.addView(row, lp);
    }

    private void focusFirst() {
        for (int i = 0; i < column.getChildCount(); i++) {
            View c = column.getChildAt(i);
            if (c.isFocusable()) { c.requestFocus(); return; }
        }
    }

    @Override
    public void onBackPressed() {
        // Back steps out of the app list to the button list first.
        if (!"Button mapping".contentEquals(title.getText())) {
            showButtons();
            return;
        }
        super.onBackPressed();
    }

    private void style(TextView t, float sp, int color, int weight) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (outfit != null) t.setTypeface(outfit);
        try { t.setFontVariationSettings("'wght' " + weight); } catch (Throwable ignored) {}
        t.setIncludeFontPadding(false);
    }

    private int dp(int v) { return Math.round(v * density); }
}
