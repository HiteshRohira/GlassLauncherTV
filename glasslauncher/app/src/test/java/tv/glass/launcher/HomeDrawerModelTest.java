package tv.glass.launcher;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link HomeDrawerModel} (v1.5.0, COLS = 6). Runs on the JVM
 * (no Android emulator), exercising the "home favourites row + pull-down
 * drawer" geometry, focus navigation, and the insert-based Move rules
 * (including promote / demote across the favourites row).
 */
public class HomeDrawerModelTest {

    private static List<String> list(int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add("p" + i);
        return l;
    }

    // ── homeCount hygiene ────────────────────────────────────────────────

    @Test public void cols_isSix() { assertEquals(6, HomeDrawerModel.COLS); }

    @Test public void clamp_bounds() {
        assertEquals(0, HomeDrawerModel.clampHomeCount(-3, 20));
        assertEquals(6, HomeDrawerModel.clampHomeCount(100, 20)); // cap = COLS = 6
        assertEquals(5, HomeDrawerModel.clampHomeCount(5, 20));
        assertEquals(3, HomeDrawerModel.clampHomeCount(5, 3));
        assertEquals(0, HomeDrawerModel.clampHomeCount(5, 0));
    }

    @Test public void defaultHomeCount_isFirstSix() {
        assertEquals(6, HomeDrawerModel.defaultHomeCount(20));
        assertEquals(6, HomeDrawerModel.defaultHomeCount(6));
        assertEquals(5, HomeDrawerModel.defaultHomeCount(5));
        assertEquals(0, HomeDrawerModel.defaultHomeCount(0));
    }

    // ── geometry ─────────────────────────────────────────────────────────

    @Test public void geometry_homeFiveThenGrid() {
        // 5 home apps + 20 total → row0=5, then 15 across rows 1..3 (6,6,3).
        int size = 20, hc = 5;
        assertEquals(4, HomeDrawerModel.rowCount(size, hc));
        assertEquals(5, HomeDrawerModel.rowLength(0, size, hc));
        assertEquals(6, HomeDrawerModel.rowLength(1, size, hc));
        assertEquals(6, HomeDrawerModel.rowLength(2, size, hc));
        assertEquals(3, HomeDrawerModel.rowLength(3, size, hc));
        assertEquals(0, HomeDrawerModel.rowOf(4, hc));
        assertEquals(4, HomeDrawerModel.colOf(4, hc));
        assertEquals(1, HomeDrawerModel.rowOf(5, hc));
        assertEquals(0, HomeDrawerModel.colOf(5, hc));
        assertEquals(2, HomeDrawerModel.rowOf(11, hc));
        assertEquals(0, HomeDrawerModel.colOf(11, hc));
        assertEquals(11, HomeDrawerModel.indexAt(2, 0, size, hc));
        assertEquals(-1, HomeDrawerModel.indexAt(3, 3, size, hc)); // last row only 3 wide
        assertEquals(-1, HomeDrawerModel.indexAt(0, 5, size, hc)); // home row only 5 wide
    }

    @Test public void geometry_homeFull() {
        int size = 20, hc = 6;
        assertEquals(4, HomeDrawerModel.rowCount(size, hc)); // 6 + ceil(14/6)=3
        assertEquals(6, HomeDrawerModel.rowLength(0, size, hc));
        assertEquals(6, HomeDrawerModel.rowLength(1, size, hc));
        assertEquals(2, HomeDrawerModel.rowLength(3, size, hc)); // 20-6=14 -> 6,6,2
        assertEquals(0, HomeDrawerModel.rowOf(5, hc));
        assertEquals(1, HomeDrawerModel.rowOf(6, hc));
    }

    @Test public void geometry_homeZero_plainGrid() {
        int size = 10, hc = 0;
        assertEquals(0, HomeDrawerModel.nonHomeBaseRow(hc));
        assertEquals(2, HomeDrawerModel.rowCount(size, hc)); // ceil(10/6) = 2
        assertEquals(0, HomeDrawerModel.rowOf(5, hc));
        assertEquals(1, HomeDrawerModel.rowOf(6, hc));
        assertEquals(6, HomeDrawerModel.indexAt(1, 0, size, hc));
    }

    @Test public void geometry_allHome_noSecondRow() {
        int size = 5, hc = 5;
        assertEquals(1, HomeDrawerModel.rowCount(size, hc));
        assertEquals(5, HomeDrawerModel.rowLength(0, size, hc));
        assertEquals(0, HomeDrawerModel.rowLength(1, size, hc));
    }

