package com.oxylab.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * The "Wallpaper" screen in the quick-settings panel: a master on/off,
 * then one of three mutually-exclusive sources (Unsplash, the images in
 * Pictures/Wallpapers, or a solid color). Switching sources never
 * discards the other two's settings - each remembers its own state
 * independently.
 */
public class WallpaperActivity extends Activity {

    private static final int REQ_PERM = 72;

    private static final int[] PALETTE = {
            // No pure black - that's what "off" already means. No pure
            // white either: these are muted mid-tone companions to the
            // dark row below (dusty, not pastel-near-white), each kept
            // above ~0.6 luminance so WallpaperManager's auto dark-text
            // detection picks them up reliably.
            0xFF14213D, 0xFF2B2D42, 0xFF1B4332, 0xFF3C1642,
            0xFF4A1A1A, 0xFF1E3A5F, 0xFF3D2C1F, 0xFF0F3D3E,
            0xFF8CA5C8, 0xFFBEA0BE, 0xFFA5C3A0, 0xFFD2A0A5,
            0xFFDCAF96, 0xFFAAAFD7, 0xFFD7C38C, 0xFF96C8BE,
    };

    // refresh() rebuilds the whole body, which would otherwise always
    // snap focus back to the top. body's children are always in this fixed
    // order, so every action sets pendingFocusIndex to where the user should
    // land - the row itself for the three mode rows, or IDX_SECTION (whose
    // first focusable descendant gets it, since the section is a container,
    // not itself focusable) for anything inside the current mode's settings.
    // IDX_TOP holds two focusable cells (refresh, toggle), so
    // pendingDescendantIndex picks which one.
    private static final int IDX_TOP = 0, IDX_SCHEDULE = 1, IDX_DARK_TEXT = 2, IDX_TITLE = 3,
            IDX_UNSPLASH = 4, IDX_FILE = 5, IDX_COLOR = 6, IDX_SECTION = 7;
    private int pendingFocusIndex = IDX_TOP;
    // When >= 0, restoreFocus lands on this focusable descendant of the
    // target section instead of its first one - keeps the D-pad cursor on
    // the swatch/file row the user just picked instead of snapping to 0.
    private int pendingDescendantIndex = -1;

    private float density;
    private Typeface outfit;
    private LinearLayout body;
    // Ask for storage access at most once per screen; if denied, the folder
    // just reads as empty rather than re-prompting on every rebuild.
    private boolean permAsked;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        try { outfit = getResources().getFont(R.font.outfit); } catch (Throwable ignored) {}

