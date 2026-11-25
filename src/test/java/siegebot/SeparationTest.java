package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** {@link Separation} の Bukkit に依存しない計算（押し分け・崖のガード・空き地探し・間隔の集計）。 */
class SeparationTest {

    private static final double EPS = 1e-9;
    private static final double MIN = 0.9;
    private static final Separation.HazardGuard NO_GUARD = (b, vx, vz) -> new double[] {vx, vz};

    /** 経路なし・向きなし・適格のボット（速度 0）。 */
    private static Separation.BotSample bot(String key, double x, double z) {
        return sample(key, Side.ATTACK, x, 64, z, 0, 0, 0, true, false, false, 0, 0);
    }

    private static Separation.BotSample sample(String key, Side side, double x, double y, double z, double vx,
                                               double vy, double vz, boolean eligible, boolean nav, boolean hasDir,
                                               double dirX, double dirZ) {
        return new Separation.BotSample(key, side, x, y, z, vx, vy, vz, eligible, nav, hasDir, dirX, dirZ);
    }

    /** 経路を歩いている同じ陣営のボット（向き dirX, dirZ）。 */
    private static Separation.BotSample walker(String key, double x, double z, double dirX, double dirZ) {
        return sample(key, Side.ATTACK, x, 64, z, 0, 0, 0, true, true, true, dirX, dirZ);
    }

    private static Map<String, Separation.VelocityDelta> steps(List<Separation.BotSample> bots, Set<Separation.PairKey> passing) {
        return Separation.computeSteps(bots, MIN, passing, NO_GUARD);
    }

    private static Map<String, Separation.VelocityDelta> steps(List<Separation.BotSample> bots) {
        return steps(bots, new HashSet<>());
    }

    private static double len(Separation.VelocityDelta d) {
        return Math.hypot(d.dvX(), d.dvZ());
    }

    // ---- 厳密な分離 ----

    @Test
    void twoBotsOnSameSpotAreSplitInOppositeDirectionsDeterministically() {
        List<Separation.BotSample> bots = List.of(bot("alpha", 10, 10), bot("beta", 10, 10));
        Map<String, Separation.VelocityDelta> r1 = steps(bots);
        Map<String, Separation.VelocityDelta> r2 = steps(bots);
        assertEquals(r1, r2);
        Separation.VelocityDelta a = r1.get("alpha");
        Separation.VelocityDelta b = r1.get("beta");
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(-a.dvX(), b.dvX(), EPS);
        assertEquals(-a.dvZ(), b.dvZ(), EPS);
        assertEquals(Separation.MAX_PUSH, len(a), EPS); // 0.6 * 0.9 = 0.54 は上限 0.25 に切られる
        // 入力の順序を変えても同じ
        Map<String, Separation.VelocityDelta> r3 = steps(List.of(bot("beta", 10, 10), bot("alpha", 10, 10)));
        assertEquals(r1, r3);
    }

