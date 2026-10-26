package tv.glass.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class GoogleTvSetupPlanTest {

    private static final String DEBUG_HOME =
            "tv.glass.launcher.debug/tv.glass.launcher.LauncherActivity";

    @Test public void launcherHomeComponent_usesRuntimeApplicationId() {
        assertEquals("tv.glass.launcher/tv.glass.launcher.LauncherActivity",
                GoogleTvSetupPlan.launcherHomeComponent("tv.glass.launcher"));
        assertEquals(DEBUG_HOME,
                GoogleTvSetupPlan.launcherHomeComponent("tv.glass.launcher.debug"));
    }

    @Test public void launcherHomeComponent_supportsMirageApplicationId() {
        assertEquals("tv.mirage.launcher/tv.glass.launcher.LauncherActivity",
                GoogleTvSetupPlan.launcherHomeComponent("tv.mirage.launcher"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void launcherHomeComponent_rejectsUnsafeApplicationId() {
        GoogleTvSetupPlan.launcherHomeComponent("tv.glass.launcher; pm disable-user other");
    }

    @Test public void setupCommands_setHomeBeforeAndAfterBothGooglePackages() {
        List<String> commands = GoogleTvSetupPlan.setupCommands(DEBUG_HOME, true, true);

        assertEquals(Arrays.asList(
                "cmd package set-home-activity --user 0 tv.glass.launcher.debug/tv.glass.launcher.LauncherActivity",
                "pm disable-user --user 0 com.google.android.tungsten.setupwraith",
                "pm disable-user --user 0 com.google.android.apps.tv.launcherx",
                "cmd package set-home-activity --user 0 tv.glass.launcher.debug/tv.glass.launcher.LauncherActivity"
        ), commands);
    }

    @Test public void setupCommands_skipPackagesNotInstalledOnDevice() {
        assertEquals(Arrays.asList(
                GoogleTvSetupPlan.setHomeCommand(DEBUG_HOME),
                GoogleTvSetupPlan.setHomeCommand(DEBUG_HOME)
        ), GoogleTvSetupPlan.setupCommands(DEBUG_HOME, false, false));
    }

    @Test public void restoreCommands_enableOnlyInstalledGooglePackages() {
        assertEquals(Collections.singletonList(
                        "pm enable --user 0 com.google.android.apps.tv.launcherx"),
                GoogleTvSetupPlan.restoreCommands(DEBUG_HOME, true, false, null));
        assertEquals(Collections.singletonList(
                        "pm enable --user 0 com.google.android.tungsten.setupwraith"),
                GoogleTvSetupPlan.restoreCommands(DEBUG_HOME, false, true, null));
    }

    @Test public void restoreCommands_enableLauncherBeforeSetupPackage() {
        assertEquals(Arrays.asList(
                "pm enable --user 0 com.google.android.apps.tv.launcherx",
                "pm enable --user 0 com.google.android.tungsten.setupwraith"
        ), GoogleTvSetupPlan.restoreCommands(DEBUG_HOME, true, true, null));
    }

    @Test public void restoreCommands_restorePreviousHomeAfterPackages() {
        assertEquals(Arrays.asList(
                "pm enable --user 0 com.google.android.apps.tv.launcherx",
                "pm enable --user 0 com.google.android.tungsten.setupwraith",
                "cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity"
        ), GoogleTvSetupPlan.restoreCommands(DEBUG_HOME, true, true,
                "com.google.android.apps.tv.launcherx/.home.HomeActivity"));
    }

    @Test public void restoreCommands_rejectUnsafeStoredComponent() {
        assertEquals(Collections.singletonList(
                        "pm enable --user 0 com.google.android.apps.tv.launcherx"),
                GoogleTvSetupPlan.restoreCommands(DEBUG_HOME, true, false,
                        "com.example/.Home; pm disable-user com.other"));
        assertFalse(GoogleTvSetupPlan.isSafeComponent("android/.ResolverActivity --user 10"));
    }

    @Test public void responseLooksSuccessful_acceptsCleanZeroExit() {
        assertTrue(GoogleTvSetupPlan.responseLooksSuccessful(0, ""));
        assertTrue(GoogleTvSetupPlan.responseLooksSuccessful(
                0, "Package com.google.android.apps.tv.launcherx new state: disabled-user"));
    }

    @Test public void responseLooksSuccessful_rejectsExitCodeAndShellErrors() {
        assertFalse(GoogleTvSetupPlan.responseLooksSuccessful(1, ""));
        assertFalse(GoogleTvSetupPlan.responseLooksSuccessful(0, "Error: package unavailable"));
        assertFalse(GoogleTvSetupPlan.responseLooksSuccessful(0, "java.lang.SecurityException"));
        assertFalse(GoogleTvSetupPlan.responseLooksSuccessful(0, "Unknown command: set-home-activity"));
        assertFalse(GoogleTvSetupPlan.responseLooksSuccessful(0, "Operation not allowed"));
    }
}
