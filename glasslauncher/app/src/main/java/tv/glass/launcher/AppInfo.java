package tv.glass.launcher;

import android.content.ComponentName;
import android.content.pm.ResolveInfo;

/**
 * Record describing one launchable app on the home shelf.
 *
 * <p>The identity fields ({@link #packageName}, {@link #sourceLabel},
 * {@link #component}) are immutable. {@link #label} is the effective
 * user-visible label and may be replaced by a local launcher override;
 * {@link #ri} and {@link #displayLabel} are mutable per-instance caches.
 *
 * <p>Hot-path access pattern:
 * <ul>
 *   <li>{@link #packageName} — keys for icon caches, the keymap
 *       SparseArray, the hidden-apps ArraySet, and the app-list
 *       persisted-order map.</li>
 *   <li>{@link #label} — drawn directly under each cell's icon.</li>
 *   <li>{@link #component} — fast-path launch via
 *       {@code Intent.setComponent} (skips PackageManager resolution
 *       binder hops; saves 50-200 ms per launch on slow ROMs).</li>
 *   <li>{@link #ri} — used by {@code preWarmIcon} / {@code loadIconAsync}
 *       to call {@link ResolveInfo#loadIcon}. May be {@code null} for
 *       AppInfo instances reconstructed from the on-disk
 *       {@link AppListCache} — those entries fall back to
 *       {@code PackageManager.getActivityIcon(component)}.</li>
 * </ul>
 *
 * <p>This class moved out of {@link LauncherActivity} in v1.4.0 so cache
 * helpers can construct entries without reaching into an activity-owned
 * nested type. It remains package-private because only launcher internals
 * consume it.
 */
final class AppInfo {
    static final String TV_INPUT_PREFIX = "tvinput://";

    final String        packageName;
    /** Label supplied by PackageManager or the TV Input Framework. */
    final String        sourceLabel;
    /** Effective launcher label; source label unless the user renamed this entry. */
    volatile String     label;
    final ComponentName component;
    /**
     * The platform {@link ResolveInfo} for this app, when known.
     *
     * <p>Non-final and {@code volatile} so the {@link LauncherActivity}
     * reconcile path can graft a fresh {@code ri} onto a cache-sourced
     * AppInfo that was reconstructed from {@link AppListCache} (which
     * cannot serialise ResolveInfo and therefore stores it as
     * {@code null}). Without the graft, every icon load for that
     * AppInfo falls through to the slower
     * {@link android.content.pm.PackageManager#getActivityIcon(android.content.ComponentName)}
     * binder path forever — even after the post-launch PM scan has
     * resolved the activity.
     *
     * <p>Volatile because the field is read on the
     * {@code iconExecutor} worker thread (inside
     * {@link LauncherActivity}'s {@code resolveIconDrawable}) and
     * written on the UI thread (inside the {@code loadApps} reconcile).
     * The volatile semantics give the worker an immediate visibility
     * guarantee on the upgraded value; absent it, a worker thread
     * already running could observe the stale {@code null} for an
     * arbitrarily long window after the upgrade and still hit the slow
     * fallback. Worst case is identical to the pre-graft behaviour
     * (slow path), but volatile makes the upgrade reliable rather than
     * timing-dependent.
     */
    volatile ResolveInfo ri;

    /**
     * Memoised, ellipsised label as drawn under the shelf cell — lazily
     * computed and cached the first time any {@code CellView} binds this
     * app, then reused by every subsequent bind.
     *
     * <p>The truncation result is a pure function of the effective label,
     * shelf cell's width budget, label text size, and typeface. These inputs
     * stay fixed between rename/configuration events, and both events clear
     * this memo before cells repaint. There is
     * therefore no reason for a recycled cell that scrolls back onto a
     * previously-seen app during a fast fling to re-run
     * {@link android.text.TextUtils#ellipsize} (which allocates a
     * {@code CharSequence} + a {@code String}) or even
     * {@code Paint.measureText}. Caching the result on the model collapses
     * that to one measure + at-most-one ellipsize per app for the whole
     * session.
     *
     * <p>{@code null} means "not computed yet". For live entries the field
     * is read and written only on the UI thread by cell binding, rename, and
     * configuration-change paths. Background scans may clear it only on new
     * AppInfo objects before those objects are published to the UI, so no
     * synchronization is required — unlike {@link #ri}, which workers read
     * after publication.
     */
    String displayLabel;

    /**
     * Non-null only for a TV-input "app" (HDMI 1, AV, tuner, …) surfaced from
     * the platform TV Input Framework. When set, this entry is launched by
     * switching to the passthrough input via {@code TvContract} rather than by
     * starting an activity, its banner tile is a generated input glyph, and
     * the "Uninstall" / "App info" context-menu rows are suppressed (an input
     * is not an installed package). Everything else — ordering, home/drawer
     * placement, reorder, hide, remote-key binding — treats it exactly like a
     * normal app. {@code null} for ordinary apps. See {@link TvInputs}.
     */
    final String tvInputId;

    AppInfo(String pkg, String lbl, ComponentName cmp, ResolveInfo r) {
        this(pkg, lbl, cmp, r, null);
    }

    private AppInfo(String pkg, String lbl, ComponentName cmp, ResolveInfo r, String tvInput) {
        packageName = pkg;
        sourceLabel = lbl;
        label       = lbl;
        component   = cmp;
        ri          = r;
        tvInputId   = tvInput;
    }

    /** Apply a sanitized local name, or restore the source label when null. */
    void setCustomLabel(String customLabel) {
        label = customLabel != null ? customLabel : sourceLabel;
        displayLabel = null;
    }

    /** Build a TV-input entry. The synthetic {@link #packageName}
     *  ({@code "tvinput://" + inputId}) is the stable key used everywhere a
     *  package name is — order persistence, the hidden set, the icon/banner
     *  caches, remote-key bindings — so an input round-trips through all of
     *  them like any other app. It can never collide with a real package
     *  (real package names are never of this shape) and contains no comma, so
     *  it is safe inside the comma-separated persisted order string. */
    static AppInfo tvInput(String inputId, String label) {
        return new AppInfo(TV_INPUT_PREFIX + inputId, label, null, null, inputId);
    }

    static boolean isTvInputIdentity(String identity) {
        return identity != null && identity.startsWith(TV_INPUT_PREFIX)
                && identity.length() > TV_INPUT_PREFIX.length();
    }
}