    @Test
    void thirtyBotsOnSameSpotAreSpreadAndCappedAtMaxPush() {
        List<Separation.BotSample> bots = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            bots.add(bot("bot" + i, 5, 5));
        }
        Map<String, Separation.VelocityDelta> r = steps(bots);
        assertEquals(r, steps(bots)); // 決定的
        Set<Long> directions = new HashSet<>();
        for (Separation.VelocityDelta d : r.values()) {
            assertTrue(len(d) <= Separation.MAX_PUSH + EPS, "押しは MAX_PUSH 以下: " + len(d));
            directions.add(Math.round(Math.toDegrees(Math.atan2(d.dvZ(), d.dvX())) / 30.0));
        }
        assertTrue(directions.size() >= 6, "多方向へ分かれる: " + directions.size());
    }

    @Test
    void thirtyBotsOnSameSpotKeepSpreadingOverTicks() {
        // 毎 tick 速度を作り直して動かす（摩擦で止まる想定）。最小距離が大きく改善し、位置がばらばらになる
        int n = 30;
        double[] x = new double[n];
        double[] z = new double[n];
        for (int tick = 0; tick < 300; tick++) {
            List<Separation.BotSample> bots = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                bots.add(bot("bot" + i, x[i], z[i]));
            }
            Map<String, Separation.VelocityDelta> r = steps(bots);
            for (int i = 0; i < n; i++) {
                Separation.VelocityDelta d = r.get("bot" + i);
                if (d != null) {
                    x[i] += d.dvX();
                    z[i] += d.dvZ();
                }
            }
        }
        List<double[]> pos = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            pos.add(new double[] {x[i], 64, z[i]});
        }
        Separation.SpacingStats st = Separation.measureSpacing(pos);
        assertTrue(st.minDistance() > 0.5, "30 体が大きく離れる: " + st.minDistance());
    }

    @Test
    void iceFrictionDoesNotAccumulateVelocity() {
        // 氷（摩擦 0.89）の上で 20 tick: 押す向きの速度は目標までしか足さないので、積もらない
        double friction = 0.89;
        double ax = 0;
        double bx = 0.3;
        double avx = 0;
        double bvx = 0;
        for (int tick = 0; tick < 20; tick++) {
            List<Separation.BotSample> bots = List.of(
                    sample("a", Side.ATTACK, ax, 64, 0, avx, 0, 0, true, false, false, 0, 0),
                    sample("b", Side.ATTACK, bx, 64, 0, bvx, 0, 0, true, false, false, 0, 0));
            Map<String, Separation.VelocityDelta> r = steps(bots);
            if (r.containsKey("a")) {
                avx += r.get("a").dvX();
            }
            if (r.containsKey("b")) {
                bvx += r.get("b").dvX();
            }
            assertTrue(Math.abs(avx) <= Separation.MAX_PUSH + EPS, "a: " + avx);
            assertTrue(Math.abs(bvx) <= Separation.MAX_PUSH + EPS, "b: " + bvx);
            ax += avx;
            bx += bvx;
            avx *= friction;
            bvx *= friction;
        }
    }

    @Test
    void headOnWalkingCollisionSettlesAboveHitboxWidth() {
        // 向かい合って歩く速さ 0.123（MINECRAFT の歩行）。歩く入力は毎 tick 足され、押しはそれを打ち消す
        double walk = 0.123;
        double ax = -3;
        double bx = 3;
        double minD = Double.MAX_VALUE;
        for (int tick = 0; tick < 100; tick++) {
            List<Separation.BotSample> bots = List.of(
                    sample("a", Side.ATTACK, ax, 64, 0, walk, 0, 0, true, false, false, 0, 0),
                    sample("b", Side.DEFEND, bx, 64, 0, -walk, 0, 0, true, false, false, 0, 0));
            Map<String, Separation.VelocityDelta> r = steps(bots);
            double avx = walk + (r.containsKey("a") ? r.get("a").dvX() : 0);
            double bvx = -walk + (r.containsKey("b") ? r.get("b").dvX() : 0);
            ax += avx;
            bx += bvx;
            minD = Math.min(minD, bx - ax);
        }
        assertTrue(minD > 0.60, "最小の距離: " + minD);
    }

    @Test
    void symmetricThreeBotsLeaveTheMiddleAloneRegardlessOfOrder() {
        Separation.BotSample a = bot("a", -0.5, 0);
        Separation.BotSample b = bot("b", 0, 0);
        Separation.BotSample c = bot("c", 0.5, 0);
        Map<String, Separation.VelocityDelta> r1 = steps(List.of(a, b, c));
        assertNull(r1.get("b"), "中央はほぼ動かない");
        assertTrue(r1.get("a").dvX() < 0);
        assertTrue(r1.get("c").dvX() > 0);
        assertEquals(-r1.get("a").dvX(), r1.get("c").dvX(), EPS);
        assertEquals(r1, steps(List.of(c, b, a)));
        assertEquals(r1, steps(List.of(b, c, a)));
    }

    @Test
    void outwardKnockbackFasterThanMaxPushIsNotCutAndVerticalIsUntouched() {
        // a は b から離れる向き（-x）に 0.40 > MAX_PUSH で飛ばされている。押しは足さず、削りもしない
        List<Separation.BotSample> bots = List.of(
                sample("a", Side.ATTACK, 0, 64, 0, -0.40, 0.42, 0, true, false, false, 0, 0),
                sample("b", Side.DEFEND, 0.4, 64, 0, 0, 0, 0, true, false, false, 0, 0));
        Map<String, Separation.VelocityDelta> r = steps(bots);
        assertNull(r.get("a"));
        assertNotNull(r.get("b")); // b だけが押される
        // 結果は水平（dvX・dvZ）だけで、縦の速度（vy）は変えない
        assertEquals(2, Separation.VelocityDelta.class.getRecordComponents().length);
    }

    @Test
    void verticalGapOfOnePointEightIsIgnored() {
        List<Separation.BotSample> far = List.of(
                sample("a", Side.ATTACK, 0, 64, 0, 0, 0, 0, true, false, false, 0, 0),
                sample("b", Side.ATTACK, 0.1, 64 + 2, 0, 0, 0, 0, true, false, false, 0, 0));
        assertTrue(steps(far).isEmpty());
        // ちょうど 1.8（0 と 1.8 なら浮動小数でも差が 1.8 ちょうど）も無視
        assertTrue(steps(List.of(
                sample("a", Side.ATTACK, 0, 0, 0, 0, 0, 0, true, false, false, 0, 0),
                sample("b", Side.ATTACK, 0.1, Separation.VERTICAL_OVERLAP, 0, 0, 0, 0, true, false, false, 0, 0))).isEmpty());
        List<Separation.BotSample> near = List.of(
                sample("a", Side.ATTACK, 0, 64, 0, 0, 0, 0, true, false, false, 0, 0),
                sample("b", Side.ATTACK, 0.1, 64 + Separation.VERTICAL_OVERLAP - 0.01, 0, 0, 0, 0, true, false, false, 0, 0));
        assertEquals(2, steps(near).size());
    }

    @Test
    void ineligibleBotIsNotPushedButIsAvoided() {
        List<Separation.BotSample> bots = List.of(
                sample("a", Side.ATTACK, 0, 64, 0, 0, 0, 0, false, false, false, 0, 0),
                bot("b", 0.6, 0));
        Map<String, Separation.VelocityDelta> r = steps(bots);
        assertNull(r.get("a"));
        Separation.VelocityDelta b = r.get("b");
        assertNotNull(b);
        assertEquals(Separation.PUSH_GAIN * (MIN - 0.6), b.dvX(), EPS); // 相手の分まで押し返さない（b だけが避ける）
        assertEquals(0, b.dvZ(), EPS);
    }

    @Test
    void botsFartherThanMinDistanceAreNotPushed() {
        assertTrue(steps(List.of(bot("a", 0, 0), bot("b", MIN, 0))).isEmpty());
        assertTrue(steps(List.of(bot("a", 0, 0), bot("b", MIN + 0.5, 0))).isEmpty());
    }

    @Test
    void smallPushBelowMinVelocityDeltaIsDropped() {
        // d = 0.89 のとき押しは 0.6 * 0.01 = 0.006 < 0.01
        assertTrue(steps(List.of(bot("a", 0, 0), bot("b", 0.89, 0))).isEmpty());
    }

    // ---- 逆向きのすれ違い ----

    @Test
    void opposingWalkersOnSameSideGetOnlySoftPushAndStaySoftWhileKept() {
        Set<Separation.PairKey> passing = new HashSet<>();
        Separation.BotSample a = walker("a", 0, 0, 1, 0);
        Separation.BotSample b = walker("b", 0.4, 0, -1, 0);
        Map<String, Separation.VelocityDelta> r = steps(List.of(a, b), passing);
        assertTrue(passing.contains(Separation.PairKey.of("a", "b")));
        assertTrue(len(r.get("a")) <= Separation.SOFT_PUSH + EPS);
        assertTrue(len(r.get("a")) > 0);
        assertTrue(r.get("a").dvX() < 0);

        // 向きが変わっても（同じ向きに見えても）、記録がある間は弱いまま
        Separation.BotSample b2 = walker("b", 0.2, 0, 1, 0);
        Map<String, Separation.VelocityDelta> r2 = steps(List.of(a, b2), passing);
        assertTrue(passing.contains(Separation.PairKey.of("a", "b")));
        assertTrue(len(r2.get("a")) <= Separation.SOFT_PUSH + EPS);
    }

    @Test
    void passingRecordsAreKeptPerWorldAndAbsentWorldsAreSwept() {
        Separation.PairKey pa = Separation.PairKey.of("a1", "a2");
        Separation.PairKey pb = Separation.PairKey.of("b1", "b2");
        Map<String, List<Separation.BotSample>> byWorld = new HashMap<>();
        byWorld.put("world_a", List.of(walker("a1", 0, 0, 1, 0), walker("a2", 0.4, 0, -1, 0)));
        byWorld.put("world_b", List.of(walker("b1", 0, 0, 1, 0), walker("b2", 0.4, 0, -1, 0)));
        Map<String, Set<Separation.PairKey>> passing = new HashMap<>();
        Map<String, Separation.VelocityDelta> r = Separation.computeAllWorlds(byWorld, MIN, passing, w -> NO_GUARD);
        assertEquals(Set.of("a1", "a2", "b1", "b2"), r.keySet());
        assertEquals(Set.of(pa), passing.get("world_a"));
        assertEquals(Set.of(pb), passing.get("world_b"));

        // もう一度回しても両方のワールドの記録が残る（他のワールドの組を消さない）
        Separation.computeAllWorlds(byWorld, MIN, passing, w -> NO_GUARD);
        assertEquals(Set.of(pa), passing.get("world_a"));
        assertEquals(Set.of(pb), passing.get("world_b"));

        // world_b にボットがいなくなったら、その記録だけ掃除される
        Map<String, List<Separation.BotSample>> onlyA = new HashMap<>();
        onlyA.put("world_a", byWorld.get("world_a"));
        Separation.computeAllWorlds(onlyA, MIN, passing, w -> NO_GUARD);
        assertEquals(Set.of(pa), passing.get("world_a"));
        assertFalse(passing.containsKey("world_b"));
    }

    @Test
    void mixedCaseKeysOnSameSpotAreSplitStrictlyOppositeRegardlessOfOrder() {
        Map<String, Separation.VelocityDelta> r1 = steps(List.of(bot("Alpha", 10, 10), bot("beta", 10, 10)));
        Map<String, Separation.VelocityDelta> r2 = steps(List.of(bot("beta", 10, 10), bot("Alpha", 10, 10)));
        assertEquals(r1, r2);
        Separation.VelocityDelta a = r1.get("Alpha");
        Separation.VelocityDelta b = r1.get("beta");
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(-a.dvX(), b.dvX(), EPS);
        assertEquals(-a.dvZ(), b.dvZ(), EPS);
        assertTrue(len(a) > 0);
    }

    @Test
    void passingRecordIsClearedWhenFarStoppedOrNotNavigating() {
        Separation.PairKey pk = Separation.PairKey.of("a", "b");
        Set<Separation.PairKey> passing = new HashSet<>(Set.of(pk));

        // minDist + 0.15 未満の距離なら保たれる（d = 1.0 は押す範囲の外だが、記録は残る）
        steps(List.of(walker("a", 0, 0, 1, 0), walker("b", 1.0, 0, -1, 0)), passing);
        assertTrue(passing.contains(pk));
        // minDist + 0.15 以上離れたら外れる
        steps(List.of(walker("a", 0, 0, 1, 0), walker("b", MIN + Separation.PASS_KEEP_MARGIN, 0, -1, 0)), passing);
        assertFalse(passing.contains(pk));

        // 片方が止まったら（経路なし）外れて、厳密な分離に戻る
        passing.add(pk);
        Map<String, Separation.VelocityDelta> r = steps(List.of(walker("a", 0, 0, 1, 0), bot("b", 0.6, 0)), passing);
        assertFalse(passing.contains(pk));
        assertEquals(Separation.PUSH_GAIN * (MIN - 0.6), Math.abs(r.get("a").dvX()), EPS);

        // 組の片方がいなくなったら外れる
        passing.add(pk);
        steps(List.of(walker("a", 0, 0, 1, 0)), passing);
        assertTrue(passing.isEmpty());
    }

    @Test
    void enemiesSameDirectionAndEntityTargetStayStrict() {
        double strict = Separation.PUSH_GAIN * (MIN - 0.6);

        // 敵どうし
        Set<Separation.PairKey> passing = new HashSet<>();
        Separation.BotSample enemy = sample("b", Side.DEFEND, 0.6, 64, 0, 0, 0, 0, true, true, true, -1, 0);
        Map<String, Separation.VelocityDelta> r = steps(List.of(walker("a", 0, 0, 1, 0), enemy), passing);
        assertTrue(passing.isEmpty());
        assertEquals(strict, len(r.get("a")), EPS);

        // 同じ向き
        r = steps(List.of(walker("a", 0, 0, 1, 0), walker("b", 0.6, 0, 1, 0)), passing);
        assertTrue(passing.isEmpty());
        assertEquals(strict, len(r.get("a")), EPS);

        // 敵を追っている（経路ではなく EntityTarget。navLocationOnly が false）
        Separation.BotSample chaser = sample("b", Side.ATTACK, 0.6, 64, 0, 0, 0, 0, true, false, true, -1, 0);
        r = steps(List.of(walker("a", 0, 0, 1, 0), chaser), passing);
        assertTrue(passing.isEmpty());
        assertEquals(strict, len(r.get("a")), EPS);

        // 逆向きだが、互いに向かい合っていない（すれ違い終わって離れていく向き）
        r = steps(List.of(walker("a", 0, 0, -1, 0), walker("b", 0.6, 0, 1, 0)), passing);
        assertTrue(passing.isEmpty());
        assertEquals(strict, len(r.get("a")), EPS);
    }

    @Test
    void strictAndSoftPushesBothApplyToTheSameBot() {
        // a: x 方向の敵 e（厳密）と、z 方向の逆向きの味方 f（弱い）に挟まれる
        Separation.BotSample a = walker("a", 0, 0, 0, 1);
        Separation.BotSample e = sample("e", Side.DEFEND, 0.5, 64, 0, 0, 0, 0, true, false, false, 0, 0);
        Separation.BotSample f = walker("f", 0, 0.5, 0, -1);
        Set<Separation.PairKey> passing = new HashSet<>();
        Map<String, Separation.VelocityDelta> r = steps(List.of(a, e, f), passing);
        assertTrue(passing.contains(Separation.PairKey.of("a", "f")));
        Separation.VelocityDelta d = r.get("a");
        assertEquals(-Separation.PUSH_GAIN * (MIN - 0.5), d.dvX(), EPS); // 厳密の分
        assertTrue(d.dvZ() < 0, "弱い押しも捨てられない: " + d.dvZ());
        assertEquals(-Separation.SOFT_PUSH * (MIN - 0.5) / MIN, d.dvZ(), EPS);
    }

    // ---- 崖・危険なブロック ----

    private static final Separation.SafeColumnProbe ALL_SAFE = (px, by, pz) -> true;

    @Test
    void hazardFilterPassesEverythingWhenSafe() {
        double[] f = Separation.filterByHazard(10.5, 64, 10.5, 0.2, -0.1, ALL_SAFE);
        assertEquals(0.2, f[0], EPS);
        assertEquals(-0.1, f[1], EPS);
    }

    @Test
    void hazardFilterSlidesAlongTheSafeAxis() {
        // +x の先が崖: x を 0 にして z だけ進める
        Separation.SafeColumnProbe noEast = (px, by, pz) -> px < 10.5 + 0.1;
        double[] f = Separation.filterByHazard(10.5, 64, 10.5, 0.2, 0.1, noEast);
        assertEquals(0, f[0], EPS);
        assertEquals(0.1, f[1], EPS);
        // 逆向き（-x）の押しは通る
        f = Separation.filterByHazard(10.5, 64, 10.5, -0.2, 0.1, noEast);
        assertEquals(-0.2, f[0], EPS);
    }

    @Test
    void hazardFilterChecksTheDiagonalAndKeepsTheLargerAxis() {
        // 軸ごとには安全だが、斜め先（+x,+z の角）だけが危険
        Separation.SafeColumnProbe cornerBad = (px, by, pz) -> !(px > 10.5 + 0.1 && pz > 10.5 + 0.1);
        double[] f = Separation.filterByHazard(10.5, 64, 10.5, 0.2, 0.1, cornerBad);
        assertEquals(0.2, f[0], EPS);
        assertEquals(0, f[1], EPS);
        f = Separation.filterByHazard(10.5, 64, 10.5, 0.1, -0.2, (px, by, pz) -> !(px > 10.6 && pz < 10.4));
        assertEquals(0, f[0], EPS);
        assertEquals(-0.2, f[1], EPS);
    }

    @Test
    void hazardFilterUsesFloorOfFeetPlusHalfBlockOffset() {
        // ハーフブロック（y = 64.5）の上では足元の段は 64
        int[] seen = new int[1];
        Separation.filterByHazard(0.5, 64.5, 0.5, 0.2, 0, (px, by, pz) -> {
            seen[0] = by;
            return true;
        });
        assertEquals(64, seen[0]);
        // 64.999 でも 64 のまま。65.0 になれば 65
        Separation.filterByHazard(0.5, 65.0, 0.5, 0.2, 0, (px, by, pz) -> {
            seen[0] = by;
            return true;
        });
        assertEquals(65, seen[0]);
    }

    @Test
    void probeDistanceIsBodyHalfWidthPlusMaxPushPlusMargin() {
        assertEquals(0.60, Separation.CLIFF_PROBE, 1e-12);
        double[] seen = new double[1];
        Separation.filterByHazard(10.0, 64, 10.0, 0.2, 0, (px, by, pz) -> {
            seen[0] = px;
            return true;
        });
        assertEquals(10.60, seen[0], 1e-12);
    }

    @Test
    void computeStepsUsesTheGuardToSlideTowardSafety() {
        // a は +x 側の b に押されて -x へ。-x 側が崖なら、押しは止まる
        List<Separation.BotSample> bots = List.of(bot("a", 0, 0), bot("b", 0.3, 0));
        Separation.HazardGuard noWest = (b, vx, vz) -> new double[] {vx < 0 ? 0 : vx, vz};
        Map<String, Separation.VelocityDelta> r = Separation.computeSteps(bots, MIN, new HashSet<>(), noWest);
        assertNull(r.get("a"));
        assertTrue(r.get("b").dvX() > 0);
    }

    // ---- isSafeColumn（柱の安全） ----

    private static Separation.Cell air() {
        return new Separation.Cell(Material.AIR, false, true, false);
    }

    private static Separation.Cell stone() {
        return new Separation.Cell(Material.STONE, false, false, true);
    }

    private static Separation.Cell of(Material m, boolean liquid, boolean passable, boolean solid) {
        return new Separation.Cell(m, liquid, passable, solid);
    }

    /** 高さ 64 を足元として、y → セルの柱。 */
    private static IntFunction<Separation.Cell> column(Separation.Cell at64, Separation.Cell at63, Separation.Cell at62) {
        return y -> y == 64 ? at64 : y == 63 ? at63 : y == 62 ? at62 : stone();
    }

    @Test
    void wallsAndFloorsAreSafe() {
        assertTrue(Separation.isSafeColumn(column(stone(), stone(), stone()), 64)); // 壁（押しても入れない）
        assertTrue(Separation.isSafeColumn(column(air(), stone(), stone()), 64)); // 平らな床
    }

    @Test
    void oneStepDropIsSafeButTwoStepsIsNot() {
        assertTrue(Separation.isSafeColumn(column(air(), air(), stone()), 64)); // 1 段落ちる
        assertFalse(Separation.isSafeColumn(column(air(), air(), air()), 64)); // 2 段以上
    }

    @Test
    void liquidsAndHazardousBlocksAreUnsafe() {
        assertFalse(Separation.isSafeColumn(column(of(Material.LAVA, true, true, false), stone(), stone()), 64));
        assertFalse(Separation.isSafeColumn(column(of(Material.WATER, true, true, false), stone(), stone()), 64));
        assertFalse(Separation.isSafeColumn(column(air(), of(Material.POWDER_SNOW, false, true, false), stone()), 64));
        assertFalse(Separation.isSafeColumn(column(air(), of(Material.MAGMA_BLOCK, false, false, true), stone()), 64));
        assertFalse(Separation.isSafeColumn(column(air(), of(Material.CACTUS, false, false, true), stone()), 64));
        assertFalse(Separation.isSafeColumn(column(air(), air(), of(Material.LAVA, true, true, false)), 64));
        assertFalse(Separation.isSafeColumn(column(air(), air(), of(Material.MAGMA_BLOCK, false, false, true)), 64));
        assertFalse(Separation.isSafeColumn(column(of(Material.FIRE, false, true, false), stone(), stone()), 64));
    }

    @Test
    void hazardTable() {
        assertFalse(Separation.isHazard(null));
        assertFalse(Separation.isHazard(Material.STONE));
        assertFalse(Separation.isHazard(Material.AIR));
        for (Material m : new Material[] {Material.WATER, Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
                Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.MAGMA_BLOCK, Material.CACTUS,
                Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.POWDER_SNOW}) {
            assertTrue(Separation.isHazard(m), m.name());
        }
    }

    // ---- findOpenGridSpot ----

    /** 平らな広場（高さ 64）。 */
    private static final Separation.StandableGrid FLAT = (bx, bz, refY) -> Math.abs(64 - refY) <= 1 ? 64 : Integer.MIN_VALUE;

    private static double hdist(double[] p, double x, double z) {
        return Math.hypot(p[0] - x, p[2] - z);
    }

    @Test
    void clearOriginIsReturnedAsIs() {
        double[] r = Separation.findOpenGridSpot(3.2, 64, 4.7, List.of(new double[] {10, 64, 10}), MIN, FLAT);
        assertEquals(3.2, r[0], EPS);
        assertEquals(4.7, r[2], EPS);
    }

    @Test
    void crowdedOriginMovesToTheNearestOpenCell() {
        double[] r = Separation.findOpenGridSpot(0.5, 64, 0.5, List.of(new double[] {0.5, 64, 0.5}), MIN, FLAT);
        assertTrue(hdist(r, 0.5, 0.5) >= MIN);
        assertEquals(1.0, hdist(r, 0.5, 0.5), EPS); // 隣のマスの中心
        assertEquals(64, r[1], EPS);
    }

    @Test
    void largerMinDistanceSkipsAdjacentAndDiagonalCells() {
        double[] r = Separation.findOpenGridSpot(0.5, 64, 0.5, List.of(new double[] {0.5, 64, 0.5}), 1.5, FLAT);
        assertTrue(hdist(r, 0.5, 0.5) >= 1.5, "隣（1.0）・斜め（1.414）は選ばない: " + hdist(r, 0.5, 0.5));
        assertEquals(2.0, hdist(r, 0.5, 0.5), EPS);
    }

    @Test
    void wallsAreNotCrossed() {
        // 原点のマスは x が 0 以下の幅 1 の部屋。x = 1 以上は壁、z は 0 だけが床。x < -1 の外も壁（閉じた小部屋）
        Separation.StandableGrid room = (bx, bz, refY) -> (bx == 0 || bx == -1) && bz == 0 ? 64 : Integer.MIN_VALUE;
        double[] r = Separation.findOpenGridSpot(0.5, 64, 0.5, List.of(new double[] {0.5, 64, 0.5}), MIN, room);
        assertEquals(-0.5, r[0], EPS); // 部屋の中で唯一空いているマス
        // 部屋の 2 マスとも占められていれば、壁の向こうの空きには出ず、元の場所のまま
        double[] full = Separation.findOpenGridSpot(0.5, 64, 0.5,
                List.of(new double[] {0.5, 64, 0.5}, new double[] {-0.5, 64, 0.5}), MIN, room);
        assertEquals(0.5, full[0], EPS);
        assertEquals(0.5, full[2], EPS);
    }

    @Test
    void stepsOfOneAreWalkedButStepsOfTwoAreNot() {
        // 高さが x に応じて 1 段ずつ上がる階段（x = 0 → 64, 1 → 65）。段差 1 は歩ける
        Separation.StandableGrid stairs = (bx, bz, refY) -> {
            int h = 64 + Math.max(0, bx);
            return Math.abs(h - refY) <= 1 ? h : Integer.MIN_VALUE;
        };
        double[] up = Separation.findOpenGridSpot(0.5, 64, 0.5,
                List.of(new double[] {0.5, 64, 0.5}, new double[] {-0.5, 64, 0.5}, new double[] {0.5, 64, 1.5},
                        new double[] {0.5, 64, -0.5}, new double[] {-0.5, 64, 1.5}), MIN, stairs);
        assertTrue(up[1] >= 64);
        assertFalse(up[0] == 0.5 && up[2] == 0.5);

        // 段差 2 の崖の向こう（x >= 1）は歩いて行けない: 周りを占めれば元の場所のまま
        Separation.StandableGrid cliff = (bx, bz, refY) -> {
            int h = bx >= 1 ? 66 : 64;
            return Math.abs(h - refY) <= 1 ? h : Integer.MIN_VALUE;
        };
        List<double[]> occ = new ArrayList<>();
        for (int x = -6; x <= 0; x++) {
            for (int z = -6; z <= 6; z++) {
                occ.add(new double[] {x + 0.5, 64, z + 0.5});
            }
        }
        double[] r = Separation.findOpenGridSpot(0.5, 64, 0.5, occ, MIN, cliff);
        assertEquals(0.5, r[0], EPS);
        assertEquals(0.5, r[2], EPS);
    }

    @Test
    void fullAreaFallsBackToOrigin() {
        List<double[]> occ = new ArrayList<>();
        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                occ.add(new double[] {x + 0.5, 64, z + 0.5});
            }
        }
        double[] r = Separation.findOpenGridSpot(0.5, 64, 0.5, occ, MIN, FLAT);
        assertEquals(0.5, r[0], EPS);
        assertEquals(64, r[1], EPS);
        assertEquals(0.5, r[2], EPS);
    }

    @Test
    void manyBotsAreAssignedDistinctSpotsWithinMinDistance() {
        // 30 体を順に同じ地点から探して並べると、どの 2 体も minDist 以上離れる（占有の一覧に足しながら探す）
        List<double[]> occ = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            double[] spot = Separation.findOpenGridSpot(0.5, 64, 0.5, occ, MIN, FLAT);
            occ.add(spot);
        }
        for (int i = 0; i < occ.size(); i++) {
            for (int j = i + 1; j < occ.size(); j++) {
                assertTrue(Math.hypot(occ.get(i)[0] - occ.get(j)[0], occ.get(i)[2] - occ.get(j)[2]) >= MIN - EPS,
                        "重なり: " + i + "," + j);
            }
        }
    }

    @Test
    void verticallySeparatedOccupantsDoNotBlock() {
        double[] r = Separation.findOpenGridSpot(0.5, 0, 0.5, List.of(new double[] {0.5, Separation.VERTICAL_OVERLAP, 0.5}), MIN,
                (bx, bz, refY) -> 0);
        assertEquals(0.5, r[0], EPS);
        assertEquals(0.5, r[2], EPS);
    }

    // ---- measureSpacing ----

    @Test
    void measureSpacingFindsMinimumAndOverlaps() {
        List<double[]> pos = List.of(new double[] {0, 64, 0}, new double[] {0.5, 64, 0}, new double[] {3, 64, 0});
        Separation.SpacingStats st = Separation.measureSpacing(pos);
        assertEquals(3, st.pairs());
        assertEquals(0.5, st.minDistance(), EPS);
        assertEquals(1, st.overlappingCount());
    }

    @Test
    void measureSpacingExcludesVerticallyFarPairs() {
        List<double[]> pos = List.of(new double[] {0, 0, 0}, new double[] {0.1, Separation.VERTICAL_OVERLAP, 0});
        Separation.SpacingStats st = Separation.measureSpacing(pos);
        assertEquals(0, st.pairs());
        assertEquals(0, st.overlappingCount());
        assertEquals(Double.POSITIVE_INFINITY, st.minDistance());
        assertEquals(0, Separation.measureSpacing(Collections.emptyList()).pairs());
    }

    @Test
    void measureSpacingBoundaryAtHitboxWidthIsNotOverlap() {
        List<double[]> pos = List.of(new double[] {0, 64, 0}, new double[] {Separation.HITBOX_WIDTH, 64, 0});
        assertEquals(0, Separation.measureSpacing(pos).overlappingCount());
        List<double[]> pos2 = List.of(new double[] {0, 64, 0}, new double[] {Separation.HITBOX_WIDTH - 0.001, 64, 0});
        assertEquals(1, Separation.measureSpacing(pos2).overlappingCount());
    }

    // ---- 陣営ごとの最小距離（minDist を各ボットが持つ版） ----

    private static Separation.BotSample withDist(String key, Side side, double x, double z, boolean eligible, boolean nav,
                                                 double dirX, double dirZ, double minDist) {
        return new Separation.BotSample(key, side, x, 64, z, 0, 0, 0, eligible, nav, nav, dirX, dirZ, minDist);
    }

    @Test
    void pairOfDifferentMinDistancesIsPushedSymmetricallyByTheLarger() {
        Separation.BotSample a = withDist("a", Side.ATTACK, 0, 0, true, false, 0, 0, 0.7);
        Separation.BotSample b = withDist("b", Side.DEFEND, 1.0, 0, true, false, 0, 0, 1.2);
        Map<String, Separation.VelocityDelta> r = Separation.computeSteps(List.of(a, b), new HashSet<>(), NO_GUARD);
        double expected = Separation.PUSH_GAIN * (1.2 - 1.0); // 組の距離は大きい方の 1.2
        assertEquals(-expected, r.get("a").dvX(), EPS);
        assertEquals(expected, r.get("b").dvX(), EPS);
        assertEquals(0.0, r.get("a").dvZ(), EPS);
        // 入力の順序を変えても同じ
        assertEquals(r, Separation.computeSteps(List.of(b, a), new HashSet<>(), NO_GUARD));
        // 小さい方の距離（0.7）だけなら、この間隔（1.0）では押さない
        Separation.BotSample a2 = withDist("a", Side.ATTACK, 0, 0, true, false, 0, 0, 0.7);
        Separation.BotSample b2 = withDist("b", Side.DEFEND, 1.0, 0, true, false, 0, 0, 0.7);
        assertTrue(Separation.computeSteps(List.of(a2, b2), new HashSet<>(), NO_GUARD).isEmpty());
    }

    @Test
    void sideWithSeparationDisabledIsNotPushedButTheOtherSideAvoidsIt() {
        Separation.BotSample off = withDist("off", Side.ATTACK, 0, 0, false, false, 0, 0, 0.0);
        Separation.BotSample on = withDist("on", Side.DEFEND, 0.5, 0, true, false, 0, 0, 0.9);
        Map<String, Separation.VelocityDelta> r = Separation.computeSteps(List.of(off, on), new HashSet<>(), NO_GUARD);
        assertFalse(r.containsKey("off"));
        assertEquals(Separation.PUSH_GAIN * (0.9 - 0.5), r.get("on").dvX(), EPS);
        assertEquals(1, r.size());

        // どちらも無効（minDist 0）なら何もしない
        Separation.BotSample off2 = withDist("off2", Side.DEFEND, 0.1, 0, false, false, 0, 0, 0.0);
        assertTrue(Separation.computeSteps(List.of(off, off2), new HashSet<>(), NO_GUARD).isEmpty());
        // 無効側が適格でも minDist が 0 なら組の距離は相手の値（押されるのは適格な側だけ、倍にならない）
        Separation.BotSample onSame = withDist("on", Side.DEFEND, 0.5, 0, true, false, 0, 0, 0.9);
        Separation.BotSample onAlso = withDist("also", Side.ATTACK, 0.0, 0, true, false, 0, 0, 0.9);
        Map<String, Separation.VelocityDelta> both = Separation.computeSteps(List.of(onAlso, onSame), new HashSet<>(), NO_GUARD);
        assertEquals(-both.get("also").dvX(), both.get("on").dvX(), EPS);
        assertEquals(Separation.PUSH_GAIN * (0.9 - 0.5), both.get("on").dvX(), EPS);
    }

    @Test
    void passingRecordIsReleasedByTheSidesOwnMinDistance() {
        Set<Separation.PairKey> passing = new HashSet<>();
        passing.add(Separation.PairKey.of("a", "b"));
        // 間隔 1.2 は minDist 0.9 + 余裕 0.15 = 1.05 以上なので記録は外れる
        Separation.BotSample a = withDist("a", Side.ATTACK, 0, 0, true, true, 1, 0, 0.9);
        Separation.BotSample b = withDist("b", Side.ATTACK, 1.2, 0, true, true, -1, 0, 0.9);
        Separation.computeSteps(List.of(a, b), passing, NO_GUARD);
        assertFalse(passing.contains(Separation.PairKey.of("a", "b")));

        // 同じ間隔でも、陣営の minDist が 1.5 なら 1.65 未満なので記録は残る
        passing.add(Separation.PairKey.of("a", "b"));
        Separation.BotSample a2 = withDist("a", Side.ATTACK, 0, 0, true, true, 1, 0, 1.5);
        Separation.BotSample b2 = withDist("b", Side.ATTACK, 1.2, 0, true, true, -1, 0, 1.5);
        Separation.computeSteps(List.of(a2, b2), passing, NO_GUARD);
        assertTrue(passing.contains(Separation.PairKey.of("a", "b")));

        // minDist が 0（押し分け無効）の組の記録は捨てる
        Separation.BotSample a3 = withDist("a", Side.ATTACK, 0, 0, false, true, 1, 0, 0.0);
        Separation.BotSample b3 = withDist("b", Side.ATTACK, 0.3, 0, false, true, -1, 0, 0.0);
        Separation.computeSteps(List.of(a3, b3), passing, NO_GUARD);
        assertFalse(passing.contains(Separation.PairKey.of("a", "b")));
    }

    @Test
    void computeAllWorldsWithPerBotMinDistances() {
        Map<String, List<Separation.BotSample>> byWorld = new HashMap<>();
        byWorld.put("w", List.of(withDist("a", Side.ATTACK, 0, 0, true, false, 0, 0, 0.7),
                withDist("b", Side.DEFEND, 1.0, 0, true, false, 0, 0, 1.2)));
        Map<String, Set<Separation.PairKey>> passing = new HashMap<>();
        Map<String, Separation.VelocityDelta> r = Separation.computeAllWorlds(byWorld, passing, w -> NO_GUARD);
        assertEquals(2, r.size());
        assertEquals(-r.get("a").dvX(), r.get("b").dvX(), EPS);
    }

    // ---- PairKey ----

    @Test
    void pairKeyIsCaseInsensitiveAndOrdered() {
        assertEquals(Separation.PairKey.of("Bob", "alice"), Separation.PairKey.of("ALICE", "bob"));
        assertEquals("alice", Separation.PairKey.of("Bob", "alice").a());
        Map<Separation.PairKey, Integer> m = new HashMap<>();
        m.put(Separation.PairKey.of("x", "Y"), 1);
        assertEquals(1, m.get(Separation.PairKey.of("y", "X")));
    }
}
