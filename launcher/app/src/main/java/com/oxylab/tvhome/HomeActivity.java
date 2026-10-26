package com.oxylab.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class HomeActivity extends Activity {

    private float density;
    private Typeface outfit;

    private FrameLayout root;
    private View highlight;
    private NoAutoScrollView scroll;
    private LinearLayout content;
    private FrameLayout page1;
    private LinearLayout gridBox;
    private LinearLayout dockBand;
    private TextView dockTitle;
    private TextView clockView, dateView, greetView;
    private View timeWidget;
    private IconButton inputsBtn;
    private WeatherView weatherView;
    private Weather.Data weather;
    private boolean weatherFetching;
    private int weatherGen;
    private boolean askedLocation;

    private final List<Tile> dockTiles = new ArrayList<>();
    private final List<Tile> gridTiles = new ArrayList<>();
    private final List<IconButton> pills = new ArrayList<>();

    private List<AppEntry> allApps = new ArrayList<>();
    private List<AppEntry> dockOrder = new ArrayList<>();
    private int dockTileW, dockTileH, gridTileW, gridTileH;

    // Pin-to-tray placement: Left/Right choose which dock slot the newly
    // pinned app would take, OK saves it, Back cancels. dockBaseline is
    // the dock exactly as it was before this pin started and is never
    // itself mutated during the walk - each step re-previews it fresh
    // with placingEntry dropped at the candidate slot. Pin to Tray is
    // disabled in the long-press menu for anything already in the dock
    // (AppContextMenu), so placingEntry is never already a dockOrder
    // member here - no "move" case to account for. While placing,
    // dispatchKeyEvent answers to handlePlacingKey() instead of the
    // normal navigate() state machine.
    private boolean placing = false;
    private List<AppEntry> dockBaseline;
    private AppEntry placingEntry;

    private int columns = 6;

    // Explicit focus model.  Navigation is computed here rather than left
    // to FocusFinder, which on this layout happily jumps sideways along
    // the dock instead of down into the off-screen grid.
    private static final int ZONE_TOP = 0, ZONE_DOCK = 1, ZONE_GRID = 2, ZONE_BAR = 3;
    private int zone = ZONE_DOCK;
    private int pillIdx = 0;
    private int barIdx = 0;    // 0 = clock, 1 = weather
    private int dockIdx = 0;   // remembered: the last dock tile used
    private int gridIdx = 0;   // remembered: the last grid tile used
    private View lastFocusedTile;

    private int[] settingsKeys = new int[0];
    private int[] inputKeys = new int[0];
    private final java.util.HashMap<Integer, String> appKeys = new java.util.HashMap<>();

    private ValueAnimator hlAnim;
    private int hlW = -1, hlH = -1;

    private final BroadcastReceiver timeTick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateClock();
            maybeRefreshWeather();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        columns = Math.max(2, getResources().getInteger(R.integer.columns));
        try { outfit = getResources().getFont(R.font.outfit); } catch (Throwable ignored) {}

        loadKeyConfig();
        buildUi();
        loadApps();
        updateClock();

        // Lay out page 1 to exactly one screen so the grid sits below the
        // fold and Down from the dock scrolls it up.
        root.getViewTreeObserver().addOnGlobalLayoutListener(
            new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    int h = root.getHeight();
                    if (h <= 0) return;
                    root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    page1.getLayoutParams().height = h;
                    page1.requestLayout();
                    root.post(() -> {
                        zone = ZONE_DOCK;
                        applyFocus(false);
                    });
                }
            });

        scroll.getViewTreeObserver().addOnScrollChangedListener(
                () -> moveHighlight(lastFocusedTile, false));
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        WallpaperManager.attachHome(root, this::applyTextTheme);
        WallpaperManager.apply(this, root);
        root.setClipChildren(false);

        // The single glass plate, behind the content so artwork stays crisp
        // and the plate reads as a glow around the focused tile.
        highlight = new View(this);
        highlight.setBackgroundResource(R.drawable.glass_highlight);
        highlight.setAlpha(0f);
        root.addView(highlight, new FrameLayout.LayoutParams(dp(10), dp(10)));

        scroll = new NoAutoScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setClipChildren(false);
        // We place focus ourselves; the ScrollView must not fight us.
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // Don't let the framework restore a scroll position behind our back.
        scroll.setSaveEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page1 = new FrameLayout(this);
        page1.setClipChildren(false);
        content.addView(page1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(600)));

        buildTopBar();
        buildDockArea();
        applyTextTheme();

        gridBox = new LinearLayout(this);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        gridBox.setClipChildren(false);
        gridBox.setPadding(dp(60), dp(28), dp(60), dp(80));
        content.addView(gridBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void buildTopBar() {
        FrameLayout bar = new FrameLayout(this);
        // Every other ancestor on the way up to the icon buttons already
        // has this set; bar was the one missing it, so their focused
        // 1.07x scale-up got clipped flat against its unscaled bounds.
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.TOP;
        blp.leftMargin = dp(60); blp.rightMargin = dp(60); blp.topMargin = dp(44);
        page1.addView(bar, blp);

        // Left: greeting, the clock, the date - the clock deliberately the
        // same size and weight as the weather block's temperature opposite
        // it, so the two cards read as one row.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        greetView = new TextView(this);
        style(greetView, 15f, 0xB3FFFFFF, 400);
        // Explicit WRAP_CONTENT: a bare addView() in a VERTICAL LinearLayout
        // defaults to MATCH_PARENT, and a single MATCH_PARENT child makes the
        // WRAP_CONTENT parent size to the *other* (clock) child only - which
        // clipped this greeting and the date whenever the clock was narrow
        // (24-hour mode). See the same note in WeatherView.
        left.addView(greetView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        clockView = new TextView(this);
        style(clockView, 46f, 0xFFFFFFFF, 300);
        clockView.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(2);
        left.addView(clockView, clp);

        dateView = new TextView(this);
        style(dateView, 14f, 0x8CFFFFFF, 400);
        left.addView(dateView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Wraps its content so the backdrop hugs the text rather than
        // spanning a fixed column; the weather block is anchored to the
        // opposite edge, so the two never collide.
        left.setPadding(dp(22), dp(14), dp(22), dp(14));
        // Center every row within the card so the shorter lines (greeting,
        // clock) get equal space on both sides instead of hugging the left.
        left.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        timeWidget = left;
        // Tapping (or OK on) the clock flips it between 24-hour and
        // 12-hour with an AM/PM suffix - see updateClock. Focusable so the
        // D-pad can reach it (ZONE_BAR), with the widget backdrop
        // brightening to a readable frosted card while it is selected.
        left.setOnClickListener(v -> { ClockConfig.toggle24Hour(this); updateClock(); });
        left.setFocusable(true);
        left.setOnFocusChangeListener((v, has) -> { if (has) onWidgetFocus(); });
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        bar.addView(left, llp);

        // Right: the weather block, in the same voice as the clock
        // opposite it. The three action buttons used to live here; they
        // now sit on the tray (see buildDockArea).
        weatherView = new WeatherView(this, outfit);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        weatherView.setPadding(dp(22), dp(14), dp(22), dp(14));
        // Same tap-to-toggle idea as the clock, for the unit: the reading
        // in hand is converted on the spot so the change is instant, then
        // the unit change is forced straight to the network. This bypasses
        // the 30-minute cache - otherwise the still-fresh reading would be
        // served and the toggle would never actually reload.
        weatherView.setOnClickListener(v -> {
            Weather.toggleUnit(this);
            Weather.convert(this, weather);
            if (weatherView != null) weatherView.setData(weather);
            maybeRefreshWeather(true);
        });
        weatherView.setFocusable(true);
        weatherView.setOnFocusChangeListener((v, has) -> { if (has) onWidgetFocus(); });
        bar.addView(weatherView, wlp);
    }

    private void buildDockArea() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);

        // Title row: the focused dock app's name on the left, the three
        // action buttons pushed to the far right so they sit on the tray's
        // top edge rather than competing with the clock for the top bar.
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.BOTTOM | Gravity.END);
        titleRow.setClipChildren(false);
        titleRow.setClipToPadding(false);

        // The focused dock app's name, with an ambient glow in its own colour.
        dockTitle = new TextView(this);
        style(dockTitle, 40f, 0xFFFFFFFF, 300);
        dockTitle.setPadding(dp(74), 0, dp(60), dp(14));
        titleRow.addView(dockTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);

        IconButton inputs = new IconButton(this, 0, R.drawable.ic_input, "Inputs", outfit,
                () -> fadeHighlight(false));
        IconButton settings = new IconButton(this, IconView.SETTINGS, 0, "Settings", outfit,
                () -> fadeHighlight(false));
        IconButton wallpaper = new IconButton(this, IconView.WALLPAPER, 0, "Wallpaper", outfit,
                () -> fadeHighlight(false));

        inputsBtn = inputs;
        inputs.setOnClickListener(v -> InputsPopup.show(this, v, outfit));
        settings.setOnClickListener(v -> safeStart(new Intent(this, QuickPanelActivity.class)));
        wallpaper.setOnClickListener(v -> safeStart(new Intent(this, WallpaperActivity.class)));

        for (IconButton b : new IconButton[]{inputs, settings, wallpaper}) {
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.leftMargin = dp(14);
            right.addView(b, plp);
            pills.add(b);
        }
        titleRow.addView(right, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Same right inset as the tray band, so the buttons line up with
        // the tray's edge instead of running to the screen edge.
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trlp.rightMargin = dp(60);
        bottom.addView(titleRow, trlp);

        dockBand = new LinearLayout(this);
        dockBand.setOrientation(LinearLayout.HORIZONTAL);
        dockBand.setBackgroundResource(R.drawable.dock_band);
        dockBand.setClipChildren(false);
        dockBand.setClipToPadding(false);
        dockBand.setPadding(dp(18), dp(16), dp(18), dp(16));
        dockBand.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(60); dlp.rightMargin = dp(60);
        bottom.addView(dockBand, dlp);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        blp.bottomMargin = dp(46);
        page1.addView(bottom, blp);
    }

    // ------------------------------------------------------------- apps

    private void loadApps() {
        Set<String> hidden = new HashSet<>(
                Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        allApps = AppRepo.load(this, hidden);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int usable = screenW - dp(120);
        int gap = dp(16);

        List<AppEntry> dock = fillDock(resolveDock(DockConfig.load(this)));

        // Dock tiles, sized to however many are pinned.
        int n = Math.max(1, dock.size());
        int bandInner = usable - dp(36);
        dockTileW = (bandInner - gap * (n - 1)) / n;
        dockTileH = Math.round(dockTileW * 9f / 16f);

        gridTileW = (usable - gap * (columns - 1)) / columns;
        gridTileH = Math.round(gridTileW * 9f / 16f);

        rebuildDock(dock);
        buildGrid(allApps);

        // Scroll headroom: without it the last rows cannot reach the top
        // slot and the dock is left half on screen behind the grid.
        int rowH = gridTileH + dp(42) + dp(26);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        gridBox.setPadding(dp(60), dp(28), dp(60),
                Math.max(dp(80), screenH - rowH - dp(70)));
    }

    /** Dock package names (persisted order, or config.xml's default)
     *  resolved against the apps actually installed right now. */
    private List<AppEntry> resolveDock(List<String> pkgs) {
        List<AppEntry> dock = new ArrayList<>();
        for (String p : pkgs) {
            for (AppEntry e : allApps) {
                if (e.pkg.equals(p)) { dock.add(e); break; }
            }
        }
        return dock;
    }

    /** The dock always stays at least this full - there is no unpin, so
     *  the only way it shrinks below this is a fresh install (nothing
     *  resolved yet) or a pinned app getting uninstalled. */
    private static final int MIN_DOCK_SIZE = 4;

    /** Tops a resolved dock up to MIN_DOCK_SIZE with the first installed
     *  apps not already in it. Never shrinks a dock that's already larger
     *  (e.g. a deliberately bigger config.xml default) - this only fills
     *  gaps. */
    private List<AppEntry> fillDock(List<AppEntry> dock) {
        if (dock.size() >= MIN_DOCK_SIZE) return dock;
        List<AppEntry> filled = new ArrayList<>(dock);
        for (AppEntry e : allApps) {
            if (filled.size() >= MIN_DOCK_SIZE) break;
            if (!filled.contains(e)) filled.add(e);
        }
        return filled;
    }

    /** Clears and rebuilds the dock band from the given order. Cheap
     *  enough (a handful of tiles) to call on every placement keypress,
     *  not just at load time. */
    private void rebuildDock(List<AppEntry> order) {
        dockOrder = new ArrayList<>(order);
        dockBand.removeAllViews();
        dockTiles.clear();
        int gap = dp(16);
        for (int i = 0; i < dockOrder.size(); i++) {
            Tile t = makeTile(dockOrder.get(i), dockTileW, dockTileH);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = gap;
            dockBand.addView(t, lp);
            dockTiles.add(t);
        }
        if (!dockTiles.isEmpty()) setDockTitle(dockTiles.get(0).entry);
    }

    /** Clears and rebuilds the app grid. Grid tiles (not dock tiles) get
     *  the long-press context menu (pin to tray / app info). */
    private void buildGrid(List<AppEntry> rest) {
        gridBox.removeAllViews();
        gridTiles.clear();
        int gap = dp(16);
        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(26);
                gridBox.addView(row, rlp);
            }
            final Tile t = makeTile(rest.get(i), gridTileW, gridTileH);
            t.setOnLongClickListener(v -> {
                boolean alreadyPinned = dockOrder.contains(t.entry);
                AppContextMenu.show(this, t, t.entry, outfit, alreadyPinned, () -> pinToTray(t.entry));
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % columns != 0) lp.leftMargin = gap;
            row.addView(t, lp);
            gridTiles.add(t);
        }
    }

    // ------------------------------------------------------- pin to tray

    /**
     * Pins an app into the dock and enters placement mode: Left/Right
     * choose which slot it would take, OK saves, Back cancels. Nothing
     * real changes until OK - dockBaseline (the dock exactly as it was)
     * is never touched during the walk. Each step just re-previews
     * dockBaseline with the new app dropped at the candidate slot, so an
     * app passed over along the way is never actually evicted, only
     * temporarily hidden behind the preview; it reappears the instant the
     * cursor moves off it. Only the slot where the user finally presses
     * OK loses its app - to the grid, which it was already showing in
     * too: the grid always lists every installed app regardless of dock
     * membership, so pinning something never removes it from "all apps".
     * e is never already in dockOrder here - AppContextMenu disables Pin
     * to Tray for anything already pinned.
     */
    private void pinToTray(AppEntry e) {
        dockBaseline = new ArrayList<>(dockOrder);
        placingEntry = e;

        if (dockBaseline.isEmpty()) {
            List<String> pkgs = new ArrayList<>();
            pkgs.add(e.pkg);
            DockConfig.save(this, pkgs);
            rebuildDock(java.util.Collections.singletonList(e));
            Toast.makeText(this, "Pinned", Toast.LENGTH_SHORT).show();
            applyFocus(false);
            return;
        }

        placing = true;
        zone = ZONE_DOCK;
        updatePlacementPreview(0);
        applyFocus(false);
        Toast.makeText(this, "Left/Right to move, OK to place", Toast.LENGTH_SHORT).show();
    }

    /** Re-renders the dock as dockBaseline with placingEntry dropped at
     *  the given slot - always built fresh from the untouched baseline,
     *  never from whatever the dock currently shows, so moving the
     *  cursor never cascades a shuffle through the other apps. */
    private void updatePlacementPreview(int index) {
        List<AppEntry> preview = new ArrayList<>(dockBaseline);
        preview.set(index, placingEntry);
        dockIdx = index;
        rebuildDock(preview);
    }

    /** All D-pad handling while placing a newly pinned app. Swallows
     *  everything but Left/Right/OK/Back so the dock can't be left
     *  half-chosen by wandering into the grid mid-placement. */
    private boolean handlePlacingKey(int k) {
        int n = dockBaseline.size();
        if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (dockIdx > 0) { updatePlacementPreview(dockIdx - 1); applyFocus(true); }
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (dockIdx < n - 1) { updatePlacementPreview(dockIdx + 1); applyFocus(true); }
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            confirmPlacement();
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            cancelPlacement();
            return true;
        }
        return true;
    }

    private void confirmPlacement() {
        placing = false;
        List<String> pkgs = new ArrayList<>();
        for (AppEntry e : dockOrder) pkgs.add(e.pkg);
        DockConfig.save(this, pkgs);
        Toast.makeText(this, "Pinned", Toast.LENGTH_SHORT).show();
        applyFocus(false);
    }

    private void cancelPlacement() {
        placing = false;
        rebuildDock(dockBaseline);
        applyFocus(false);
    }

    private Tile makeTile(AppEntry e, int w, int h) {
        final Tile t = new Tile(this, e, w, h, outfit);
        t.setNameColor(tileNameColor());
        t.setId(View.generateViewId());
        t.setOnClickListener(v -> safeStart(e.launch));
        t.setOnFocusChangeListener((v, has) -> {
            if (!has) return;
            lastFocusedTile = v;
            int di = dockTiles.indexOf(v);
            if (di >= 0) {
                dockIdx = di;
                setDockTitle(((Tile) v).entry);
                dockTitle.animate().alpha(1f).setDuration(160).start();
            } else {
                gridIdx = Math.max(0, gridTiles.indexOf(v));
                dockTitle.animate().alpha(0f).setDuration(160).start();
            }
            fadeHighlight(true);
            moveHighlight(v, true);
        });
        return t;
    }

    // ------------------------------------------------------- remote keys

    /**
     * Remote buttons this launcher answers while Home is in front.
     *
     * These are plain key events delivered to the focused window, so no
     * accessibility key-filter service is involved - which matters,
     * because a bound key-filtering service can swallow key *repeats* and
     * turn a ramping volume into one step per press.  The cost is that
     * these only work on Home, not inside other apps.
     */
    private void loadKeyConfig() {
        settingsKeys = parseInts(getString(R.string.settings_key_keycodes));
        inputKeys = parseInts(getString(R.string.input_key_keycodes));
        for (String pair : getString(R.string.app_keys).split(",")) {
            pair = pair.trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                appKeys.put(Integer.parseInt(pair.substring(0, eq).trim()),
                            pair.substring(eq + 1).trim());
            } catch (NumberFormatException ignored) {}
        }
    }

    private static int[] parseInts(String csv) {
        if (csv == null || csv.trim().isEmpty()) return new int[0];
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            try { out[n++] = Integer.parseInt(p.trim()); } catch (NumberFormatException ignored) {}
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private boolean handleRemoteKey(int k) {
        if (contains(settingsKeys, k)) {
            safeStart(new Intent(Settings.ACTION_SETTINGS));
            return true;
        }
        if (contains(inputKeys, k)) {
            if (inputsBtn != null) InputsPopup.show(this, inputsBtn, outfit);
            return true;
        }
        String pkg = appKeys.get(k);
        if (pkg != null) {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) { safeStart(i); return true; }
        }
        return false;
    }

    // ------------------------------------------------------ navigation

    /**
     * All D-pad movement is resolved here, so every press has exactly one
     * defined outcome: Up/Down move one row, the ends of a row and of the
     * top bar stay put rather than wrapping or falling through, and the
     * dock and grid each remember the tile you left.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            int k = ev.getKeyCode();
            if (placing) return handlePlacingKey(k);
            if (handleRemoteKey(k)) return true;
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                    || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (navigate(k)) return true;
            }
        }
        return super.dispatchKeyEvent(ev);
    }

    private boolean navigate(int key) {
        int nGrid = gridTiles.size();
        int nDock = dockTiles.size();
        int nPill = pills.size();

        switch (zone) {
            case ZONE_BAR:
                // The clock and the weather block are the top bar's two
                // stops; Down drops back onto the pill row beneath them.
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) barIdx = Math.max(0, barIdx - 1);
                else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) barIdx = Math.min(1, barIdx + 1);
                else if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    zone = nPill > 0 ? ZONE_TOP : ZONE_DOCK;
                }
                // Up from the top bar does nothing; it must not wrap.
                break;

            case ZONE_TOP:
                if (key == KeyEvent.KEYCODE_DPAD_LEFT)  pillIdx = Math.max(0, pillIdx - 1);
                else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) pillIdx = Math.min(nPill - 1, pillIdx + 1);
                else if (key == KeyEvent.KEYCODE_DPAD_UP && timeWidget != null) zone = ZONE_BAR;
                else if (key == KeyEvent.KEYCODE_DPAD_DOWN && nDock > 0) zone = ZONE_DOCK;
                break;

            case ZONE_DOCK:
                if (key == KeyEvent.KEYCODE_DPAD_LEFT)  dockIdx = Math.max(0, dockIdx - 1);
                else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) dockIdx = Math.min(nDock - 1, dockIdx + 1);
                else if (key == KeyEvent.KEYCODE_DPAD_UP && nPill > 0) zone = ZONE_TOP;
                else if (key == KeyEvent.KEYCODE_DPAD_DOWN && nGrid > 0) {
                    zone = ZONE_GRID;
                    // Enter the grid under the dock tile you were on.
                    gridIdx = clamp(dockIdx, 0, Math.min(columns, nGrid) - 1);
                }
                break;

            case ZONE_GRID: {
                int col = gridIdx % columns;
                int row = gridIdx / columns;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (col > 0) gridIdx--;
                } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (col < columns - 1 && gridIdx + 1 < nGrid) gridIdx++;
                } else if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    if (row > 0) gridIdx -= columns;
                    else { zone = ZONE_DOCK; dockIdx = clamp(col, 0, nDock - 1); }
                } else if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (gridIdx + columns < nGrid) gridIdx += columns;
                    else {
                        // Last row: step to the final tile if it is short.
                        int lastRowStart = ((nGrid - 1) / columns) * columns;
                        if (row < (nGrid - 1) / columns) gridIdx = nGrid - 1;
                        else return true;
                    }
                }
                break;
            }
        }
        applyFocus(true);
        return true;
    }

    /** Move real focus and the viewport to wherever the model now points. */
    private void applyFocus(boolean animate) {
        View target = null;
        switch (zone) {
            case ZONE_BAR:
                target = clamp(barIdx, 0, 1) == 0 ? timeWidget : weatherView;
                break;
            case ZONE_TOP:
                if (!pills.isEmpty()) target = pills.get(clamp(pillIdx, 0, pills.size() - 1));
                break;
            case ZONE_DOCK:
                if (!dockTiles.isEmpty())
                    target = dockTiles.get(clamp(dockIdx, 0, dockTiles.size() - 1));
                break;
            case ZONE_GRID:
                if (!gridTiles.isEmpty())
                    target = gridTiles.get(clamp(gridIdx, 0, gridTiles.size() - 1));
                break;
        }
        if (target == null) return;
        target.requestFocus();
        if (zone == ZONE_GRID) scrollToShow(target); else scrollToTop();
        if (!animate) moveHighlight(target, false);
    }

    // -------------------------------------------------------- highlight

    private void moveHighlight(View target, boolean animate) {
        if (!(target instanceof Tile)) return;
        View art = ((Tile) target).artView();
        int[] r = new int[2], a = new int[2];
        root.getLocationInWindow(r);
        art.getLocationInWindow(a);
        int pad = dp(9);
        int w = Math.round(art.getWidth() * 1.08f) + pad * 2;
        int h = Math.round(art.getHeight() * 1.08f) + pad * 2;
        // getLocationInWindow() reports the *scaled* top-left - the focus
        // scale-up is part of the view's transformation matrix - so the
        // half-extent must be scaled to match, otherwise the plate lands
        // up-left of the tile by width*(scale-1)/2.
        float cx = a[0] - r[0] + art.getWidth() * art.getScaleX() / 2f;
        float cy = a[1] - r[1] + art.getHeight() * art.getScaleY() / 2f;
        final float x = cx - w / 2f, y = cy - h / 2f;

        if (w != hlW || h != hlH) {
            hlW = w; hlH = h;
            ViewGroup.LayoutParams lp = highlight.getLayoutParams();
            lp.width = w; lp.height = h;
            highlight.setLayoutParams(lp);
        }
        if (hlAnim != null) hlAnim.cancel();
        if (!animate) {
            highlight.setTranslationX(x);
            highlight.setTranslationY(y);
            return;
        }
        final float x0 = highlight.getTranslationX(), y0 = highlight.getTranslationY();
        hlAnim = ValueAnimator.ofFloat(0f, 1f);
        hlAnim.setDuration(230);
        hlAnim.setInterpolator(new OvershootInterpolator(1.4f));
        hlAnim.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            highlight.setTranslationX(x0 + (x - x0) * f);
            highlight.setTranslationY(y0 + (y - y0) * f);
        });
        hlAnim.start();
    }

    private void fadeHighlight(boolean show) {
        highlight.animate().alpha(show ? 1f : 0f).setDuration(140).start();
    }

    /** The glass plate only ever tracks a Tile, so a top-bar widget taking
     *  focus parks the plate and forgets the last tile rather than leaving
     *  a stale glow behind it. */
    private void onWidgetFocus() {
        lastFocusedTile = null;
        fadeHighlight(false);
    }

    // ----------------------------------------------------------- scroll

    private void scrollToTop() {
        if (scroll.getScrollY() != 0) scroll.smoothScrollTo(0, 0);
    }

    private void scrollToShow(View v) {
        // Park the focused row near the top: enough of an offset that the
        // dock scrolls fully away instead of leaving a clipped sliver.
        int top = topInContent(v);
        int target = Math.max(0, top - dp(70));
        if (Math.abs(scroll.getScrollY() - target) > dp(4)) scroll.smoothScrollTo(0, target);
    }

    private int topInContent(View v) {
        int y = 0;
        View cur = v;
        while (cur != null && cur != content) {
            y += cur.getTop();
            if (!(cur.getParent() instanceof View)) break;
            cur = (View) cur.getParent();
        }
        return y;
    }

    // ------------------------------------------------------------ misc

    private void setDockTitle(AppEntry e) {
        dockTitle.setText(e.label);
        int glow = (e.accent & 0x00FFFFFF) | 0xCC000000;
        dockTitle.setShadowLayer(24f, 0f, 0f, glow);
    }

    /** Light text (the default) or dark, for a bright wallpaper - toggled
     *  in the Wallpaper panel. Flips the overlay text and, with it, the
     *  frosted-glass surfaces: the time/weather cards, dock band, pills
     *  and shared focus plate. Each of those takes the tint that is the
     *  inverse of the text colour - light text on a dark tint, dark text
     *  on a light one - so text and backdrop always contrast. Called once
     *  in buildUi() and again on every resume, so a change made in the
     *  panel shows up next time Home is back in front. */
    private void applyTextTheme() {
        boolean dark = WallpaperConfig.isDarkText(this);
        greetView.setTextColor(dark ? 0xB3000000 : 0xB3FFFFFF);
        clockView.setTextColor(dark ? 0xFF000000 : 0xFFFFFFFF);
        dateView.setTextColor(dark ? 0x8C000000 : 0x8CFFFFFF);
        dockTitle.setTextColor(dark ? 0xFF000000 : 0xFFFFFFFF);

        // Backdrops and focus plates are frosted glass tinted against the
        // text, not with it: dark text wants a light (white) tint, light
        // text a dark one. Pairing dark text with a dark tint is the one
        // combination that leaves nothing readable.
        for (IconButton p : pills) p.setTheme(dark);
        if (weatherView != null) weatherView.setTheme(dark);
        int backdrop = dark ? R.drawable.widget_bg : R.drawable.widget_bg_dark;
        if (timeWidget != null) timeWidget.setBackgroundResource(backdrop);
        if (weatherView != null) weatherView.setBackgroundResource(backdrop);
        dockBand.setBackgroundResource(dark ? R.drawable.dock_band : R.drawable.dock_band_dark);
        highlight.setBackgroundResource(dark ? R.drawable.glass_highlight : R.drawable.glass_highlight_dark);

        int nameColor = tileNameColor();
        for (Tile t : dockTiles) t.setNameColor(nameColor);
        for (Tile t : gridTiles) t.setNameColor(nameColor);
    }

    private int tileNameColor() {
        return WallpaperConfig.isDarkText(this) ? 0xFF1A1A1A : 0xFFE8E8E8;
    }

    private void updateClock() {
        Date now = new Date();
        // Two shapes only: 24-hour, or 12-hour with an AM/PM suffix that
        // is drawn smaller so the number stays the dominant element. The
        // initial choice follows the system setting until the widget is
        // tapped (see ClockConfig).
        if (ClockConfig.is24Hour(this)) {
            clockView.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        } else {
            String s = new SimpleDateFormat("h:mm a", Locale.getDefault()).format(now);
            SpannableString span = new SpannableString(s);
            int i = s.lastIndexOf(' ');
            if (i >= 0) {
                span.setSpan(new RelativeSizeSpan(0.42f), i + 1, s.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            clockView.setText(span);
        }
        dateView.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        String g = getString(R.string.greeting);
        if (g == null || g.isEmpty()) {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            g = hour < 5 ? "Good night" : hour < 12 ? "Good morning"
                    : hour < 18 ? "Good afternoon" : "Good evening";
        }
        greetView.setText(g);
    }

    // ---------------------------------------------------------- weather

    /** Called on resume and on each minute tick: repaints from cache and,
     *  if that cache has gone stale (30 minutes, see Weather.FRESH_MILLIS),
     *  kicks off a background refresh. force=true skips the freshness check
     *  and is used by the C/F toggle, which must reload immediately. */
    private void maybeRefreshWeather() { maybeRefreshWeather(false); }

    /** Ask for coarse location once, so the widget can use the TV's own
     *  fused/network fix instead of an IP lookup. A denial is fine: the
     *  widget quietly falls back to the IP services. */
    private void requestLocationIfNeeded() {
        if (askedLocation) return;
        askedLocation = true;
        String perm = android.Manifest.permission.ACCESS_COARSE_LOCATION;
        if (checkSelfPermission(perm)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        try {
            requestPermissions(new String[]{perm}, 1);
        } catch (Throwable ignored) {}
    }

    private void maybeRefreshWeather(boolean force) {
        // Automatic refreshes (resume / minute tick) wait for the 30-minute
        // cache to expire AND for the post-failure backoff to elapse, so a
        // failed fetch is not retried every minute. A forced refresh (the
        // C/F tap) is user-initiated and skips both.
        if (!force && (weatherFetching || Weather.isFresh(this, weather)
                || !Weather.canAttempt(this))) return;
        if (!force && weather != null) {
            // The 30-minute cache has expired: drop the stale reading so
            // nothing older than that is ever painted, then reload. The
            // geolocated location is kept, so the refresh still knows where
            // to look.
            Weather.clearReading(this);
            weather = null;
            if (weatherView != null) weatherView.setData(null);
        }
        weatherFetching = true;
        // Generation token: if a forced refresh starts while an older fetch
        // is still in flight, the older callback is ignored so a stale
        // reading can never land after the newer one.
        final int gen = ++weatherGen;
        Weather.fetch(this, d -> {
            if (gen != weatherGen) return;
            weatherFetching = false;
            if (d == null) {
                // Network failure: drop the reading and show the offline
                // state. The location survives for the next attempt.
                Weather.clearReading(this);
                weather = null;
                if (weatherView != null) {
                    weatherView.setError(Weather.cachedCity(this));
                }
                return;
            }
            weather = d;
            if (weatherView != null) weatherView.setData(d);
        });
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    private void style(TextView t, float sp, int color, int weight) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (outfit != null) t.setTypeface(outfit);
        try { t.setFontVariationSettings("'wght' " + weight); } catch (Throwable ignored) {}
        t.setIncludeFontPadding(false);
    }

    private int dp(int v) { return Math.round(v * density); }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // --------------------------------------------------------- lifecycle

    @Override protected void onResume() {
        super.onResume();
        requestLocationIfNeeded();
        registerReceiver(timeTick, new IntentFilter(Intent.ACTION_TIME_TICK));
        updateClock();
        // Re-applies from cache every time (cheap - already downsampled),
        // since WallpaperActivity may have changed the source, color or
        // text theme while we were away. maybeScheduledRefresh only does
        // network work if Unsplash is active and its schedule has elapsed -
        // when it does, that happens on a background thread, so its own
        // callback re-syncs the text theme too rather than relying on the
        // applyTextTheme() below, which would otherwise run before that
        // download (and the auto theme pick apply() makes from it) lands.
        WallpaperManager.apply(this, root);
        WallpaperManager.maybeScheduledRefresh(this, () -> {
            WallpaperManager.apply(this, root);
            applyTextTheme();
        });
        applyTextTheme();

        weather = Weather.cached(this);
        if (weatherView != null) {
            // If the last attempt failed, keep showing the offline state
            // rather than a blank placeholder while the backoff runs.
            if (weather == null && Weather.lastAttemptFailed(this)) {
                weatherView.setError(Weather.cachedCity(this));
            } else {
                weatherView.setData(weather);
            }
        }
        maybeRefreshWeather();
    }

    /**
     * Focus is re-asserted here rather than in onResume: the framework
     * restores its own idea of the focused view after onResume runs, so
     * anything we set there is overwritten and Home comes back on
     * whatever grid tile happened to be focused last.
     */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFocus(false);
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(timeTick); } catch (Throwable ignored) {}
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        zone = ZONE_DOCK;
        applyFocus(false);
    }

    @Override public void onBackPressed() {
        // Home is the bottom of the stack; Back returns to the dock.
        zone = ZONE_DOCK;
        applyFocus(false);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        WallpaperManager.detachHome(root);
    }
}