    @Test public void geometry_empty() {
        assertEquals(0, HomeDrawerModel.rowCount(0, 0));
        assertEquals(0, HomeDrawerModel.rowLength(0, 0, 0));
        assertEquals(-1, HomeDrawerModel.indexAt(0, 0, 0, 0));
    }

    // ── navigation ───────────────────────────────────────────────────────

    @Test public void nav_leftRight_stopsAtRowEdges() {
        int size = 20, hc = 5; // row1 = indices 5..10 (cols 0..5)
        assertEquals(5, HomeDrawerModel.navLeft(5, size, hc));   // col0 of row1 → stay
        assertEquals(5, HomeDrawerModel.navLeft(6, size, hc));   // col1 → col0
        assertEquals(7, HomeDrawerModel.navRight(6, size, hc));  // col1 → col2 within row1
        assertEquals(10, HomeDrawerModel.navRight(10, size, hc)); // row1 right edge → stay
        assertEquals(4, HomeDrawerModel.navRight(4, size, hc));  // home right edge (hc=5) → stay
        assertEquals(3, HomeDrawerModel.navLeft(4, size, hc));
    }

    @Test public void nav_up_topRowCloses() {
        int size = 20, hc = 5;
        assertEquals(HomeDrawerModel.CLOSE_DRAWER, HomeDrawerModel.navUp(0, size, hc));
        assertEquals(HomeDrawerModel.CLOSE_DRAWER, HomeDrawerModel.navUp(4, size, hc));
        assertEquals(HomeDrawerModel.CLOSE_DRAWER, HomeDrawerModel.navUp(0, size, 0));
    }

    @Test public void nav_up_snapsToShorterHomeRow() {
        int size = 20, hc = 5;
        // row1 col5 (index 10) UP → home only 5 wide → snap to last home cell (4)
        assertEquals(4, HomeDrawerModel.navUp(10, size, hc));
        // row1 col2 (index 7) UP → home col2 (index 2)
        assertEquals(2, HomeDrawerModel.navUp(7, size, hc));
    }

    @Test public void nav_upEnteringFavoritesClosesDrawerImmediately() {
        int size = 20, hc = 5;
        int homeTarget = HomeDrawerModel.navUp(7, size, hc);
        assertEquals(2, homeTarget);
        assertEquals(true, HomeDrawerModel.shouldCloseOnNavUp(
                7, homeTarget, size, hc));

        int gridTarget = HomeDrawerModel.navUp(13, size, hc);
        assertEquals(7, gridTarget);
        assertEquals(false, HomeDrawerModel.shouldCloseOnNavUp(
                13, gridTarget, size, hc));
        assertEquals(false, HomeDrawerModel.shouldCloseOnNavUp(
                6, HomeDrawerModel.navUp(6, size, 0), size, 0));
    }

    @Test public void nav_down_snapsToShorterLastRow() {
        int size = 20, hc = 5; // rows: 5, 6, 6, 3 ; last row cols 0..2
        // home col4 (index 4) DOWN → row1 col4 (index 9)
        assertEquals(9, HomeDrawerModel.navDown(4, size, hc));
        // row2 col5 (index 16) DOWN → last row only 3 wide → snap to col2 (index 19)
        assertEquals(19, HomeDrawerModel.navDown(16, size, hc));
        // last row → stay
        assertEquals(19, HomeDrawerModel.navDown(19, size, hc));
    }

    @Test public void geometry_runtimeColumnsReflowAndClampFavorites() {
        assertEquals(4, HomeDrawerModel.clampHomeCount(4, 6, 20));
        assertEquals(5, HomeDrawerModel.rowCount(4, 20, 6));
        assertEquals(3, HomeDrawerModel.rowCount(7, 20, 7));
        assertEquals(1, HomeDrawerModel.rowOf(4, 4, 4));
        assertEquals(0, HomeDrawerModel.colOf(4, 4, 4));
        assertEquals(1, HomeDrawerModel.rowOf(7, 7, 7));
        assertEquals(0, HomeDrawerModel.colOf(7, 7, 7));
        assertEquals(11, HomeDrawerModel.indexAt(7, 1, 4, 20, 7));
    }

    @Test public void navigation_runtimeColumnsUseMatchingVerticalSlots() {
        assertEquals(2, HomeDrawerModel.navUp(4, 6, 20, 4));
        assertEquals(2, HomeDrawerModel.navUp(7, 9, 20, 7));
        assertEquals(6, HomeDrawerModel.navDown(4, 2, 20, 4));
        assertEquals(9, HomeDrawerModel.navDown(7, 2, 20, 7));
    }

