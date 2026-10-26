package com.oxylab.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Long-press menu on a grid tile: pin the app to the dock, or jump to the
 * system App info screen. App info is deliberately not reimplemented here
 * - uninstall, force stop, clear data and permissions all already live
 * there, and that's also the launcher's answer to "a way to uninstall
 * apps" rather than a separate uninstall action.
 *
 * There is no unpin: the dock always stays full (HomeActivity.fillDock),
 * and the only way to change what's in it is to pin something not
 * already there into a chosen slot. So Pin to Tray is disabled, not
 * hidden, for an app that's already pinned - it stays visible as a
 * reminder of why, rather than the option just disappearing.
 */
final class AppContextMenu {

    static void show(final Activity act, View anchor, final AppEntry entry,
                      Typeface tf, boolean alreadyPinned, final Runnable onPin) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.popup_bg);
        int p = dp(act, 8);
        box.setPadding(p, p, p, p);

        final PopupWindow pop = new PopupWindow(box,
                dp(act, 220), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setOutsideTouchable(true);

        TextView pin = row(act, tf, alreadyPinned ? "Pin to Tray (already pinned)" : "Pin to Tray");
        if (alreadyPinned) {
            pin.setTextColor(0xFF6B6B6B);
            pin.setFocusable(false);
            pin.setClickable(false);
        } else {
            pin.setOnClickListener(v -> { pop.dismiss(); onPin.run(); });
        }
        box.addView(pin, rowLp());

        TextView info = row(act, tf, "App info");
        info.setOnClickListener(v -> {
            pop.dismiss();
            try {
                act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + entry.pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                Toast.makeText(act, "Could not open app info", Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(info, rowLp());

        pop.setElevation(dp(act, 12));
        pop.showAsDropDown(anchor, 0, dp(act, 10), Gravity.START);
        box.getChildAt(alreadyPinned ? 1 : 0).requestFocus();
    }

    private static TextView row(Activity act, Typeface tf, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextColor(0xFFEDEDED);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        if (tf != null) tv.setTypeface(tf);
        tv.setBackgroundResource(R.drawable.popup_item_bg);
        tv.setFocusable(true);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        int ph = dp(act, 14), pv = dp(act, 10);
        tv.setPadding(ph, pv, ph, pv);
        return tv;
    }

    private static LinearLayout.LayoutParams rowLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Activity act, int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private AppContextMenu() {}
}
