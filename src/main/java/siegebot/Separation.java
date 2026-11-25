package siegebot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.PathStrategy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

/**
 * ボットどうしが重ならないようにする。毎 tick、近すぎる（水平距離が separation.min-distance 未満）ボットの組を水平に押し分け、
 * 出す・呼び寄せる・移すときは {@link #findOpenSpot} で空いている立てる所を探す。人間のプレイヤーは対象にしない。
 * 同じ陣営のボットが道の上を逆向きに歩いてすれ違うときだけは、プレイヤーどうしの接触と同じく弱い押しだけにする。
 * 押し分けの計算（{@link #computeSteps}）・崖のガード（{@link #filterByHazard}）・空き地探し（{@link #findOpenGridSpot}）は
 * Bukkit に依存しない純粋な計算にしてあり、単体テストできる。メインスレッドからだけ呼ぶ。
 */
final class Separation {

    static final int TICK_PERIOD = 1;

    /** プレイヤーの高さ。縦の差がこれ未満の組だけを重なりの対象にする。 */
    static final double VERTICAL_OVERLAP = 1.8;
    /** 当たり判定の幅。/sb debug の重なりの判定の閾値。 */
    static final double HITBOX_WIDTH = 0.6;
    static final double HALF_WIDTH = 0.3;
    /** 近すぎる分（minDist - d）に掛けて押す速さ（ブロック/tick）にする係数。 */
    static final double PUSH_GAIN = 0.60;
    /** 厳密に分ける押しの上限（ブロック/tick）。 */
    static final double MAX_PUSH = 0.25;
    /** 逆向きにすれ違う組 1 つあたりの最大の弱い押し（ブロック/tick）。 */
    static final double SOFT_PUSH = 0.05;
    /** 弱い押しの合計の上限（ブロック/tick）。 */
    static final double SOFT_MAX = 0.05;
    /** これを超える速度の変化があるときだけ setVelocity する。 */
    static final double MIN_VELOCITY_DELTA = 0.01;
    /** 押す先の足元を確かめる距離（体の半分 + 最大の押し + 余裕）。 */
    static final double CLIFF_PROBE = HALF_WIDTH + MAX_PUSH + 0.05;
    /** すれ違いの記録は、距離が minDist + この値まで離れるまで保つ（縁で行ったり来たりしないため）。 */
    static final double PASS_KEEP_MARGIN = 0.15;
    /** ダメージを受けてからこの tick の間は押さない（ノックバックを消さない）。 */
    static final int DAMAGE_GRACE_TICKS = 2;
    static final int BFS_MAX_RADIUS = 6;
    static final int BFS_MAX_VISITED = 160;

    private static final double EPS = 1e-9;
    private static final double SAME_POS = 1e-6;

    private final SiegeBotPlugin plugin;
    /** 逆向きにすれ違っている最中の組（TTL はなく、距離・航行の条件で保つ・外す）。 */
    private final Map<UUID, Set<PairKey>> passingByWorld = new HashMap<>();

