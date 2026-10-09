package tv.glass.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fixed shell-command plan used by the one-time local-ADB Google TV setup.
 *
 * <p>No user-supplied value is ever interpolated into a command. Keeping the
 * complete command surface here makes the elevated operation easy to audit and
 * prevents the setup UI from turning into an arbitrary shell.
 */
final class GoogleTvSetupPlan {

    static final String LAUNCHER_ACTIVITY = "tv.glass.launcher.LauncherActivity";
    static final String GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx";
    static final String GOOGLE_TV_SETUP = "com.google.android.tungsten.setupwraith";

    private GoogleTvSetupPlan() { }

    static String launcherHomeComponent(String applicationId) {
        if (applicationId == null || !applicationId.matches("[A-Za-z0-9_.$]+")) {
            throw new IllegalArgumentException("Invalid application ID");
        }
        return applicationId + "/" + LAUNCHER_ACTIVITY;
    }

    static String setHomeCommand(String component) {
        if (!isSafeComponent(component)) {
            throw new IllegalArgumentException("Invalid HOME component");
        }
        return "cmd package set-home-activity --user 0 " + component;
    }

    static boolean isSafeComponent(String component) {
        if (component == null) return false;
        return component.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+");
    }

    static List<String> setupCommands(String homeComponent,
                                      boolean hasGoogleTvLauncher, boolean hasGoogleTvSetup) {
        ArrayList<String> commands = new ArrayList<>(4);
        // Best effort before disabling the competing packages, then repeat
        // after the package state changes so the final HOME resolution wins.
        commands.add(setHomeCommand(homeComponent));
        if (hasGoogleTvSetup) {
            commands.add("pm disable-user --user 0 " + GOOGLE_TV_SETUP);
        }
        if (hasGoogleTvLauncher) {
            commands.add("pm disable-user --user 0 " + GOOGLE_TV_LAUNCHER);
        }
        commands.add(setHomeCommand(homeComponent));
        return Collections.unmodifiableList(commands);
    }

    static List<String> restoreCommands(String homeComponent,
                                        boolean hasGoogleTvLauncher, boolean hasGoogleTvSetup,
                                        String previousHomeComponent) {
        if (!isSafeComponent(homeComponent)) {
            throw new IllegalArgumentException("Invalid current HOME component");
        }
        ArrayList<String> commands = new ArrayList<>(3);
        if (hasGoogleTvLauncher) {
            commands.add("pm enable --user 0 " + GOOGLE_TV_LAUNCHER);
        }
        if (hasGoogleTvSetup) {
            commands.add("pm enable --user 0 " + GOOGLE_TV_SETUP);
        }
        if (isSafeComponent(previousHomeComponent)
                && !homeComponent.equals(previousHomeComponent)) {
            commands.add(setHomeCommand(previousHomeComponent));
        }
        return Collections.unmodifiableList(commands);
    }

    static boolean responseLooksSuccessful(int exitCode, String output) {
        if (exitCode != 0) return false;
        if (output == null || output.isEmpty()) return true;
        String lower = output.toLowerCase(java.util.Locale.ROOT);
        return !lower.contains("error:")
                && !lower.contains("exception")
                && !lower.contains("unknown command")
                && !lower.contains("not allowed");
    }
}
