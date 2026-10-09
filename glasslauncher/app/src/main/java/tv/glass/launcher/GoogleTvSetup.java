package tv.glass.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

/**
 * One-time Google TV launcher takeover over the device's local ADB daemon.
 *
 * <p>The caller must run {@link #setup} and {@link #restore} off the main
 * thread. The only elevated operations are the fixed commands exposed by
 * {@link GoogleTvSetupPlan}; this class deliberately has no arbitrary-shell
 * entry point.
 */
final class GoogleTvSetup {

    private static final String LOCAL_ADB_HOST = "127.0.0.1";
    private static final int LOCAL_ADB_PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int AUTH_TIMEOUT_MS = 45_000;
    private static final String SETUP_PREFS = "google_tv_setup";
    private static final String KEY_PREVIOUS_HOME = "previous_home_component";

    enum State {
        ACTIVE,
        PARTIAL,
        NOT_CONFIGURED
    }

    static final class Status {
        final State state;
        final String homePackage;
        final boolean googleTvLauncherInstalled;
        final boolean googleTvLauncherEnabled;
        final boolean googleTvSetupInstalled;
        final boolean googleTvSetupEnabled;

        Status(State state, String homePackage,
               boolean googleTvLauncherInstalled, boolean googleTvLauncherEnabled,
               boolean googleTvSetupInstalled, boolean googleTvSetupEnabled) {
            this.state = state;
            this.homePackage = homePackage;
            this.googleTvLauncherInstalled = googleTvLauncherInstalled;
            this.googleTvLauncherEnabled = googleTvLauncherEnabled;
            this.googleTvSetupInstalled = googleTvSetupInstalled;
            this.googleTvSetupEnabled = googleTvSetupEnabled;
        }

        boolean isActive() {
            return state == State.ACTIVE;
        }

        boolean googlePackagesRestored() {
            return (!googleTvLauncherInstalled || googleTvLauncherEnabled)
                    && (!googleTvSetupInstalled || googleTvSetupEnabled);
        }
    }

    static final class Result {
        final boolean success;
        final String message;
        final Status status;

        private Result(boolean success, String message, Status status) {
            this.success = success;
            this.message = message;
            this.status = status;
        }

        static Result success(String message, Status status) {
            return new Result(true, message, status);
        }

        static Result failure(String message, Status status) {
            return new Result(false, message, status);
        }
    }

    private GoogleTvSetup() { }

    static Status inspect(Context context) {
        PackageManager pm = context.getPackageManager();
        PackageState launcher = packageState(pm, GoogleTvSetupPlan.GOOGLE_TV_LAUNCHER);
        PackageState setup = packageState(pm, GoogleTvSetupPlan.GOOGLE_TV_SETUP);
        String homePackage = resolveHomePackage(pm);
        boolean glassIsHome = context.getPackageName().equals(homePackage);
        boolean googlePackagesDisabled = (!launcher.installed || !launcher.enabled)
                && (!setup.installed || !setup.enabled);
        boolean anyGooglePackageDisabled = (launcher.installed && !launcher.enabled)
                || (setup.installed && !setup.enabled);

        State state = glassIsHome && googlePackagesDisabled
                ? State.ACTIVE
                : (glassIsHome || anyGooglePackageDisabled ? State.PARTIAL : State.NOT_CONFIGURED);
        return new Status(state, homePackage,
                launcher.installed, launcher.enabled, setup.installed, setup.enabled);
    }