    // ── Move mode: insert, promote / demote ──────────────────────────────

    @Test public void move_up_promote_insertsAtColumnAndGrowsHome() {
        List<String> order = list(20);
        // index 7 = row1 col2 (row1 starts at 5). Home not full → grows.
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveUp(order, 7, 5);
        assertEquals(6, r.homeCount);
        assertEquals(2, r.index);
        assertEquals(Arrays.asList("p0", "p1", "p7", "p2", "p3", "p4", "p5", "p6", "p8"),
                order.subList(0, 9));
    }

    @Test public void move_up_promote_pastShortHomeRow_appends() {
        List<String> order = list(20);
        // index 8 = row1 col5 (row1 starts at 3); home is only 3 wide.
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveUp(order, 8, 3);
        assertEquals(4, r.homeCount);
        assertEquals(3, r.index);
        assertEquals(Arrays.asList("p0", "p1", "p2", "p8", "p3", "p4"), order.subList(0, 6));
    }

    @Test public void move_up_fullHome_insertsAndPushesLastFavoriteDown() {
        List<String> order = list(20);
        // index 8 = row1 col2. Full home: inserted at col 2, p5 drops to the
        // first grid slot, and the grid apps before index 8 shift one later.
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveUp(order, 8, 6);
        assertEquals(6, r.homeCount);
        assertEquals(2, r.index);
        assertEquals(Arrays.asList("p0", "p1", "p8", "p2", "p3", "p4"), order.subList(0, 6));
        assertEquals(Arrays.asList("p5", "p6", "p7", "p9"), order.subList(6, 10));
    }