    Separation(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    /** 名前を小文字にして辞書順に並べた 2 体の組。 */
    record PairKey(String a, String b) {
        static PairKey of(String x, String y) {
            String lx = x.toLowerCase(Locale.ROOT);
            String ly = y.toLowerCase(Locale.ROOT);
            return lx.compareTo(ly) <= 0 ? new PairKey(lx, ly) : new PairKey(ly, lx);
        }
    }

    /**
     * 1 体の、その tick の計算に使う値。
     *
     * @param eligible        押される側として適格か（地面にいる・乗り物に乗っていない・直前に攻撃を受けていない・登れる所や液体でない）。
     *                        false でも他のボットからは障害物として避けられる
     * @param navLocationOnly 経路を歩いている最中で、敵などの追跡（EntityTarget）ではない
     * @param hasDir          進む向き（dirX, dirZ。長さ 1）が分かるか
     * @param minDist         このボットの陣営の押し分ける最小の水平距離（separation.min-distance。押し分けが無効なら 0）。
     *                        組の距離は 2 体の大きい方（片方だけ有効なら、その有効な側の値）
     */
    record BotSample(String key, Side side, double x, double y, double z, double vx, double vy, double vz,
                     boolean eligible, boolean navLocationOnly, boolean hasDir, double dirX, double dirZ,
                     double minDist) {

        /** minDist を決めない形（{@link #computeSteps(List, double, Set, HazardGuard)} が全員に同じ値を入れる）。 */
        BotSample(String key, Side side, double x, double y, double z, double vx, double vy, double vz,
                  boolean eligible, boolean navLocationOnly, boolean hasDir, double dirX, double dirZ) {
            this(key, side, x, y, z, vx, vy, vz, eligible, navLocationOnly, hasDir, dirX, dirZ, 0.0);
        }

        BotSample withMinDist(double d) {
            return new BotSample(key, side, x, y, z, vx, vy, vz, eligible, navLocationOnly, hasDir, dirX, dirZ, d);
        }
    }

    /** 速度に足す変化（水平だけ。縦は変えない）。 */
    record VelocityDelta(double dvX, double dvZ) {
    }

    /** 押す向きを、足元の安全を確かめて直す。戻りは {fx, fz}。 */
    @FunctionalInterface
    interface HazardGuard {
        double[] filter(BotSample bot, double vx, double vz);
    }

    @FunctionalInterface
    interface SafeColumnProbe {
        boolean isSafe(double probeX, int feetY, double probeZ);
    }

    /** (bx, bz) の柱で、refY の近く（dy が 0・1・-1）に立てる足の高さ。立てなければ {@link Integer#MIN_VALUE}。 */
    @FunctionalInterface
    interface StandableGrid {
        int standY(int bx, int bz, int refY);
    }

    enum DirSource { PATH, VELOCITY, NONE }

    record DirInfo(boolean hasDir, double dx, double dz, DirSource source) {
    }

    record SpacingStats(int pairs, double minDistance, int overlappingCount) {
    }

    void clearState() {
        passingByWorld.clear();
    }

    // ---- ブロックの判定 ----

    /** 立つと危ない・入ってはいけないブロック（液体以外）。 */
    static boolean isHazard(Material m) {
        return m != null && (m == Material.WATER || m == Material.LAVA || m == Material.FIRE
                || m == Material.SOUL_FIRE || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE
                || m == Material.MAGMA_BLOCK || m == Material.CACTUS || m == Material.SWEET_BERRY_BUSH
                || m == Material.WITHER_ROSE || m == Material.POWDER_SNOW);
    }

    private static boolean isBad(Block b) {
        return b.isLiquid() || isHazard(b.getType());
    }

    /** 足元か頭が、液体・危険なブロック・蜘蛛の巣・登れるブロックなら true（押すと動きを邪魔する・危ない）。 */
    static boolean isImpeded(Block feet) {
        Block head = feet.getRelative(BlockFace.UP);
        return isBad(feet) || isBad(head) || feet.getType() == Material.COBWEB || head.getType() == Material.COBWEB
                || Tag.CLIMBABLE.isTagged(feet.getType());
    }

    // ---- 進む向き ----

    /**
     * 経路を歩いている最中の進む向き。経路の次の目的地があればそちら（PATH）、なければ速度（VELOCITY）。
     * 歩いていない・敵を追っている（EntityTarget）・向きが決められないときは NONE。
     */
    static DirInfo resolveDirection(SoldierBot bot, LivingEntity le) {
        Navigator nav = bot.npc.getNavigator();
        if (!nav.isNavigating() || nav.getEntityTarget() != null) {
            return new DirInfo(false, 0, 0, DirSource.NONE);
        }
        PathStrategy ps = nav.getPathStrategy();
        Location dest = ps != null ? ps.getCurrentDestination() : null;
        if (dest != null && dest.getWorld() == le.getWorld()) {
            double dx = dest.getX() - le.getLocation().getX();
            double dz = dest.getZ() - le.getLocation().getZ();
            double len = Math.hypot(dx, dz);
            if (len > 0.15) {
                return new DirInfo(true, dx / len, dz / len, DirSource.PATH);
            }
        }
        Vector vel = le.getVelocity();
        double len = Math.hypot(vel.getX(), vel.getZ());
        if (len > 0.05) {
            return new DirInfo(true, vel.getX() / len, vel.getZ() / len, DirSource.VELOCITY);
        }
        return new DirInfo(false, 0, 0, DirSource.NONE);
    }

    // ---- 毎 tick ----

    void tick() {
        if (!plugin.settings(Side.ATTACK).separationEnabled && !plugin.settings(Side.DEFEND).separationEnabled) {
            clearState();
            return;
        }
        long now = Bukkit.getCurrentTick();
        Map<UUID, List<BotSample>> byWorld = new HashMap<>();
        Map<UUID, World> worlds = new HashMap<>();
        Map<String, LivingEntity> entities = new HashMap<>();
        for (SoldierBot b : plugin.bots().all()) {
            if (!b.alive() || !(b.npc.getEntity() instanceof LivingEntity le) || le.isDead()) {
                continue;
            }
            Location l = le.getLocation();
            Vector v = le.getVelocity();
            Block feet = le.getWorld().getBlockAt(l.getBlockX(), (int) Math.floor(l.getY() + 0.01), l.getBlockZ());
            Settings bs = plugin.settings(b.side());
            boolean eligible = bs.separationEnabled && le.isOnGround() && !le.isInsideVehicle()
                    && now - b.lastDamagedTick >= DAMAGE_GRACE_TICKS && !isImpeded(feet);
            double botMinDist = bs.separationEnabled ? bs.separationMinDistance : 0.0;
            Navigator nav = b.npc.getNavigator();
            boolean navLocationOnly = nav.isNavigating() && nav.getEntityTarget() == null;
            DirInfo dir = navLocationOnly ? resolveDirection(b, le) : new DirInfo(false, 0, 0, DirSource.NONE);
            String key = b.name().toLowerCase(Locale.ROOT);
            worlds.put(le.getWorld().getUID(), le.getWorld());
            byWorld.computeIfAbsent(le.getWorld().getUID(), w -> new ArrayList<>()).add(new BotSample(key, b.side(),
                    l.getX(), l.getY(), l.getZ(), v.getX(), v.getY(), v.getZ(), eligible, navLocationOnly,
                    dir.hasDir(), dir.dx(), dir.dz(), botMinDist));
            entities.put(key, le);
        }
        Map<String, VelocityDelta> steps = computeAllWorlds(byWorld, passingByWorld,
                id -> (bot, vx, vz) -> filterByHazard(bot.x(), bot.y(), bot.z(), vx, vz,
                        (px, by, pz) -> isSafeColumn(worlds.get(id), px, by, pz)));
        for (Map.Entry<String, VelocityDelta> st : steps.entrySet()) {
            LivingEntity le = entities.get(st.getKey());
            Vector cur = le.getVelocity();
            le.setVelocity(new Vector(cur.getX() + st.getValue().dvX(), cur.getY(), cur.getZ() + st.getValue().dvZ()));
        }
    }

    /**
     * ワールドごとに {@link #computeSteps} を呼んで結果をまとめる。すれ違いの記録はワールドごとに持ち、
     * ボットがいなくなったワールドの記録は捨てる（空になった記録も残さない）。
     */
    static <K> Map<String, VelocityDelta> computeAllWorlds(Map<K, List<BotSample>> byWorld, double minDist,
                                                           Map<K, Set<PairKey>> passingByWorld,
                                                           Function<K, HazardGuard> guardOf) {
        Map<K, List<BotSample>> withDist = new HashMap<>();
        for (Map.Entry<K, List<BotSample>> e : byWorld.entrySet()) {
            List<BotSample> list = new ArrayList<>();
            for (BotSample s : e.getValue()) {
                list.add(s.withMinDist(minDist));
            }
            withDist.put(e.getKey(), list);
        }
        return computeAllWorlds(withDist, passingByWorld, guardOf);
    }

    /** {@link #computeAllWorlds(Map, double, Map, Function)} の、各ボットが minDist を持つ版。 */
    static <K> Map<String, VelocityDelta> computeAllWorlds(Map<K, List<BotSample>> byWorld,
                                                           Map<K, Set<PairKey>> passingByWorld,
                                                           Function<K, HazardGuard> guardOf) {
        passingByWorld.keySet().retainAll(byWorld.keySet());
        Map<String, VelocityDelta> all = new HashMap<>();
        for (Map.Entry<K, List<BotSample>> e : byWorld.entrySet()) {
            K worldKey = e.getKey();
            Set<PairKey> wp = passingByWorld.computeIfAbsent(worldKey, k -> new HashSet<>());
            all.putAll(computeSteps(e.getValue(), wp, guardOf.apply(worldKey)));
            if (wp.isEmpty()) {
                passingByWorld.remove(worldKey);
            }
        }
        return all;
    }

    // ---- 押し分けの計算（純粋） ----

    /**
     * 1 つのワールドのボットを押し分ける速度の変化を出す。適格（eligible）なボットで、変化が {@link #MIN_VELOCITY_DELTA} を
     * 超えるものだけを返す。縦（Y）は変えない。
     * <ul>
     *   <li>厳密な分離: 組ごとに (minDist - d) × {@link #PUSH_GAIN} の押しを足し、合計を {@link #MAX_PUSH} までにして、
     *       押す向きの速度がその大きさに足りなければ足す（今の速度は削らない。氷の上でも速度が積もらない）</li>
     *   <li>弱い押し: 同じ陣営で、どちらも経路を歩いていて逆向きに近づく組（passingPairs に記録）は {@link #SOFT_PUSH} まで。
     *       合計は {@link #SOFT_MAX} までで、厳密な押しとは別に足す</li>
     * </ul>
     * 適格でないボットは押されないが、他のボットからは避けられる。
     *
     * @param passingPairs 逆向きにすれ違っている最中の組（この関数が足す・外す）
     * @param guard        押す向きを崖などから守る
     */
    static Map<String, VelocityDelta> computeSteps(List<BotSample> bots, double minDist, Set<PairKey> passingPairs,
                                                   HazardGuard guard) {
        List<BotSample> withDist = new ArrayList<>();
        for (BotSample s : bots) {
            withDist.add(s.withMinDist(minDist));
        }
        return computeSteps(withDist, passingPairs, guard);
    }

    /**
     * {@link #computeSteps(List, double, Set, HazardGuard)} の、各ボット（{@link BotSample#minDist}）が自分の陣営の最小距離を持つ版。
     * 組の距離は 2 体の大きい方で、0 以下なら（両方とも押し分けが無効なら）その組は何もしない。
     */
    static Map<String, VelocityDelta> computeSteps(List<BotSample> bots, Set<PairKey> passingPairs, HazardGuard guard) {
        int n = bots.size();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(bots.get(i).key().toLowerCase(Locale.ROOT), i);
        }
        passingPairs.removeIf(pk -> {
            Integer ia = index.get(pk.a());
            Integer ib = index.get(pk.b());
            if (ia == null || ib == null) {
                return true;
            }
            BotSample a = bots.get(ia);
            BotSample b = bots.get(ib);
            double pairDist = Math.max(a.minDist(), b.minDist());
            return pairDist <= 0.0 || a.side() != b.side() || !a.navLocationOnly() || !b.navLocationOnly()
                    || Math.abs(a.y() - b.y()) >= VERTICAL_OVERLAP
                    || Math.hypot(a.x() - b.x(), a.z() - b.z()) >= pairDist + PASS_KEEP_MARGIN;
        });

        double[] strictX = new double[n];
        double[] strictZ = new double[n];
        double[] softX = new double[n];
        double[] softZ = new double[n];
        for (int i = 0; i < n; i++) {
            BotSample bi = bots.get(i);
            for (int j = i + 1; j < n; j++) {
                BotSample bj = bots.get(j);
                if (Math.abs(bi.y() - bj.y()) >= VERTICAL_OVERLAP) {
                    continue;
                }
                double dx = bi.x() - bj.x();
                double dz = bi.z() - bj.z();
                double d = Math.hypot(dx, dz);
                double pairDist = Math.max(bi.minDist(), bj.minDist());
                if (pairDist <= 0.0 || d >= pairDist) {
                    continue;
                }
                PairKey pk = PairKey.of(bi.key(), bj.key());
                boolean soft = passingPairs.contains(pk);
                if (!soft && isOpposingPass(bi, bj, dx, dz, d)) {
                    passingPairs.add(pk);
                    soft = true;
                }
                if (soft) {
                    if (d >= SAME_POS) {
                        double mag = SOFT_PUSH * (pairDist - d) / pairDist;
                        double ux = dx / d;
                        double uz = dz / d;
                        if (bi.eligible()) {
                            softX[i] += ux * mag;
                            softZ[i] += uz * mag;
                        }
                        if (bj.eligible()) {
                            softX[j] -= ux * mag;
                            softZ[j] -= uz * mag;
                        }
                    }
                    continue;
                }
                double ux;
                double uz;
                if (d >= SAME_POS) {
                    ux = dx / d;
                    uz = dz / d;
                } else {
                    // 完全に重なっている: 組ごとに決まる向きへ、互いに逆向きに分ける（同じ場所の多体が同じ向きへ偏らない）
                    // 辞書順で小さい方がその向き、大きい方が厳密に逆向き（入力の順序によらず同じ結果）
                    double angle = pairAngle(pk);
                    double sign = bi.key().toLowerCase(Locale.ROOT).compareTo(bj.key().toLowerCase(Locale.ROOT)) <= 0 ? 1.0 : -1.0;
                    ux = sign * Math.cos(angle);
                    uz = sign * Math.sin(angle);
                }
                double mag = PUSH_GAIN * (pairDist - d);
                if (bi.eligible()) {
                    strictX[i] += ux * mag;
                    strictZ[i] += uz * mag;
                }
                if (bj.eligible()) {
                    strictX[j] -= ux * mag;
                    strictZ[j] -= uz * mag;
                }
            }
        }

        Map<String, VelocityDelta> result = new HashMap<>();
        for (int i = 0; i < n; i++) {
            BotSample b = bots.get(i);
            if (!b.eligible()) {
                continue;
            }
            double curVx = b.vx();
            double curVz = b.vz();
            double strictLen = Math.hypot(strictX[i], strictZ[i]);
            if (strictLen > EPS) {
                double k = Math.min(1.0, MAX_PUSH / strictLen);
                double[] f = guard.filter(b, strictX[i] * k, strictZ[i] * k);
                double targetSpeed = Math.hypot(f[0], f[1]);
                if (targetSpeed > EPS) {
                    double dirX = f[0] / targetSpeed;
                    double dirZ = f[1] / targetSpeed;
                    double vDir = curVx * dirX + curVz * dirZ;
                    if (vDir < targetSpeed) {
                        curVx += dirX * (targetSpeed - vDir);
                        curVz += dirZ * (targetSpeed - vDir);
                    }
                }
            }
            double softLen = Math.hypot(softX[i], softZ[i]);
            if (softLen > EPS) {
                double k = Math.min(1.0, SOFT_MAX / softLen);
                double[] f = guard.filter(b, softX[i] * k, softZ[i] * k);
                curVx += f[0];
                curVz += f[1];
            }
            double dvX = curVx - b.vx();
            double dvZ = curVz - b.vz();
            if (Math.hypot(dvX, dvZ) > MIN_VELOCITY_DELTA) {
                result.put(b.key(), new VelocityDelta(dvX, dvZ));
            }
        }
        return result;
    }