    static Result setup(Context context) {
        Context appContext = context.getApplicationContext();
        Status before = inspect(appContext);
        String currentHome = currentHomeComponent(appContext);
        String previousHome = resolveHomeComponent(appContext.getPackageManager());
        if (GoogleTvSetupPlan.isSafeComponent(previousHome)
                && !previousHome.startsWith("android/")
                && !currentHome.equals(previousHome)) {
            appContext.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_PREVIOUS_HOME, previousHome).apply();
        }
        List<String> commands = GoogleTvSetupPlan.setupCommands(currentHome,
                before.googleTvLauncherInstalled, before.googleTvSetupInstalled);
        String failure = execute(appContext, commands, true);
        pauseForPackageManager();
        Status after = inspect(appContext);
        if (after.isActive()) {
            return Result.success(
                    "Glass Launcher is now the default Home app. The Google TV launcher "
                            + "packages are disabled, and this should survive normal restarts.",
                    after);
        }
        if (failure != null) {
            return Result.failure(failure + "\n\n" + verificationSummary(after), after);
        }
        return Result.failure(
                "The commands completed, but Android did not confirm the full launcher takeover.\n\n"
                        + verificationSummary(after), after);
    }

    static Result restore(Context context) {
        Context appContext = context.getApplicationContext();
        Status before = inspect(appContext);
        if (!before.googleTvLauncherInstalled && !before.googleTvSetupInstalled) {
            return Result.failure("No supported Google TV launcher packages were found on this device.", before);
        }
        String currentHome = currentHomeComponent(appContext);
        SharedPreferences prefs = appContext.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE);
        String previousHome = prefs.getString(KEY_PREVIOUS_HOME, null);
        List<String> commands = GoogleTvSetupPlan.restoreCommands(currentHome,
                before.googleTvLauncherInstalled, before.googleTvSetupInstalled, previousHome);
        String failure = execute(appContext, commands, false);
        pauseForPackageManager();
        Status after = inspect(appContext);
        if (failure == null && after.googlePackagesRestored()) {
            prefs.edit().remove(KEY_PREVIOUS_HOME).apply();
            return Result.success(
                    "Google TV Home is enabled again. Press Home and choose Google TV Home "
                            + "if Android asks which launcher to use.", after);
        }
        return Result.failure(
                (failure != null ? failure + "\n\n" : "") + verificationSummary(after), after);
    }

    /**
     * @param ignoreFirstFailure setup's first set-home command is deliberately
     *                           best effort; it is repeated after package disable.
     * @return the first required-command failure, or {@code null}.
     */
    private static String execute(Context context, List<String> commands,
                                  boolean ignoreFirstFailure) {
        try (Dadb dadb = connect(context)) {
            String firstFailure = null;
            for (int i = 0; i < commands.size(); i++) {
                String command = commands.get(i);
                AdbShellResponse response = dadb.shell(command);
                String output = response.getAllOutput() == null
                        ? "" : response.getAllOutput().trim();
                if (!GoogleTvSetupPlan.responseLooksSuccessful(response.getExitCode(), output)
                        && !(ignoreFirstFailure && i == 0)) {
                    firstFailure = "Command failed: " + command
                            + (output.isEmpty() ? "" : "\n" + output);
                    break;
                }
            }
            return firstFailure;
        } catch (ConnectException e) {
            return "Could not connect to local ADB at 127.0.0.1:5555. Enable Network "
                    + "debugging in Developer options, then try again.";
        } catch (SocketTimeoutException e) {
            return "ADB authorization timed out. Try again and accept the debugging "
                    + "authorization prompt on the TV.";
        } catch (Exception e) {
            return "ADB setup failed: " + usefulMessage(e);
        }
    }

    private static Dadb connect(Context context) throws IOException {
        File privateKey = new File(context.getNoBackupFilesDir(), "glasslauncher_adbkey");
        File publicKey = new File(context.getNoBackupFilesDir(), "glasslauncher_adbkey.pub");
        if (!privateKey.isFile() || !publicKey.isFile()) {
            AdbKeyPair.generate(privateKey, publicKey);
        }
        AdbKeyPair keyPair = AdbKeyPair.read(privateKey, publicKey);
        return Dadb.create(LOCAL_ADB_HOST, LOCAL_ADB_PORT, keyPair,
                CONNECT_TIMEOUT_MS, AUTH_TIMEOUT_MS);
    }

    private static String usefulMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message.trim();
    }

    private static void pauseForPackageManager() {
        try {
            Thread.sleep(250L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String verificationSummary(Status status) {
        String home = status.homePackage == null ? "none" : status.homePackage;
        return "Current Home: " + home
                + "\nGoogle TV launcher: " + packageDescription(
                        status.googleTvLauncherInstalled, status.googleTvLauncherEnabled)
                + "\nGoogle TV setup: " + packageDescription(
                        status.googleTvSetupInstalled, status.googleTvSetupEnabled);
    }

    private static String packageDescription(boolean installed, boolean enabled) {
        return !installed ? "not installed" : (enabled ? "enabled" : "disabled");
    }

    private static String currentHomeComponent(Context context) {
        return new ComponentName(context, LauncherActivity.class).flattenToShortString();
    }

    private static String resolveHomeComponent(PackageManager pm) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo result = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (result == null || result.activityInfo == null) return null;
            return new ComponentName(result.activityInfo.packageName, result.activityInfo.name)
                    .flattenToShortString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String resolveHomePackage(PackageManager pm) {
        String component = resolveHomeComponent(pm);
        if (!GoogleTvSetupPlan.isSafeComponent(component)) return null;
        int slash = component.indexOf('/');
        return slash <= 0 ? null : component.substring(0, slash);
    }

    @SuppressWarnings("deprecation")
    private static PackageState packageState(PackageManager pm, String packageName) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(
                    packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            int setting = pm.getApplicationEnabledSetting(packageName);
            boolean explicitlyDisabled = setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
            return new PackageState(true, info.enabled && !explicitlyDisabled);
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException ignored) {
            return new PackageState(false, false);
        }
    }

    private static final class PackageState {
        final boolean installed;
        final boolean enabled;

        PackageState(boolean installed, boolean enabled) {
            this.installed = installed;
            this.enabled = enabled;
        }
    }
}
