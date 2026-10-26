package com.oxylab.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replacement for the Google TV quick-settings panel.
 *
 * The original lives inside the stock launcher
 * (launcherx/.dashboard.DashboardHandler) and cannot be reached once that
 * launcher is disabled - it is disabled because it steals the HOME key
 * back the moment it is enabled, and disabling only its HomeActivity is
 * refused with a SecurityException. So this is a rebuild of the idea, in
 * the Android/Apple-TV "quick settings" shape: icon tiles rather than a
 * plain list, a volume slider, and a status card.
 *
 * Wi-Fi is a status + deep-link tile, not a hard toggle:
 * WifiManager.setWifiEnabled() always returns false for a non-privileged
 * app since Android 10, and this remote very likely pairs over Bluetooth
 * Low Energy (autopair is in the kept-enabled list for exactly that
 * reason) - flipping the radio off from here could strand the remote with
 * no way to turn it back on. Tapping Wi-Fi opens the real settings screen
 * instead, where the OS's own toggle is used deliberately. Bluetooth is
 * read-only info (see addStatusRow()): its only deep link on this
 * firmware is general Settings, which "All settings" already covers, so
 * it isn't clickable.
 */
public class QuickPanelActivity extends Activity {

    private static final int REQ_BT = 41;

    private float density;
    private Typeface outfit;
    private AudioManager audio;

    private TextView volValue;
    private View volFill;
    private View volTrack;
    private LinearLayout column;
    private LinearLayout statusRow;
    private LinearLayout notifSection;
    private TextView wifiSub;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try { outfit = getResources().getFont(R.font.outfit); } catch (Throwable ignored) {}

        FrameLayout root = new FrameLayout(this);