    /** 同じ陣営の 2 体が、経路を歩いていて、向かい合って逆向きに進んでいるか。(dx, dz) は i から見た i - j。 */
    private static boolean isOpposingPass(BotSample bi, BotSample bj, double dx, double dz, double d) {
        if (bi.side() != bj.side() || !bi.navLocationOnly() || !bj.navLocationOnly()
                || !bi.hasDir() || !bj.hasDir()) {
            return false;
        }
        if (bi.dirX() * bj.dirX() + bi.dirZ() * bj.dirZ() >= -0.5) {
            return false;
        }
        if (d < SAME_POS) {
            return true;
        }
        double toJx = -dx / d;
        double toJz = -dz / d;
        return bi.dirX() * toJx + bi.dirZ() * toJz > 0.2 && bj.dirX() * -toJx + bj.dirZ() * -toJz > 0.2;
    }

    /** 組から決まる 0〜2π の角度（同じ組なら常に同じ。ハッシュを混ぜて、名前が似ていても散らす）。 */
    private static double pairAngle(PairKey pk) {
        long h = (pk.a() + "\0" + pk.b()).hashCode();
        h *= 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53 * 2 * Math.PI;
    }

    // ---- 崖・危険なブロックのガード ----

    /**
     * 押す先が崖・液体・危険なブロックなら、その軸の押しを 0 にする（安全な軸へ横に滑らせる）。
     * 押す軸ごとに体の端の少し先（{@link #CLIFF_PROBE}）の足元を調べ、両軸が残るときは斜め先も調べて、
     * 危なければ大きい方の軸だけを残す。戻りは {fx, fz}。
     */
    static double[] filterByHazard(double x, double y, double z, double vx, double vz, SafeColumnProbe probe) {
        int by = (int) Math.floor(y + 0.01); // ハーフブロックの上でも足元の段を取り違えない
        double fx = vx;
        double fz = vz;
        if (fx != 0 && !probe.isSafe(x + Math.signum(vx) * CLIFF_PROBE, by, z)) {
            fx = 0;
        }
        if (fz != 0 && !probe.isSafe(x, by, z + Math.signum(vz) * CLIFF_PROBE)) {
            fz = 0;
        }
        if (fx != 0 && fz != 0
                && !probe.isSafe(x + Math.signum(fx) * CLIFF_PROBE, by, z + Math.signum(fz) * CLIFF_PROBE)) {
            if (Math.abs(fx) >= Math.abs(fz)) {
                fz = 0;
            } else {
                fx = 0;
            }
        }
        return new double[] {fx, fz};
    }

