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
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
 * 「退却」中（{@link SoldierBot#retreating}）は 2 を飛ばして、戦わずに持ち場へ戻る（{@link #retreat}）。
 * メインスレッドからだけ呼ぶ。
 */
public final class BotManager {

    public static final int TICK_PERIOD = 10;
    /** 経路があるのにこの tick 数のあいだ動かなければ、経路を取り直す。 */
    static final int STUCK_TICKS = 60;
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
    /** 最後に自動で回復させた時刻（サーバーの tick）。 */
    private long lastRegenTick;

    private final NameTags nameTags;

    public BotManager(SiegeBotPlugin plugin) {
        this.plugin = plugin;
        this.registry = CitizensAPI.createInMemoryNPCRegistry("siegebot");
        this.nameTags = new NameTags(plugin);
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
                eq.set(slot, equipmentItem(e.getValue()));
            }
        }
        Map<String, Material> equipped = plugin.settings().equipment;
        if (plugin.settings().shieldEnabled && !equipped.containsKey("off_hand") && !equipped.containsKey("offhand")) {
            eq.set(Equipment.EquipmentSlot.OFF_HAND, equipmentItem(Material.SHIELD)); // 左手が未指定なら盾を持たせる
        }
        applyNavigatorSettings(npc);
        SoldierBot bot = new SoldierBot(name, side, npc, owner);
        bot.order = owner != null && ownerName != null
                ? Order.follow(ownerName, Order.Stance.GUARD)
                : Order.hold(Point.of(at), Order.Stance.GUARD);
        bots.put(name.toLowerCase(Locale.ROOT), bot);
        if (plugin.settings().nameTagTeamOnly) {
            npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false); // Citizens の名前を一瞬も出さない
        }
        npc.spawn(at, SpawnReason.PLUGIN);
        return bot;
    }

    public boolean remove(String name) {
        SoldierBot b = name == null ? null : bots.remove(name.toLowerCase(Locale.ROOT));
        if (b == null) {
            return false;
        }
        nameTags.remove(b);
        b.npc.destroy();
        return true;
    }

    public void removeAll() {
        nameTags.removeAll();
        for (SoldierBot b : bots.values()) {
            b.npc.destroy();
        }
        bots.clear();
    }

    // ---- 専用のボット（プレイヤーごと。1 人が複数体持てる） ----

    /** そのプレイヤーの専用のボット（出した順）。なければ空。 */
    public List<SoldierBot> ownedBy(UUID player) {
        List<SoldierBot> r = new ArrayList<>();
        if (player == null) {
            return r;
        }
        for (SoldierBot b : bots.values()) {
            if (player.equals(b.owner)) {
                r.add(b);
            }
        }
        return r;
    }

    /** そのプレイヤーの専用のボットを全部消す。 */
    public void removeOwned(UUID player) {
        for (SoldierBot b : ownedBy(player)) {
            remove(b.name);
        }
    }

    /**
     * 専用のボットを、オンラインのプレイヤーとチームに合わせる。チームを抜けた・移った人と、退出した人のボットを消す。
     * 新しく出すことはしない（出すのは {@link #revive} だけ。コマンドブロックの /sb revive から呼ぶ）。
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
            for (SoldierBot mine : ownedBy(p.getUniqueId())) {
                if (mine.side != side) {
                    remove(mine.name);
                }
            }
        }
        for (SoldierBot b : new ArrayList<>(bots.values())) {
            if (b.owner != null && !online.contains(b.owner)) {
                remove(b.name);
            }
        }
    }

    /**
     * {@link #revive} の結果（1 人分）。
     *
     * @param status  OK なら下の数が意味を持つ
     * @param spawned 新しく出した数
     * @param revived 倒れていたのを蘇生した数
     * @param alive   もともと生きていた数（何もしなかった）
     */
    public record ReviveReport(Status status, int spawned, int revived, int alive) {
        public enum Status {
            OK,
            /** 攻め・守りのどちらのチームにも入っていない */
            NO_TEAM,
            /** 出す場所が決まらず、1 体も出せなかった */
            NO_PLACE,
            /** personal-bots.enabled が false */
            DISABLED
        }

        static ReviveReport of(Status s) {
            return new ReviveReport(s, 0, 0, 0);
        }
    }

    /**
     * プレイヤーの専用のボットを count 体そろえる。持っているボットのうち倒れているものを蘇生し（方針はそのまま）、
     * 足りなければ新しく出す（持ち主に追従）。出す場所は陣営のリスポーン地点、未設定ならプレイヤーのいる所。
     *
     * <p>count が null なら「持っている全部を蘇生する（1 体もなければ 1 体出す）」。count は 1〜max-per-player に収める。
     * count より多く持っているときは、多い分には手を付けない（消さない・蘇生しない）。
     */
    public ReviveReport revive(Player p, Integer count) {
        if (!plugin.settings().personalBots) {
            return ReviveReport.of(ReviveReport.Status.DISABLED);
        }
        Side side = sideOf(p);
        if (side == null) {
            return ReviveReport.of(ReviveReport.Status.NO_TEAM);
        }
        for (SoldierBot old : ownedBy(p.getUniqueId())) {
            if (old.side != side) {
                remove(old.name); // チームを移った後の古いボット
            }
        }
        List<SoldierBot> mine = ownedBy(p.getUniqueId());
        int max = plugin.settings().maxBotsPerPlayer;
        int want = Math.max(1, Math.min(max, count == null ? Math.max(1, mine.size()) : count));

        int spawned = 0;
        int revived = 0;
        int alive = 0;
        SoldierBot voice = null;
        for (int i = 0; i < Math.min(want, mine.size()); i++) {
            SoldierBot b = mine.get(i);
            if (b.npc.isSpawned()) {
                alive++;
            } else if (reviveBot(b, false)) {
                revived++;
                voice = voice == null ? b : voice;
            }
        }
        for (int i = mine.size(); i < want; i++) {
            SoldierBot b = spawn(personalName(p.getName()), side, p.getLocation(), p.getUniqueId(), p.getName());
            if (b == null) {
                break;
            }
            spawned++;
            voice = voice == null ? b : voice;
        }
        if (voice != null) {
            List<String> parts = new ArrayList<>();
            if (spawned > 0) {
                parts.add(spawned + " 体参戦");
            }
            if (revived > 0) {
                parts.add(revived + " 体復活");
            }
            say(voice, p.getName() + " の兵、" + String.join("・", parts) + "した");
        }
        if (spawned + revived + alive == 0) {
            return ReviveReport.of(ReviveReport.Status.NO_PLACE);
        }
        return new ReviveReport(ReviveReport.Status.OK, spawned, revived, alive);
    }

    /** 倒れているボットを出し直す（方針はそのまま）。生きていれば何もせず false。 */
    public boolean reviveBot(SoldierBot bot) {
        return reviveBot(bot, true);
    }

    private boolean reviveBot(SoldierBot bot, boolean announce) {
        if (bot.npc.isSpawned()) {
            return false;
        }
        Location at = respawnLocation(bot);
        if (at == null || !bot.npc.spawn(at, SpawnReason.RESPAWN)) {
            return false;
        }
        bot.target = null;
        if (announce) {
            say(bot, "復活した");
        }
        return true;
    }

    /** 持ち主の専用のボットの方針を、出したときと同じ「持ち主についていく・持ち場優先」に戻す。 */
    public void resetOrders(Player owner) {
        for (SoldierBot b : ownedBy(owner.getUniqueId())) {
            setOrder(b, Order.follow(owner.getName(), Order.Stance.GUARD)); // 退却も終わる
            b.target = null;
        }
    }

    /** 持ち主が倒れたら、その専用のボットを全部倒す。倒れたボットは /sb revive まで倒れたまま。 */
    public void killOwnedBots(UUID owner) {
        for (SoldierBot b : ownedBy(owner)) {
            if (b.npc.isSpawned() && b.npc.getEntity() instanceof LivingEntity le) {
                le.setHealth(0.0);
            }
        }
    }

    /**
     * 専用のボットの名前（「名前の兵」、2 体目からは「名前2の兵」…）。16 文字に収まるよう名前を縮める
     * （max-per-player は 9 までなので番号は 1 桁）。
     */
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
        // 落ちてよい高さ。Citizens は既定（-1）だと高い所から降りる経路を探さない
        p.fallDistance(s.maxFallDistance);
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

    /** 装備のアイテム。盾は壊れないようにする（構えるたびに耐久が減るため）。 */
    private static ItemStack equipmentItem(Material m) {
        ItemStack item = new ItemStack(m);
        if (m == Material.SHIELD) {
            ItemMeta meta = item.getItemMeta();
            meta.setUnbreakable(true);
            item.setItemMeta(meta);
        }
        return item;
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

    /** 方針を変える。方針の変更は必ずここを通す（新しい指示を受けたら退却をやめるため）。 */
    public void setOrder(SoldierBot bot, Order order) {
        bot.order = order;
        bot.retreatUntilTick = 0;
    }

    /**
     * 退却させる: 今の狙いを捨て、盾を下ろし、持ち場（追従なら持ち主）へ戻らせる。戻るまでは戦わない。
     * 着く・持ち場が決められない・orders.retreat-timeout-seconds 過ぎる・新しい方針を受ける・持ち主が倒れる、で終わる。
     * 倒れているボットは対象にしない。最初のボットに復唱させる。
     */
    public void retreat(List<SoldierBot> targets) {
        long now = Bukkit.getCurrentTick();
        int count = 0;
        SoldierBot voice = null;
        for (SoldierBot b : targets) {
            if (!b.npc.isSpawned()) {
                continue;
            }
            b.retreatUntilTick = now + plugin.settings().retreatTimeoutTicks;
            b.target = null;
            Navigator nav = b.npc.getNavigator();
            if (nav.getEntityTarget() != null) {
                nav.cancelNavigation();
            }
            voice = voice == null ? b : voice;
            count++;
        }
        if (voice != null) {
            say(voice, count == 1 ? "了解。退却する" : "了解。" + count + " 体退却する");
        }
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
        return sideOfTeam(Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(p.getName()));
    }

    /** エンティティ（プレイヤーかボット）の陣営。どちらでもなければ null。 */
    public Side sideOf(Entity e) {
        SoldierBot b = byEntity(e);
        if (b != null) {
            return b.side;
        }
        if (e instanceof Player p) {
            return sideOf(p);
        }
        // モブはスコアボードのチームに UUID で入る（/team join <チーム> <セレクタ>）
        Team t = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(e.getUniqueId().toString());
        return sideOfTeam(t);
    }

    /**
     * スコアボードのチームが、どちらの陣営のチームか（config.yml の teams と、大文字・小文字を区別せずに比べる）。
     * 陣営の判定はすべてここを通す。
     */
    private Side sideOfTeam(Team t) {
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

    /** その陣営のスコアボードのチーム（名前は大文字・小文字を区別しない）。なければ null。 */
    private Team teamOf(Side side) {
        for (Team t : Bukkit.getScoreboardManager().getMainScoreboard().getTeams()) {
            if (sideOfTeam(t) == side) {
                return t;
            }
        }
        return null;
    }

    /** その陣営のチームに入っているモブ（プレイヤーとボットと防具立てを除く、生きている読み込み済みのもの）。 */
    private List<LivingEntity> mobs(Side side) {
        List<LivingEntity> r = new ArrayList<>();
        Team t = teamOf(side);
        if (t == null) {
            return r;
        }
        for (String entry : t.getEntries()) {
            UUID id;
            try {
                id = UUID.fromString(entry);
            } catch (IllegalArgumentException notUuid) {
                continue; // プレイヤー名の登録
            }
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity le && !(e instanceof Player) && !(e instanceof ArmorStand)
                    && byEntity(e) == null && le.isValid() && !le.isDead()) {
                r.add(le);
            }
        }
        return r;
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

    /**
     * ボットの発言を送る。持ち主のいるボットは持ち主にだけ（オフラインなら誰にも送らない）、
     * 持ち主のいないボット（/sb spawn で出したもの）は同じ陣営のプレイヤーに送る。
     */
    public void say(SoldierBot bot, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Component msg = Component.text("<" + bot.name + "> ", NamedTextColor.AQUA)
                .append(Component.text(text, NamedTextColor.WHITE));
        sendToAudience(bot, msg);
    }

    /**
     * ボットに関する知らせを送る。持ち主がいれば持ち主にだけ（いなければ誰にも送らない）、
     * 持ち主のいないボット（/sb spawn）なら同じ陣営のプレイヤー全員に送る。
     */
    public void sendToAudience(SoldierBot bot, Component msg) {
        if (bot.owner != null) {
            Player owner = Bukkit.getPlayer(bot.owner);
            if (owner != null) {
                owner.sendMessage(msg);
            }
            return;
        }
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

    /** 倒れたボットを出し直す場所: リスポーン地点、なければ持ち主の横、なければ最後にいた所。 */
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
        Settings s = plugin.settings();
        // 自動の回復: regen-interval-seconds ごとに regen-amount ずつ（最大体力を超えない）
        boolean regen = s.regenAmount > 0 && now - lastRegenTick >= s.regenIntervalTicks;
        if (regen) {
            lastRegenTick = now;
        }
        for (SoldierBot bot : bots.values()) {
            try {
                if (regen && bot.npc.isSpawned() && bot.npc.getEntity() instanceof LivingEntity le
                        && !le.isDead() && le.getHealth() < le.getMaxHealth()
                        && now - bot.lastDamagedTick >= s.regenDelayTicks) {
                    le.heal(s.regenAmount, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.REGEN);
                }
                tickBot(bot, now);
                nameTags.update(bot);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("ボット " + bot.name + " の処理で例外: " + e);
            }
        }
    }

    private void tickBot(SoldierBot bot, long now) {
        NPC npc = bot.npc;
        if (!npc.isSpawned()) {
            bot.target = null;
            bot.retreatUntilTick = 0; // 倒れたら退却も終わる
            return; // 倒れたボットは自動では復活しない（/sb revive で出し直す）
        }
        if (!(npc.getEntity() instanceof LivingEntity self)) {
            return;
        }
        Settings s = plugin.settings();
        Location anchor = anchorFor(bot);
        if (anchor != null && anchor.getWorld() != self.getWorld()) {
            anchor = null;
        }
        Navigator nav = npc.getNavigator();

        // 退却中: 持ち場が決められない・着いたら終わり（期限が過ぎれば retreating が false になる）。続くなら戦わずに戻る
        if (bot.retreating(now)) {
            if (anchor == null) {
                bot.retreatUntilTick = 0;
            } else if (arrived(bot, self, anchor, s)) {
                bot.retreatUntilTick = 0;
                say(bot, "戻った");
            } else {
                bot.target = null;
                if (nav.getEntityTarget() != null) {
                    nav.cancelNavigation();
                }
                walkTo(bot, nav, self, anchor, now);
                return;
            }
        }

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
        if (arrived(bot, self, anchor, s)) {
            if (nav.isNavigating() && bot.order.mode() == Order.Mode.FOLLOW) {
                nav.cancelNavigation();
            }
            return;
        }
        walkTo(bot, nav, self, anchor, now);
    }

    /** 持ち場に着いたか（追従なら follow-distance 以内、地点を守るなら arrive-radius 以内）。 */
    private static boolean arrived(SoldierBot bot, LivingEntity self, Location anchor, Settings s) {
        double arrive = bot.order.mode() == Order.Mode.FOLLOW ? s.followDistance : s.arriveRadius;
        return self.getLocation().distanceSquared(anchor) <= arrive * arrive;
    }

    /** 持ち場へ歩かせる。動けなくなっていれば経路を取り直し、目的地が変わったときだけ経路を引き直す。 */
    private void walkTo(SoldierBot bot, Navigator nav, LivingEntity self, Location anchor, long now) {
        // 経路があるのに STUCK_TICKS の間 1 ブロックも動いていなければ、経路を取り直す（古い経路のまま止まるのを防ぐ）
        Location here = self.getLocation();
        if (!nav.isNavigating() || bot.stuckPos == null || bot.stuckPos.getWorld() != here.getWorld()
                || bot.stuckPos.distanceSquared(here) > 1.0) {
            bot.stuckPos = here;
            bot.stuckTick = now;
        } else if (now - bot.stuckTick >= STUCK_TICKS) {
            nav.cancelNavigation();
            bot.stuckPos = here;
            bot.stuckTick = now;
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

    /** 敵: 敵の陣営のプレイヤー・ボットと、敵のチームに入っているモブ。 */
    List<LivingEntity> enemies(Side side) {
        List<LivingEntity> r = new ArrayList<>(humans(side.opposite()));
        for (SoldierBot b : of(side.opposite())) {
            if (b.npc.isSpawned() && b.npc.getEntity() instanceof LivingEntity le) {
                r.add(le);
            }
        }
        r.addAll(mobs(side.opposite()));
        return r;
    }
}
