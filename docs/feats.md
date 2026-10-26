# Features

What ships, one entry per feature. Refinements to an existing feature are
folded into its entry rather than listed as their own. `docs/process.md`
covers the methodology; git history has the build-up.

1. **Debloated TV** - identify and `pm disable-user` manufacturer
   telemetry, ACR, VIDAA ad/content providers, and unused preloaded apps,
   in small verified batches, with a backup + restore script
   (`backup/`, `tools/disable.sh`, `tools/restore.sh`).

2. **Ad-blocking Private DNS** - Android's Private DNS set to AdGuard.

3. **Custom native launcher, set as Home** - the stock Google TV launcher
   is replaced by a hand-built Apple-TV-style Home screen: black
   background, dock row, app grid, deterministic D-pad navigation, Outfit
   font. The stock launcher and `setupwraith` (the Google TV setup wizard,
   which otherwise reclaims HOME) are disabled, and the new launcher is
   set as Home (`HomeActivity`, `AppRepo`, `Tile`, `ColorSampler`).

4. **Remote-key service** - the stock launcher used to answer the remote's
   app buttons and the gear/settings button, so disabling it broke them.
   `KeyService`, an accessibility service, catches these keys before the
   window policy swallows them. Accepted trade-off: it costs Volume key
   repeat.

5. **Custom Quick Settings panel** - a rebuild of the Google TV dashboard:
   Volume slider, Wi-Fi/Bluetooth/Network status, Inputs, Picture, Sound,
   Accessibility, All settings. Home's Settings pill opens it, so there is
   one settings entry point (`QuickPanelActivity`).

6. **Remote button mapping** - a screen inside the Quick Settings panel
   lets the user reassign which app each physical remote button launches,
   persisted in `SharedPreferences` (`RemoteKeysActivity`, `KeyMap`).

7. **Notifications panel** - current Android notifications are shown
   inline in the Quick Settings panel (`TvNotificationListener`). Tapping
   one fires its `contentIntent` (falling back to launching the app), a
   one-tap action clears everything clearable, and ongoing
   foreground-service notifications (`FLAG_ONGOING_EVENT`) are split out
   and grouped by app name under "Background Tasks".

8. **Pin to Tray, and app uninstall via long-press** - long-pressing a
   grid app shows "Pin to Tray" and "App info"; App info opens the system
   App Info screen (uninstall lives there, not reimplemented). Pin to Tray
   drops the app into the dock's first slot and enters a placement mode -
   Left/Right walk it to a position, OK saves it, Back cancels - persisted
   the same way as button mapping (`DockConfig`, `AppContextMenu`).

9. **Wallpaper** - Home's background, chosen from the quick-settings panel
   (`WallpaperActivity`): a master on/off, then a source - Unsplash,
   custom images, or solid colors. The custom-image source reads straight
   from `Pictures/Wallpapers` on the TV (no in-app picker and no stored
   file list; the folder is the source of truth), accepting only images at
   least half the display's width x height, and showing the first three as
   overlapping thumbnails plus a "+N more" card. A refresh-schedule row
   advances the active color/image (or downloads a fresh Unsplash photo)
   on the next Home load once the interval has elapsed; the interval is
   counted in whole local calendar days, not a rolling 24h window, so
   "Every day" means "the next day" - a change made at 23:59 still rolls
   over once the date passes midnight. The panel opens focused on a
   **Refresh now** cell that steps the current source immediately; the
   on/off toggle shares its top row, the schedule sits just below it, a
   "Source" heading precedes the three source rows, and the schedule shows
   chevrons around its value to signal the Left/Right control. It is a
   last-updated check at onResume time, deliberately not an
   AlarmManager/background timer (`WallpaperConfig`, `WallpaperManager`).

10. **Clock and weather cards** - Home's top bar holds two cards on the
    same translucent, borderless backdrop as the dock tray
    (`widget_bg`/`widget_bg_dark`); the clock is the same size and weight
    as the weather temperature so the two read as one row. The backdrop
    and focus-plate tint is the *inverse* of the overlay text colour, so
    text and surface always contrast. Both cards are D-pad reachable as a
    top-bar zone - Up from the pill row, Left/Right between clock and
    weather, Down back to the pills - and while focused their backdrop
    goes from 8% to 70% opaque. Tapping or pressing OK toggles a card: the
    clock between 24-hour and 12-hour with a small AM/PM suffix
    (`ClockConfig`), the weather between °C and °F (a `Weather` unit
    override that converts the reading in hand so the change is instant,
    then forces an immediate reload that bypasses the 30-minute cache).

    The weather card shows the city, a large temperature with a hand-drawn
    condition glyph, and a next-hour line ("Sunny day ahead"). Location
    comes from the TV's own last-known fix (whichever system provider is
    freshest) when coarse location has been granted, reverse-geocoded to a
    city with `Geocoder`, otherwise from IP geolocation (ipwho.is, falling
    back to ipinfo.io); the weather itself is Open-Meteo. Both the
    location and the reading are cached in `SharedPreferences` for 30
    minutes, and resume plus the minute tick repaint from that cache,
    only hitting the network once it has expired. The two halves fail
    differently: an expired location is kept and reused when a lookup
    fails, so the widget survives an outage, while an expired reading is
    dropped and the widget shows "--" / "No network" rather than stale
    weather. The unit follows the geolocated country (metric by default;
    °F only for US/LR/MM), not the device locale, so a Montreal reading
    shows °C on an en-US TV. The three Inputs/Settings/Wallpaper buttons
    sit at the right end of the tray's title row, aligned with the tray
    edge, and the Inputs menu opens to the left of its pill (bottom-aligned
    and clamped to the screen) (`Weather`, `WeatherView`,
    `WeatherGlyphView`, `HomeActivity.updateClock`, `IconButton.setTheme`).
