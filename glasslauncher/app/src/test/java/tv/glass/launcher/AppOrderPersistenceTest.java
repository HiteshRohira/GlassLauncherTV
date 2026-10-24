package tv.glass.launcher;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** The user's order survives apps that are briefly missing (updates). */
public class AppOrderPersistenceTest {
    private static String save(String previous, String... current) {
        return AppOrder.serialize(AppOrder.merge(previous, Arrays.asList(current)));
    }

    @Test public void anUpdatingAppKeepsItsPlace() {
        // prime is mid-update while the user moves youtube to the front
        assertEquals("youtube,plezy,prime,jiotv", save("plezy,youtube,prime,jiotv", "youtube", "plezy", "jiotv"));
    }

    @Test public void missingFirstAppStaysFirst() {
        assertEquals("plezy,youtube,prime", save("plezy,youtube,prime", "youtube", "prime"));
    }

    @Test public void consecutiveMissingAppsKeepTheirRelativeOrder() {
        assertEquals("a,b,c,d,e", save("a,b,c,d,e", "a", "e"));
    }

    @Test public void newAppsAndMovesAreKept() {
        assertEquals("c,a,new,b", save("a,b,c", "c", "a", "new", "b"));
    }

    @Test public void nothingStoredYet() {
        assertEquals("a,b", save(null, "a", "b"));
        assertEquals("a,b", save("", "a", "b"));
    }

    @Test public void realUninstallLeavesTheOrder() {
        assertEquals("a,c", AppOrder.without("a,b,c", "b"));
        assertEquals("a,b", AppOrder.without("a,b", "zzz"));
    }

    @Test public void whenTheAppReturnsItSortsBackIntoPlace() {
        String stored = save("plezy,youtube,prime,jiotv", "plezy", "youtube", "jiotv");
        List<String> back = Arrays.asList("jiotv", "plezy", "prime", "youtube");
        java.util.Map<String, Integer> rank = AppOrder.parse(stored);
        java.util.ArrayList<String> sorted = new java.util.ArrayList<>(back);
        sorted.sort((x, y) -> rank.get(x) - rank.get(y));
        assertEquals(Arrays.asList("plezy", "youtube", "prime", "jiotv"), sorted);
    }
}
