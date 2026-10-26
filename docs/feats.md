# Features, in order requested

The main feature asks for this project, in the order they were requested.
Does not include cosmetic/UI refinements (spacing, alignment, colors,
icon-left-vs-top, wording) or bug fixes to existing features - see
`docs/process.md` for how those get picked up, and git history / this
session's transcript for the full detail if needed.

1. **Debloat the TV** - identify and `pm disable-user` manufacturer
   telemetry, ACR, VIDAA ad/content providers, and unused preloaded apps,
   in small verified batches, with a backup + restore script
   (`backup/`, `tools/disable.sh`, `tools/restore.sh`).

2. **Ad-blocking Private DNS** - set Android's Private DNS to AdGuard.

3. **Custom native launcher** - replace the stock Google TV launcher with
   a hand-built Apple-TV-style Home screen: black background, dock row,
   app grid, deterministic D-pad navigation, Outfit font
   (`HomeActivity`, `AppRepo`, `Tile`, `ColorSampler`).

4. **Make it the default Home** - disable the stock launcher and
   `setupwraith` (the Google TV setup wizard, which otherwise reclaims
   HOME), set the new launcher as Home.

5. **Restore remote-control functionality lost in step 4** - the stock
   launcher used to answer the remote's app buttons and the gear/settings
   button; disabling it broke them. Fixed with `KeyService`, an
   accessibility service that catches these keys before the window policy
   swallows them (documented, accepted trade-off: costs Volume key
   repeat).

6. **Custom Quick Settings panel** - rebuild of the Google TV dashboard
   lost in step 4: Volume slider, Wi-Fi/Bluetooth/Network status,
   Inputs, Picture, Sound, Accessibility, All settings
   (`QuickPanelActivity`).

7. **Button mapping** - a screen inside the Quick Settings panel letting
   the user reassign which app each physical remote button launches,
   persisted in `SharedPreferences` (`RemoteKeysActivity`, `KeyMap`).

8. **Notifications in the panel** - view current Android notifications
   inline in the Quick Settings panel (`TvNotificationListener`).

9. **Clear all notifications** - a one-tap action to dismiss everything
   clearable.

10. **Interactive notifications** - tapping a notification fires its
    `contentIntent` (falls back to launching the app) instead of being
    display-only.

11. **Split Notifications vs Background Tasks** - separate genuinely
    dismissable notifications from ongoing foreground-service ones
    (`FLAG_ONGOING_EVENT`); the latter show grouped by app name only,
    under a new "Background Tasks" section.

12. **Home screen Settings pill opens Quick Settings** - instead of the
    system Settings app, for one consistent settings entry point.

13. **Pin to Tray, and app uninstall via long-press** - long-pressing a
    grid app shows "Pin to Tray" and "App info"; App info opens the
    system App Info screen (uninstall lives there, not reimplemented).
    Pin to Tray drops the app into the dock's first slot and enters a
    placement mode - Left/Right walk it to a position, OK saves it,
    Back cancels - persisted the same way as button mapping
    (`DockConfig`, `AppContextMenu`).

14. **Wallpaper picker + rotation** - Home's background, chosen from the
    quick-settings panel (`WallpaperActivity`): master on/off, then a
    source - Unsplash, multi-select custom images, or multi-select solid
    colors. A common refresh-schedule row at the bottom of the list
    advances the active color/image (or downloads a fresh Unsplash
    photo) on the next Home load once the interval has elapsed. The
    interval is counted in whole local calendar days, not a rolling 24h
    window, so "Every day" means "the next day": a change made at 23:59
    still rolls over once the date passes midnight (`WallpaperConfig`
    stores the last-refresh day; `WallpaperManager.maybeScheduledRefresh`
    compares it to today). It is a last-updated check at onResume time,
    deliberately not an AlarmManager/background timer.

15. **Wallpaper folder (Pictures/Wallpapers) + instant refresh** - the
    custom-image source reads straight from `Pictures/Wallpapers` on the TV -
    no in-app picker and no stored file list; the folder is the sole source of
    truth. Only images at least half the display's width x height are accepted
    (`WallpaperConfig.wallpaperFolder/getFolderImages/isBigEnough`), and the
    panel shows the first three as overlapping thumbnails plus a "+N more"
    card. The panel opens with focus on a **Refresh now** cell that steps the
    current source immediately (next photo/color/image); it shares the top row
    with the Wallpaper on/off toggle, the refresh schedule sits just below it,
    and a "Source" heading precedes the three source rows. The schedule shows
    chevrons around its value to signal the Left/Right control
    (`WallpaperActivity`).

16. **Weather widget + tray action buttons** - Home's top-right now holds a
    weather block in the same voice as the clock opposite it: city, a large
    temperature with a hand-drawn condition glyph, and a next-hour line
    ("Sunny day ahead"). Location comes from the TV's own fused/network
    provider when coarse location has been granted (reverse-geocoded to a
    city with `Geocoder`), otherwise from IP geolocation (ipwho.is, falling
    back to ipinfo.io); the weather itself is Open-Meteo. Both the location
    and the reading are cached in SharedPreferences for 30 minutes; resume
    and the minute tick repaint from that cache and only hit the network
    once it has expired. The two halves fail differently: an expired location
    is kept and reused when a lookup fails, so the widget survives an outage,
    while an expired reading is dropped and the widget shows "--" / "No
    network" rather than stale weather. The unit follows the geolocated
    country (metric by default; °F only for US/LR/MM), not the device
    locale, so a Montreal reading shows °C on an en-US TV (`Weather`,
    `WeatherView`, `WeatherGlyphView`). The three Inputs/Settings/Wallpaper buttons that
    used to sit there moved down to the tray's title row, far right, lining
    up with the tray edge. The Inputs menu opens to the left of its pill
    (bottom-aligned and clamped to the screen) rather than dropping down,
    since the pill now sits near the bottom with no room beneath it.

17. **Clock and weather cards: backdrops, focus and tap actions** - the
    time and weather blocks sit on the same translucent, borderless
    backdrop as the dock tray (`widget_bg`/`widget_bg_dark`), and the
    clock is now the same size and weight as the weather temperature so
    the two read as one row. The backdrop and focus-plate tint is the
    *inverse* of the overlay text colour instead of the same as it: dark
    text takes the white-tinted glass, light text the black-tinted one,
    so text and surface always contrast (this also fixes the previous
    pairing, where dark text sat on a dark-tinted pill, band and focus
    plate). Both cards are D-pad reachable as a new top-bar zone - Up
    from the pill row, Left/Right between clock and weather, Down back to
    the pills - and while focused their backdrop goes from 8% to 70%
    opaque, so the card reads as selected and its text stays legible over
    a busy wallpaper. Tapping or pressing OK on a card toggles it: the
    clock between 24-hour and 12-hour with a small AM/PM suffix
    (`ClockConfig`), the weather between °C and °F (a `Weather` unit
    override that converts the reading in hand so the change is instant,
    then forces an immediate reload that bypasses the 30-minute cache -
    otherwise the still-fresh reading would be served and the toggle would
    never actually refresh).
    `HomeActivity.updateClock`, `IconButton.setTheme`.
