package com.oxylab.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Brings back the remote's app buttons and gear button.
 *
 * Those keys never reach a normal app on this firmware: the window
 * manager's policy consumes them before dispatch (verified - only DPAD
 * keys arrived at the launcher window). An accessibility service with
 * key filtering is the one hook that runs earlier, because
 * InputDispatcher calls filterInputEvent() before the policy sees the
 * event at all.
 *
 * The documented hazard is that a bound key-filtering service can swallow
 * key *repeats*, which turns a ramping volume into one step per press.
 * To keep that risk as small as possible this returns false immediately
 * for every key it does not map, so unmapped keys - volume included - are
 * passed straight through untouched.
 *
 * Mappings live in res/values/config.xml. Disable the whole thing by
 * turning the service off in Settings > Accessibility.
 */
public class KeyService extends AccessibilityService {

    private KeyConfig cfg;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        cfg = KeyConfig.from(this);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (cfg == null) return false;
        int k = event.getKeyCode();
        boolean mappedByUser = KeyMap.target(this, k) != null;
        // Anything we do not map is passed through immediately and
        // untouched - this is what protects volume key repeats.
        if (!cfg.isMapped(k) && !mappedByUser) return false;

        // Consume the UP of a key we handled, so no app sees a stray half.
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        // Ignore auto-repeat on a mapped key: one press, one launch.
        if (event.getRepeatCount() > 0) return true;

        if (KeyConfig.contains(cfg.settingsKeys, k)) {
            startOwn(new Intent(this, QuickPanelActivity.class));
            return true;
        }
        if (KeyConfig.contains(cfg.inputKeys, k)) {
            startOwn(new Intent(this, InputsActivity.class));
            return true;
        }
        String pkg = KeyMap.target(this, k);
        if (pkg != null) {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) { startApp(i); return true; }
        }
        return true;
    }

    /** Another app: the usual launcher flags. */
    private void startApp(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Throwable ignored) {}
    }

    /**
     * One of our own screens. Deliberately NEW_TASK only: adding
     * RESET_TASK_IF_NEEDED here makes the panel start and then vanish,
     * because it resets against the launcher's own Home task.
     */
    private void startOwn(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {}
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }
}