    @Test public void move_up_fullHome_runtimeWidths() {
        List<String> four = list(16);
        HomeDrawerModel.MoveResult r4 = HomeDrawerModel.moveUp(4, four, 6, 4);
        assertEquals(4, r4.homeCount);
        assertEquals(2, r4.index);
        assertEquals(Arrays.asList("p0", "p1", "p6", "p2", "p3", "p4", "p5", "p7"),
                four.subList(0, 8));

        List<String> seven = list(21);
        HomeDrawerModel.MoveResult r7 = HomeDrawerModel.moveUp(7, seven, 9, 7);
        assertEquals(7, r7.homeCount);
        assertEquals(2, r7.index);
        assertEquals(Arrays.asList("p0", "p1", "p9", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p10"),
                seven.subList(0, 11));
    }

    @Test public void move_up_lowerRow_insertsAndShifts() {
        List<String> order = list(30);
        // index 12 = row2 col1 → row1 col1 (index 6); p6..p11 shift one later.
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveUp(order, 12, 5);
        assertEquals(5, r.homeCount);
        assertEquals(6, r.index);
        assertEquals(Arrays.asList("p5", "p12", "p6", "p7", "p8", "p9", "p10", "p11", "p13"),
                order.subList(5, 14));
    }

    @Test public void move_up_inFavorites_noop() {
        List<String> order = list(20);
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveUp(order, 2, 5);
        assertEquals(5, r.homeCount);
        assertEquals(2, r.index);
        assertEquals(list(20), order);
    }

    @Test public void move_down_demote_landsUnderSameColumn() {
        List<String> order = list(20);
        // home index 1 → first grid row col 1; the favourites close the gap.
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveDown(order, 1, 5);
        assertEquals(4, r.homeCount);
        assertEquals(5, r.index);
        assertEquals(1, HomeDrawerModel.colOf(r.index, r.homeCount));
        assertEquals(Arrays.asList("p0", "p2", "p3", "p4", "p5", "p1", "p6", "p7"),
                order.subList(0, 8));
    }

    @Test public void move_down_demote_lastFavorite() {
        List<String> order = list(20);
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveDown(order, 4, 5);
        assertEquals(4, r.homeCount);
        assertEquals(8, r.index);
        assertEquals(4, HomeDrawerModel.colOf(r.index, r.homeCount));
        assertEquals(Arrays.asList("p0", "p1", "p2", "p3", "p5", "p6", "p7", "p8", "p4", "p9"),
                order.subList(0, 10));
    }

    @Test public void move_down_demote_intoShortGrid_goesToEnd() {
        List<String> order = list(7);
        // One grid app; the demoted favourite lands after it.
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveDown(order, 5, 6);
        assertEquals(5, r.homeCount);
        assertEquals(6, r.index);
        assertEquals(Arrays.asList("p0", "p1", "p2", "p3", "p4", "p6", "p5"), order);
    }

    @Test public void move_demoteToZero_thenPromoteBack() {
        List<String> order = list(10);
        HomeDrawerModel.MoveResult d = HomeDrawerModel.moveDown(order, 0, 1);
        assertEquals(0, d.homeCount);
        assertEquals(0, d.index);
        assertEquals(list(10), order);
        HomeDrawerModel.MoveResult u = HomeDrawerModel.moveUp(order, 0, 0);
        assertEquals(1, u.homeCount);
        assertEquals(0, u.index);
        List<String> other = list(10);
        HomeDrawerModel.MoveResult u2 = HomeDrawerModel.moveUp(other, 5, 0);
        assertEquals(1, u2.homeCount);
        assertEquals(0, u2.index);
        assertEquals("p5", other.get(0));
    }

    @Test public void move_down_insertsAndShiftsInsteadOfSwapping() {
        List<String> order = list(30);
        // index 6 = row1 col1 → row2 col1 (index 12); p7..p12 shift one earlier.
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveDown(order, 6, 5);
        assertEquals(5, r.homeCount);
        assertEquals(12, r.index);
        assertEquals(Arrays.asList("p5", "p7", "p8", "p9", "p10", "p11", "p12", "p6", "p13"),
                order.subList(5, 14));
    }

    @Test public void move_down_ontoShortLastRow_goesToEnd() {
        List<String> order = list(20);
        // rows 5, 6, 6, 3: index 16 = row2 col5; last row only 3 wide.
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveDown(order, 16, 5);
        assertEquals(19, r.index);
        assertEquals(Arrays.asList("p17", "p18", "p19", "p16"), order.subList(16, 20));
    }

    @Test public void move_down_lastRow_noop() {
        List<String> order = list(20);
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveDown(order, 18, 5);
        assertEquals(18, r.index);
        assertEquals(list(20), order);
    }

    @Test public void move_downThenUp_restoresOrder() {
        List<String> order = list(30);
        HomeDrawerModel.MoveResult d = HomeDrawerModel.moveDown(order, 12, 5);
        HomeDrawerModel.MoveResult u = HomeDrawerModel.moveUp(order, d.index, d.homeCount);
        assertEquals(12, u.index);
        assertEquals(list(30), order);
    }

    @Test public void move_leftRight_adjacentWithinRow() {
        List<String> order = list(20);
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveRight(order, 1, 5);
        assertEquals(2, r.index);
        assertEquals("p1", order.get(2));
        assertEquals("p2", order.get(1));
        HomeDrawerModel.MoveResult l = HomeDrawerModel.moveLeft(order, 2, 5);
        assertEquals(1, l.index);
        assertEquals(list(20), order);
    }

    @Test public void move_rightAndLeft_wrapBetweenRows() {
        List<String> order = list(20);
        // index 10 = last cell of row1 → first cell of row2.
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveRight(order, 10, 5);
        assertEquals(11, r.index);
        assertEquals(2, HomeDrawerModel.rowOf(r.index, 5));
        assertEquals("p10", order.get(11));
        HomeDrawerModel.MoveResult l = HomeDrawerModel.moveLeft(order, 11, 5);
        assertEquals(10, l.index);
        assertEquals(list(20), order);
    }

    @Test public void move_right_lastFavorite_demotes() {
        List<String> order = list(20);
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveRight(order, 4, 5);
        assertEquals(4, r.homeCount);
        assertEquals(4, r.index);
        assertEquals(1, HomeDrawerModel.rowOf(r.index, r.homeCount));
        assertEquals(list(20), order);
    }

    @Test public void move_left_firstGridSlot_promotes() {
        List<String> order = list(20);
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveLeft(order, 5, 5);
        assertEquals(6, r.homeCount);
        assertEquals(5, r.index);
        assertEquals(list(20), order);

        List<String> full = list(20);
        HomeDrawerModel.MoveResult f = HomeDrawerModel.moveLeft(full, 6, 6);
        assertEquals(6, f.homeCount);
        assertEquals(5, f.index);
        assertEquals("p6", full.get(5));
        assertEquals("p5", full.get(6));
    }

    @Test public void move_ends_noop() {
        List<String> order = list(20);
        assertEquals(0, HomeDrawerModel.moveLeft(order, 0, 5).index);
        assertEquals(19, HomeDrawerModel.moveRight(order, 19, 5).index);
        assertEquals(list(20), order);
    }
}
