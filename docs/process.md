# Process & methodology

How this project has been worked on so far, written so a fresh session (no
memory of prior conversations) can pick up feature work on the launcher
without re-deriving the environment, the build loop, or the bugs we already
hit once.

Read this before touching `launcher/`. Read `docs/feats.md` for what has
shipped, in order. Read `README.md` for user-facing usage.

## The device

- Hisense SmartTV 4K, device `lushan`, product `lushan-NF`.
- Android 12, SDK 31, MediaTek SoC, 1.8 GB RAM.
- Reached over ADB at **`10.0.0.97:5555`** (wired Ethernet). Confirm it's
  still there before assuming: `adb connect 10.0.0.97:5555`.
- If it's gone: Settings > Developer options > Network/ADB debugging may
  have been switched off, or the IP changed (check the router/ARP table,
  or ask the user). No remote-access tooling exists in this repo - if the~
  TV is off-LAN, ask the user how to reach it rather than assuming a
  relay script exists.
- The TV has **no Gradle build on disk** and this project doesn't need one.
  `tools/build-launcher.sh` drives `aapt2`/`javac`/`d8`/`zipalign`/
  `apksigner` from the Android SDK directly.

## Repo layout

```
launcher/app/src/main/java/com/oxylab/tvhome/   all launcher source
launcher/app/src/main/res/                       drawables, font, config.xml
launcher/app/src/main/AndroidManifest.xml        activities/services/queries
launcher/tvhome.apk                              last built output
tools/build-launcher.sh                          build + install (no Gradle)
tools/tv.py                                       adb wrapper + screenshot
tools/disable.sh / restore.sh                    debloat tooling (unrelated to launcher work)
docs/DISABLED.md                                 what debloat disabled and why
docs/feats.md                                     feature list, in order
```

Package is `com.oxylab.tvhome`. The launcher's Home activity is
`HomeActivity`; the Quick Settings panel is `QuickPanelActivity`; the
accessibility service that restores remote-button handling is
`KeyService`.

## The core edit loop

This is the loop used for every change in this project. Follow it for new
work rather than improvising a shortcut - the steps exist because each one
caught a real bug at least once (see Gotchas below).

1. **Read the current file before editing it.** Several rounds of this
   project applied a diff on top of a stale mental model of the file and
   had to re-derive it. `grep -n` for the method, then `Read` with an
   offset/limit around it.

2. **Compile-check before touching the device.** This project does not use
   Gradle, so there is no fast incremental compiler; `javac` directly
   against the SDK's `android.jar` is fast enough to run after every edit:

   ```bash
   rm -rf /tmp/classcheck && mkdir -p /tmp/classcheck
   PLATFORM=~/Library/Android/sdk/platforms/android-36
   javac --release 11 -encoding UTF-8 -cp "$PLATFORM/android.jar" -d /tmp/classcheck \
     $(find launcher/app/src/main/java launcher/build/gen -name '*.java') 2>&1
   ```

   `launcher/build/gen` only exists after a real build (it holds `R.java`);
   run `tools/build-launcher.sh --build` once first if it's missing. Expect
   only deprecation notes, never errors, before moving on.

3. **Build and install:**

   ```bash
   tools/build-launcher.sh            # build + push + pm install -r -g
   tools/build-launcher.sh --build    # build only, skip install
   ```

   The `-g` flag on `pm install` auto-grants runtime permissions
   (`BLUETOOTH_CONNECT` etc.) with no dialog. The script also detects if
   the remote-key accessibility service was enabled before the install and
   re-enables it after (see Gotcha below) - watch for its
   `re-enabled the remote-key accessibility service` line.

4. **If testing an activity that isn't launchable from the UI yet**
   (`android:exported="false"`, the normal state for every screen except
   `HomeActivity`/`KeyService`/`TvNotificationListener`/
   `NotificationPanelActivity`), flip it to `exported="true"`, rebuild,
   `am start -n com.oxylab.tvhome/.WhateverActivity`, screenshot, iterate.
   **Always flip it back to `false` and rebuild before finishing** - do not
   leave a debug-exported activity in a shipped build.

