package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link Formation} の Bukkit に依存しない計算（同じ持ち場の判定・向き・横隊の並び）。 */
class FormationTest {

    private static final double EPS = 1e-9;
    private static final double SPACING = Formation.FORMATION_SPACING;
    private static final Point P1 = new Point("world", 12.5, 64.0, -30.5);
    private static final Point P2 = new Point("world", 13.5, 64.0, -30.5);

    // ---- sameStation ----

    @Test
    void sameStationFollowComparesPlayerIgnoringCaseAndStance() {
        Order a = Order.follow("Steve", Order.Stance.GUARD);
        assertTrue(Formation.sameStation(a, Order.follow("Steve", Order.Stance.PUSH)));
        assertTrue(Formation.sameStation(a, Order.follow("sTEVE", Order.Stance.GUARD)));
        assertFalse(Formation.sameStation(a, Order.follow("Alex", Order.Stance.GUARD)));
    }

    @Test
    void sameStationHoldComparesPoint() {
        Order a = Order.hold(P1, Order.Stance.GUARD);
        assertTrue(Formation.sameStation(a, Order.hold(new Point("world", 12.5, 64.0, -30.5), Order.Stance.PUSH)));
        assertFalse(Formation.sameStation(a, Order.hold(P2, Order.Stance.GUARD)));
    }

    @Test
    void sameStationDifferentModesOrNullAreNotTheSame() {
        Order follow = Order.follow("Steve", Order.Stance.GUARD);
        Order hold = Order.hold(P1, Order.Stance.GUARD);
        assertFalse(Formation.sameStation(follow, hold));
        assertFalse(Formation.sameStation(hold, follow));
        assertFalse(Formation.sameStation(null, follow));
        assertFalse(Formation.sameStation(follow, null));
        assertFalse(Formation.sameStation(null, null));
    }

    // ---- yawToward ----

    @Test
    void yawTowardUsesMinecraftYaw() {
        assertEquals(0.0, Formation.yawToward(0, 1), EPS); // +Z
        assertEquals(90.0, Formation.yawToward(-1, 0), EPS); // -X
        assertEquals(-90.0, Formation.yawToward(1, 0), EPS); // +X
        assertEquals(180.0, Math.abs(Formation.yawToward(0, -1)), EPS); // -Z（+180 と -180 のどちらでもよい）
        assertEquals(-45.0, Formation.yawToward(1, 1), EPS);
        assertEquals(135.0, Formation.yawToward(-1, -1), EPS);
    }

    @Test
    void yawTowardIsScaleIndependent() {
        assertEquals(Formation.yawToward(-1, 2), Formation.yawToward(-50, 100), EPS);
    }

    @Test
    void yawTowardNearZeroDistanceIsZero() {
        assertEquals(0.0, Formation.yawToward(0, 0), EPS);
        assertEquals(0.0, Formation.yawToward(1e-4, 1e-4), EPS);
        assertEquals(0.0, Formation.yawToward(-5e-4, 5e-4), EPS);
    }

    // ---- snapYaw45 ----

    @Test
    void snapYaw45RoundsToTheNearest45() {
        assertEquals(0.0, Formation.snapYaw45(0.0), EPS);
        assertEquals(0.0, Formation.snapYaw45(22.0), EPS);
        assertEquals(45.0, Formation.snapYaw45(23.0), EPS);
        assertEquals(45.0, Formation.snapYaw45(45.0), EPS);
        assertEquals(45.0, Formation.snapYaw45(67.0), EPS);
        assertEquals(90.0, Formation.snapYaw45(68.0), EPS);
        assertEquals(-45.0, Formation.snapYaw45(-45.0), EPS);
        assertEquals(-45.0, Formation.snapYaw45(-23.0), EPS);
        assertEquals(0.0, Formation.snapYaw45(-22.0), EPS);
        assertEquals(90.0, Formation.snapYaw45(90.0), EPS);
        assertEquals(180.0, Formation.snapYaw45(179.0), EPS);
        assertEquals(-180.0, Formation.snapYaw45(-180.0), EPS);
    }

    // ---- perRow ----

    @Test
    void perRowIsOneRowUpToFiveThenTwoRowsFrontHeavy() {
        for (int n = 1; n <= 5; n++) {
            assertEquals(n, Formation.perRow(n), "n=" + n);
        }
        assertEquals(3, Formation.perRow(6));
        assertEquals(4, Formation.perRow(7));
        assertEquals(4, Formation.perRow(8));
        assertEquals(5, Formation.perRow(9));
        assertEquals(5, Formation.perRow(10));
    }

    // ---- slotOffset ----

    /** yaw 0（正面が +Z）の total 体の横隊の dx, dz を並べて返す。 */
    private static double[][] line(int total, double yaw, double baseBack) {
        double[][] r = new double[total][];
        for (int i = 0; i < total; i++) {
            r[i] = Formation.slotOffset(i, total, yaw, baseBack);
        }
        return r;
    }