    /** 1 つのブロックの、安全の判定に使う性質（Bukkit に依存しない形）。 */
    record Cell(Material type, boolean liquid, boolean passable, boolean solid) {
        boolean bad() {
            return liquid || isHazard(type);
        }

        /** 足場になる（固い、または通れない）。 */
        boolean floor() {
            return solid || !passable;
        }
    }

    static Cell cellOf(Block b) {
        return new Cell(b.getType(), b.isLiquid(), b.isPassable(), b.getType().isSolid());
    }

    /**
     * その柱が押されて入っても安全か（ワールド版）。{@link #isSafeColumn(IntFunction, int)} を、(probeX, probeZ) の柱で呼ぶ。
     */
    static boolean isSafeColumn(World w, double probeX, int by, double probeZ) {
        int bx = (int) Math.floor(probeX);
        int bz = (int) Math.floor(probeZ);
        return isSafeColumn(y -> cellOf(w.getBlockAt(bx, y, bz)), by);
    }

    /**
     * その柱が押されて入っても安全か。足元（by）が液体・危険なら false、通れない（壁・1 段上の床）なら true（押しても入れない）。
     * 通れるなら、0 段または 1 段落ちる所までを安全とし、2 段以上の穴・崖は false。途中に液体・危険があれば false。
     *
     * @param cellAt 高さ y のブロックの性質
     */
    static boolean isSafeColumn(IntFunction<Cell> cellAt, int by) {
        Cell feet = cellAt.apply(by);
        if (feet.bad()) {
            return false;
        }
        if (!feet.passable()) {
            return true;
        }
        Cell below1 = cellAt.apply(by - 1);
        if (below1.bad()) {
            return false;
        }
        if (below1.floor()) {
            return true;
        }
        Cell below2 = cellAt.apply(by - 2);
        if (below2.bad()) {
            return false;
        }
        return below2.floor();
    }