5. **Screenshot to verify, don't assume.** `tools/tv.py --screenshot
   shots/NN-name.png [KEYS...]` presses keys then captures. Read every
   screenshot; this project caught several real rendering bugs (crushed
   text, misaligned tiles, overflowing rows) that looked fine in the Java
   source and were wrong on the actual screen. For focus state that isn't
   visually obvious, confirm with `uiautomator dump` rather than guessing
   from a screenshot:

   ```bash
   adb -s 10.0.0.97:5555 shell uiautomator dump /sdcard/ui.xml
   adb -s 10.0.0.97:5555 shell cat /sdcard/ui.xml | tr -d '\r' | \
     grep -oE '<node[^>]*focused="true"[^>]*>'
   ```

6. **Final pass after closing the test hatch:** rebuild, then confirm Home
   is actually focused and (if remote-key work was touched) the key
   service is bound:

   ```bash
   python3 tools/tv.py --keys HOME; sleep 3
   adb -s 10.0.0.97:5555 shell dumpsys activity activities | grep mFocusedApp
   adb -s 10.0.0.97:5555 shell dumpsys accessibility | grep "Bound services"
   ```

## Gotchas already paid for - don't rediscover these

- **`addView(view)` without explicit `LayoutParams` inside a `VERTICAL`
  `LinearLayout` defaults to `MATCH_PARENT` width, not `WRAP_CONTENT`.**
  This silently crushed sibling text views to the narrowest line's width
  (a two-line info pill where the short "On" line squeezed "Bluetooth"
  down to an ellipsis). Any time a vertical stack of TextViews is built,
  give each one explicit `WRAP_CONTENT` params via the two-arg `addView`.
  Horizontal `LinearLayout`s default to `WRAP_CONTENT` and are not at risk.

- **Package-visibility filtering (Android 11+) hides apps with no
  launcher activity.** `AppRepo`'s `<queries>` block only covers
  `LEANBACK_LAUNCHER`/`LAUNCHER` intents, so a background-only daemon
  (AirPlay receiver, TV remote service) is invisible to
  `PackageManager.getApplicationInfo()` and `appLabel()` silently falls
  back to the raw package name. Fixed with `QUERY_ALL_PACKAGES` in the
  manifest - correct call for a sideloaded, non-Play app; do not add a
  `<queries><package>` entry per-app instead, it doesn't scale.

- **Android revokes accessibility-service consent on APK reinstall**, and
  separately **force-stopping the app also unbinds it** (Android refuses
  to rebind an accessibility service after an explicit force-stop).
  `tools/build-launcher.sh` already handles the reinstall case
  automatically; if you force-stop the app while testing, you must
  manually re-enable it:

  ```bash
  adb -s 10.0.0.97:5555 shell settings put secure enabled_accessibility_services \
    com.oxylab.tvhome/com.oxylab.tvhome.KeyService
  adb -s 10.0.0.97:5555 shell settings put secure accessibility_enabled 1
  ```

- **`FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` on an intent targeting our own
  activity makes it start and immediately vanish**, because it resets
  against the Home task. Use `FLAG_ACTIVITY_NEW_TASK` alone for intents
  that launch our own activities (see `openOwnOrApp`/`startOwn` in
  `QuickPanelActivity`/`KeyService`); the full flag set is fine - and
  needed - when launching another app's activity.

- **Injected key events (`adb shell input keyevent`) are flagged
  `POLICY_FLAG_FILTERED`, which skips accessibility key-filtering.**
  `KeyService` cannot be tested by injecting keys - it will appear to do
  nothing even when working. Only the physical remote exercises that path;
  ask the user to press the real button.

- **`adb shell input keyevent --longpress KEYCODE_DPAD_CENTER` works** for
  testing `View.setOnLongClickListener` on a focused tile - a normal
  `input keyevent` is an instant down+up and never triggers it. This is a
  different mechanism from the `POLICY_FLAG_FILTERED` issue above (it's
  View-level long-press detection, not accessibility key filtering), so it
  tests fine via ADB unlike KeyService.

- **The accessibility key-filter service costs Volume key *repeat*,
  measured on this exact TV**: binding `KeyService` (even though it passes
  unmapped keys straight through) makes holding Volume Up step once per
  press instead of ramping. This is a hardware/firmware-level cost of
  *binding*, not of the filtering logic - already documented in
  `config.xml` and `README.md`. Don't re-investigate this; it's settled.

- **`PendingIntent.send()` on a notification's `contentIntent` is the
  correct way to "open" a notification** (it deep-links where the posting
  app intended, not just its front door) but is frequently `null`,
  especially for system/background-daemon notifications. Always fall back
  to `getLeanbackLaunchIntentForPackage`/`getLaunchIntentForPackage`, and
  accept that some notifications (pure daemons with no launcher activity)
  genuinely have nothing to open - show a toast, don't treat it as a bug.

- **`cancelAllNotifications()` only removes genuinely dismissable
  notifications.** Anything with `FLAG_ONGOING_EVENT` (foreground-service
  "I'm running" notifications - Stremio's server, DLNA, AirPlay, the TV
  remote service) is skipped, matching the real Android notification
  shade. If "Clear all" seems to do nothing, check
  `dumpsys notification --noredact | grep flags=` before assuming it's
  broken - it's probably correct.

- **`tools:ignore="..."` in the manifest needs a declared `xmlns:tools`
  namespace**, and is meaningless anyway since this project builds with
  `aapt2` directly, not Gradle/lint. Don't add `tools:` attributes.

- **macOS `sips` can crop a screenshot for close-up pixel inspection**
  when a detail is too small to read in a full screenshot:
  `sips -c <h> <w> --cropOffset <y> <x> in.png --out crop.png`. Must write
  the crop inside the project directory - the Read tool can't see outside
  the working directory (e.g. not `/tmp`).

## Design conventions already established

- **Panel chrome**: `QuickPanelActivity` is a right-docked sidebar,
  `dp(400)` wide, full screen height (`MATCH_PARENT`, no top/bottom
  margin), square corners, `panel_bg` solid fill - not a floating rounded
  card. Content has real padding (`shell.setPadding(...)`); only the
  *margin* to the screen edge was removed.
- **Two button styles**: `tile()` (icon-left, two-line label+status,
  `panel_item_bg` focus-selector background, used in the 2-column grid)
  and `fullRow()`/`actionPill()` (icon+single-line-label, for solo
  actions). `infoPill()` is a third, deliberately different style: no
  background, no focus affordance beyond scale/alpha, because it's pure
  status display for Ethernet/Wi-Fi/Bluetooth, not an action.
- **Plain text lists with no card background** (Background Tasks) use a
  bare `TextView` with `bottomMargin` for spacing, no `panel_item_bg`, not
  focusable - this is the pattern for "informational, not interactive."
- **Borderless clickable controls still need a focus affordance.** Where
  there's no card background (`infoPill` when it used to be clickable,
  the notifications "Clear all" ✕), reuse `popup_item_bg` (a selector:
  transparent normal, `#2EFFFFFF` fill when focused) rather than inventing
  a new drawable or relying on scale/alpha alone - a D-pad UI with zero
  visible focus indicator is a real usability bug, not a style choice.
