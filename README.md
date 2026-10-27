# cleanyourTV — Hisense SmartTV 4K (`lushan-NF`)

Debloated a Hisense Google TV and replaced the Google TV launcher with a
small native one. Everything here is reversible.

| | |
|---|---|
| Model | Hisense SmartTV 4K, device `lushan`, product `lushan-NF` |
| OS | Android 12 (SDK 31), Google TV, build `STT2.230929.001.102` |
| SoC / RAM | MediaTek, 1.8 GB |
| Panel | 3840×2160, UI renders at 1920×1080, density 320 |
| Network | **Wired Ethernet** (`eth0`, 10.0.0.97). USB host not connected, so no USB-Ethernet adapter is involved |
| ADB | TCP 5555, `ro.adb.secure=1` |

## What changed

* **22 packages disabled** — Hisense ACR and telemetry, the VIDAA ad/content
  providers that fed the home rows, a junk cleaner, store-demo and
  first-run leftovers, and the preloads you said you don't use.
  Full table with reasons: [`docs/DISABLED.md`](docs/DISABLED.md).
* **Private DNS** set to AdGuard (`dns.adguard-dns.com`), validated against
  `94.140.15.15` / `94.140.14.14`.
* **New launcher** `com.oxylab.tvhome` installed and set as Home; the stock
  Google TV launcher and the setup wizard are disabled.
* **Remote keys restored** via an accessibility key-filter service: the gear
  button opens a rebuilt quick-settings panel, and all six app buttons are
  remappable from inside it. This costs volume key repeat - see below.

All 22 disables survived a reboot — this firmware has no re-enable-at-boot
list, so nothing needs re-applying.

## Scripts

```bash
tools/tv.py 'getprop ro.build.version.sdk'     # run a shell command on the TV
tools/tv.py --screenshot shots/x.png           # capture what's on screen
tools/tv.py --screenshot shots/x.png DPAD_DOWN # press keys, then capture
tools/build-launcher.sh                        # build + install the launcher
tools/build-launcher.sh --build                # build only
tools/disable.sh <pkg> [...]                   # disable, with a guard list
tools/restore.sh                               # undo everything
tools/apply.sh                                 # re-apply everything
tools/adb-closed.py 10.0.0.97                  # prove ADB is shut
```

`TV_HOST` / `TV_PORT` override the address for every script.

## Undoing things

Re-enable one app:

```bash
adb -s 10.0.0.97:5555 shell pm enable --user 0 com.netflix.ninja
```

Undo everything (all packages, the stock launcher as Home, and Private DNS):

```bash
tools/restore.sh              # then: adb -s 10.0.0.97:5555 reboot
tools/restore.sh --dns-only   # just put DNS back to opportunistic
```

Re-apply everything after a restore (re-disable the packages, make TV Home
the launcher again, re-enable its services, put AdGuard DNS back):

```bash
tools/apply.sh                  # then: adb -s 10.0.0.97:5555 reboot
tools/apply.sh --launcher-only  # just Home + its services
tools/apply.sh --dns-only       # just put AdGuard DNS back
```

`apply.sh` does not build the launcher - run `tools/build-launcher.sh`
first if `com.oxylab.tvhome` is not installed. It also does not re-create
the deleted Wi-Fi network `oxylab_mo`.

If the stock launcher ever reappears (for example after a reboot), run
`tools/apply.sh` again - it re-disables it and hands Home back to TV Home.

Turn off ad-blocking DNS only:

```bash
adb -s 10.0.0.97:5555 shell settings put global private_dns_mode opportunistic
```

## Changing the launcher

Everything you'd normally want to change is in
`launcher/app/src/main/res/values/config.xml`; run `tools/build-launcher.sh`
after editing.

* **Dock apps and order** — the `dock_apps` array, one `<item>` per package,
  left to right. The dock sizes itself to however many you list.
  Currently: Nuvio, YouTube, Live TV, Spotify.
* **Grid columns** — the `columns` integer (currently 6).
* **Hide an app from the grid** — add it to `hidden_apps`.
* **Greeting** — set `greeting` to fixed text, or leave empty for a
  time-of-day greeting.