    // ---- 空いている立てる所を探す ----

    /** (x, y, z) が、occupied のどれとも（縦に重なる範囲で）水平に minDist 以上離れているか。 */
    private static boolean isClear(double x, double y, double z, List<double[]> occupied, double minDist) {
        for (double[] o : occupied) {
            if (Math.abs(y - o[1]) >= VERTICAL_OVERLAP) {
                continue;
            }
            double dx = x - o[0];
            double dz = z - o[2];
            if (dx * dx + dz * dz < minDist * minDist) {
                return false;
            }
        }
        return true;
    }

    /**
     * origin に立てるなら origin。重なるなら、origin から歩いて行ける（4 近傍で段差 ±1 まで・半径 {@link #BFS_MAX_RADIUS}・
     * 訪ねるマスは {@link #BFS_MAX_VISITED} まで）マスの中心のうち、最初に空いているものを返す。なければ origin のまま。
     *
     * @param occupied {x, y, z} の一覧（同じワールドのもの）
     */
    static double[] findOpenGridSpot(double originX, double originY, double originZ, List<double[]> occupied,
                                     double minDist, StandableGrid grid) {
        double[] origin = {originX, originY, originZ};
        if (isClear(originX, originY, originZ, occupied, minDist)) {
            return origin;
        }
        int startX = (int) Math.floor(originX);
        int startY = (int) Math.floor(originY + 0.01);
        int startZ = (int) Math.floor(originZ);
        int stand = grid.standY(startX, startZ, startY);
        if (stand != Integer.MIN_VALUE) {
            startY = stand;
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(new int[] {startX, startY, startZ});
        visited.add(cellKey(startX, startZ));
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            if ((cur[0] != startX || cur[2] != startZ || stand != Integer.MIN_VALUE)
                    && isClear(cur[0] + 0.5, cur[1], cur[2] + 0.5, occupied, minDist)) {
                return new double[] {cur[0] + 0.5, cur[1], cur[2] + 0.5};
            }
            for (int[] dd : dirs) {
                int nx = cur[0] + dd[0];
                int nz = cur[2] + dd[1];
                if (Math.abs(nx - startX) > BFS_MAX_RADIUS || Math.abs(nz - startZ) > BFS_MAX_RADIUS
                        || visited.size() >= BFS_MAX_VISITED || !visited.add(cellKey(nx, nz))) {
                    continue;
                }
                int ny = grid.standY(nx, nz, cur[1]);
                if (ny != Integer.MIN_VALUE && Math.abs(ny - cur[1]) <= 1) {
                    queue.add(new int[] {nx, ny, nz});
                }
            }
        }
        return origin;
    }