    @Test
    void oneToFiveUnitsStandInASingleRow() {
        for (int n = 1; n <= 5; n++) {
            double[][] o = line(n, 0, 2.0);
            for (int i = 0; i < n; i++) {
                assertEquals(-2.0, o[i][1], EPS, "後ろは全員 -Z に baseBack（n=" + n + " i=" + i + "）");
            }
        }
    }

    @Test
    void singleUnitStandsExactlyAtTheBaseBack() {
        double[] o = Formation.slotOffset(0, 1, 0, 1.5);
        assertEquals(0.0, o[0], EPS);
        assertEquals(-1.5, o[1], EPS);
    }

    @Test
    void yawZeroFacesPlusZSoBackIsMinusZAndRowIsSymmetricAboutX() {
        for (int n = 2; n <= 5; n++) {
            double[][] o = line(n, 0, 0.0);
            for (int i = 0; i < n; i++) {
                int mirror = n - 1 - i;
                assertEquals(-o[mirror][0], o[i][0], EPS, "X が左右対称（n=" + n + "）");
                assertEquals(o[mirror][1], o[i][1], EPS);
            }
            // 隣どうしの間隔は FORMATION_SPACING（X 軸に沿って並ぶ）
            for (int i = 0; i + 1 < n; i++) {
                assertEquals(SPACING, Math.abs(o[i + 1][0] - o[i][0]), EPS);
                assertEquals(0.0, o[i + 1][1] - o[i][1], EPS);
            }
        }
        // 3 体: 真ん中は X = 0、左右は ±1.6
        double[][] three = line(3, 0, 0.0);
        assertEquals(SPACING, three[0][0], EPS);
        assertEquals(0.0, three[1][0], EPS);
        assertEquals(-SPACING, three[2][0], EPS);
    }

    @Test
    void sixUnitsAreThreeInFrontAndThreeBehind() {
        double[][] o = line(6, 0, 1.0);
        for (int i = 0; i < 3; i++) {
            assertEquals(-1.0, o[i][1], EPS, "前の列 i=" + i);
            assertEquals(-1.0 - SPACING, o[i + 3][1], EPS, "後ろの列 i=" + (i + 3));
            assertEquals(o[i][0], o[i + 3][0], EPS, "前後の列は同じ X に並ぶ");
        }
        assertEquals(0.0, o[1][0], EPS);
    }

    @Test
    void sevenUnitsAreFourInFrontAndThreeBehindCentered() {
        double[][] o = line(7, 0, 0.0);
        for (int i = 0; i < 4; i++) {
            assertEquals(0.0, o[i][1], EPS, "前の列 i=" + i);
            assertEquals((1.5 - i) * SPACING, o[i][0], EPS); // 4 体の列の中心は X = 0
        }
        for (int i = 4; i < 7; i++) {
            assertEquals(-SPACING, o[i][1], EPS, "後ろの列 i=" + i);
        }
        // 後ろの列は 3 体なので、真ん中の 1 体が X = 0
        assertEquals(SPACING, o[4][0], EPS);
        assertEquals(0.0, o[5][0], EPS);
        assertEquals(-SPACING, o[6][0], EPS);
    }

    @Test
    void yaw90FacesMinusXSoBackIsPlusXAndRowIsSymmetricAboutZ() {
        double[][] o = line(3, 90, 2.0);
        for (double[] v : o) {
            assertEquals(2.0, v[0], EPS, "後ろは +X");
        }
        assertEquals(0.0, o[1][1], EPS);
        assertEquals(-o[2][1], o[0][1], EPS, "Z が左右対称");
        assertEquals(SPACING, Math.abs(o[1][1] - o[0][1]), EPS);

        double[][] two = line(6, 90, 0.0);
        for (int i = 0; i < 3; i++) {
            assertEquals(SPACING, two[i + 3][0] - two[i][0], EPS, "2 列目はさらに +X");
            assertEquals(two[i][1], two[i + 3][1], EPS);
        }
    }

    @Test
    void yawMinus90FacesPlusXSoBackIsMinusX() {
        double[][] o = line(3, -90, 2.0);
        for (double[] v : o) {
            assertEquals(-2.0, v[0], EPS, "後ろは -X");
        }
        assertEquals(0.0, o[1][1], EPS);
        assertEquals(-o[2][1], o[0][1], EPS);
    }

    @Test
    void yaw180FacesMinusZSoBackIsPlusZ() {
        double[][] o = line(3, 180, 2.0);
        for (double[] v : o) {
            assertEquals(2.0, v[1], EPS, "後ろは +Z");
        }
        assertEquals(0.0, o[1][0], EPS);
        assertEquals(-o[2][0], o[0][0], EPS);
    }

