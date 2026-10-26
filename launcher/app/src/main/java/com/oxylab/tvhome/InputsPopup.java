package com.oxylab.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The compact glass dropdown under the Inputs pill: the HDMI ports, AV and
 * Live TV, switched through the TV input framework.
 */
final class InputsPopup {

    private static class Item {
        final String label;
        final Runnable action;
        /** HDMI ports first (in port order), then AV, then Live TV last. */
        final int sortKey;
        Item(String label, int sortKey, Runnable action) {
            this.label = label; this.sortKey = sortKey; this.action = action;
        }
    }

    /** The framework reports inputs in arbitrary order; make it predictable. */
    private static int sortKeyFor(String label) {
        String u = label.toUpperCase(java.util.Locale.ROOT);
        if (u.startsWith("HDMI")) {
            int n = 0;
            for (int i = 0; i < u.length(); i++) {
                char c = u.charAt(i);
                if (c >= '0' && c <= '9') n = n * 10 + (c - '0');
            }
            return 100 + n;
        }
        return 500;   // AV, composite, component and anything else
    }

    static void show(final Activity act, View anchor, Typeface tf) {
        final List<Item> items = buildItems(act);
        if (items.isEmpty()) {
            Toast.makeText(act, "No TV inputs reported by this TV", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.popup_bg);
        int p = dp(act, 8);
        box.setPadding(p, p, p, p);

        final PopupWindow pop = new PopupWindow(box,
                dp(act, 230), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setOutsideTouchable(true);

        for (final Item it : items) {
            TextView tv = new TextView(act);
            tv.setText(it.label);
            tv.setTextColor(0xFFEDEDED);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            if (tf != null) tv.setTypeface(tf);
            tv.setBackgroundResource(R.drawable.popup_item_bg);
            tv.setFocusable(true);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            int ph = dp(act, 14), pv = dp(act, 10);
            tv.setPadding(ph, pv, ph, pv);
            tv.setOnClickListener(v -> { pop.dismiss(); it.action.run(); });
            box.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        pop.setElevation(dp(act, 12));

        // The pill now lives on the tray, near the bottom of the screen, so
        // a drop-down would run straight off it. Open the list to the left
        // of the pill instead, bottom-aligned with it, and clamp to the
        // screen so an unusually long input list still stays visible.
        int popW = dp(act, 230);
        int gap = dp(act, 10);
        box.measure(View.MeasureSpec.makeMeasureSpec(popW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int popH = box.getMeasuredHeight();

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = loc[0] - gap - popW;
        int y = loc[1] + anchor.getHeight() - gap - popH;

        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        if (x < gap) x = gap;
        if (x + popW > screenW - gap) x = screenW - gap - popW;
        if (y < gap) y = gap;
        if (y + popH > screenH - gap) y = screenH - gap - popH;

        pop.showAtLocation(anchor, Gravity.TOP | Gravity.START, x, y);
        // Post the focus: the popup's content is not laid out yet at this
        // point, so an immediate requestFocus() is ignored and the window
        // falls back to focusing its last item.
        box.post(() -> box.getChildAt(0).requestFocus());
    }

    private static List<Item> buildItems(final Activity act) {
        List<Item> items = new ArrayList<>();
        TvInputManager tim = null;
        try {
            tim = (TvInputManager) act.getSystemService(Context.TV_INPUT_SERVICE);
        } catch (Throwable ignored) {}

        if (tim != null) {
            List<TvInputInfo> inputs;
            try { inputs = tim.getTvInputList(); } catch (Throwable t) { inputs = null; }
            if (inputs != null) {
                for (final TvInputInfo info : inputs) {
                    int type = info.getType();
                    // Only physical sources belong in this menu.
                    if (type == TvInputInfo.TYPE_TUNER) continue;
                    if (!info.isPassthroughInput()) continue;
                    CharSequence cs = null;
                    try { cs = info.loadCustomLabel(act); } catch (Throwable ignored) {}
                    if (cs == null) {
                        try { cs = info.loadLabel(act); } catch (Throwable ignored) {}
                    }
                    final String id = info.getId();
                    String label = cs == null ? id : cs.toString();
                    items.add(new Item(label, sortKeyFor(label), () -> {
                        try {
                            act.startActivity(new Intent(Intent.ACTION_VIEW,
                                    TvContract.buildChannelUriForPassthroughInput(id))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Throwable t) {
                            Toast.makeText(act, "Could not switch to " + id,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }));
                }
            }
        }

        java.util.Collections.sort(items, (a, b) -> Integer.compare(a.sortKey, b.sortKey));

        // Live TV / the tuner, as its own entry at the end.
        final Intent live = liveTvIntent(act);
        if (live != null) {
            items.add(new Item("Live TV", 900, () -> {
                try { act.startActivity(live); } catch (Throwable ignored) {}
            }));
        }
        return items;
    }

    private static Intent liveTvIntent(Activity act) {
        Intent i = act.getPackageManager()
                .getLeanbackLaunchIntentForPackage("com.vt.tv.tvcenter");
        if (i == null) {
            i = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
            if (i.resolveActivity(act.getPackageManager()) == null) return null;
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private InputsPopup() {}
}
