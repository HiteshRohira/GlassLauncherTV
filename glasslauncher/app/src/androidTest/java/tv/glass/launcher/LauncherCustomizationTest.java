package tv.glass.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Device-level regressions for context-menu and drawer focus ownership. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class LauncherCustomizationTest {

    private static final long WAIT_TIMEOUT_MS = 8_000L;

    /** ActivityScenario closes the activity but intentionally keeps app data. */
    @Before
    public void clearCustomNamesBeforeTest() {
        clearPersistedCustomNames();
    }

    @After
    public void clearCustomNamesAfterTest() {
        clearPersistedCustomNames();
    }

    @Test
    public void contextMenu_forApp_exposesRename() {
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            View homeCell = focusHomeCell(scenario);
            scenario.onActivity(activity -> assertTrue(homeCell.performLongClick()));

            View rename = awaitVisibleViewField(scenario, "menuRename");
            assertNotNull(rename);
        }
    }

    @Test
    public void contextMenu_dimsAttachedCardsSynchronouslyAtFiveAndSixColumns() {
        Context context = getInstrumentation().getTargetContext();
        String prefsName = (String) staticField(LauncherActivity.class, "PREFS");
        String columnsKey = (String) staticField(
                LauncherActivity.class, "KEY_LAYOUT_COLUMNS");
        SharedPreferences preferences = context.getSharedPreferences(
                prefsName, Context.MODE_PRIVATE);
        boolean hadColumns = preferences.contains(columnsKey);
        int oldColumns = preferences.getInt(columnsKey, LayoutOptions.DEFAULT_COLUMNS);

        try {
            assertSynchronizedContextMenuDimming(preferences, columnsKey, 5);
            assertSynchronizedContextMenuDimming(preferences, columnsKey, 6);
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            if (hadColumns) restore.putInt(columnsKey, oldColumns);
            else restore.remove(columnsKey);
            assertTrue(restore.commit());
        }
    }

    @Test
    public void renameDialog_hasFocusedInputAndWorkingSaveResetButtons() {
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            View homeCell = focusHomeCell(scenario);
            AppInfo originalApp = (AppInfo) field(homeCell, "boundApp");
            String identity = originalApp.packageName;
            String sourceLabel = originalApp.sourceLabel;

            AlertDialog saveDialog = openRenameDialog(scenario, homeCell, false);
            scenario.onActivity(activity -> {
                assertSame(saveDialog, field(activity, "renameDialog"));
                EditText input = (EditText) field(activity, "renameInput");
                input.setText("Game Console");
                assertTrue(saveDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            });

            View renamedCell = awaitFocusedApp(scenario, "CellView", identity,
                    "Game Console");
            AlertDialog resetDialog = openRenameDialog(scenario, renamedCell, true);
            scenario.onActivity(activity -> {
                assertSame(resetDialog, field(activity, "renameDialog"));
                assertTrue(resetDialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
            });

            awaitFocusedApp(scenario, "CellView", identity, sourceLabel);
        }
    }

    @Test
    public void renameDialog_imeDoneHidesKeyboardAndFocusesSave() {
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            AlertDialog dialog = openRenameDialog(scenario, focusHomeCell(scenario), false);
            scenario.onActivity(activity -> {
                EditText input = (EditText) field(activity, "renameInput");
                EditorInfo editorInfo = new EditorInfo();
                InputConnection connection = input.onCreateInputConnection(editorInfo);
                assertNotNull(connection);
                assertEquals(EditorInfo.IME_ACTION_DONE,
                        editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION);
                assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE));
            });

            awaitValue(scenario, "hidden rename keyboard and focused Save", activity -> {
                EditText input = (EditText) field(activity, "renameInput");
                View save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                int softInputState = dialog.getWindow().getAttributes().softInputMode
                        & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE;
                return dialog.isShowing()
                        && !input.hasFocus()
                        && save.hasFocus()
                        && softInputState
                        == WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                        ? dialog : null;
            });
            scenario.onActivity(activity ->
                    assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()));
        }
    }

    @Test
    public void renameDialog_inputHasBalancedHorizontalSpacing() {
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            AlertDialog dialog = openRenameDialog(
                    scenario, focusHomeCell(scenario), false);
            scenario.onActivity(activity -> {
                EditText input = (EditText) field(activity, "renameInput");
                ViewGroup inputContainer = (ViewGroup) input.getParent();
                int expectedSpacing = Math.round(24 * activity.getResources()
                        .getDisplayMetrics().density);

                assertEquals(expectedSpacing, inputContainer.getPaddingLeft());
                assertEquals(expectedSpacing, inputContainer.getPaddingRight());
                assertTrue(inputContainer.getWidth() > input.getWidth());
                assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            });
        }
    }

    @Test
    public void settingsCategoryHub_groupsExistingActionsAndTitlesPages() {
        try (ActivityScenario<LauncherActivity> scenario = launchSettledLauncher()) {
            scenario.onActivity(activity -> {
                invoke(activity, "showSettingsPanel");
                assertSettingsPage(activity, R.string.settings_title_main,
                        R.string.settings_row_appearance_menu,
                        R.string.settings_row_wallpaper_menu,
                        R.string.settings_row_apps_menu,
                        R.string.settings_row_device_menu,
                        R.string.settings_row_system_settings);
                assertEquals(activity.getString(R.string.settings_hint_open),
                        ((android.widget.TextView) field(activity, "settingsHintView"))
                                .getText().toString());

                invoke(activity, "activateSettingsRowId", new Class<?>[] {int.class},
                        staticField(LauncherActivity.class, "SR_APPS_MENU"));
                assertSettingsPage(activity, R.string.settings_title_apps,
                        R.string.settings_row_manage_hidden,
                        R.string.settings_row_button_shortcuts);
                invoke(activity, "handleSettingsKey", new Class<?>[] {int.class},
                        KeyEvent.KEYCODE_BACK);
                assertSettingsPage(activity, R.string.settings_title_main,
                        R.string.settings_row_appearance_menu,
                        R.string.settings_row_wallpaper_menu,
                        R.string.settings_row_apps_menu,
                        R.string.settings_row_device_menu,
                        R.string.settings_row_system_settings);
                assertEquals(2, ((Integer) field(activity, "settingsSelectedRow")).intValue());

                invoke(activity, "activateSettingsRowId", new Class<?>[] {int.class},
                        staticField(LauncherActivity.class, "SR_DEVICE_MENU"));
                assertSettingsPage(activity, R.string.settings_title_device,
                        R.string.settings_row_launcher_setup_menu,
                        R.string.settings_row_backup_menu);
                invoke(activity, "activateSettingsRowId", new Class<?>[] {int.class},
                        staticField(LauncherActivity.class, "SR_LAUNCHER_SETUP_MENU"));
                assertSettingsPage(activity, R.string.settings_title_launcher_setup,
                        R.string.settings_row_launcher_activate,
                        R.string.settings_row_launcher_restore);
                invoke(activity, "handleSettingsKey", new Class<?>[] {int.class},
                        KeyEvent.KEYCODE_BACK);
                assertSettingsPage(activity, R.string.settings_title_device,
                        R.string.settings_row_launcher_setup_menu,
                        R.string.settings_row_backup_menu);
                invoke(activity, "handleSettingsKey", new Class<?>[] {int.class},
                        KeyEvent.KEYCODE_BACK);
                assertEquals(3, ((Integer) field(activity, "settingsSelectedRow")).intValue());
            });
        }
    }

    private static void assertSettingsPage(
            LauncherActivity activity, int titleRes, int... rowLabelResources) {
        android.widget.TextView title = (android.widget.TextView)
                field(activity, "settingsTitleView");
        android.widget.LinearLayout column = (android.widget.LinearLayout)
                field(activity, "settingsColumn");
        assertEquals(activity.getString(titleRes), title.getText().toString());
        assertEquals(rowLabelResources.length, column.getChildCount());
        for (int i = 0; i < rowLabelResources.length; i++) {
            ViewGroup row = (ViewGroup) column.getChildAt(i);
            android.widget.TextView label = (android.widget.TextView) row.getChildAt(0);
            String text = label.getText().toString();
            assertEquals(activity.getString(rowLabelResources[i]), text.split("\\n", 2)[0]);
            android.widget.LinearLayout.LayoutParams labelParams =
                    (android.widget.LinearLayout.LayoutParams) label.getLayoutParams();
            assertEquals(0, labelParams.width);
            assertEquals(1f, labelParams.weight, 0f);
            int expectedMargin = row.getChildCount() > 1
                    ? Math.round(10 * activity.getResources().getDisplayMetrics().density) : 0;
            assertEquals(expectedMargin, labelParams.getMarginEnd());
            assertEquals(Math.round(250 * activity.getResources()
                    .getDisplayMetrics().density), row.getMinimumWidth());
            if (row.getChildCount() > 1) {
                android.widget.TextView indicator =
                        (android.widget.TextView) row.getChildAt(1);
                assertEquals(android.view.Gravity.END,
                        indicator.getGravity()
                                & android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
            }
        }
    }

    @Test
    public void layoutSettings_exposesColumnsRoundnessAndFocusControls() {
        Context context = getInstrumentation().getTargetContext();
        String prefsName = (String) staticField(LauncherActivity.class, "PREFS");
        String columnsKey = (String) staticField(LauncherActivity.class, "KEY_LAYOUT_COLUMNS");
        String cornerKey = (String) staticField(
                LauncherActivity.class, "KEY_CARD_CORNER_PERCENT");
        String borderKey = (String) staticField(
                LauncherActivity.class, "KEY_FOCUS_BORDER_ENABLED");
        String colorKey = (String) staticField(
                LauncherActivity.class, "KEY_FOCUS_BORDER_COLOR");
        SharedPreferences preferences = context.getSharedPreferences(
                prefsName, Context.MODE_PRIVATE);
        boolean hadColumns = preferences.contains(columnsKey);
        boolean hadCorner = preferences.contains(cornerKey);
        boolean hadBorder = preferences.contains(borderKey);
        boolean hadColor = preferences.contains(colorKey);
        int oldColumns = preferences.getInt(columnsKey, LayoutOptions.DEFAULT_COLUMNS);
        int oldCorner = preferences.getInt(cornerKey, LayoutOptions.DEFAULT_CORNER_PERCENT);
        boolean oldBorder = preferences.getBoolean(
                borderKey, LayoutOptions.DEFAULT_FOCUS_BORDER_ENABLED);
        int oldColor = preferences.getInt(colorKey, LayoutOptions.DEFAULT_FOCUS_COLOR);
        assertTrue(preferences.edit().putInt(columnsKey, 6).putInt(cornerKey, 20)
                .putBoolean(borderKey, true)
                .putInt(colorKey, LayoutOptions.DEFAULT_FOCUS_COLOR).commit());

        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            scenario.onActivity(activity -> invoke(activity, "showSettingsPanel"));
            awaitVisibleViewField(scenario, "settingsOverlay");

            scenario.onActivity(activity -> invoke(activity, "activateSettingsRowId",
                    new Class<?>[] {int.class},
                    staticField(LauncherActivity.class, "SR_APPEARANCE_MENU")));
            awaitValue(scenario, "Appearance settings rows", activity -> {
                android.widget.LinearLayout column = (android.widget.LinearLayout)
                        field(activity, "settingsColumn");
                return column != null && column.getChildCount() == 6 ? column : null;
            });

            scenario.onActivity(activity -> {
                invoke(activity, "stepLayoutColumns", new Class<?>[] {int.class}, -1);
                invoke(activity, "stepCardCorner", new Class<?>[] {int.class}, -1);
                invoke(activity, "toggleFocusBorder");
                invoke(activity, "stepFocusColor", new Class<?>[] {int.class}, 1);
            });
            android.widget.LinearLayout column = awaitValue(
                    scenario, "updated Layout settings", activity -> {
                        android.widget.LinearLayout current = (android.widget.LinearLayout)
                                field(activity, "settingsColumn");
                        if (current == null || current.getChildCount() != 6) return null;
                        String columnsText = ((android.widget.TextView)
                                ((ViewGroup) current.getChildAt(0)).getChildAt(1))
                                .getText().toString();
                        String cornerText = ((android.widget.TextView)
                                ((ViewGroup) current.getChildAt(1)).getChildAt(1))
                                .getText().toString();
                        return "< 5 >".equals(columnsText)
                                && "< 18% >".equals(cornerText)
                                && preferences.getInt(columnsKey, -1) == 5
                                && preferences.getInt(cornerKey, -1) == 18
                                && (Boolean) field(activity, "layoutApplyPending")
                                ? current : null;
                    });

            scenario.onActivity(activity -> {
                assertSame(column, field(activity, "settingsColumn"));
                assertEquals(activity.getString(R.string.settings_title_appearance),
                        ((android.widget.TextView) field(activity, "settingsTitleView"))
                                .getText().toString());
                assertEquals(activity.getString(R.string.settings_hint_adjust),
                        ((android.widget.TextView) field(activity, "settingsHintView"))
                                .getText().toString());
                assertEquals(6, column.getChildCount());
                assertEquals(activity.getString(R.string.settings_row_layout_columns),
                        ((android.widget.TextView) ((ViewGroup) column.getChildAt(0))
                                .getChildAt(0)).getText().toString());
                assertEquals("< 5 >", ((android.widget.TextView)
                        ((ViewGroup) column.getChildAt(0)).getChildAt(1)).getText().toString());
                assertEquals("< 18% >", ((android.widget.TextView)
                        ((ViewGroup) column.getChildAt(1)).getChildAt(1)).getText().toString());
                assertEquals("Off", ((android.widget.TextView)
                        ((ViewGroup) column.getChildAt(2)).getChildAt(1)).getText().toString());
                assertEquals("< Cyan >", ((android.widget.TextView)
                        ((ViewGroup) column.getChildAt(3)).getChildAt(1)).getText().toString());
                assertEquals(5, preferences.getInt(columnsKey, -1));
                assertEquals(18, preferences.getInt(cornerKey, -1));
                assertEquals(false, preferences.getBoolean(borderKey, true));
                assertEquals(0xFF00E5FF, preferences.getInt(colorKey, 0));
                assertTrue((Boolean) field(activity, "layoutApplyPending"));
            });
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            if (hadColumns) restore.putInt(columnsKey, oldColumns);
            else restore.remove(columnsKey);
            if (hadCorner) restore.putInt(cornerKey, oldCorner);
            else restore.remove(cornerKey);
            if (hadBorder) restore.putBoolean(borderKey, oldBorder);
            else restore.remove(borderKey);
            if (hadColor) restore.putInt(colorKey, oldColor);
            else restore.remove(colorKey);
            assertTrue(restore.commit());
        }
    }

    @Test
    public void fourColumnLayout_demotesOverflowFavoritesAndUsesFiveByThreeCards() {
        Context context = getInstrumentation().getTargetContext();
        String prefsName = (String) staticField(LauncherActivity.class, "PREFS");
        String columnsKey = (String) staticField(LauncherActivity.class, "KEY_LAYOUT_COLUMNS");
        String cornerKey = (String) staticField(
                LauncherActivity.class, "KEY_CARD_CORNER_PERCENT");
        String homeCountKey = (String) staticField(LauncherActivity.class, "KEY_HOME_COUNT");
        String hiddenKey = (String) staticField(LauncherActivity.class, "KEY_HIDDEN");
        SharedPreferences preferences = context.getSharedPreferences(
                prefsName, Context.MODE_PRIVATE);
        boolean hadColumns = preferences.contains(columnsKey);
        boolean hadCorner = preferences.contains(cornerKey);
        boolean hadHomeCount = preferences.contains(homeCountKey);
        boolean hadHidden = preferences.contains(hiddenKey);
        int oldColumns = preferences.getInt(columnsKey, LayoutOptions.DEFAULT_COLUMNS);
        int oldCorner = preferences.getInt(cornerKey, LayoutOptions.DEFAULT_CORNER_PERCENT);
        int oldHomeCount = preferences.getInt(homeCountKey, -1);
        String oldHidden = preferences.getString(hiddenKey, "");
        assertTrue(preferences.edit().putInt(columnsKey, 4).putInt(cornerKey, 0)
                .putInt(homeCountKey, 6).putString(hiddenKey, "").commit());

        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            awaitValue(scenario, "loaded four-column layout", activity -> {
                Object shelf = field(activity, "shelf");
                @SuppressWarnings("unchecked")
                java.util.List<AppInfo> displayed = (java.util.List<AppInfo>)
                        field(shelf, "displayed");
                return ((Integer) field(activity, "homeCount")) >= 0
                        && !displayed.isEmpty() ? shelf : null;
            });
            scenario.onActivity(activity -> {
                Object shelf = field(activity, "shelf");
                @SuppressWarnings("unchecked")
                java.util.List<AppInfo> displayed = (java.util.List<AppInfo>)
                        field(shelf, "displayed");
                assertEquals("selected columns", 4,
                        ((Integer) field(activity, "layoutColumns")).intValue());
                assertEquals("overflow favorites demoted", 4,
                        ((Integer) field(activity, "homeCount")).intValue());
                assertEquals("shelf renders four favorites", 4, displayed.size());
                int tileWidth = (Integer) field(activity, "tileWpx");
                int tileHeight = (Integer) field(activity, "bannerHpx");
                assertEquals("cards use 5:3 geometry", tileWidth * 3,
                        tileHeight * 5, 2);
                assertEquals("zero percent gives square corners", 0,
                        ((Integer) field(activity, "tileCornerPx")).intValue());
            });
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            if (hadColumns) restore.putInt(columnsKey, oldColumns);
            else restore.remove(columnsKey);
            if (hadCorner) restore.putInt(cornerKey, oldCorner);
            else restore.remove(cornerKey);
            if (hadHomeCount) restore.putInt(homeCountKey, oldHomeCount);
            else restore.remove(homeCountKey);
            if (hadHidden) restore.putString(hiddenKey, oldHidden);
            else restore.remove(hiddenKey);
            assertTrue(restore.commit());
        }
    }

    @Test
    public void drawerBlurCrossfade_reachesBlurredAndSharpEndStates() {
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                awaitValue(scenario, "loaded wallpaper drawable", activity -> {
                    ImageView wallpaper = (ImageView) field(activity, "wallpaperFront");
                    return wallpaper.getDrawable() != null ? wallpaper : null;
                });
            }

            scenario.onActivity(activity -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    Bitmap preview = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
                    preview.eraseColor(0xFF315A82);
                    setField(activity, "legacyGridBlurBitmap", preview);
                }
                View layer = (View) field(activity, "drawerBlurLayer");
                invoke(activity, "applyDrawerBlur",
                        new Class<?>[] {boolean.class}, true);
                assertEquals(View.VISIBLE, layer.getVisibility());
                assertEquals(0f, layer.getAlpha(), 0.01f);
                assertEquals(NavigationMotion.SURFACE_DURATION_MS,
                        layer.animate().getDuration());
            });

            awaitValue(scenario, "blurred wallpaper end state", activity -> {
                ImageView layer = (ImageView) field(activity, "drawerBlurLayer");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    return layer.getVisibility() == View.GONE ? layer : null;
                }
                return layer.getVisibility() == View.VISIBLE
                        && Math.abs(layer.getAlpha() - 1f) < 0.01f ? layer : null;
            });

            scenario.onActivity(activity -> invoke(activity, "applyDrawerBlur",
                    new Class<?>[] {boolean.class}, false));
            awaitValue(scenario, "sharp wallpaper end state", activity -> {
                ImageView layer = (ImageView) field(activity, "drawerBlurLayer");
                return layer.getVisibility() == View.GONE
                        && Math.abs(layer.getAlpha()) < 0.01f ? layer : null;
            });
        }
    }

    @Test
    public void favoritesGlass_matchesHomeAndGridAcrossBlurPaths() {
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            View blurLayer = awaitVisibleViewField(scenario, "favoritesBlurLayer");
            scenario.onActivity(activity -> {
                View shelf = (View) field(activity, "shelf");
                assertTrue(shelf.getBackground() instanceof FavoritesGlassDrawable);
            });

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                scenario.onActivity(activity -> {
                    WallpaperController controller = (WallpaperController)
                            field(activity, "wallpaperCtl");
                    if (controller.frostedPreview() != null) return;
                    Bitmap source = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
                    source.eraseColor(0xFF315A82);
                    Bitmap preview = (Bitmap) invokeForResult(controller,
                            "createFrostedPreview", new Class<?>[] {Bitmap.class}, source);
                    source.recycle();
                    assertNotNull(preview);
                    invoke(controller, "replaceFrostedPreview",
                            new Class<?>[] {Bitmap.class}, preview);
                });
                Bitmap preview = awaitValue(scenario, "legacy frosted wallpaper preview", activity -> {
                    WallpaperController controller = (WallpaperController)
                            field(activity, "wallpaperCtl");
                    Bitmap current = controller.frostedPreview();
                    return current != null && !current.isRecycled() ? current : null;
                });
                int[] expectedSize = WallpaperController.frostedPreviewSize(
                        getInstrumentation().getTargetContext().getResources()
                                .getDisplayMetrics().widthPixels,
                        getInstrumentation().getTargetContext().getResources()
                                .getDisplayMetrics().heightPixels);
                assertEquals(expectedSize[0], preview.getWidth());
                assertEquals(expectedSize[1], preview.getHeight());
            }

            scenario.onActivity(activity -> invoke(activity, "openDrawer"));
            View drawer = awaitVisibleViewField(scenario, "drawer");
            scenario.onActivity(activity -> {
                assertEquals(View.INVISIBLE, blurLayer.getVisibility());
                FavoritesGlassDrawable homeGlass = (FavoritesGlassDrawable)
                        ((View) field(activity, "shelf")).getBackground();
                FavoritesGlassDrawable gridGlass = (FavoritesGlassDrawable)
                        field(drawer, "favoritesGlass");
                int[] homeFill = (int[]) field(homeGlass, "fillColors");
                int[] gridFill = (int[]) field(gridGlass, "fillColors");
                int[] homeInner = (int[]) field(homeGlass, "innerEdgeColors");
                int[] gridInner = (int[]) field(gridGlass, "innerEdgeColors");
                assertTrue("grid glass should be quieter than Home",
                        Color.alpha(gridFill[gridFill.length - 1])
                                < Color.alpha(homeFill[homeFill.length - 1]));
                assertTrue("grid inner reflection should be quieter than Home",
                        Color.alpha(gridInner[0]) < Color.alpha(homeInner[0]));
                invoke(activity, "closeDrawer");
            });

            awaitValue(scenario, "restored Home glass", activity ->
                    blurLayer.getVisibility() == View.VISIBLE
                            && ((View) field(activity, "shelf")).getVisibility() == View.VISIBLE
                            ? blurLayer : null);
        }
    }

    @Test
    public void idleHide_hidesEntireFavoritesSurfaceAndKeepsClockVisible() {
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            awaitVisibleViewField(scenario, "favoritesBlurLayer");
            scenario.onActivity(activity -> {
                View clock = (View) field(activity, "clockView");
                clock.setVisibility(View.VISIBLE);
                clock.setAlpha(1f);
                invoke(activity, "applyIdleHide",
                        new Class<?>[] {boolean.class}, true);
            });

            awaitValue(scenario, "hidden favorites shelf and blur layer", activity -> {
                View shelf = (View) field(activity, "shelf");
                View blur = (View) field(activity, "favoritesBlurLayer");
                View clock = (View) field(activity, "clockView");
                return Math.abs(shelf.getAlpha()) < 0.01f
                        && Math.abs(blur.getAlpha()) < 0.01f
                        && clock.getVisibility() == View.VISIBLE
                        && Math.abs(clock.getAlpha() - 1f) < 0.01f
                        ? blur : null;
            });

            scenario.onActivity(activity -> invoke(activity, "applyIdleHide",
                    new Class<?>[] {boolean.class}, false));
            awaitValue(scenario, "restored favorites shelf and blur layer", activity -> {
                View shelf = (View) field(activity, "shelf");
                View blur = (View) field(activity, "favoritesBlurLayer");
                return Math.abs(shelf.getAlpha() - 1f) < 0.01f
                        && Math.abs(blur.getAlpha() - 1f) < 0.01f
                        ? blur : null;
            });
        }
    }

    @Test
    public void idleHide_closesAppGridForStaticWallpaper() {
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            focusHomeCell(scenario);
            scenario.onActivity(activity -> invoke(activity, "openDrawer"));
            View drawer = awaitVisibleViewField(scenario, "drawer");

            scenario.onActivity(activity -> {
                setField(activity, "idleHideSec", 60);
                setField(activity, "idleHideGeneration", 1);
                invoke(activity, "beginIdleHide", new Class<?>[] {int.class}, 1);
            });

            awaitValue(scenario, "hidden home surface after drawer closes", activity -> {
                View shelf = (View) field(activity, "shelf");
                View blur = (View) field(activity, "favoritesBlurLayer");
                return drawer.getVisibility() == View.GONE
                        && Math.abs(shelf.getAlpha()) < 0.01f
                        && Math.abs(blur.getAlpha()) < 0.01f
                        ? blur : null;
            });

            scenario.onActivity(activity -> invoke(activity, "applyIdleHide",
                    new Class<?>[] {boolean.class}, false));
        }
    }

    @Test
    public void drawerOpen_continuesWhenLegacyBlurFrameNeverArrives() {
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            focusHomeCell(scenario);
            scenario.onActivity(activity -> {
                FrameLayout originalRoot = (FrameLayout) field(activity, "root");
                FrameLayout stalledFrameSource = new FrameLayout(activity) {
                    @Override
                    public void postOnAnimation(Runnable action) {
                        // Simulate a legacy compositor that never supplies the requested frame.
                    }
                };
                setField(activity, "root", stalledFrameSource);
                try {
                    invoke(activity, "openDrawer");
                } finally {
                    setField(activity, "root", originalRoot);
                }
            });

            awaitVisibleViewField(scenario, "drawer");
        }
    }

    @Test
    public void dpadUp_fromFirstGridRow_returnsDirectlyToMatchingHomeFavorite() {
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            View originalHomeCell = focusHomeCell(scenario);
            AppInfo expectedFavorite = (AppInfo) field(originalHomeCell, "boundApp");

            scenario.onActivity(activity -> invoke(activity, "openDrawer"));
            View drawer = awaitVisibleViewField(scenario, "drawer");
            View gridCell = awaitFocusedCell(scenario, "DrawerCell");
            scenario.onActivity(activity -> {
                assertTrue(gridCell.dispatchKeyEvent(
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)));
                gridCell.dispatchKeyEvent(
                        new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));
            });

            awaitValue(scenario, "closed drawer", activity ->
                    drawer.getVisibility() != View.VISIBLE ? drawer : null);
            View returnedHomeCell = awaitFocusedCell(scenario, "CellView");
            scenario.onActivity(activity -> {
                AppInfo returnedFavorite = (AppInfo) field(returnedHomeCell, "boundApp");
                assertNotNull(returnedFavorite);
                assertEquals(expectedFavorite.packageName, returnedFavorite.packageName);
                assertEquals(View.VISIBLE,
                        ((View) field(activity, "shelf")).getVisibility());
            });
        }
    }

    @Test
    public void dpadUp_fromHomeCell_focusesSettingsPill() {
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            View homeCell = focusHomeCell(scenario);
            scenario.onActivity(activity -> {
                assertTrue(homeCell.dispatchKeyEvent(
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)));
                homeCell.dispatchKeyEvent(
                        new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));
            });

            awaitValue(scenario, "focused settings pill", activity -> {
                View gear = (View) field(activity, "mapperBtnView");
                return gear != null && gear.isFocused() ? gear : null;
            });
        }
    }

    @Test
    public void systemWallpaper_togglesTransparentWindowAndRestores() {
        java.util.Map<String, ?> saved = launcherPrefs().getAll();
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            scenario.onActivity(activity -> {
                setSystemWallpaper(activity, true);
                assertSystemWallpaper(activity, true);
                setSystemWallpaper(activity, false);
                assertSystemWallpaper(activity, false);
            });
        } finally {
            restoreLauncherPrefs(saved, "KEY_SYSTEM_WALLPAPER");
        }
    }

    @Test
    public void systemWallpaper_persistsAcrossRecreate() {
        java.util.Map<String, ?> saved = launcherPrefs().getAll();
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            scenario.onActivity(activity -> setSystemWallpaper(activity, true));
            scenario.recreate();
            scenario.onActivity(activity -> assertSystemWallpaper(activity, true));
        } finally {
            restoreLauncherPrefs(saved, "KEY_SYSTEM_WALLPAPER");
        }
    }

    @Test
    public void systemWallpaper_pickingSlideshowFolderTurnsItOff() {
        java.util.Map<String, ?> saved = launcherPrefs().getAll();
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            scenario.onActivity(activity -> {
                setSystemWallpaper(activity, true);
                @SuppressWarnings("unchecked")
                java.util.List<String[]> folders =
                        (java.util.List<String[]>) field(activity, "folderPickerData");
                folders.clear();
                folders.add(new String[] {"glass-test-bucket", "Test", "0"});
                invoke(activity, "selectFolder", new Class<?>[] {int.class}, 0);
                assertSystemWallpaper(activity, false);
            });
        } finally {
            restoreLauncherPrefs(saved, "KEY_SYSTEM_WALLPAPER", "KEY_SLIDESHOW_FOLDER",
                    "KEY_SLIDESHOW_INDEX", "KEY_SLIDESHOW_SEED", "KEY_SLIDESHOW_DURATION",
                    "KEY_IDLE_HIDE");
        }
    }

    @Test
    public void favoritesBackground_settingRemovesPlateAfterRebuild() {
        java.util.Map<String, ?> saved = launcherPrefs().getAll();
        String key = (String) staticField(LauncherActivity.class, "KEY_FAVORITES_BACKGROUND");
        try {
            assertTrue(launcherPrefs().edit().remove(key).commit());
            try (ActivityScenario<LauncherActivity> scenario =
                         launchSettledLauncher()) {
                scenario.onActivity(activity -> assertFavoritesBackground(activity, true));

                scenario.onActivity(activity -> invoke(activity, "showSettingsPanel"));
                awaitVisibleViewField(scenario, "settingsOverlay");
                scenario.onActivity(activity -> invoke(activity, "activateSettingsRowId",
                        new Class<?>[] {int.class},
                        staticField(LauncherActivity.class, "SR_APPEARANCE_MENU")));
                awaitValue(scenario, "Appearance settings rows", activity -> {
                    android.widget.LinearLayout column = (android.widget.LinearLayout)
                            field(activity, "settingsColumn");
                    return column != null && column.getChildCount() == 6 ? column : null;
                });

                scenario.onActivity(activity -> {
                    assertEquals(activity.getString(R.string.settings_row_favorites_background),
                            favoritesBackgroundRowText(activity, 0));
                    assertEquals("On", favoritesBackgroundRowText(activity, 1));
                    invoke(activity, "activateSettingsRowId", new Class<?>[] {int.class},
                            staticField(LauncherActivity.class, "SR_FAVORITES_BACKGROUND"));
                    assertEquals("Off", favoritesBackgroundRowText(activity, 1));
                    assertEquals(false, launcherPrefs().getBoolean(key, true));
                    assertTrue((Boolean) field(activity, "layoutApplyPending"));
                });

                scenario.recreate();
                scenario.onActivity(activity -> assertFavoritesBackground(activity, false));
            }
        } finally {
            restoreLauncherPrefs(saved, "KEY_FAVORITES_BACKGROUND");
        }
    }

    @Test
    public void immediateDrawerClose_cancelsDeferredOpen() {
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            focusHomeCell(scenario);
            AtomicReference<View> drawerRef = new AtomicReference<>();
            scenario.onActivity(activity -> {
                View drawer = (View) field(activity, "drawer");
                drawerRef.set(drawer);
                invoke(drawer, "open",
                        new Class<?>[] {int.class, float.class}, 0, 900f);
                invoke(drawer, "close",
                        new Class<?>[] {float.class, Runnable.class}, 900f, null);
            });

            awaitValue(scenario, "deferred drawer open to remain cancelled", activity -> {
                View drawer = drawerRef.get();
                return drawer.getVisibility() == View.GONE
                        && !(Boolean) field(drawer, "closing") ? drawer : null;
            });
        }
    }

    @Test
    public void gridFocusStaysAnchoredForSingleStepAndHeldNavigation() {
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            focusHomeCell(scenario);
            scenario.onActivity(activity -> invoke(activity, "openDrawer"));
            View drawer = awaitVisibleViewField(scenario, "drawer");
            awaitFocusedCell(scenario, "DrawerCell");
            awaitValue(scenario, "settled drawer surface", activity ->
                    Math.abs(drawer.getTranslationY()) < 0.5f
                            && Math.abs(drawer.getAlpha() - 1f) < 0.01f
                            ? drawer : null);

            AtomicReference<View> focusedCell = new AtomicReference<>();
            scenario.onActivity(activity -> {
                int previous = (Integer) field(drawer, "focusedIndex");
                @SuppressWarnings("unchecked")
                ArrayList<AppInfo> displayed =
                        (ArrayList<AppInfo>) field(drawer, "displayed");
                int columns = (Integer) field(activity, "layoutColumns");
                int homeCount = (Integer) field(activity, "homeCount");
                int target = HomeDrawerModel.navRight(
                        columns, previous, displayed.size(), homeCount);
                assertTrue("test grid needs an adjacent app", target != previous);

                invoke(drawer, "requestFocusOnIndex",
                        new Class<?>[] {int.class, boolean.class}, target, false);
                View focused = activity.getWindow().getDecorView().findFocus();
                assertNotNull(focused);
                assertEquals(target, ((Integer) field(focused, "boundIndex")).intValue());
                assertEquals("single-step focus must not move the card",
                        0f, focused.getTranslationX(), 0.1f);
                assertEquals("single-step focus must not move the card",
                        0f, focused.getTranslationY(), 0.1f);
                focusedCell.set(focused);
            });

            View settledCell = awaitValue(scenario, "settled anchored focus scale",
                    activity -> Math.abs(focusedCell.get().getTranslationX()) < 0.1f
                            && Math.abs(focusedCell.get().getTranslationY()) < 0.1f
                            && Math.abs(focusedCell.get().getScaleX() - 1.10f) < 0.01f
                            ? focusedCell.get() : null);

            scenario.onActivity(activity -> {
                int previous = (Integer) field(settledCell, "boundIndex");
                @SuppressWarnings("unchecked")
                ArrayList<AppInfo> displayed =
                        (ArrayList<AppInfo>) field(drawer, "displayed");
                int columns = (Integer) field(activity, "layoutColumns");
                int homeCount = (Integer) field(activity, "homeCount");
                int target = HomeDrawerModel.navRight(
                        columns, previous, displayed.size(), homeCount);
                assertTrue("test grid needs a second adjacent app", target != previous);

                invoke(drawer, "requestFocusOnIndex",
                        new Class<?>[] {int.class, boolean.class}, target, true);
                View focused = activity.getWindow().getDecorView().findFocus();
                assertNotNull(focused);
                assertEquals(0f, focused.getTranslationX(), 0.1f);
                assertEquals(0f, focused.getTranslationY(), 0.1f);
                assertEquals(1.10f, focused.getScaleX(), 0.01f);
            });
        }
    }

    @Test
    public void refreshArtwork_whileDrawerVisible_keepsFocusInDrawer() {
        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            focusHomeCell(scenario);
            scenario.onActivity(activity -> invoke(activity, "openDrawer"));
            View drawer = awaitVisibleViewField(scenario, "drawer");
            scenario.onActivity(activity -> invoke(drawer, "requestFocusOnIndex",
                    new Class<?>[] {int.class, boolean.class}, 0, true));
            View drawerCell = awaitFocusedCell(scenario, "DrawerCell");

            scenario.onActivity(activity -> {
                AppInfo app = (AppInfo) field(drawerCell, "boundApp");
                assertNotNull(app);
                invoke(activity, "refreshAppArtwork", new Class<?>[] {AppInfo.class}, app);

                View focusedAfter = activity.getWindow().getDecorView().findFocus();
                assertSame(drawerCell, focusedAfter);
                assertTrue(isDescendant(drawer, focusedAfter));
                assertEquals(View.VISIBLE, drawer.getVisibility());
            });
        }
    }

    @Test
    public void focusedHomeAndGridTiles_showCustomBorderScaleAndShadow() {
        Context context = getInstrumentation().getTargetContext();
        String prefsName = (String) staticField(LauncherActivity.class, "PREFS");
        String borderKey = (String) staticField(
                LauncherActivity.class, "KEY_FOCUS_BORDER_ENABLED");
        String colorKey = (String) staticField(
                LauncherActivity.class, "KEY_FOCUS_BORDER_COLOR");
        SharedPreferences preferences = context.getSharedPreferences(
                prefsName, Context.MODE_PRIVATE);
        boolean hadBorder = preferences.contains(borderKey);
        boolean hadColor = preferences.contains(colorKey);
        boolean oldBorder = preferences.getBoolean(
                borderKey, LayoutOptions.DEFAULT_FOCUS_BORDER_ENABLED);
        int oldColor = preferences.getInt(colorKey, LayoutOptions.DEFAULT_FOCUS_COLOR);
        int customColor = 0xFF00E5FF;
        assertTrue(preferences.edit().putBoolean(borderKey, true)
                .putInt(colorKey, customColor).commit());

        try (ActivityScenario<LauncherActivity> scenario =
                     launchSettledLauncher()) {
            View homeCell = focusHomeCell(scenario);
            float expectedShadow = awaitValue(scenario, "configured focus shadow", activity ->
                    Math.round(((Integer) staticField(LauncherActivity.class,
                            "FOCUS_SHADOW_Z_DP"))
                            * activity.getResources().getDisplayMetrics().density * 10f) / 10f);

            awaitValue(scenario, "home tile focus visuals", activity -> {
                View ring = (View) field(activity, "ringView");
                return ring != null
                        && ring.getVisibility() == View.VISIBLE
                        && Math.abs(homeCell.getScaleX() - 1.10f) < 0.01f
                        && Math.abs(homeCell.getTranslationZ() - expectedShadow) < 0.6f
                        ? homeCell : null;
            });
            scenario.onActivity(activity -> {
                View ringView = (View) field(activity, "ringView");
                android.graphics.Paint paint = (android.graphics.Paint)
                        field(ringView, "ring");
                assertEquals(customColor, paint.getColor());

                FrameLayout root = (FrameLayout) field(activity, "root");
                int[] cellLocation = new int[2];
                int[] rootLocation = new int[2];
                homeCell.getLocationOnScreen(cellLocation);
                root.getLocationOnScreen(rootLocation);
                int cellHeight = (Integer) field(activity, "cellHpx");
                int bannerHeight = (Integer) field(activity, "bannerHpx");
                float artworkCenter = At4kHomeLayout.centeredTop(
                        cellHeight, bannerHeight) + bannerHeight / 2f;
                float expectedCenterY = cellLocation[1] - rootLocation[1]
                        + artworkCenter * homeCell.getScaleY();
                float actualCenterY = ringView.getY() + ringView.getHeight() / 2f;
                assertEquals("favorites border follows artwork center",
                        expectedCenterY, actualCenterY, 1f);
            });

            scenario.onActivity(activity -> invoke(activity, "openDrawer"));
            View gridCell = awaitFocusedCell(scenario, "DrawerCell");
            awaitValue(scenario, "grid tile focus visuals", activity ->
                    Math.abs(gridCell.getScaleX() - 1.10f) < 0.01f
                            && Math.abs(gridCell.getTranslationZ() - expectedShadow) < 0.6f
                            ? gridCell : null);

            scenario.onActivity(activity -> gridCell.setFocusable(false));
            awaitValue(scenario, "shadow removed from blurred grid tile", activity ->
                    Math.abs(gridCell.getTranslationZ()) < 0.1f ? gridCell : null);
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            if (hadBorder) restore.putBoolean(borderKey, oldBorder);
            else restore.remove(borderKey);
            if (hadColor) restore.putInt(colorKey, oldColor);
            else restore.remove(colorKey);
            assertTrue(restore.commit());
        }
    }

    private static void assertSynchronizedContextMenuDimming(
            SharedPreferences preferences, String columnsKey, int columns) {
        assertTrue(preferences.edit().putInt(columnsKey, columns).commit());
        try (ActivityScenario<LauncherActivity> scenario = launchSettledLauncher()) {
            View homeCell = focusHomeCell(scenario);
            scenario.onActivity(activity -> {
                assertEquals(columns, ((Integer) field(activity, "layoutColumns")).intValue());
                assertTrue(homeCell.performLongClick());
                View shelf = (View) field(activity, "shelf");
                int selected = (Integer) field(homeCell, "boundIndex");
                assertAttachedCardAlphas(shelf, selected, true);
                invoke(shelf, "exitReorderMode", new Class<?>[] {boolean.class}, false);
                assertAttachedCardAlphas(shelf, selected, false);
            });

            scenario.onActivity(activity -> invoke(activity, "openDrawer"));
            View drawerCell = awaitFocusedCell(scenario, "DrawerCell");
            scenario.onActivity(activity -> {
                assertTrue(drawerCell.performLongClick());
                View drawer = (View) field(activity, "drawer");
                int selected = (Integer) field(drawerCell, "boundIndex");
                assertAttachedCardAlphas(drawer, selected, true);
                invoke(drawer, "exitReorderMode", new Class<?>[] {boolean.class}, false);
                assertAttachedCardAlphas(drawer, selected, false);
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertAttachedCardAlphas(
            View host, int selectedIndex, boolean menuOpen) {
        SparseArray<View> attached = (SparseArray<View>) field(host, "attached");
        assertTrue("expected more than one attached app card", attached.size() > 1);
        for (int i = 0; i < attached.size(); i++) {
            int index = attached.keyAt(i);
            float expected = menuOpen && index != selectedIndex ? 0.4f : 1f;
            assertEquals("card alpha at index " + index,
                    expected, attached.valueAt(i).getAlpha(), 0.001f);
        }
    }

    private static ActivityScenario<LauncherActivity> launchSettledLauncher() {
        ActivityScenario<LauncherActivity> scenario =
                ActivityScenario.launch(LauncherActivity.class);
        try {
            awaitValue(scenario, "laid-out landscape LauncherActivity", activity -> {
                View root = activity.findViewById(android.R.id.content);
                return activity.getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_LANDSCAPE
                        && root != null
                        && root.getWidth() > root.getHeight()
                        && !activity.isChangingConfigurations()
                        ? activity : null;
            });
            return scenario;
        } catch (RuntimeException | Error error) {
            scenario.close();
            throw error;
        }
    }

    private static AlertDialog openRenameDialog(
            ActivityScenario<LauncherActivity> scenario, View cell, boolean expectReset) {
        AppInfo app = (AppInfo) field(cell, "boundApp");
        int focusHint = (Integer) field(cell, "boundIndex");
        assertNotNull(app);
        scenario.onActivity(activity -> invoke(activity, "showRenameDialog",
                new Class<?>[] {AppInfo.class, boolean.class, int.class},
                app, false, focusHint));
        AlertDialog dialog = awaitRenameDialogReady(scenario, expectReset);
        focusRenameInput(scenario);
        return dialog;
    }

    private static AlertDialog awaitRenameDialogReady(
            ActivityScenario<LauncherActivity> scenario, boolean expectReset) {
        AlertDialog dialog = awaitValue(scenario, "visible Rename dialog with actions", activity -> {
            AlertDialog current = (AlertDialog) field(activity, "renameDialog");
            EditText input = (EditText) field(activity, "renameInput");
            if (current == null || !current.isShowing() || input == null) {
                return null;
            }
            if (current.getButton(AlertDialog.BUTTON_POSITIVE) == null
                    || current.getButton(AlertDialog.BUTTON_NEGATIVE) == null
                    || (expectReset
                    && current.getButton(AlertDialog.BUTTON_NEUTRAL) == null)) {
                return null;
            }
            return current;
        });
        return awaitValue(scenario, "focusable Rename actions", activity -> {
            if (!dialog.getButton(AlertDialog.BUTTON_POSITIVE).isFocusable()
                    || !dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isFocusable()) {
                return null;
            }
            if (expectReset
                    && !dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isFocusable()) {
                return null;
            }
            return dialog;
        });
    }

    private static void focusRenameInput(ActivityScenario<LauncherActivity> scenario) {
        scenario.onActivity(activity -> {
            EditText input = (EditText) field(activity, "renameInput");
            if (!input.hasFocus()) {
                input.setFocusableInTouchMode(true);
                input.requestFocus();
            }
        });
        awaitValue(scenario, "focused Rename input", activity -> {
            EditText input = (EditText) field(activity, "renameInput");
            return input != null && input.hasFocus() ? input : null;
        });
    }

    private static View awaitVisibleViewField(
            ActivityScenario<LauncherActivity> scenario, String fieldName) {
        return awaitValue(scenario, "visible " + fieldName, activity -> {
            View view = (View) field(activity, fieldName);
            return view != null && view.getVisibility() == View.VISIBLE && view.isShown()
                    ? view : null;
        });
    }

    private static View focusHomeCell(ActivityScenario<LauncherActivity> scenario) {
        return awaitValue(scenario, "focused home CellView", activity -> {
            View shelf = (View) field(activity, "shelf");
            if (shelf == null || shelf.getVisibility() != View.VISIBLE || !shelf.isShown()) {
                return null;
            }
            invoke(shelf, "requestFocusOnIndex",
                    new Class<?>[] {int.class, boolean.class}, 0, true);
            View focused = activity.getWindow().getDecorView().findFocus();
            return focused != null
                    && focused.getClass().getSimpleName().equals("CellView")
                    ? focused : null;
        });
    }

    private static View awaitFocusedApp(ActivityScenario<LauncherActivity> scenario,
                                        String classSuffix, String identity,
                                        String expectedLabel) {
        return awaitValue(scenario, "focused " + classSuffix + " bound to " + identity
                + " with label " + expectedLabel, activity -> {
            View focused = activity.getWindow().getDecorView().findFocus();
            if (focused == null
                    || !focused.getClass().getSimpleName().equals(classSuffix)) {
                return null;
            }
            AppInfo app = (AppInfo) field(focused, "boundApp");
            return app != null && identity.equals(app.packageName)
                    && expectedLabel.equals(app.label) ? focused : null;
        });
    }

    private static View awaitFocusedCell(ActivityScenario<LauncherActivity> scenario,
                                         String classSuffix) {
        return awaitValue(scenario, "focused " + classSuffix, activity -> {
            View focused = activity.getWindow().getDecorView().findFocus();
            return focused != null && focused.getClass().getSimpleName().equals(classSuffix)
                    ? focused : null;
        });
    }

    /** Wait for an observable UI postcondition; never guess animation or looper timing. */
    private static <T> T awaitValue(ActivityScenario<LauncherActivity> scenario,
                                    String description,
                                    Function<LauncherActivity, T> probe) {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS;
        do {
            getInstrumentation().waitForIdleSync();
            AtomicReference<T> result = new AtomicReference<>();
            scenario.onActivity(activity -> result.set(probe.apply(activity)));
            if (result.get() != null) return result.get();
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Timed out waiting for " + description);
        return null;
    }

    private static SharedPreferences launcherPrefs() {
        Context context = getInstrumentation().getTargetContext();
        String prefsName = (String) staticField(LauncherActivity.class, "PREFS");
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }

    /** Put back only the prefs a test changed, named by their KEY_ constants.
     *  Clearing everything also drops home_count, which the launcher saved for
     *  itself, and the cached app list then pins the next home row to one app. */
    @SuppressWarnings("unchecked")
    private static void restoreLauncherPrefs(java.util.Map<String, ?> saved,
                                             String... keyFields) {
        SharedPreferences.Editor editor = launcherPrefs().edit();
        for (String keyField : keyFields) {
            String key = (String) staticField(LauncherActivity.class, keyField);
            Object value = saved.get(key);
            if (value == null) editor.remove(key);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof java.util.Set) {
                editor.putStringSet(key, (java.util.Set<String>) value);
            }
        }
        if (!editor.commit()) throw new AssertionError("Failed to restore launcher prefs");
    }

    private static void setSystemWallpaper(LauncherActivity activity, boolean on) {
        invoke(activity, "setSystemWallpaper",
                new Class<?>[] {boolean.class, boolean.class}, on, true);
    }

    private static void assertSystemWallpaper(LauncherActivity activity, boolean on) {
        int flags = activity.getWindow().getAttributes().flags;
        assertEquals("FLAG_SHOW_WALLPAPER", on,
                (flags & WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER) != 0);
        assertEquals("launcher wallpaper visibility", on ? View.GONE : View.VISIBLE,
                ((View) field(activity, "wallpaperFront")).getVisibility());
        android.graphics.drawable.Drawable background =
                ((View) field(activity, "root")).getBackground();
        assertEquals("root background", on ? Color.TRANSPARENT : Color.BLACK,
                ((android.graphics.drawable.ColorDrawable) background).getColor());
        String key = (String) staticField(LauncherActivity.class, "KEY_SYSTEM_WALLPAPER");
        assertEquals("persisted setting", on, launcherPrefs().getBoolean(key, false));
    }

    /** Text of the Favorites background row: child 0 is the label, 1 the value. */
    private static String favoritesBackgroundRowText(LauncherActivity activity, int child) {
        android.widget.LinearLayout column = (android.widget.LinearLayout)
                field(activity, "settingsColumn");
        ViewGroup row = (ViewGroup) column.getChildAt(4);
        return ((android.widget.TextView) row.getChildAt(child)).getText().toString();
    }

    private static void assertFavoritesBackground(LauncherActivity activity, boolean on) {
        assertEquals("favorites background setting", on,
                field(activity, "favoritesBackground"));
        assertEquals("favorites blur layer", on,
                field(activity, "favoritesBlurLayer") != null);
        assertEquals("shelf glass background", on,
                ((View) field(activity, "shelf")).getBackground() != null);
    }

    private static void clearPersistedCustomNames() {
        Context context = getInstrumentation().getTargetContext();
        String prefsName = (String) staticField(LauncherActivity.class, "PREFS");
        String key = (String) staticField(LauncherActivity.class, "KEY_CUSTOM_NAMES");
        boolean committed = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().remove(key).commit();
        if (!committed) throw new AssertionError("Failed to clear custom-name test state");
    }

    private static boolean isDescendant(View ancestor, View candidate) {
        if (candidate == ancestor) return true;
        android.view.ViewParent current = candidate != null ? candidate.getParent() : null;
        while (current != null) {
            if (current == ancestor) return true;
            current = current.getParent();
        }
        return false;
    }

    private static Object staticField(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object invokeForResult(Object target, String name,
                                          Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void invoke(Object target, String name) {
        invoke(target, name, new Class<?>[0]);
    }

    private static void invoke(Object target, String name, Class<?>[] parameterTypes,
                               Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
