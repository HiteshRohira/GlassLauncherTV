package tv.glass.launcher;

/**
 * The surface that currently owns the shared reorder context menu (the
 * "Hide / Change icon / Reset icon / Rename / App Info / Uninstall / Move"
 * plate). Either the bottom home shelf
 * ({@code RecyclingShelfView}) or the pull-down app drawer
 * ({@code AppDrawer}) can be the active reorder host at any one time — never
 * both, since the drawer is only reorder-able while it is open (covering the
 * shelf).
 *
 * <p>Introduced in v1.5.0 so the single lazily-built menu overlay
 * ({@code LauncherActivity.ensureMenuOverlay}) and its highlight logic
 * ({@code updateMenuHighlight}) can drive whichever surface is reordering
 * without hard-wiring to the shelf. The activity holds the current host in
 * {@code menuHost}; the menu's click listeners and the highlight painter call
 * through this interface instead of reaching into the shelf directly.
 */
interface ReorderHost {

    /** Current menu cursor — one of Hide, Change icon, Reset icon, Rename,
     *  Uninstall, App info, or Move. Read by the highlight painter. */
    int menuSelection();

    /** The user activated the Hide row (hides the app from the shelf/drawer;
     *  it stays installed and listed in the Manage-hidden-apps screen). */
    void onMenuHide();

    /** The user activated the Uninstall row (touch click or D-pad confirm). */
    void onMenuUninstall();

    /** The user activated the App Info row. */
    void onMenuAppInfo();

    /** The user activated Change icon and should be shown the local image picker. */
    void onMenuChangeIcon();

    /** The user activated Reset icon and should return to the default artwork. */
    void onMenuResetIcon();

    /** The user activated Rename and should be shown the local-name dialog. */
    void onMenuRename();

    /** The user activated the Move row (confirm reorder). */
    void onMenuMove();

    /** Whether the selected app or input currently has persistent custom artwork. */
    boolean menuAppHasCustomIcon();

    /** {@code true} when the selected entry is a TV input (HDMI/AV/…).
     *  The activity suppresses only Uninstall and App info; local icon,
     *  rename, Move, and Hide actions remain available. */
    boolean menuAppIsInput();
}