* **Wallpaper** — off by default (pitch black, which is what the design is
  built around). Turn it on and pick a source from the quick-settings
  panel's **Wallpaper** screen; the mode, schedule and chosen image/color
  live in `SharedPreferences`, not here. `unsplash_access_key` is only a
  build-time fallback for the Unsplash source - the in-app field wins.

### Remote keys

The stock launcher owned the remote's app buttons and the gear button, so
disabling it broke them. `KeyService` is an **accessibility key-filter
service** that catches those keys before the window policy consumes them —
the one hook that runs earlier than dispatch. It is what makes the buttons
work, and it is on by default (enable/disable it in Settings →
Accessibility).

`remote_buttons` in `config.xml` lists the branded buttons as
`keycode|Label`, and `app_keys` maps each keycode to the app it launches.
The quick-settings panel's **Remote buttons** screen changes those without a
rebuild (stored in `SharedPreferences`, and it wins over `config.xml`).
`settings_key_keycodes` and `input_key_keycodes` do the same for the
settings and inputs keys.

The cost is real and accepted: **binding the service stops Volume Up/Down
from ramping** — holding the key steps once per press instead of ramping.
The service passes every unmapped key straight through, so it is the
binding itself, not the filtering, that costs the repeat. Turning the
service off in Settings restores the ramp and loses the app buttons again;
it is all or nothing.

Find a button's keycode with:

```bash
tools/tv.py 'getevent -lt'      # press the button, read the scancode
adb -s 10.0.0.97:5555 shell logcat -d | grep interceptKeyTq
```

## Known limitations

* **The quick-settings panel is a rebuild, not the original.** Google's panel
  lives in the stock launcher (`launcherx/.dashboard.DashboardHandler`) and
  cannot coexist with this one: enabling launcherx always takes the HOME key
  back, and disabling only its `HomeActivity` is refused with a
  `SecurityException`. The replacement reaches what an ordinary app can
  reach - settings screens, inputs, volume - so it has no Google account
  tiles or notification feed.
* **Volume no longer ramps when held**, as a direct cost of the remote-key
  service. Undo above.
* The gear button's native route is also closed: SystemUI resolves
  `ACTION_TOGGLE_NOTIFICATION_HANDLER_PANEL` **system-apps-only**, so
  `NotificationPanelActivity` here resolves from the shell but SystemUI will
  never pick it. The accessibility service is what makes the button work.
* **The saved Wi-Fi network `oxylab_mo` was deleted.** Android removes
  networks owned by a package when that package is disabled, and this hidden
  network was created by the setup wizard. The TV is on wired Ethernet so
  nothing dropped. Re-add it in Settings → Network & Internet → Add new
  network (it's hidden, so type the SSID). Wi-Fi was left switched **on** for
  that; it was off before.
* **Private DNS bypasses your router's DNS.** If you later run Pi-hole or
  AdGuard Home on the network, those rules won't apply to this TV until you
  set `private_dns_mode` back to `opportunistic`.
* AdGuard DNS does **not** block YouTube ads — verified on the TV.
* Nuvio starts itself at boot (it has its own `BOOT_COMPLETED` receiver), so
  the TV may land on Nuvio rather than Home after a cold boot. That's Nuvio,
  not the launcher. Press Home.

## Building the launcher

No Gradle needed. `tools/build-launcher.sh` drives `aapt2`, `javac`, `d8`,
`zipalign` and `apksigner` from the Android SDK directly and signs with the
standard debug keystore. A `build.gradle` is included if you'd rather use
Gradle.

The launcher is plain Java against the framework — no AndroidX — which keeps
the APK at ~90 KB and the running launcher at ~36 MB PSS on a 1.8 GB TV.

## Backups

`backup/<timestamp>/` holds the package lists, `getprop` and all three
settings namespaces as they were before any change.
`backup/disabled-by-cleanyourtv.txt` is the exact list `tools/restore.sh`
replays and `tools/apply.sh` re-disables.