        FrameLayout root = new FrameLayout(this);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundResource(R.drawable.panel_bg);
        shell.setPadding(dp(20), dp(24), dp(20), dp(20));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                dp(400), ViewGroup.LayoutParams.MATCH_PARENT);
        clp.gravity = Gravity.END;
        root.addView(shell, clp);

        TextView title = new TextView(this);
        title.setText("Wallpaper");
        style(title, 22f, 0xFFFFFFFF, 300);
        title.setPadding(0, 0, 0, 6);
        shell.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Choose what shows behind the Home screen.");
        style(desc, 13f, 0x99FFFFFF, 400);
        desc.setPadding(0, 0, 0, dp(16));
        shell.addView(desc);

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        shell.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // requestFocus() during refresh() can land before the panel window
        // actually holds focus, leaving nothing focused until the first
        // D-pad press. Once focus is here, grab the default target (Refresh
        // now) if nothing is focused yet.
        if (hasFocus && getCurrentFocus() == null) {
            body.post(this::focusFirst);
        }
    }

    // ------------------------------------------------------------ build

    private void refresh() {
        body.removeAllViews();

        boolean enabled = WallpaperConfig.isEnabled(this);
        body.addView(topRow(enabled), rowLp());

        if (!enabled) {
            TextView off = new TextView(this);
            off.setText("Off - Home is plain black. Turn this on to choose a source.");
            style(off, 13f, 0x8CFFFFFF, 400);
            off.setPadding(dp(2), dp(4), 0, 0);
            body.addView(off);
            body.post(this::focusFirst);
            return;
        }

        // Second row: the refresh schedule, promoted to the top since it's a
        // common setting shared by every source.
        LinearLayout.LayoutParams schedLp = rowLp();
        schedLp.topMargin = dp(10);
        body.addView(scheduleRow(), schedLp);

        // Also common to every source: whether Home's overlay text (clock,
        // greeting, dock title) is light or dark, for a bright wallpaper.
        boolean darkText = WallpaperConfig.isDarkText(this);
        body.addView(toggleRow("Dark text", darkText, v -> {
            WallpaperConfig.setDarkText(this, !darkText);
            // Not markChanged(): this doesn't touch the displayed
            // wallpaper, so the refresh schedule has nothing to restart.
            WallpaperManager.notifyChanged();
            pendingFocusIndex = IDX_DARK_TEXT;
            refresh();
        }), rowLp());

        TextView source = new TextView(this);
        source.setText("Source");
        style(source, 12f, 0x99FFFFFF, 600);
        source.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams titleLp = rowLp();
        titleLp.topMargin = dp(18);
        titleLp.bottomMargin = dp(2);
        titleLp.leftMargin = dp(2);
        body.addView(source, titleLp);

        String mode = WallpaperConfig.getMode(this);

        LinearLayout.LayoutParams modeLp = rowLp();
        modeLp.topMargin = dp(8);
        body.addView(modeRow("Unsplash", WallpaperConfig.MODE_UNSPLASH.equals(mode)), modeLp);
        body.addView(modeRow("Custom image", WallpaperConfig.MODE_FILE.equals(mode)), rowLp());
        body.addView(modeRow("Solid color", WallpaperConfig.MODE_COLOR.equals(mode)), rowLp());

        LinearLayout.LayoutParams sectionLp = rowLp();
        sectionLp.topMargin = dp(16);
        if (WallpaperConfig.MODE_UNSPLASH.equals(mode)) {
            body.addView(unsplashSection(), sectionLp);
        } else if (WallpaperConfig.MODE_FILE.equals(mode)) {
            body.addView(fileSection(), sectionLp);
        } else {
            body.addView(colorSection(), sectionLp);
        }

        restoreFocus();
    }

    /** Lands focus at pendingFocusIndex (set by whatever action just
     *  triggered this rebuild) instead of always snapping back to the
     *  top toggle. That index's child is focused directly if it's
     *  focusable (the toggle, a mode row); otherwise (the mode-specific
     *  section, a container) its first focusable descendant gets it. */
    private void restoreFocus() {
        final int target = pendingFocusIndex;
        final int descendant = pendingDescendantIndex;
        pendingFocusIndex = IDX_TOP;
        pendingDescendantIndex = -1;
        body.post(() -> {
            View v = target < body.getChildCount() ? body.getChildAt(target) : null;
            if (v == null) { focusFirst(); return; }
            if (v.isFocusable()) { v.requestFocus(); return; }
            View f = null;
            if (v instanceof ViewGroup) {
                f = descendant >= 0
                        ? findFocusableDescendant((ViewGroup) v, descendant)
                        : findFocusableDescendant((ViewGroup) v);
            }
            if (f != null) f.requestFocus(); else focusFirst();
        });
    }

    // --------------------------------------------------------- rows

    /** Top row: "Refresh now" on the left, the Wallpaper on/off toggle on
     *  the right. They share one row because both are immediate actions -
     *  refresh is the default focus so the user can just hit OK. */
    private LinearLayout topRow(boolean enabled) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView refresh = new TextView(this);
        refresh.setText("⟳  Refresh now");
        style(refresh, 14f, enabled ? 0xFFF0F0F0 : 0x59FFFFFF, 500);
        refresh.setBackgroundResource(R.drawable.panel_item_bg);
        refresh.setFocusable(enabled);
        refresh.setFocusableInTouchMode(enabled);
        refresh.setGravity(Gravity.CENTER_VERTICAL);
        refresh.setPadding(dp(14), dp(13), dp(14), dp(13));
        if (enabled) refresh.setOnClickListener(v -> refreshNow());
        row.addView(refresh, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout toggle = new LinearLayout(this);
        toggle.setOrientation(LinearLayout.HORIZONTAL);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setBackgroundResource(R.drawable.panel_item_bg);
        toggle.setFocusable(true);
        toggle.setFocusableInTouchMode(true);
        toggle.setPadding(dp(14), dp(13), dp(14), dp(13));

        TextView name = new TextView(this);
        name.setText("Wallpaper");
        style(name, 14f, 0xFFF0F0F0, 500);
        toggle.addView(name);

        TextView state = new TextView(this);
        state.setText(enabled ? "On" : "Off");
        style(state, 13f, enabled ? 0xFFF0F0F0 : 0x73FFFFFF, 500);
        state.setPadding(dp(10), 0, 0, 0);
        toggle.addView(state);

        toggle.setOnClickListener(v -> {
            WallpaperConfig.setEnabled(this, !enabled);
            markChanged();
            pendingFocusIndex = IDX_TOP;
            pendingDescendantIndex = 1;
            refresh();
        });

        // Equal width with the refresh cell: both are weight 1 (the 8dp gap
        // is split out first, so the two cells still match).
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        row.addView(toggle, tlp);
        return row;
    }

    /** A full-width on/off row - same look as the Wallpaper toggle inside
     *  topRow(), just standalone instead of sharing a row with another
     *  cell. */
    private LinearLayout toggleRow(String label, boolean on, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.panel_item_bg);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setPadding(dp(14), dp(13), dp(14), dp(13));

        TextView name = new TextView(this);
        name.setText(label);
        style(name, 14f, 0xFFF0F0F0, 500);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView state = new TextView(this);
        state.setText(on ? "On" : "Off");
        style(state, 13f, on ? 0xFFF0F0F0 : 0x73FFFFFF, 500);
        row.addView(state);

        row.setOnClickListener(onClick);
        return row;
    }

    private TextView modeRow(final String label, final boolean active) {
        TextView row = new TextView(this);
        row.setText((active ? "✓  " : "    ") + label);
        style(row, 14f, active ? 0xFFF0F0F0 : 0xFFB3B3B3, active ? 600 : 400);
        row.setBackgroundResource(R.drawable.panel_item_bg);
        row.setFocusable(true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = rowLp();
        lp.bottomMargin = dp(2);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> {
            if (active) return;
            String target = "Unsplash".equals(label) ? WallpaperConfig.MODE_UNSPLASH
                    : "Custom image".equals(label) ? WallpaperConfig.MODE_FILE
                    : WallpaperConfig.MODE_COLOR;
            int idx = "Unsplash".equals(label) ? IDX_UNSPLASH
                    : "Custom image".equals(label) ? IDX_FILE
                    : IDX_COLOR;
            WallpaperConfig.setMode(this, target);
            markChanged();
            pendingFocusIndex = idx;
            refresh();
            if (WallpaperConfig.MODE_UNSPLASH.equals(target)
                    && WallpaperConfig.getCachedImagePath(this) == null) {
                startUnsplashRefresh();
            }
        });
        return row;
    }

    // ------------------------------------------------------- unsplash

    private LinearLayout unsplashSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView keyLabel = new TextView(this);
        keyLabel.setText("API key");
        style(keyLabel, 12f, 0x99FFFFFF, 600);
        LinearLayout.LayoutParams keyLabelLp = rowLp();
        keyLabelLp.bottomMargin = dp(4);
        keyLabelLp.leftMargin = dp(2);
        box.addView(keyLabel, keyLabelLp);

        final EditText keyField = fieldRow(WallpaperConfig.getApiKey(this), "Paste your Unsplash key", true);
        LinearLayout.LayoutParams keyLp = rowLp();
        keyLp.bottomMargin = dp(12);
        box.addView(keyField, keyLp);
        keyField.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) commitApiKey(keyField); });
        keyField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { v.clearFocus(); return true; }
            return false;
        });

        boolean hasConfigKey = WallpaperConfig.getApiKey(this).isEmpty()
                && !WallpaperConfig.getEffectiveApiKey(this).isEmpty();
        if (hasConfigKey) {
            TextView configNote = new TextView(this);
            configNote.setText("Using the key from config.xml - type one above to override it.");
            style(configNote, 11f, 0x73FFFFFF, 400);
            LinearLayout.LayoutParams cnlp = rowLp();
            cnlp.topMargin = dp(-8);
            cnlp.bottomMargin = dp(12);
            cnlp.leftMargin = dp(2);
            box.addView(configNote, cnlp);
        }

        TextView tagsLabel = new TextView(this);
        tagsLabel.setText("Search tags (optional)");
        style(tagsLabel, 12f, 0x99FFFFFF, 600);
        LinearLayout.LayoutParams tagsLabelLp = rowLp();
        tagsLabelLp.bottomMargin = dp(4);
        tagsLabelLp.leftMargin = dp(2);
        box.addView(tagsLabel, tagsLabelLp);

        final EditText tagsField = fieldRow(WallpaperConfig.getSearchQuery(this),
                "e.g. nature, city, mountains", false);
        LinearLayout.LayoutParams tagsLp = rowLp();
        tagsLp.bottomMargin = dp(10);
        box.addView(tagsField, tagsLp);
        tagsField.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) commitSearchQuery(tagsField); });
        tagsField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { v.clearFocus(); return true; }
            return false;
        });

        if (WallpaperConfig.getEffectiveApiKey(this).isEmpty()) {
            TextView notice = new TextView(this);
            notice.setText("Add a free Unsplash API key above to enable downloads - "
                    + "unsplash.com/developers, about 2 minutes, no review needed.");
            style(notice, 12f, 0x8CFFFFFF, 400);
            LinearLayout.LayoutParams nlp = rowLp();
            nlp.bottomMargin = dp(10);
            box.addView(notice, nlp);
        }

        String credit = WallpaperConfig.getCredit(this);
        if (credit != null) {
            TextView creditView = new TextView(this);
            creditView.setText(credit);
            style(creditView, 12f, 0x73FFFFFF, 400);
            LinearLayout.LayoutParams clp2 = rowLp();
            clp2.topMargin = dp(4);
            box.addView(creditView, clp2);
        }
        return box;
    }

    /** A single-line text field styled to match the panel rows.
     *  NO_SUGGESTIONS keeps autocorrect from fighting a pasted key or tag
     *  list. masked=true (the API key field) shows dots instead of the
     *  actual characters - a credential has no business being readable on
     *  screen, in a screenshot, or by anyone glancing at the TV. Dots
     *  sidestep the font question entirely (unlike the VISIBLE_PASSWORD
     *  variant tried earlier, which forced AOSP's monospace rendering). */
    private EditText fieldRow(String initial, String hint, boolean masked) {
        EditText e = new EditText(this);
        e.setText(initial);
        e.setHint(hint);
        e.setHintTextColor(0x66FFFFFF);
        e.setTextColor(0xFFF0F0F0);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        if (outfit != null) e.setTypeface(outfit);
        e.setSingleLine(true);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (masked ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        e.setBackgroundResource(R.drawable.panel_item_bg);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setFocusable(true);
        e.setFocusableInTouchMode(true);
        return e;
    }

    /** Persists the key. If this is the first key ever entered and Unsplash
     *  has no cached photo yet, kicks off a download immediately instead of
     *  making the user find the Refresh now button. */
    private void commitApiKey(EditText field) {
        String val = field.getText().toString().trim();
        boolean hadNone = WallpaperConfig.getApiKey(this).isEmpty();
        WallpaperConfig.setApiKey(this, val);
        if (!val.isEmpty() && hadNone
                && WallpaperConfig.MODE_UNSPLASH.equals(WallpaperConfig.getMode(this))
                && WallpaperConfig.getCachedImagePath(this) == null) {
            startUnsplashRefresh();
        }
    }

    private void commitSearchQuery(EditText field) {
        WallpaperConfig.setSearchQuery(this, field.getText().toString().trim());
    }

    private TextView chevron(String glyph) {
        TextView t = new TextView(this);
        t.setText(glyph);
        style(t, 20f, 0x99FFFFFF, 500);
        return t;
    }

    private LinearLayout scheduleRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.panel_item_bg);
        row.setFocusable(true);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText("Refresh schedule");
        style(label, 14f, 0xFFF0F0F0, 500);
        head.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Chevrons around the value hint that Left/Right changes it - the row
        // itself is one focus target, so the arrows are just signals.
        TextView left = chevron("‹");
        left.setPadding(0, 0, dp(4), 0);
        head.addView(left);

        final TextView value = new TextView(this);
        style(value, 13f, 0xB3FFFFFF, 400);
        head.addView(value);

        TextView right = chevron("›");
        right.setPadding(dp(4), 0, 0, 0);
        head.addView(right);
        row.addView(head);

        Runnable paint = () -> {
            int days = WallpaperConfig.getScheduleDays(this);
            value.setText(days <= 0 ? "Never" : days == 1 ? "Every day" : "Every " + days + " days");
        };
        paint.run();

        row.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            int days = WallpaperConfig.getScheduleDays(this);
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                days = Math.max(0, days - 1);
            } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                days = Math.min(364, days + 1);
            } else {
                return false;
            }
            WallpaperConfig.setScheduleDays(this, days);
            paint.run();
            return true;
        });
        return row;
    }

    private void startUnsplashRefresh() {
        Toast.makeText(this, "Downloading a new wallpaper…", Toast.LENGTH_SHORT).show();
        WallpaperManager.refreshUnsplash(this, (ok, message) -> {
            if (ok) {
                WallpaperManager.notifyChanged();
                Toast.makeText(this, "Wallpaper updated", Toast.LENGTH_SHORT).show();
                pendingFocusIndex = IDX_TOP;
                pendingDescendantIndex = 0;
                refresh();
            } else {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** The common "Refresh now" button: advances the current source's
     *  rotation immediately instead of waiting for the schedule. Unsplash
     *  downloads a fresh photo; the other two step to the next member. */
    private void refreshNow() {
        String mode = WallpaperConfig.getMode(this);
        if (WallpaperConfig.MODE_UNSPLASH.equals(mode)) {
            startUnsplashRefresh();
            return;
        }
        boolean advanced;
        if (WallpaperConfig.MODE_COLOR.equals(mode)) {
            advanced = WallpaperConfig.getSelectedColors(this).size() >= 2;
            WallpaperConfig.advanceColor(this);
        } else {
            advanced = WallpaperConfig.getFolderImages(this).size() >= 2;
            WallpaperConfig.advanceFile(this);
        }
        markChanged();
        pendingFocusIndex = IDX_TOP;
        pendingDescendantIndex = 0;
        refresh();
        if (!advanced) {
            Toast.makeText(this, WallpaperConfig.MODE_COLOR.equals(mode)
                    ? "Tick at least two colors to rotate"
                    : "Add more images to Pictures/" + WallpaperConfig.FOLDER_NAME,
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ file

    private LinearLayout fileSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        ensureStoragePermission();

        TextView label = new TextView(this);
        label.setText("Pictures/" + WallpaperConfig.FOLDER_NAME);
        style(label, 12f, 0x99FFFFFF, 400);
        label.setPadding(dp(2), 0, 0, dp(10));
        box.addView(label);

        List<String> files = WallpaperConfig.getFolderImages(this);
        if (files.isEmpty()) {
            File dir = WallpaperConfig.wallpaperFolder(this);
            TextView status = new TextView(this);
            status.setText(dir.exists()
                    ? "No images here are at least " + minW() + "×" + minH()
                      + ". Add bigger pictures and they'll show up automatically."
                    : "Put some pictures in Pictures/" + WallpaperConfig.FOLDER_NAME
                      + " on the TV and they'll show up automatically.");
            style(status, 12f, 0x8CFFFFFF, 400);
            box.addView(status);
            return box;
        }

        box.addView(previewRow(files), rowLp());

        TextView hint = new TextView(this);
        hint.setText(files.size() + (files.size() == 1 ? " image" : " images")
                + " in this folder - the refresh schedule cycles through them.");
        style(hint, 11f, 0x73FFFFFF, 400);
        LinearLayout.LayoutParams hlp = rowLp();
        hlp.topMargin = dp(2);
        box.addView(hint, hlp);
        return box;
    }

    /** Three overlapping thumbnails, then a "+N more" card when the folder
     *  holds more than three - enough to show what's in there without
     *  listing every file. Later views draw on top, so the overlap reads as a
     *  stack; a thin white border keeps each edge visible. */
    private LinearLayout previewRow(List<String> files) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        int shown = Math.min(3, files.size());
        int w = dp(96), h = dp(64), overlap = dp(18);
        for (int i = 0; i < shown; i++) {
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(previewBg());
            thumb.setPadding(dp(2), dp(2), dp(2), dp(2));
            Bitmap bmp = loadThumb(files.get(i));
            if (bmp != null) thumb.setImageBitmap(bmp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            if (i > 0) lp.leftMargin = -overlap;
            row.addView(thumb, lp);
        }

        int more = files.size() - shown;
        if (more > 0) {
            TextView card = new TextView(this);
            card.setText("+" + more + " more");
            card.setGravity(Gravity.CENTER);
            card.setTextColor(0xFFFFFFFF);
            card.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            if (outfit != null) card.setTypeface(outfit);
            card.setBackground(previewBg());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.leftMargin = -overlap;
            row.addView(card, lp);
        }
        return row;
    }

    private GradientDrawable previewBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x33FFFFFF);
        d.setCornerRadius(dp(8));
        d.setStroke(dp(2), 0xE6FFFFFF);
        return d;
    }

    /** Small RGB_565 thumbnail - the preview only needs to jog the memory,
     *  and three full photos decoded on the UI thread would hitch the panel. */
    private Bitmap loadThumb(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 256
                    && bounds.outHeight / (sample * 2) >= 128) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private int minW() { return getResources().getDisplayMetrics().widthPixels / 2; }
    private int minH() { return getResources().getDisplayMetrics().heightPixels / 2; }

    private void ensureStoragePermission() {
        String perm = android.os.Build.VERSION.SDK_INT >= 33
                ? android.Manifest.permission.READ_MEDIA_IMAGES
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !permAsked) {
            permAsked = true;
            requestPermissions(new String[]{perm}, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERM) {
            pendingFocusIndex = IDX_SECTION;
            refresh();
        }
    }

    // ----------------------------------------------------------- color

    private LinearLayout colorSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        List<Integer> selected = WallpaperConfig.getSelectedColors(this);
        int active = WallpaperConfig.getActiveColor(this);

        int perRow = 4;
        LinearLayout row = null;
        for (int i = 0; i < PALETTE.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(10);
                box.addView(row, rlp);
            }
            final int color = PALETTE[i];
            boolean isSelected = selected.contains(color);
            View swatch = colorSwatch(color, isSelected, isSelected && color == active, i);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(64), dp(64));
            if (i % perRow != 0) slp.leftMargin = dp(10);
            row.addView(swatch, slp);
        }

        TextView legend = new TextView(this);
        legend.setText("✓ in rotation   ● showing now");
        style(legend, 11f, 0x73FFFFFF, 400);
        LinearLayout.LayoutParams llp = rowLp();
        llp.topMargin = dp(2);
        box.addView(legend, llp);
        return box;
    }

    private View colorSwatch(final int color, boolean selected, boolean active, final int index) {
        // A TextView so the swatch can carry a mark: ✓ = included in the
        // rotation, ● = the one on screen right now. Focus stays a separate
        // channel (white ring) so the D-pad cursor is never confused with
        // selection, even after the schedule advances on its own.
        boolean lightSwatch = WallpaperManager.luminance(color) > 0.55f;
        TextView v = new TextView(this);
        v.setGravity(Gravity.CENTER);
        v.setText(active ? "●" : selected ? "✓" : "");
        v.setTextColor(lightSwatch ? 0xFF1A1A1A : 0xFFFFFFFF);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        if (outfit != null) v.setTypeface(outfit);
        v.setFocusable(true);
        v.setBackground(swatchBackground(color));
        v.setOnClickListener(click -> {
            WallpaperConfig.toggleColor(this, color);
            markChanged();
            pendingFocusIndex = IDX_SECTION;
            pendingDescendantIndex = index;
            refresh();
        });
        return v;
    }

    private StateListDrawable swatchBackground(int color) {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, swatchShape(color, true));
        sld.addState(new int[]{}, swatchShape(color, false));
        return sld;
    }

    private GradientDrawable swatchShape(int color, boolean focused) {
        boolean lightSwatch = WallpaperManager.luminance(color) > 0.55f;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(10));
        d.setColor(color);
        d.setStroke(focused ? dp(3) : 0, lightSwatch ? 0xFF1A1A1A : 0xFFFFFFFF);
        return d;
    }

    // ------------------------------------------------------------ misc

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        return lp;
    }

    /** Common tail for any UI change that should show up immediately: repaint
     *  Home now, and restart the refresh clock so the schedule counts from
     *  this explicit choice rather than an old timestamp. */
    private void markChanged() {
        // Order matters: notifyChanged() -> apply() reads lastRefreshMillis
        // to decide whether the wallpaper's content actually changed (for
        // auto dark-text detection). Set it first, or apply() sees the old
        // timestamp, assumes nothing changed, and skips recomputing.
        WallpaperConfig.setLastRefreshMillis(this, System.currentTimeMillis());
        WallpaperManager.notifyChanged();
    }

    private void focusFirst() {
        View v = findFocusableDescendant(body);
        if (v != null) v.requestFocus();
    }

    private View findFocusableDescendant(ViewGroup g) {
        return findFocusableDescendant(g, 0);
    }

    /** The nth focusable in traversal order, so a rebuilt section can land
     *  focus back on the exact swatch the user picked. */
    private View findFocusableDescendant(ViewGroup g, int index) {
        return findFocusableDescendant(g, index, new int[]{0});
    }

    private View findFocusableDescendant(ViewGroup g, int index, int[] seen) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.isFocusable()) {
                if (seen[0] == index) return c;
                seen[0]++;
            }
            if (c instanceof ViewGroup) {
                View f = findFocusableDescendant((ViewGroup) c, index, seen);
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