        // Full height, flush to the screen edges: no outer margin, no
        // rounded corners - an edge-to-edge sidebar rather than a floating
        // card. Padding is back inside it so content doesn't touch the
        // screen edge.
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundResource(R.drawable.panel_bg);
        shell.setPadding(dp(20), dp(24), dp(20), dp(20));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                dp(400), ViewGroup.LayoutParams.MATCH_PARENT);
        clp.gravity = Gravity.END;
        root.addView(shell, clp);

        TextView title = new TextView(this);
        title.setText("Quick settings");
        style(title, 22f, 0xFFFFFFFF, 300);
        title.setPadding(0, 0, 0, dp(16));
        shell.addView(title);

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        shell.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addStatusRow(body);
        addVolumeRow(body);
        addGrid(body);
        addNotificationsSection(body);

        setContentView(root);
        body.post(this::focusFirst);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshVolume();
        refreshStatus();
        refreshNotifications();
    }

    // ------------------------------------------------------------ volume

    private void addVolumeRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.panel_item_bg);
        row.setFocusable(true);
        row.setPadding(dp(14), dp(12), dp(14), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(icon(IconView.SOUND), new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView label = new TextView(this);
        label.setText("Volume");
        style(label, 15f, 0xFFF0F0F0, 500);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        llp.leftMargin = dp(10);
        head.addView(label, llp);
        volValue = new TextView(this);
        style(volValue, 15f, 0xB3FFFFFF, 400);
        head.addView(volValue);
        row.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout bar = new FrameLayout(this);
        volTrack = new View(this);
        volTrack.setBackgroundResource(R.drawable.vol_track);
        bar.addView(volTrack, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        volFill = new View(this);
        volFill.setBackgroundResource(R.drawable.vol_fill);
        bar.addView(volFill, new FrameLayout.LayoutParams(0, dp(6)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        blp.topMargin = dp(12);
        row.addView(bar, blp);

        row.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { step(AudioManager.ADJUST_LOWER); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { step(AudioManager.ADJUST_RAISE); return true; }
            return false;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        parent.addView(row, lp);
    }

    private void step(int direction) {
        try { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0); } catch (Throwable ignored) {}
        refreshVolume();
    }

    private void refreshVolume() {
        try {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            volValue.setText(String.valueOf(cur));
            int w = volTrack.getWidth();
            if (w <= 0) { volTrack.post(this::refreshVolume); return; }
            ViewGroup.LayoutParams lp = volFill.getLayoutParams();
            lp.width = max <= 0 ? 0 : Math.round(w * (cur / (float) max));
            volFill.setLayoutParams(lp);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------- status row

    /**
     * Ethernet/Wi-Fi, Bluetooth and All settings, sized to content rather
     * than stretched, sharing one row at the very top of the panel.
     *
     * Ethernet/Wi-Fi and Bluetooth are plain info pills with no background
     * fill - they're status, not primary actions, and neither is
     * clickable: Bluetooth has no exported deep link on this firmware
     * (general Settings is the closest an app can get, and "All settings"
     * right next to it already covers that - a second control that does
     * the same thing is redundant). All settings keeps its normal
     * filled-button look since it is the one real action in the row.
     */
    private void addStatusRow(LinearLayout parent) {
        statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        parent.addView(statusRow, lp);
    }

    private void refreshStatus() {
        statusRow.removeAllViews();
        NetInfo net = NetInfo.read(this);

        LinearLayout netPill = infoPill(
                net.connected ? net.name : "Offline",
                net.ip != null ? net.ip : (net.ethernet ? "Ethernet" : "No IP address"),
                net.ethernet ? IconView.INPUT : IconView.NETWORK);

        // Just a status read, not a click target - request this each time
        // rather than from a tap, since tapping no longer does anything.
        ensureBtPermission();
        Boolean bt = NetInfo.bluetoothEnabled(this);
        LinearLayout btPill = infoPill("Bluetooth",
                bt == null ? "Unknown" : (bt ? "On" : "Off"),
                IconView.BLUETOOTH);

        LinearLayout allSettings = actionPill("All settings", IconView.SETTINGS,
                v -> openOwnOrApp(new Intent(Settings.ACTION_SETTINGS)));

        // Ethernet/Wi-Fi and Bluetooth stay grouped on the left, sized to
        // their own content; that group takes a weighted share of the row
        // so whatever space is left pushes All settings flush to the end.
        LinearLayout infoGroup = new LinearLayout(this);
        infoGroup.setOrientation(LinearLayout.HORIZONTAL);
        infoGroup.setGravity(Gravity.CENTER_VERTICAL);
        infoGroup.addView(netPill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams btlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btlp.leftMargin = dp(12);
        infoGroup.addView(btPill, btlp);

        statusRow.addView(infoGroup, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusRow.addView(allSettings, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (wifiSub != null) {
            wifiSub.setText(NetInfo.wifiEnabled(this) ? "On" : "Off");
        }
    }

    /**
     * A borderless, non-focusable status pill: small icon, one line of
     * text, plus an optional second, dimmer line (the IP address, for
     * Ethernet/Wi-Fi). Pure display - neither Ethernet/Wi-Fi nor
     * Bluetooth is clickable, so there is no background or focus state to
     * draw here.
     */
    private LinearLayout infoPill(String text, String subtitle, int iconKind) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        row.addView(icon(iconKind), new LinearLayout.LayoutParams(dp(15), dp(15)));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(text);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setMaxWidth(dp(108));
        style(name, 13f, 0xFFE8E8E8, 500);
        // Explicit WRAP_CONTENT: addView(View) alone would fall back to
        // LinearLayout's own default for a VERTICAL parent, which is
        // MATCH_PARENT - that stretched/shrank "name" to match whichever
        // of the two lines was narrower (e.g. "On"), truncating
        // "Bluetooth" down to an ellipsis even with plenty of room.
        textCol.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (subtitle != null) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setMaxLines(1);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setMaxWidth(dp(108));
            style(sub, 11f, 0x73FFFFFF, 400);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(1);
            textCol.addView(sub, slp);
        }

        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = dp(6);
        row.addView(textCol, nlp);
        return row;
    }

    // -------------------------------------------------------------- grid

    private void addGrid(LinearLayout parent) {
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        parent.addView(column, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addGridRow(
            tile("Wi-Fi", IconView.WIFI, this::wifiSubtitleHolder,
                 v -> openOwnOrApp(new Intent("android.settings.WIFI_SETTINGS"))),
            tile("Inputs", IconView.INPUT, null,
                 v -> { startActivity(new Intent(this, InputsActivity.class)); finish(); })
        );
        addGridRow(
            tile("Picture", IconView.PICTURE, null,
                 v -> openOwnOrApp(new Intent("android.settings.DISPLAY_SETTINGS"))),
            tile("Sound", IconView.SOUND, null,
                 v -> openOwnOrApp(new Intent("android.settings.SOUND_SETTINGS")))
        );
        addGridRow(
            tile("Button mapping", IconView.APPS, null,
                 v -> { startActivity(new Intent(this, RemoteKeysActivity.class)); finish(); }),
            tile("Accessibility", IconView.ACCESSIBILITY, null,
                 v -> openOwnOrApp(new Intent("android.settings.ACCESSIBILITY_SETTINGS")))
        );

        // Wallpaper is a standard half-width grid pill like the six above,
        // not a full-width row; the right half of its row stays empty.
        addGridRow(
            tile("Wallpaper", IconView.WALLPAPER, null,
                 v -> { startActivity(new Intent(this, WallpaperActivity.class)); finish(); }),
            new LinearLayout(this)
        );
    }

    // ----------------------------------------------------- notifications

    private void addNotificationsSection(LinearLayout parent) {
        notifSection = new LinearLayout(this);
        notifSection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        parent.addView(notifSection, lp);
    }

    private void refreshNotifications() {
        notifSection.removeAllViews();

        boolean enabled = TvNotificationListener.isEnabled(this);
        if (!enabled) {
            notifSection.addView(notificationsHeader(false), headerLp());
            notifSection.addView(fullRow("Grant notification access", IconView.NOTIFICATIONS,
                    v -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Throwable ignored) {}
                    }));
            return;
        }

        // Split on FLAG_ONGOING_EVENT: that is exactly what separates a
        // real, dismissable notification from a foreground service just
        // reporting that it is running (Stremio's server, DLNA, the
        // AirPlay daemon, ...) - those can't be cleared and the content
        // is rarely interesting, just which app is doing it.
        StatusBarNotification[] all = TvNotificationListener.current();
        List<StatusBarNotification> dismissable = new ArrayList<>();
        List<StatusBarNotification> ongoing = new ArrayList<>();
        if (all != null) {
            for (StatusBarNotification sbn : all) {
                boolean isOngoing = (sbn.getNotification().flags
                        & android.app.Notification.FLAG_ONGOING_EVENT) != 0;
                (isOngoing ? ongoing : dismissable).add(sbn);
            }
        }

        // No nested scroller anywhere here: the whole panel already
        // scrolls as one unit (see the outer ScrollView in onCreate), so
        // however long either list is, it scrolls with everything else
        // rather than in its own separate bounded viewport.

        notifSection.addView(notificationsHeader(!dismissable.isEmpty()), headerLp());
        if (dismissable.isEmpty()) {
            notifSection.addView(emptyText("No notifications"));
        } else {
            LinearLayout items = new LinearLayout(this);
            items.setOrientation(LinearLayout.VERTICAL);
            for (StatusBarNotification sbn : dismissable) items.addView(notificationRow(sbn));
            notifSection.addView(items, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout.LayoutParams btlp = headerLp();
        btlp.topMargin = dp(16);
        notifSection.addView(plainSectionHeader("BACKGROUND TASKS"), btlp);
        if (ongoing.isEmpty()) {
            notifSection.addView(emptyText("No background tasks"));
        } else {
            // Grouped by app: several notifications from the same package
            // (e.g. a service that re-posts) collapse into one row with a
            // count, rather than repeating the app name.
            LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
            for (StatusBarNotification sbn : ongoing) {
                String pkg = sbn.getPackageName();
                Integer c = counts.get(pkg);
                counts.put(pkg, c == null ? 1 : c + 1);
            }
            LinearLayout items = new LinearLayout(this);
            items.setOrientation(LinearLayout.VERTICAL);
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                items.addView(backgroundTaskRow(appLabel(e.getKey()), e.getValue()));
            }
            notifSection.addView(items, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private TextView emptyText(String text) {
        TextView empty = new TextView(this);
        empty.setText(text);
        style(empty, 13f, 0x8CFFFFFF, 400);
        empty.setPadding(dp(2), dp(8), 0, dp(14));
        return empty;
    }

    /** A plain section label with no clear action, e.g. "BACKGROUND TASKS". */
    private TextView plainSectionHeader(String title) {
        TextView header = new TextView(this);
        header.setText(title);
        style(header, 11f, 0x73FFFFFF, 600);
        header.setLetterSpacing(0.08f);
        return header;
    }

    /** One background task: just the app name, plus a count badge if more
     *  than one of its notifications were grouped together. Not
     *  interactive - this is status, not a list of things to open. */
    private TextView backgroundTaskRow(String appName, int count) {
        TextView row = new TextView(this);
        row.setText(count > 1 ? appName + "   " + count : appName);
        row.setMaxLines(1);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        style(row, 13f, 0xB3FFFFFF, 400);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private LinearLayout.LayoutParams headerLp() {
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(8);
        return hlp;
    }

    /** The "NOTIFICATIONS" label, plus a small "x" to clear everything
     *  when there is something to clear. */
    private LinearLayout notificationsHeader(boolean hasItems) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView header = new TextView(this);
        header.setText("NOTIFICATIONS");
        style(header, 11f, 0x73FFFFFF, 600);
        header.setLetterSpacing(0.08f);
        row.addView(header, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (hasItems) {
            TextView clear = new TextView(this);
            clear.setText("✕");
            clear.setContentDescription("Clear all notifications");
            clear.setGravity(Gravity.CENTER);
            style(clear, 13f, 0xB3FFFFFF, 600);
            clear.setBackgroundResource(R.drawable.popup_item_bg);
            clear.setFocusable(true);
            clear.setOnClickListener(v -> {
                TvNotificationListener.clearAll();
                refreshNotifications();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(28), dp(28));
            row.addView(clear, clp);
        }
        return row;
    }

    private LinearLayout notificationRow(StatusBarNotification sbn) {
        CharSequence title2 = null, text = null;
        try {
            Bundle extras = sbn.getNotification().extras;
            title2 = extras.getCharSequence(android.app.Notification.EXTRA_TITLE);
            text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT);
        } catch (Throwable ignored) {}
        String appName = appLabel(sbn.getPackageName());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.panel_item_bg);
        row.setFocusable(true);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setOnClickListener(v -> openNotification(sbn));

        TextView t = new TextView(this);
        t.setText(title2 != null ? title2.toString() : appName);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        style(t, 13f, 0xFFF0F0F0, 500);
        row.addView(t);

        TextView s2 = new TextView(this);
        s2.setText(text != null ? text.toString() : appName);
        s2.setMaxLines(2);
        s2.setEllipsize(android.text.TextUtils.TruncateAt.END);
        style(s2, 11f, 0x8CFFFFFF, 400);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        row.addView(s2, slp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * Tapping a notification fires its own contentIntent first - the
     * PendingIntent the posting app attached, which on a well-behaved app
     * deep-links to whatever screen the notification is about, not just
     * the app's front door. Only if that's missing or fails do we fall
     * back to launching the app generically. A notification posted with
     * FLAG_AUTO_CANCEL is then dismissed, matching what tapping it in the
     * real notification shade does.
     */
    private void openNotification(StatusBarNotification sbn) {
        android.app.Notification n = sbn.getNotification();
        boolean launched = false;
        if (n.contentIntent != null) {
            try { n.contentIntent.send(); launched = true; } catch (Throwable ignored) {}
        }
        if (!launched) {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(sbn.getPackageName());
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(sbn.getPackageName());
            if (i != null) {
                try {
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    launched = true;
                } catch (Throwable ignored) {}
            }
        }
        if (!launched) {
            Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show();
            return;
        }
        if ((n.flags & android.app.Notification.FLAG_AUTO_CANCEL) != 0) {
            TvNotificationListener.dismiss(sbn.getKey());
        }
        finish();
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
                    .toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    // So refreshStatus-time subtitle updates can find the right TextView
    // without threading extra state through the tile() factory.
    private void wifiSubtitleHolder(TextView t) { wifiSub = t; }

    private void addGridRow(LinearLayout a, LinearLayout b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        alp.rightMargin = dp(5);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = dp(5);
        row.addView(a, alp);
        row.addView(b, blp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(10);
        column.addView(row, rlp);
    }

    private interface SubtitleSink { void accept(TextView t); }

    private LinearLayout tile(String label, int iconKind, SubtitleSink sink,
                               View.OnClickListener onClick) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setBackgroundResource(R.drawable.panel_item_bg);
        t.setFocusable(true);
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        t.addView(icon(iconKind), new LinearLayout.LayoutParams(dp(20), dp(20)));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(label);
        style(name, 13f, 0xFFF0F0F0, 500);
        textCol.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Always reserve the subtitle line, even blank, so a tile with a
        // status line (Wi-Fi) is the same height as one without (Inputs)
        // and the two don't mismatch within a grid row.
        TextView sub = new TextView(this);
        style(sub, 11f, 0x73FFFFFF, 400);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        textCol.addView(sub, slp);
        if (sink != null) sink.accept(sub); else sub.setText(" ");

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(12);
        t.addView(textCol, tlp);

        t.setOnClickListener(onClick);
        return t;
    }

    /** A full-width row: icon, label and nothing else ("All settings"). */
    private LinearLayout fullRow(String label, int iconKind, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.panel_item_bg);
        row.setFocusable(true);
        row.setPadding(dp(14), dp(13), dp(14), dp(13));
        row.addView(icon(iconKind), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView name = new TextView(this);
        name.setText(label);
        style(name, 14f, 0xFFF0F0F0, 500);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = dp(12);
        row.addView(name, nlp);
        row.setOnClickListener(onClick);
        return row;
    }

    /**
     * A compact filled button: smaller icon and padding than fullRow, sized
     * to content. Used for "All settings" when it shares the top status
     * row with the info pills instead of sitting on its own full-width row.
     */
    private LinearLayout actionPill(String label, int iconKind, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.panel_item_bg);
        row.setFocusable(true);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.addView(icon(iconKind), new LinearLayout.LayoutParams(dp(15), dp(15)));
        TextView name = new TextView(this);
        name.setText(label);
        name.setMaxLines(1);
        style(name, 13f, 0xFFF0F0F0, 500);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = dp(6);
        row.addView(name, nlp);
        row.setOnClickListener(onClick);
        return row;
    }

    // ------------------------------------------------------------- misc

    private View icon(int kind) {
        IconView v = new IconView(this, kind);
        return v;
    }

    private void ensureBtPermission() {
        if (Build.VERSION.SDK_INT < 31) return;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.BLUETOOTH_CONNECT }, REQ_BT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_BT) refreshStatus();
    }

    /** Our own screens get NEW_TASK only - RESET_TASK_IF_NEEDED here makes
     *  a panel screen start and immediately vanish against the Home task.
     *  Another app's settings screen gets the full flag set. */
    private void openOwnOrApp(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        } catch (Throwable t) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    private void focusFirst() {
        View v = findFocusableDescendant(column);
        if (v != null) v.requestFocus();
    }

    private View findFocusableDescendant(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.isFocusable()) return c;
            if (c instanceof ViewGroup) {
                View f = findFocusableDescendant((ViewGroup) c);
                if (f != null) return f;
            }
        }
        return null;
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
