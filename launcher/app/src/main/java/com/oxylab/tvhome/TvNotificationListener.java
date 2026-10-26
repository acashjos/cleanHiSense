package com.oxylab.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Feeds the notifications section of QuickPanelActivity. Kept to a static
 * instance rather than a bound service, since both run in this app's own
 * process - there is nothing cross-process to wire up.
 */
public class TvNotificationListener extends NotificationListenerService {

    private static volatile TvNotificationListener instance;

    static StatusBarNotification[] current() {
        TvNotificationListener i = instance;
        if (i == null) return null;
        try { return i.getActiveNotifications(); } catch (Throwable t) { return null; }
    }

    /** Dismisses every notification this listener can currently see. */
    static void clearAll() {
        TvNotificationListener i = instance;
        if (i == null) return;
        try { i.cancelAllNotifications(); } catch (Throwable ignored) {}
    }

    /** Dismisses one notification by key, mirroring what tapping it in the
     *  real notification shade does for one that auto-cancels. */
    static void dismiss(String key) {
        TvNotificationListener i = instance;
        if (i == null || key == null) return;
        try { i.cancelNotification(key); } catch (Throwable ignored) {}
    }

    /** Whether the user has granted this app notification-listener access. */
    static boolean isEnabled(Context ctx) {
        String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        ComponentName me = new ComponentName(ctx, TvNotificationListener.class);
        return flat != null && flat.contains(me.flattenToString());
    }

    @Override public void onListenerConnected() { instance = this; }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) { }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { }
}