- **`config.xml` is the one file an end user is expected to hand-edit.**
  Every knob that isn't a one-off goes there with a comment, not hardcoded
  in Java - dock apps, columns, remote button defaults, key-service
  toggles, greeting text, wallpaper toggle.
- **Grouped vs per-item.** When the same kind of thing can repeat
  (notifications from one app, ongoing background tasks), group by package
  with a count rather than listing duplicates - established in the
  Background Tasks section, reuse that `LinkedHashMap<String,Integer>`
  counting pattern for anything similar later.

- **A short-lived modal "placement mode" overriding `dispatchKeyEvent`.**
  Pin-to-Tray introduced this pattern in `HomeActivity`: a boolean flag
  (`placing`) checked first in `dispatchKeyEvent`, routing to a dedicated
  handler that swallows every key except the few relevant to the mode
  (Left/Right/OK/Back) so the user can't wander off and leave state half
  changed. Reuse this shape for any future "do a multi-step thing with the
  D-pad, then confirm or cancel" feature rather than inventing a new one -
  it composes cleanly with the existing `zone`/`navigate()` state machine
  (placement mode runs "on top of" whatever zone was active, and simply
  doesn't call `navigate()` while active).
- **Preview from an untouched baseline, not incremental swaps.** The first
  version of Pin-to-Tray mutated the dock list a step at a time (swap the
  moving app with its neighbour on every Left/Right), which looked right
  but actually evicted the original slot-0 app to the grid the instant
  placement began - "nothing happens until you confirm" was violated well
  before the user noticed. Fixed by keeping an untouched `dockBaseline`
  snapshot and, on every keypress, rebuilding the preview *from
  `dockBaseline` fresh* with only the moving app dropped at the candidate
  index (`updatePlacementPreview()`) - never from whatever the dock
  currently shows. Real state (`dockOrder`, the grid) is only touched by
  `confirmPlacement()`/`cancelPlacement()`, never mid-walk. Apply the same
  rule to any similar "preview a change across several steps, commit once"
  interaction: diff against a frozen baseline each time, don't accumulate
  mutations.
- **Rebuild-on-every-step, not live View manipulation.** The same feature
  reorders the dock by rebuilding all dock tiles from a `List<AppEntry>`
  on every Left/Right press (`rebuildDock()`), rather than moving View
  positions in place. Cheap enough for a handful of tiles, and far
  simpler/more robust than manual View-index surgery - prefer this for any
  other small, infrequently-sized list that needs live reordering.

## Picking up a new feature request efficiently

1. Check `docs/feats.md` for what already exists so you don't rebuild it.
2. `grep -n` the relevant method name in `QuickPanelActivity.java` (it's
   the biggest file and holds most UI) rather than reading the whole file.
3. Make the targeted edit, run the compile-check, then the full loop above.
4. If the change is purely cosmetic (spacing, alignment, color), a single
   build+screenshot+compare is enough - don't re-verify unrelated features
   that weren't touched, but do a final Home-focused + key-service-bound
   check before ending, since that's cheap and catches state left over
   from a test-export flip.
5. Update `docs/feats.md` (one line, same terse style) if the request was
   a genuinely new feature rather than a refinement of an existing one.
