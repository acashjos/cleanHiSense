package com.oxylab.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/**
 * Handles the remote's gear button.
 *
 * SystemUI (TvNotificationPanel) answers KEYCODE_NOTIFICATION by launching
 * an activity for ACTION_TOGGLE_NOTIFICATION_HANDLER_PANEL.  The stock
 * Google TV launcher used to own that intent, so once it is disabled the
 * button dies with:
 *
 *   E TvNotificationPanel: Not launching notification handler activity:
 *   Could not resolve activityInfo for intent
 *   android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL
 *
 * Claiming it here brings the button back everywhere, not just on Home,
 * and without an accessibility key-filter service (which on this class of
 * firmware can swallow key repeats and break volume ramping).
 */
public class NotificationPanelActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {}
        finish();
    }
}
