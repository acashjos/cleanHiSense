package com.oxylab.tvhome;

import android.content.Context;
import android.graphics.Rect;
import android.widget.ScrollView;

/**
 * A ScrollView that never scrolls itself when a child takes focus.
 *
 * The stock behaviour fights the launcher: focus and scrolling are driven
 * explicitly from HomeActivity so that Up/Down always move exactly one
 * row, so ScrollView's own "bring the focused child on screen" pass has
 * to be switched off or the viewport jumps before our animation runs.
 */
class NoAutoScrollView extends ScrollView {
    NoAutoScrollView(Context c) { super(c); }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }
}