    private static long cellKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** そのマスに立てるか（足と頭が通れて、液体・危険・蜘蛛の巣でなく、足元が固く危険でない）。 */
    private static boolean canStand(Block feet) {
        Block head = feet.getRelative(BlockFace.UP);
        Block below = feet.getRelative(BlockFace.DOWN);
        return feet.isPassable() && head.isPassable() && !isBad(feet) && !isBad(head) && !isBad(below)
                && feet.getType() != Material.COBWEB && head.getType() != Material.COBWEB
                && (below.getType().isSolid() || !below.isPassable());
    }

    /** origin の近くの、他のボット（occupied）と minDist 以上離れた立てる場所。見つからなければ origin（の足場）のまま。 */
    Location findOpenSpot(Location origin, List<Location> occupied, double minDist) {
        Location base = Formation.groundBelow(origin);
        World w = base.getWorld();
        List<double[]> occ = new ArrayList<>();
        for (Location l : occupied) {
            if (l.getWorld() == w) {
                occ.add(new double[] {l.getX(), l.getY(), l.getZ()});
            }
        }
        double[] r = findOpenGridSpot(base.getX(), base.getY(), base.getZ(), occ, minDist, (bx, bz, refY) -> {
            for (int dy : new int[] {0, 1, -1}) {
                if (canStand(w.getBlockAt(bx, refY + dy, bz))) {
                    return refY + dy;
                }
            }
            return Integer.MIN_VALUE;
        });
        return new Location(w, r[0], r[1], r[2], origin.getYaw(), origin.getPitch());
    }

