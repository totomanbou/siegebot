package siegebot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.EntityTarget;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.PathfinderType;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.RayTraceResult;

/**
 * 兵士ボットの生成・破棄と、毎回の行動の決定。
 *
 * <p>行動の決め方（{@link #TICK_PERIOD} tick ごと）:
 * <ol>
 *   <li>方針から「持ち場」を引く（追従なら相手の足元、地点を守るならその地点。{@link #anchorFor}）。</li>
 *   <li>持ち場の周りにいる敵のうち、見えている最も近い敵を狙う（{@link #pickTarget}）。</li>
 *   <li>敵がいなければ持ち場へ歩く。</li>
 * </ol>
 * メインスレッドからだけ呼ぶ。
 */
public final class BotManager {

    public static final int TICK_PERIOD = 10;
    /** 見えていなくても気づく距離。 */
    private static final double SENSE_RADIUS = 5.0;
    /** この距離の敵には、持ち場からの距離（leash）に関係なく応戦する。 */
    private static final double SELF_DEFENSE_RADIUS = 8.0;
    /** 狙いを選び直すとき、今の相手よりこれだけ（ブロック）近い敵がいれば替える。 */
    private static final double RETARGET_MARGIN = 2.0;

    private final SiegeBotPlugin plugin;
    private final NPCRegistry registry;
    private final Map<String, SoldierBot> bots = new LinkedHashMap<>();
    private long tickCount;

    public BotManager(SiegeBotPlugin plugin) {
        this.plugin = plugin;
        this.registry = CitizensAPI.createInMemoryNPCRegistry("siegebot");
    }

    // ---- 一覧 ----

    public Collection<SoldierBot> all() {
        return bots.values();
    }

    public List<SoldierBot> of(Side side) {
        List<SoldierBot> r = new ArrayList<>();
        for (SoldierBot b : bots.values()) {
            if (b.side == side) {
                r.add(b);
            }
        }
        return r;
    }

    public SoldierBot get(String name) {
        return name == null ? null : bots.get(name.toLowerCase(Locale.ROOT));
    }

    /** エンティティがボットならそのボット、違えば null。 */
    public SoldierBot byEntity(Entity e) {
        if (e == null || !registry.isNPC(e)) {
            return null;
        }
        NPC npc = registry.getNPC(e);
        for (SoldierBot b : bots.values()) {
            if (b.npc == npc) {
                return b;
            }
        }
        return null;
    }

    // ---- 生成・破棄 ----

    /**
     * 持ち主のいないボット（管理者のボット）を作って出す。出した地点を守る。
     *
     * @param fallback リスポーン地点が未設定のときに出す場所（null 可）
     * @return 作ったボット。同名がいる・出す場所がない場合は null
     */
    public SoldierBot spawn(String name, Side side, Location fallback) {
        return spawn(name, side, fallback, null, null);
    }

    /**
     * ボットを作って出す。持ち主がいれば持ち主に追従し、いなければ出した地点を守る（どちらも持ち場優先）。
     *
     * @param owner     持ち主の UUID（管理者のボットは null）
     * @param ownerName 持ち主の名前（管理者のボットは null）
     */
    public SoldierBot spawn(String name, Side side, Location fallback, UUID owner, String ownerName) {
        if (get(name) != null) {
            return null;
        }
        Location at = spawnLocation(side);
        if (at == null) {
            at = fallback;
        }
        if (at == null) {
            return null;
        }
        NPC npc = registry.createNPC(EntityType.PLAYER, name);
        npc.setProtected(false);
        npc.data().set(NPC.Metadata.DISABLE_DEFAULT_STUCK_ACTION, true);
        npc.data().set(NPC.Metadata.KEEP_CHUNK_LOADED, true);
        Equipment eq = npc.getOrAddTrait(Equipment.class);
        for (Map.Entry<String, Material> e : plugin.settings().equipment.entrySet()) {
            Equipment.EquipmentSlot slot = slotOf(e.getKey());
            if (slot != null) {
                eq.set(slot, new ItemStack(e.getValue()));
            }
        }
        applyNavigatorSettings(npc);
        SoldierBot bot = new SoldierBot(name, side, npc, owner);
        bot.order = owner != null && ownerName != null
                ? Order.follow(ownerName, Order.Stance.GUARD)
                : Order.hold(Point.of(at), Order.Stance.GUARD);
        bots.put(name.toLowerCase(Locale.ROOT), bot);
        npc.spawn(at, SpawnReason.PLUGIN);
        return bot;
    }

