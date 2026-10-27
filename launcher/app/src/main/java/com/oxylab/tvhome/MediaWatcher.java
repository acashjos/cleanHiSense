package com.oxylab.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.util.List;

/**
 * Watches the system's active media sessions and drives Home's media row.
 *
 * This needs no new permission: an app whose notification listener is
 * enabled (TvNotificationListener, already granted) is allowed to call
 * MediaSessionManager.getActiveSessions() with that listener's component.
 * Everything runs in this process, so the callback is delivered on the
 * main thread.
 */
class MediaWatcher {

    interface Listener {
        /** visible=false means "no music app in the background" - the row
         *  should be hidden entirely. playing is true only for a session
         *  actually in STATE_PLAYING. appLabel is the app's display name. */
        void onMedia(boolean visible, boolean playing, String appLabel);
    }

    private final Context ctx;
    private final MediaSessionManager msm;
    private final ComponentName component;
    private MediaController controller;
    private MediaController.Callback callback;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private Listener listener;
    private boolean started;

    MediaWatcher(Context c) {
        ctx = c.getApplicationContext();
        msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        component = new ComponentName(ctx, TvNotificationListener.class);
    }

    void start(Listener l) {
        listener = l;
        if (started) { refresh(); return; }
        started = true;
        sessionsListener = this::onSessionsChanged;
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component);
        } catch (Throwable ignored) {}
        refresh();
    }

    void stop() {
        if (!started) return;
        started = false;
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsListener);
        } catch (Throwable ignored) {}
        sessionsListener = null;
        detach();
        listener = null;
    }

    /** Re-reads the current sessions; called whenever Home comes back to
     *  the front, in case something started while we were away. */
    void refresh() {
        List<MediaController> sessions = null;
        try { sessions = msm.getActiveSessions(component); } catch (Throwable ignored) {}
        onSessionsChanged(sessions);
    }

    boolean hasSession() { return controller != null; }

    boolean isPlaying() {
        return controller != null && state(controller) == PlaybackState.STATE_PLAYING;
    }

    String appLabel() {
        return controller == null ? "" : AppRepo.label(ctx, controller.getPackageName());
    }

    void previous() {
        if (controller == null) return;
        try { controller.getTransportControls().skipToPrevious(); } catch (Throwable ignored) {}
    }

    void next() {
        if (controller == null) return;
        try { controller.getTransportControls().skipToNext(); } catch (Throwable ignored) {}
    }

    /** Play when paused/stopped, pause when playing - the button's job. */
    void toggle() {
        if (controller == null) return;
        try {
            if (isPlaying()) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        } catch (Throwable ignored) {}
    }

    // --------------------------------------------------------- internals

    private static int state(MediaController c) {
        PlaybackState ps = c.getPlaybackState();
        return ps == null ? PlaybackState.STATE_NONE : ps.getState();
    }

    /** Picks the session the row should follow. A session that is actually
     *  playing wins over a paused one; NONE and ERROR (e.g. a disconnected
     *  Bluetooth route) are ignored so they never conjure up the row. */
    private void onSessionsChanged(List<MediaController> sessions) {
        MediaController best = null;
        if (sessions != null) {
            for (MediaController c : sessions) {
                if (c.getPackageName().equals(ctx.getPackageName())) continue;
                int st = state(c);
                if (st == PlaybackState.STATE_NONE || st == PlaybackState.STATE_ERROR) continue;
                if (best == null
                        || (state(best) != PlaybackState.STATE_PLAYING
                            && st == PlaybackState.STATE_PLAYING)) {
                    best = c;
                }
            }
        }
        if (best != controller) {
            detach();
            controller = best;
            attach();
        }
        notifyListener();
    }

    private void attach() {
        if (controller == null) return;
        callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState s) { notifyListener(); }
            @Override public void onMetadataChanged(MediaMetadata m) { notifyListener(); }
            @Override public void onSessionDestroyed() { refresh(); }
        };
        try { controller.registerCallback(callback); } catch (Throwable ignored) {}
    }

    private void detach() {
        if (controller != null && callback != null) {
            try { controller.unregisterCallback(callback); } catch (Throwable ignored) {}
        }
        callback = null;
    }

    private void notifyListener() {
        if (listener != null) listener.onMedia(controller != null, isPlaying(), appLabel());
    }
}