    // ---- /sb debug ----

    /** 1 つのワールドの位置 {x, y, z} の一覧から、縦に重なる組の水平の最小距離と、当たり判定が重なる組の数。 */
    static SpacingStats measureSpacing(List<double[]> positions) {
        int pairs = 0;
        int overlapping = 0;
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < positions.size(); i++) {
            for (int j = i + 1; j < positions.size(); j++) {
                double[] a = positions.get(i);
                double[] b = positions.get(j);
                if (Math.abs(a[1] - b[1]) >= VERTICAL_OVERLAP) {
                    continue;
                }
                double d = Math.hypot(a[0] - b[0], a[2] - b[2]);
                pairs++;
                min = Math.min(min, d);
                if (d < HITBOX_WIDTH) {
                    overlapping++;
                }
            }
        }
        return new SpacingStats(pairs, min, overlapping);
    }

    /** 全ワールドの生きているボットの間隔（組がなければ minDistance は +∞）。 */
    SpacingStats measureAll() {
        Map<World, List<double[]>> byWorld = new HashMap<>();
        for (SoldierBot b : plugin.bots().all()) {
            if (b.alive()) {
                Location l = b.npc.getEntity().getLocation();
                byWorld.computeIfAbsent(l.getWorld(), w -> new ArrayList<>()).add(new double[] {l.getX(), l.getY(), l.getZ()});
            }
        }
        int pairs = 0;
        int overlapping = 0;
        double min = Double.POSITIVE_INFINITY;
        for (List<double[]> list : byWorld.values()) {
            SpacingStats st = measureSpacing(list);
            pairs += st.pairs();
            overlapping += st.overlappingCount();
            min = Math.min(min, st.minDistance());
        }
        return new SpacingStats(pairs, min, overlapping);
    }
}