    public boolean remove(String name) {
        SoldierBot b = name == null ? null : bots.remove(name.toLowerCase(Locale.ROOT));
        if (b == null) {
            return false;
        }
        b.npc.destroy();
        return true;
    }

    public void removeAll() {
        for (SoldierBot b : bots.values()) {
            b.npc.destroy();
        }
        bots.clear();
    }

    // ---- 専用のボット（プレイヤー 1 人に 1 体） ----

    /** そのプレイヤーの専用のボット。なければ null。 */
    public SoldierBot ownedBy(UUID player) {
        if (player == null) {
            return null;
        }
        for (SoldierBot b : bots.values()) {
            if (player.equals(b.owner)) {
                return b;
            }
        }
        return null;
    }

    /** そのプレイヤーの専用のボットを消す。 */
    public void removeOwned(UUID player) {
        SoldierBot b = ownedBy(player);
        if (b != null) {
            remove(b.name);
        }
    }

    /**
     * 専用のボットを、オンラインのプレイヤーとチームに合わせる。
     * チームに入っていて専用のボットがない人には出し、チームを抜けた・移った人のボットは消す（移った人には出し直す）。
     */
    public void syncPersonalBots() {
        if (!plugin.settings().personalBots) {
            for (SoldierBot b : new ArrayList<>(bots.values())) {
                if (b.owner != null) {
                    remove(b.name);
                }
            }
            return;
        }
        List<UUID> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(p.getUniqueId());
            Side side = sideOf(p);
            SoldierBot mine = ownedBy(p.getUniqueId());
            if (mine != null && mine.side != side) {
                remove(mine.name);
                mine = null;
            }
            if (mine == null && side != null) {
                SoldierBot b = spawn(personalName(p.getName()), side, p.getLocation(), p.getUniqueId(), p.getName());
                if (b != null) {
                    say(b, p.getName() + " の兵として参戦する");
                }
            }
        }
        for (SoldierBot b : new ArrayList<>(bots.values())) {
            if (b.owner != null && !online.contains(b.owner)) {
                remove(b.name);
            }
        }
    }

    /** 専用のボットの名前（「名前の兵」）。16 文字を超えるなら名前を縮め、他と重なれば番号を付ける。 */
    private String personalName(String player) {
        String base = player.length() > 14 ? player.substring(0, 14) : player;
        String name = base + "の兵";
        for (int i = 2; get(name) != null; i++) {
            name = base.substring(0, Math.min(base.length(), 13)) + i + "の兵";
        }
        return name;
    }

    /** config.yml を読み直した後に、移動・攻撃の設定を全ボットに入れ直す。 */
    public void reapplySettings() {
        for (SoldierBot b : bots.values()) {
            applyNavigatorSettings(b.npc);
        }
    }

    private void applyNavigatorSettings(NPC npc) {
        Settings s = plugin.settings();
        NavigatorParameters p = npc.getNavigator().getDefaultParameters();
        p.range(s.pathRange);
        p.speedModifier(s.speed);
        p.attackRange(s.attackRange);
        p.attackDelayTicks(s.attackDelayTicks);
        // 動けないときに目的地へ瞬間移動しない（その場で経路を取り直す）
        p.stuckAction((n, nav) -> false);
        try {
            p.pathfinderType(PathfinderType.valueOf(s.pathfinder));
        } catch (IllegalArgumentException e) {
            p.pathfinderType(PathfinderType.CITIZENS);
        }
    }

    private static Equipment.EquipmentSlot slotOf(String key) {
        return switch (key) {
            case "hand" -> Equipment.EquipmentSlot.HAND;
            case "off_hand", "offhand" -> Equipment.EquipmentSlot.OFF_HAND;
            case "helmet" -> Equipment.EquipmentSlot.HELMET;
            case "chestplate" -> Equipment.EquipmentSlot.CHESTPLATE;
            case "leggings" -> Equipment.EquipmentSlot.LEGGINGS;
            case "boots" -> Equipment.EquipmentSlot.BOOTS;
            default -> null;
        };
    }

    // ---- 方針 ----

    public void setOrder(SoldierBot bot, Order order) {
        bot.order = order;
    }

    /**
     * 指示を反映して、最初のボットに復唱させる。全員が同じ方針なら 1 つにまとめ、違うなら 1 体ずつ並べる。
     */
    public void applyOrders(Map<SoldierBot, Order> orders) {
        if (orders.isEmpty()) {
            return;
        }
        Set<Order> distinct = new LinkedHashSet<>();
        List<String> parts = new ArrayList<>();
        for (Map.Entry<SoldierBot, Order> e : orders.entrySet()) {
            setOrder(e.getKey(), e.getValue());
            distinct.add(e.getValue());
            parts.add(e.getKey().name + "：" + e.getValue().describe());
        }
        SoldierBot voice = orders.keySet().iterator().next();
        say(voice, distinct.size() == 1 ? "了解。" + distinct.iterator().next().describe()
                : "了解。" + String.join(" / ", parts));
    }

    // ---- 陣営の判定 ----

    /** プレイヤーのスコアボードのチームから陣営を出す。どちらでもなければ null。 */
    public Side sideOf(Player p) {
        Team t = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(p.getName());
        if (t == null) {
            return null;
        }
        for (Side s : Side.values()) {
            if (t.getName().equalsIgnoreCase(plugin.settings().teamName(s))) {
                return s;
            }
        }
        return null;
    }

    /** エンティティ（プレイヤーかボット）の陣営。どちらでもなければ null。 */
    public Side sideOf(Entity e) {
        SoldierBot b = byEntity(e);
        if (b != null) {
            return b.side;
        }
        return e instanceof Player p ? sideOf(p) : null;
    }

    public List<Player> humans(Side side) {
        List<Player> r = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (sideOf(p) == side) {
                r.add(p);
            }
        }
        return r;
    }

    /** ボットの発言を、同じ陣営のプレイヤーにだけ送る。 */
    public void say(SoldierBot bot, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Component msg = Component.text("<" + bot.name + "> ", NamedTextColor.AQUA)
                .append(Component.text(text, NamedTextColor.WHITE));
        for (Player p : humans(bot.side)) {
            p.sendMessage(msg);
        }
    }

    // ---- 場所 ----

    /** 陣営のリスポーン地点。未設定なら null。 */
    public Location spawnLocation(Side side) {
        Point p = plugin.data().spawns().get(side);
        return p == null ? null : p.toLocation();
    }

    /** 倒されたボットを出し直す場所: リスポーン地点、なければ持ち主の横、なければ最後にいた所。 */
    private Location respawnLocation(SoldierBot bot) {
        Location l = spawnLocation(bot.side);
        if (l != null) {
            return l;
        }
        Player owner = bot.owner == null ? null : Bukkit.getPlayer(bot.owner);
        if (owner != null && !owner.isDead()) {
            return owner.getLocation();
        }
        return bot.npc.getStoredLocation();
    }

    /**
     * 方針から、今いるべき「持ち場」を出す。決められなければ null（その場で近くの敵と戦う）。
     * 追従の相手がいない・敵の陣営に移ったときも null。
     */
    public Location anchorFor(SoldierBot bot) {
        Order o = bot.order;
        if (o == null) {
            return null;
        }
        if (o.mode() == Order.Mode.FOLLOW) {
            // 敵の陣営でなければ追う（チームに入っていないプレイヤーも追える）
            Player p = Bukkit.getPlayerExact(o.player());
            if (p != null && !p.isDead() && sideOf(p) != bot.side.opposite()) {
                return groundBelow(p.getLocation());
            }
            return null;
        }
        Location l = o.point().toLocation();
        return l == null ? null : groundBelow(l);
    }

    /**
     * プレイヤーが見ているブロックの、見ている面の側の 1 マス（床を見ていれば床の上、壁を見ていれば壁の手前）を、
     * 足場の上に直したもの。空や hold-max-distance より遠い所を見ていれば null。
     */
    public Point lookPoint(Player p) {
        RayTraceResult r = p.rayTraceBlocks(plugin.settings().holdMaxDistance, FluidCollisionMode.NEVER);
        if (r == null || r.getHitBlock() == null) {
            return null;
        }
        Block hit = r.getHitBlock();
        BlockFace face = r.getHitBlockFace();
        Block stand = hit.getRelative(face == null ? BlockFace.UP : face);
        return Point.of(groundBelow(stand.getLocation().add(0.5, 0, 0.5)));
    }

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

    // ---- 毎回の行動 ----

    public void tick() {
        tickCount++;
        if (tickCount % 4 == 0) {
            syncPersonalBots();
        }
        long now = Bukkit.getCurrentTick();
        for (SoldierBot bot : bots.values()) {
            try {
                tickBot(bot, now);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("ボット " + bot.name + " の処理で例外: " + e);
            }
        }
    }

    private void tickBot(SoldierBot bot, long now) {
        NPC npc = bot.npc;
        if (!npc.isSpawned()) {
            bot.target = null;
            if (bot.respawnAtTick == 0) {
                bot.respawnAtTick = now + plugin.settings().respawnSeconds * 20L;
            }
            if (now >= bot.respawnAtTick) {
                Location at = respawnLocation(bot);
                if (at != null && npc.spawn(at, SpawnReason.RESPAWN)) {
                    bot.respawnAtTick = 0;
                }
            }
            return;
        }
        bot.respawnAtTick = 0;
        if (!(npc.getEntity() instanceof LivingEntity self)) {
            return;
        }
        Settings s = plugin.settings();
        Location anchor = anchorFor(bot);
        if (anchor != null && anchor.getWorld() != self.getWorld()) {
            anchor = null;
        }
        Navigator nav = npc.getNavigator();

        LivingEntity target = pickTarget(bot, self, anchor, now);
        bot.target = target;
        if (target != null) {
            EntityTarget cur = nav.getEntityTarget();
            if (cur == null || cur.getTarget() != target || !nav.isNavigating()) {
                nav.setTarget(target, true);
            }
            return;
        }
        if (nav.getEntityTarget() != null) {
            nav.cancelNavigation();
        }
        if (anchor == null) {
            return;
        }
        boolean following = bot.order.mode() == Order.Mode.FOLLOW;
        double arrive = following ? s.followDistance : s.arriveRadius;
        if (self.getLocation().distanceSquared(anchor) <= arrive * arrive) {
            if (nav.isNavigating() && following) {
                nav.cancelNavigation();
            }
            return;
        }
        Location dest = nav.isNavigating() ? nav.getTargetAsLocation() : null;
        if (dest == null || dest.getWorld() != anchor.getWorld() || dest.distanceSquared(anchor) > 4.0) {
            nav.setTarget(anchor);
        }
    }

    /**
     * 狙う敵を選ぶ。条件: 自分から engage-radius 以内、持ち場から leash 以内（持ち場があるとき）、
     * 見えている（または {@link #SENSE_RADIUS} 以内）。
     *
     * <p>今の相手が条件を満たす間は、retarget-seconds ごとにだけ選び直す。選び直すときは最も近い敵を選ぶが、
     * 今の相手より {@link #RETARGET_MARGIN} ブロック以上近い敵がいるときだけ替える（2 体の間で行ったり来たりしないため）。
     */
    private LivingEntity pickTarget(SoldierBot bot, LivingEntity self, Location anchor, long now) {
        Settings s = plugin.settings();
        double leash = bot.order != null && bot.order.stance() == Order.Stance.PUSH ? s.pushLeash : s.guardLeash;
        boolean currentValid = bot.target != null && validTarget(bot, self, bot.target, anchor, leash);
        if (currentValid && now - bot.lastRetargetTick < s.retargetTicks) {
            return bot.target;
        }
        bot.lastRetargetTick = now;
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : enemies(bot.side)) {
            if (!validTarget(bot, self, e, anchor, leash)) {
                continue;
            }
            double d = e.getLocation().distanceSquared(self.getLocation());
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        if (currentValid && best != bot.target) {
            double curD = Math.sqrt(bot.target.getLocation().distanceSquared(self.getLocation()));
            if (Math.sqrt(bestD) + RETARGET_MARGIN > curD) {
                return bot.target; // 少し近いだけなら替えない
            }
        }
        return best;
    }

    private boolean validTarget(SoldierBot bot, LivingEntity self, LivingEntity e, Location anchor, double leash) {
        if (e == self || !e.isValid() || e.isDead() || e.getWorld() != self.getWorld()) {
            return false;
        }
        if (sideOf(e) != bot.side.opposite()) {
            return false;
        }
        if (e instanceof Player p && byEntity(p) == null
                && (!p.isOnline() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
            return false;
        }
        Settings s = plugin.settings();
        double d2 = e.getLocation().distanceSquared(self.getLocation());
        if (d2 > s.engageRadius * s.engageRadius) {
            return false;
        }
        // すぐ近くの敵には、持ち場からの距離に関係なく応戦する（殴られっぱなしにしない）
        boolean selfDefense = d2 <= SELF_DEFENSE_RADIUS * SELF_DEFENSE_RADIUS;
        if (!selfDefense && anchor != null && e.getLocation().distanceSquared(anchor) > leash * leash) {
            return false;
        }
        return d2 <= SENSE_RADIUS * SENSE_RADIUS || self.hasLineOfSight(e);
    }

    private List<LivingEntity> enemies(Side side) {
        List<LivingEntity> r = new ArrayList<>(humans(side.opposite()));
        for (SoldierBot b : of(side.opposite())) {
            if (b.npc.isSpawned() && b.npc.getEntity() instanceof LivingEntity le) {
                r.add(le);
            }
        }
        return r;
    }
}