    /** どの向きでも、後ろへの距離・横の距離・直交性が保たれる（斜めの向きを含む）。 */
    @Test
    void offsetsKeepBackAndLateralDistancesForAnyYaw() {
        double[] yaws = {-90, -45, 30, 45, 90, 135, 180, 225};
        double baseBack = 1.7;
        for (double yaw : yaws) {
            double rad = Math.toRadians(yaw);
            double fx = -Math.sin(rad); // 正面（Minecraft の向き）
            double fz = Math.cos(rad);
            for (int total = 1; total <= 10; total++) {
                int perRow = Formation.perRow(total);
                for (int i = 0; i < total; i++) {
                    double[] d = Formation.slotOffset(i, total, yaw, baseBack);
                    int row = i / perRow;
                    int col = i % perRow;
                    int inRow = Math.min(perRow, total - row * perRow);
                    double back = -(d[0] * fx + d[1] * fz); // 正面と逆向きの成分
                    double lateral = d[0] * -fz + d[1] * fx; // 正面に直交する成分
                    String at = "yaw=" + yaw + " total=" + total + " i=" + i;
                    assertEquals(baseBack + row * SPACING, back, 1e-9, at + " back");
                    assertEquals((col - (inRow - 1) / 2.0) * SPACING, lateral, 1e-9, at + " lateral");
                }
                // 同じ列の隣どうしの距離は FORMATION_SPACING、前後の列の同じ並びの距離も FORMATION_SPACING
                if (total >= 2) {
                    double[] a = Formation.slotOffset(0, total, yaw, baseBack);
                    double[] b = Formation.slotOffset(1, total, yaw, baseBack);
                    if (perRow >= 2) {
                        assertEquals(SPACING, Math.hypot(b[0] - a[0], b[1] - a[1]), 1e-9);
                    }
                }
            }
        }
    }

    @Test
    void slotsInAFormationNeverOverlap() {
        for (double yaw : new double[] {0, 45, 90, -90, 180}) {
            for (int total = 2; total <= 10; total++) {
                for (int i = 0; i < total; i++) {
                    for (int j = i + 1; j < total; j++) {
                        double[] a = Formation.slotOffset(i, total, yaw, 1.5);
                        double[] b = Formation.slotOffset(j, total, yaw, 1.5);
                        assertTrue(Math.hypot(a[0] - b[0], a[1] - b[1]) >= SPACING - 1e-9,
                                "yaw=" + yaw + " total=" + total + " " + i + "," + j);
                    }
                }
            }
        }
    }

    // ---- 11 体以上の横隊 ----

    @Test
    void perRowForOneToTenMatchesTheTwoRowRule() {
        for (int n = 1; n <= 10; n++) {
            int old = n <= 5 ? n : (n + 1) / 2;
            assertEquals(old, Formation.perRow(n), "n=" + n);
        }
        assertEquals(3, Formation.perRow(6));
        assertEquals(4, Formation.perRow(7));
        assertEquals(4, Formation.perRow(8));
        assertEquals(5, Formation.perRow(9));
        assertEquals(5, Formation.perRow(10));
    }

    @Test
    void perRowForElevenToThirtyIsAtMostFiveAndNeedsOnlyTheMinimumRows() {
        for (int n = 11; n <= 30; n++) {
            int perRow = Formation.perRow(n);
            assertTrue(perRow <= Formation.FORMATION_ROW_MAX, "n=" + n);
            int rows = (n + perRow - 1) / perRow;
            assertEquals((n + Formation.FORMATION_ROW_MAX - 1) / Formation.FORMATION_ROW_MAX, rows, "n=" + n);
            assertTrue(rows >= 3, "n=" + n);
        }
        assertEquals(4, Formation.perRow(11)); // 4 + 4 + 3
        assertEquals(5, Formation.perRow(15)); // 5 + 5 + 5
        assertEquals(4, Formation.perRow(16)); // 4 x 4
        assertEquals(5, Formation.perRow(30)); // 5 x 6
    }

    @Test
    void slotsNeverOverlapForElevenToThirtyBots() {
        for (double yaw : new double[] {0, 45, 90, 180}) {
            for (int total = 11; total <= 30; total++) {
                for (int i = 0; i < total; i++) {
                    for (int j = i + 1; j < total; j++) {
                        double[] a = Formation.slotOffset(i, total, yaw, 1.5);
                        double[] b = Formation.slotOffset(j, total, yaw, 1.5);
                        assertTrue(Math.hypot(a[0] - b[0], a[1] - b[1]) >= SPACING - 1e-9,
                                "yaw=" + yaw + " total=" + total + " " + i + "," + j);
                    }
                }
            }
        }
    }

    // ---- 横隊の位置に着いたあとのヒステリシス（BotManager.updateHoldingSlot） ----

