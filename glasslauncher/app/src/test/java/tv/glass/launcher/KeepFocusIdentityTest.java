package tv.glass.launcher;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** After apps are added or removed, focus follows the app, not the slot. */
public class KeepFocusIdentityTest {
    private static List<AppInfo> apps(String... pkgs) {
        List<AppInfo> out = new ArrayList<>();
        for (String p : pkgs) out.add(new AppInfo(p, p, null, null));
        return out;
    }

    @Test public void removingAnEarlierAppKeepsFocusOnTheSameApp() {
        // focus on "c" (index 2); "a" is uninstalled
        assertEquals(1, AppOrder.keepIndexFor(apps("b", "c", "d"), "c", 2, 4));
    }

    @Test public void installingAnEarlierAppKeepsFocusOnTheSameApp() {
        assertEquals(2, AppOrder.keepIndexFor(apps("new", "a", "b"), "b", 1, 2));
    }

    @Test public void removingTheFocusedAppFallsBackToTheSameSlot() {
        assertEquals(1, AppOrder.keepIndexFor(apps("a", "c"), "b", 1, 3));
    }

    @Test public void sameSizeRefreshKeepsTheIndex() {
        // a reorder sets the intended index itself
        assertEquals(0, AppOrder.keepIndexFor(apps("b", "a", "c"), "a", 0, 3));
    }

    @Test public void indexIsClamped() {
        assertEquals(1, AppOrder.keepIndexFor(apps("a", "b"), null, 5, 0));
    }
}
