package com.oxylab.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builds the list of launchable apps. */
final class AppRepo {

    /**
     * Leanback entries first (those have proper 16:9 banners), then any
     * remaining package that only declares a phone-style LAUNCHER entry -
     * sideloaded apps such as Tailscale would otherwise be unreachable.
     *
     * Both queries need the matching <queries> block in the manifest or
     * Android 11+ package visibility returns an empty list.
     */
    static List<AppEntry> load(Context ctx, Set<String> hidden) {
        PackageManager pm = ctx.getPackageManager();
        List<AppEntry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>(hidden);
        seen.add(ctx.getPackageName());

        collect(pm, out, seen, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        collect(pm, out, seen, Intent.CATEGORY_LAUNCHER, false);

        final Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label, b.label));
        return out;
    }

    /** Display name for a package, or the package name itself if it can't
     *  be resolved (e.g. it was just uninstalled). */
    static String label(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private static void collect(PackageManager pm, List<AppEntry> out,
                                Set<String> seen, String category, boolean leanback) {
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(probe, 0);
        } catch (Throwable t) {
            return;
        }
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.applicationInfo == null) continue;
            String pkg = ai.packageName;
            if (!seen.add(pkg)) continue;

            Drawable art = null;
            boolean wide = false;
            if (leanback) {
                try { art = ai.loadBanner(pm); } catch (Throwable ignored) {}
                if (art == null) {
                    try { art = ai.applicationInfo.loadBanner(pm); } catch (Throwable ignored) {}
                }
                wide = art != null;
            }
            if (art == null) {
                try { art = ri.loadIcon(pm); } catch (Throwable ignored) {}
            }

            String label;
            try {
                CharSequence cs = ri.loadLabel(pm);
                label = cs == null ? pkg : cs.toString();
            } catch (Throwable t) {
                label = pkg;
            }

            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER
                                          : Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            AppEntry e = new AppEntry(pkg, label, art, launch, wide);
            e.accent = ColorSampler.accentOf(art);
            out.add(e);
        }
    }

    private AppRepo() {}
}