    @Test
    void holdingSlotEntersWithinEnterRadiusOnly() {
        double enter = Formation.FORMATION_ARRIVE;
        double exit = 1.0;
        assertTrue(BotManager.updateHoldingSlot(false, enter * enter, enter, exit)); // ちょうど境界は入る
        assertFalse(BotManager.updateHoldingSlot(false, enter * enter + 1e-6, enter, exit));
        assertFalse(BotManager.updateHoldingSlot(false, 0.9 * 0.9, enter, exit)); // 出る半径の内でも、入ってはいない
    }

    @Test
    void holdingSlotStaysUntilBeyondExitRadius() {
        double enter = Formation.FORMATION_ARRIVE;
        double exit = 1.0;
        assertTrue(BotManager.updateHoldingSlot(true, 0.9 * 0.9, enter, exit)); // 押し出されても歩き直さない
        assertTrue(BotManager.updateHoldingSlot(true, exit * exit, enter, exit));
        assertFalse(BotManager.updateHoldingSlot(true, exit * exit + 1e-6, enter, exit));
    }

    // ---- retreatSpot の占有回避・向き ----

    @Test
    void retreatDirectionIsNormalizedAwayVector() {
        double[] a = Formation.retreatDirection(3, 4, "anyone");
        assertEquals(0.6, a[0], EPS);
        assertEquals(0.8, a[1], EPS);
    }

    @Test
    void retreatDirectionSpreadsByNameWhenOnSameSpot() {
        double[] a = Formation.retreatDirection(0, 0, "alpha");
        double[] a2 = Formation.retreatDirection(0, 0, "alpha");
        double[] b = Formation.retreatDirection(0, 0, "bravo");
        double[] c = Formation.retreatDirection(0, 0, "charlie");
        assertEquals(1.0, Math.hypot(a[0], a[1]), 1e-9);
        assertEquals(a[0], a2[0], EPS); // 同じ名前なら同じ向き
        assertEquals(a[1], a2[1], EPS);
        assertFalse(Math.abs(a[0] - b[0]) < 1e-6 && Math.abs(a[1] - b[1]) < 1e-6);
        assertFalse(Math.abs(a[0] - c[0]) < 1e-6 && Math.abs(a[1] - c[1]) < 1e-6);
        assertFalse(Math.abs(b[0] - c[0]) < 1e-6 && Math.abs(b[1] - c[1]) < 1e-6);
        double[] none = Formation.retreatDirection(0, 0, null);
        assertEquals(1.0, Math.hypot(none[0], none[1]), 1e-9);
        // 名前を 1 つずつ変えた 30 体が、同じ向きに偏らない
        java.util.Set<Long> buckets = new java.util.HashSet<>();
        for (int i = 0; i < 30; i++) {
            double[] d = Formation.retreatDirection(0, 0, "bot" + i);
            buckets.add(Math.round(Math.toDegrees(Math.atan2(d[1], d[0])) / 45.0));
        }
        assertTrue(buckets.size() >= 4, "向きの種類: " + buckets.size());
    }

    @Test
    void retreatCandidateSkipsOccupiedSpots() {
        java.util.List<double[]> cands = java.util.Arrays.asList(
                new double[] {0, 64, 0}, new double[] {5, 64, 0}, null, new double[] {10, 64, 0});
        java.util.List<double[]> occ = java.util.List.of(new double[] {0.3, 64, 0});
        assertEquals(1, Formation.chooseRetreatCandidate(cands, occ, 0.9)); // 先頭は占められている
        assertEquals(0, Formation.chooseRetreatCandidate(cands, java.util.List.of(), 0.9));
        // minDist 0（避けない設定）なら先頭
        assertEquals(0, Formation.chooseRetreatCandidate(cands, occ, 0.0));
    }

    @Test
    void retreatCandidateFallsBackToFirstStandableWhenAllOccupied() {
        java.util.List<double[]> cands = java.util.Arrays.asList(null, new double[] {5, 64, 0}, new double[] {10, 64, 0});
        java.util.List<double[]> occ = java.util.List.of(new double[] {5, 64, 0.2}, new double[] {10, 64, 0.2});
        assertEquals(1, Formation.chooseRetreatCandidate(cands, occ, 0.9));
        assertEquals(-1, Formation.chooseRetreatCandidate(java.util.Arrays.asList(null, null), occ, 0.9));
    }

    @Test
    void retreatCandidateIgnoresVerticallyFarOccupants() {
        java.util.List<double[]> cands = java.util.List.of(new double[] {0, 0, 0}, new double[] {5, 0, 0});
        java.util.List<double[]> occ = java.util.List.of(new double[] {0, Separation.VERTICAL_OVERLAP, 0});
        assertEquals(0, Formation.chooseRetreatCandidate(cands, occ, 0.9));
    }
}
