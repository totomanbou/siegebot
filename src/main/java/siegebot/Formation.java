package siegebot;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;

/**
 * 持ち場（アンカー）と横隊の位置の計算、地形・距離のユーティリティ。
 * 横隊の並び方（{@link #perRow}・{@link #slotOffset}・{@link #snapYaw45}・{@link #yawToward(double, double)}）は
 * Bukkit に依存しない純粋な計算にしてあり、単体テストできる。メインスレッドからだけ呼ぶ。
 */
public final class Formation {

    /** 横隊の間隔（ブロック）と、1 列に並べる最大の人数（これより多ければ 2 列以上）。 */
    static final double FORMATION_SPACING = 1.6;
    static final int FORMATION_ROW_MAX = 5;
    /** 横隊の自分の位置にこの距離まで寄れば着いたとする（ブロック）。 */
    static final double FORMATION_ARRIVE = 0.6;

    /**
     * 持ち場の位置と、その位置が横隊の中の自分の位置か。
     *
     * @param location    持ち場（ボットが立つ位置）
     * @param inFormation 横隊に入っている（同じ持ち場のボットが 2 体以上いる）なら true
     */
    public record Anchor(Location location, boolean inFormation) {
    }

    private final SiegeBotPlugin plugin;

    public Formation(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- 持ち場 ----

    /**
     * 方針から、今いるべき「持ち場」を出す。決められなければ null（その場で近くの敵と戦う）。
     * 追従の相手がいない・敵の陣営に移ったときも null。副作用はなく、横隊に入っているかは戻り値に入れて返す。
     * 全ボットの持ち場を {@link #computeLayout} でまとめて出し、その中から引く（持ち場の決め方はそこだけにある）。
     */
    public Anchor anchorFor(SoldierBot bot) {
        return computeLayout().anchorFor(bot);
    }

    /** 全ボットの持ち場。{@link #computeLayout} がその時点の分をまとめて出す。 */
    public static final class Layout {
        private final Map<SoldierBot, Anchor> anchors = new IdentityHashMap<>();

        /** そのボットの持ち場。参戦していない・持ち場が決められないボットは null。 */
        public Anchor anchorFor(SoldierBot bot) {
            return anchors.get(bot);
        }
    }

    /** 持ち場を共有するボットの組（同じ陣営で、同じ人に追従・同じ地点を守る）。 */
    private record StationKey(Side side, Order.Mode mode, String player, Point point) {
    }

    /**
     * 参戦中の全ボットの持ち場を、一度にまとめて出す。同じ持ち場（同じ陣営で、同じ人に追従・同じ地点を守る）の
     * ボットを出した順に並べ、5 人までは 1 列、それより多ければ 1 列最大 5 人で複数列（前の列が多め）の横隊にする。
     * 間隔は {@link #FORMATION_SPACING}。
     * <ul>
     *   <li>追従: 相手の向き（45 度ごとに丸める）の follow-distance 後ろに横隊（2 列目以降はさらに後ろ）</li>
     *   <li>守れ: 地点を 1 列目の真ん中にし、指示した人が見ていた向き（なければ敵のリスポーン地点の方）を向く横隊</li>
     * </ul>
     * 1 体だけなら base（相手の足元・守る地点）で、横隊には入らない。
     * 横隊に入っているのに自分の位置に立てないボットは、横隊の他の位置と重ならない近くの立てる所に置く
     * （{@link Separation#findOpenSpot}。位置は横隊に入っている扱い）。
     */
    public Layout computeLayout() {
        Layout layout = new Layout();
        Map<StationKey, List<SoldierBot>> groups = new LinkedHashMap<>();
        for (SoldierBot b : plugin.bots().all()) {
            if (b.order == null || !b.npc.isSpawned()) {
                continue;
            }
            Order o = b.order;
            StationKey key = new StationKey(b.side, o.mode(),
                    o.mode() == Order.Mode.FOLLOW ? o.player().toLowerCase(Locale.ROOT) : null,
                    o.mode() == Order.Mode.HOLD ? o.point() : null);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
        }
        for (List<SoldierBot> group : groups.values()) {
            layoutGroup(layout, group);
        }
        return layout;
    }

    private void layoutGroup(Layout layout, List<SoldierBot> group) {
        Order o = group.get(0).order;
        Settings s = plugin.settings(group.get(0).side);
        Location base;
        Player leader = null;
        if (o.mode() == Order.Mode.FOLLOW) {
            // 敵の陣営でなければ追う（チームに入っていないプレイヤーも追える）
            Player p = Bukkit.getPlayerExact(o.player());
            if (p == null || p.isDead() || plugin.teams().sideOf(p) == group.get(0).side.opposite()) {
                return;
            }
            leader = p;
            base = groundBelow(p.getLocation());
        } else {
            Location l = o.point().toLocation();
            if (l == null) {
                return;
            }
            base = groundBelow(l);
        }
        int n = group.size();
        if (n == 1) {
            layout.anchors.put(group.get(0), new Anchor(base, false));
            return;
        }
        double yaw;
        double back;
        if (leader != null) {
            yaw = snapYaw45(leader.getLocation().getYaw()); // 少し向きを変えただけで並び直さない
            back = Math.max(1.5, s.followDistance);
        } else {
            Float fixed = null;
            for (SoldierBot b : group) {
                if (b.holdYaw != null) {
                    fixed = b.holdYaw; // 同じ地点の横隊は最初のボットの向きにそろえる
                    break;
                }
            }
            yaw = fixed != null ? fixed : yawToward(base, plugin.bots().spawnLocation(group.get(0).side.opposite()));
            back = 0.0;
        }
        Location[] slots = new Location[n];
        List<Location> claimed = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] d = slotOffset(i, n, yaw, back);
            slots[i] = standNear(base.getWorld(), base.getX() + d[0], base.getY(), base.getZ() + d[1]);
            if (slots[i] != null) {
                claimed.add(slots[i]);
            }
        }
        double minDist = s.separationEnabled ? s.separationMinDistance : 0.9;
        for (int i = 0; i < n; i++) {
            Location at = slots[i];
            if (at == null) {
                at = plugin.separation().findOpenSpot(base, claimed, minDist);
                claimed.add(at);
            }
            layout.anchors.put(group.get(i), new Anchor(at, true));
        }
    }

    /**
     * プレイヤーが見ているブロックの、見ている面の側の 1 マス（床を見ていれば床の上、壁を見ていれば壁の手前）を、
     * 足場の上に直したもの。空や hold-max-distance より遠い所を見ていれば null。
     */
    public Point lookPoint(Player p) {
        Side side = plugin.teams().sideOf(p);
        Settings s = side == null ? plugin.settings() : plugin.settings(side);
        RayTraceResult r = p.rayTraceBlocks(s.holdMaxDistance, FluidCollisionMode.NEVER);
        if (r == null || r.getHitBlock() == null) {
            return null;
        }
        Block hit = r.getHitBlock();
        BlockFace face = r.getHitBlockFace();
        Block stand = hit.getRelative(face == null ? BlockFace.UP : face);
        return Point.of(groundBelow(stand.getLocation().add(0.5, 0, 0.5)));
    }

    // ---- 横隊の純粋な計算 ----

    /** 同じ持ち場のボットが同じ方針か（同じ人に追従・同じ地点を守る）。 */
    static boolean sameStation(Order a, Order b) {
        if (a == null || b == null || a.mode() != b.mode()) {
            return false;
        }
        if (a.mode() == Order.Mode.FOLLOW) {
            return a.player().equalsIgnoreCase(b.player());
        }
        return a.point().equals(b.point());
    }

    /**
     * 1 列に並ぶ人数。{@link #FORMATION_ROW_MAX} 人までは全員が 1 列、それより多ければ 1 列最大
     * {@link #FORMATION_ROW_MAX} 人で必要な列数に均等に分ける（前の列が多め）。
     */
    static int perRow(int total) {
        if (total <= FORMATION_ROW_MAX) {
            return total;
        }
        int rows = (total + FORMATION_ROW_MAX - 1) / FORMATION_ROW_MAX;
        return (total + rows - 1) / rows;
    }

    /**
     * 横隊の index 番目（0 始まり。出した順）の、持ち場の基準点からのずれ（x, z）。
     * 向き yawDeg（Minecraft の向き: 0 が +Z、90 が -X）の後ろへ baseBack ブロック、2 列目以降はさらに
     * {@link #FORMATION_SPACING} ずつ後ろへ。1 列の中は基準点を真ん中にして {@link #FORMATION_SPACING} ごとに左右へ並ぶ。
     *
     * @param index    0 以上 total 未満
     * @param total    横隊の人数（1 以上）
     * @param yawDeg   横隊が向く向き（度）
     * @param baseBack 1 列目を基準点の後ろに下げる距離（ブロック）
     * @return {dx, dz}
     */
    static double[] slotOffset(int index, int total, double yawDeg, double baseBack) {
        int perRow = perRow(total);
        int row = index / perRow;
        int col = index % perRow;
        int inRow = Math.min(perRow, total - row * perRow);
        double lateral = (col - (inRow - 1) / 2.0) * FORMATION_SPACING;
        double back = baseBack + row * FORMATION_SPACING;
        double rad = Math.toRadians(yawDeg);
        double fx = -Math.sin(rad); // Minecraft の向き: yaw 0 が +Z、90 が -X
        double fz = Math.cos(rad);
        double dx = -fx * back - fz * lateral;
        double dz = -fz * back + fx * lateral;
        return new double[] {dx, dz};
    }

    /** 向きを 45 度ごとに丸める（少し向きを変えただけで横隊を並び直さないため）。 */
    static double snapYaw45(double yaw) {
        return Math.round(yaw / 45.0) * 45.0;
    }

    /** from から to を向く yaw（度）。to がなければ（または別のワールドなら）0（+Z）。 */
    static double yawToward(Location from, Location to) {
        if (to == null || to.getWorld() != from.getWorld()) {
            return 0;
        }
        return yawToward(to.getX() - from.getX(), to.getZ() - from.getZ());
    }

    /** (dx, dz) の向きを向く yaw（度。Minecraft の向き）。ほぼ 0 の距離なら 0（+Z）。 */
    static double yawToward(double dx, double dz) {
        if (dx * dx + dz * dz < 1e-6) {
            return 0;
        }
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    // ---- 地形・距離 ----

    /** 空中（ジャンプ中・飛行中）の位置なら、真下の足場の上に直す。経路探索は空中の地点に向かえないため。 */
    static Location groundBelow(Location loc) {
        Block b = loc.getBlock();
        int minY = loc.getWorld().getMinHeight();
        for (int i = 0; i < 32 && b.getY() > minY; i++) {
            if (b.getRelative(BlockFace.DOWN).getType().isSolid()) {
                return b.getLocation().add(0.5, 0, 0.5);
            }
            b = b.getRelative(BlockFace.DOWN);
        }
        return loc;
    }

    /** (x, z) の、高さ y の近く（上 2・下 3 まで）で立てる所（足と頭が通れて足元が固い）。なければ null。 */
    static Location standNear(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int by = (int) Math.floor(y);
        for (int dy : new int[] {0, 1, -1, 2, -2, -3}) {
            Block feet = w.getBlockAt(bx, by + dy, bz);
            if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(BlockFace.UP).isPassable()
                    && feet.getRelative(BlockFace.DOWN).getType().isSolid()) {
                return new Location(w, x, by + dy, z);
            }
        }
        return null;
    }

    /** {@link #retreatSpot(Location, Location, double, List, double, String)} を、他のボットを避けずに呼ぶ。 */
    static Location retreatSpot(Location here, Location from, double distance) {
        return retreatSpot(here, from, distance, List.of(), 0.0, null);
    }

    /** 逃げる向きを試す順（真後ろ・斜め後ろ・横。度）。 */
    private static final double[] RETREAT_ANGLES = {0, 45, -45, 90, -90};

    /**
     * 相手から離れる向きに distance ブロックほど先の、立てる場所。真後ろ・斜め後ろ・横の順に、段差 ±1 まで探し、
     * occupied（他のボットの位置）のどれとも水平に minDist 以上離れている最初の場所を返す。
     * どこも占められていれば、最初に立てる場所を返す。立てる場所がなければ null。
     * here と from が同じ座標なら、ボットの名前から決めた向きへ逃げる（重なった複数体が同じ向きへ逃げない）。
     */
    static Location retreatSpot(Location here, Location from, double distance, List<Location> occupied, double minDist,
                                String botName) {
        double[] a = retreatDirection(here.getX() - from.getX(), here.getZ() - from.getZ(), botName);
        double dist = Math.max(2.0, distance);
        List<Location> spots = new ArrayList<>();
        for (double deg : RETREAT_ANGLES) {
            double r = Math.toRadians(deg);
            double x = a[0] * Math.cos(r) - a[1] * Math.sin(r);
            double z = a[0] * Math.sin(r) + a[1] * Math.cos(r);
            Block base = here.getWorld().getBlockAt((int) Math.floor(here.getX() + x * dist), here.getBlockY(),
                    (int) Math.floor(here.getZ() + z * dist));
            Location found = null;
            for (int dy : new int[] {0, 1, -1}) {
                Block feet = base.getRelative(0, dy, 0);
                if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(BlockFace.UP).isPassable()
                        && feet.getRelative(BlockFace.DOWN).getType().isSolid()) {
                    found = feet.getLocation().add(0.5, 0, 0.5);
                    break;
                }
            }
            spots.add(found);
        }
        List<double[]> candidates = new ArrayList<>();
        for (Location l : spots) {
            candidates.add(l == null ? null : new double[] {l.getX(), l.getY(), l.getZ()});
        }
        List<double[]> occ = new ArrayList<>();
        for (Location l : occupied) {
            if (l.getWorld() == here.getWorld()) {
                occ.add(new double[] {l.getX(), l.getY(), l.getZ()});
            }
        }
        int i = chooseRetreatCandidate(candidates, occ, minDist);
        return i < 0 ? null : spots.get(i);
    }

    /**
     * 相手から自分への向き（dx, dz）を長さ 1 にして返す。ほぼ同じ座標（長さ 1e-6 未満）なら、名前（null なら 0）から決めた向き。
     *
     * @return {x, z}
     */
    static double[] retreatDirection(double dx, double dz, String botName) {
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            long h = (botName == null ? 0 : botName.hashCode()) * 0x9E3779B97F4A7C15L; // 似た名前でも向きが散るように混ぜる
            h ^= h >>> 32;
            double rad = Math.toRadians(Math.floorMod(h, 360L));
            return new double[] {Math.cos(rad), Math.sin(rad)};
        }
        return new double[] {dx / len, dz / len};
    }

    /**
     * 逃げる先の候補（{x, y, z}。立てなければ null。試す順）から、occupied のどれとも（縦に重なる範囲で）水平に
     * minDist 以上離れている最初のものの番号を返す。全部占められていれば最初に立てる候補の番号。立てる候補がなければ -1。
     */
    static int chooseRetreatCandidate(List<double[]> candidates, List<double[]> occupied, double minDist) {
        int firstStandable = -1;
        for (int i = 0; i < candidates.size(); i++) {
            double[] c = candidates.get(i);
            if (c == null) {
                continue;
            }
            if (firstStandable < 0) {
                firstStandable = i;
            }
            boolean clear = true;
            for (double[] o : occupied) {
                if (Math.abs(c[1] - o[1]) < Separation.VERTICAL_OVERLAP
                        && Math.hypot(c[0] - o[0], c[2] - o[2]) < minDist) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                return i;
            }
        }
        return firstStandable;
    }

    /** self の目から target の当たり判定の箱までの距離（プレイヤーのリーチの測り方）。 */
    static double reachDistance(LivingEntity self, Entity target) {
        Location eye = self.getEyeLocation();
        BoundingBox b = target.getBoundingBox();
        double x = Math.max(b.getMinX(), Math.min(eye.getX(), b.getMaxX()));
        double y = Math.max(b.getMinY(), Math.min(eye.getY(), b.getMaxY()));
        double z = Math.max(b.getMinZ(), Math.min(eye.getZ(), b.getMaxZ()));
        return Math.sqrt((x - eye.getX()) * (x - eye.getX()) + (y - eye.getY()) * (y - eye.getY())
                + (z - eye.getZ()) * (z - eye.getZ()));
    }
}
