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
    /** 追う道に蜘蛛の巣があるかを調べる間隔（tick）と、見つけた敵を追わないでおく長さ（tick）。 */
    public static final int CHASE_CHECK_PERIOD = 2;
    static final int WEB_BLOCK_TICKS = 40;
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
    public SoldierBot spawn(String name, Side side, Location fallback, UnitType type) {
        return spawn(name, side, fallback, null, null, type);
    }

    /**
     * ボットを作って出す。持ち主がいれば持ち主に追従し、いなければ出した地点を守る（どちらも持ち場優先）。
     *
     * @param owner     持ち主の UUID（管理者のボットは null）
     * @param ownerName 持ち主の名前（管理者のボットは null）
     * @param type      兵種（null なら units.default）
     */
    public SoldierBot spawn(String name, Side side, Location fallback, UUID owner, String ownerName, UnitType type) {
        if (type == null) {
            type = plugin.settings().defaultUnit;
        }
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
        for (Map.Entry<String, ItemStack> e : plugin.settings().unit(type).equipment.entrySet()) {
            Equipment.EquipmentSlot slot = slotOf(e.getKey());
            if (slot == null || (e.getValue().getType() == Material.SHIELD && !plugin.settings().shieldEnabled)) {
                continue; // shield.enabled が false なら盾は持たせない
            }
            eq.set(slot, equipmentItem(e.getValue()));
        }
        applyNavigatorSettings(npc, type);
        SoldierBot bot = new SoldierBot(name, side, npc, owner, type);
        bot.order = owner != null && ownerName != null
                ? Order.follow(ownerName, Order.Stance.GUARD)
                : Order.hold(Point.of(at), Order.Stance.GUARD);
        bots.put(name.toLowerCase(Locale.ROOT), bot);
        if (plugin.settings().nameTagTeamOnly) {
            npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false); // Citizens の名前を一瞬も出さない
        }
        npc.spawn(at, SpawnReason.PLUGIN);
        applyMaxHealth(bot, true);
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
     * @param capped  1 人あたりの上限（max-per-player）のせいで出せなかった数
     */
    public record ReviveReport(Status status, int spawned, int revived, int alive, int capped) {
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
            return new ReviveReport(s, 0, 0, 0, 0);
        }
    }

    /**
     * プレイヤーの専用のボットを count 体そろえる。持っているボットのうち倒れているものを蘇生し（方針はそのまま）、
     * 足りなければ新しく出す（持ち主に追従）。出す場所は陣営のリスポーン地点、未設定ならプレイヤーのいる所。
     *
     * <p>count が null なら「持っている全部を蘇生する（1 体もなければ 1 体出す）」。count は 1〜max-per-player に収める。
     * count より多く持っているときは、多い分には手を付けない（消さない・蘇生しない）。
     *
     * <p>type を指定すると、その兵種だけを数える: その兵種のボットを count 体そろえる（倒れていれば戻し、足りなければ
     * その兵種で出す）。ほかの兵種のボットには手を付けない。全兵種の合計は max-per-player を超えない。
     * type が null なら兵種を問わず数え、新しく出すのは units.default の兵種。
     */
    public ReviveReport revive(Player p, Integer count, UnitType type) {
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
        List<SoldierBot> all = ownedBy(p.getUniqueId());
        List<SoldierBot> mine = new ArrayList<>();
        for (SoldierBot b : all) {
            if (type == null || b.type == type) {
                mine.add(b);
            }
        }
        int max = plugin.settings().maxBotsPerPlayer;
        int want = Math.max(1, Math.min(max, count == null ? Math.max(1, mine.size()) : count));
        // 新しく出せるのは、全兵種の合計が上限に届くまで
        int room = Math.max(0, max - all.size());
        int toSpawn = Math.max(0, want - mine.size());
        int capped = Math.max(0, toSpawn - room);
        toSpawn -= capped;

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
        UnitType newType = type == null ? plugin.settings().defaultUnit : type;
        for (int i = 0; i < toSpawn; i++) {
            SoldierBot b = spawn(personalName(p.getName(), newType), side, p.getLocation(), p.getUniqueId(), p.getName(),
                    newType);
            if (b == null) {
                break;
            }
            spawned++;
            voice = voice == null ? b : voice;
        }
        if (voice != null) {
            // 新しく出したのも倒れていたのも、どちらも「参戦」と言う
            say(voice, p.getName() + " の兵、" + (spawned + revived) + " 体参戦した");
        }
        if (spawned + revived + alive == 0 && capped == 0) {
            return ReviveReport.of(ReviveReport.Status.NO_PLACE);
        }
        return new ReviveReport(ReviveReport.Status.OK, spawned, revived, alive, capped);
    }

    // ---- 兵科選択（呼べる回数を使って、兵種を選んで 1 体ずつ呼ぶ） ----

    /** プレイヤー → 兵科選択で呼べる残りの回数。コマンドブロックの /sb picks で決める。サーバーを止めると消える。 */
    private final Map<UUID, Integer> picks = new java.util.HashMap<>();

    public int picks(UUID player) {
        return picks.getOrDefault(player, 0);
    }

    /** 残りの回数を n にする（0 未満は 0）。オンラインなら兵科選択のアイテムを回数に合わせる（0 なら消す）。 */
    public void setPicks(UUID player, int n) {
        if (n <= 0) {
            picks.remove(player);
        } else {
            picks.put(player, n);
        }
        Player p = Bukkit.getPlayer(player);
        if (p != null && plugin.unitItem() != null) {
            plugin.unitItem().sync(p);
        }
    }

    /** {@link #summon} の結果。 */
    public enum SummonResult {
        /** 新しく出した */
        SPAWNED,
        /** 倒れていたその兵種のボットを戻した */
        REVIVED,
        /** 呼べる回数が残っていない */
        NO_PICKS,
        /** 1 人あたりの上限（max-per-player）に届いていて、その兵種の倒れたボットもいない */
        FULL,
        /** 攻め・守りのどちらのチームにも入っていない */
        NO_TEAM,
        /** personal-bots.enabled が false */
        DISABLED,
        /** 出す場所が決まらなかった */
        NO_PLACE
    }

    /**
     * 兵科選択: 呼べる回数を 1 使って、その兵種のボットを 1 体参戦させる。
     * その兵種の倒れているボットがいれば戻し（方針はそのまま）、いなければ新しく出す（上限 max-per-player まで）。
     * 呼べなかったときは回数を使わない。
     */
    public SummonResult summon(Player p, UnitType type) {
        if (!plugin.settings().personalBots) {
            return SummonResult.DISABLED;
        }
        Side side = sideOf(p);
        if (side == null) {
            return SummonResult.NO_TEAM;
        }
        if (picks(p.getUniqueId()) <= 0) {
            return SummonResult.NO_PICKS;
        }
        for (SoldierBot old : ownedBy(p.getUniqueId())) {
            if (old.side != side) {
                remove(old.name); // チームを移った後の古いボット
            }
        }
        List<SoldierBot> mine = ownedBy(p.getUniqueId());
        for (SoldierBot b : mine) {
            if (b.type == type && !b.npc.isSpawned() && reviveBot(b, false)) {
                setPicks(p.getUniqueId(), picks(p.getUniqueId()) - 1);
                say(b, type.label() + "、参戦した");
                return SummonResult.REVIVED;
            }
        }
        if (mine.size() >= plugin.settings().maxBotsPerPlayer) {
            return SummonResult.FULL;
        }
        SoldierBot b = spawn(personalName(p.getName(), type), side, p.getLocation(), p.getUniqueId(), p.getName(), type);
        if (b == null) {
            return SummonResult.NO_PLACE;
        }
        setPicks(p.getUniqueId(), picks(p.getUniqueId()) - 1);
        say(b, type.label() + "、参戦した");
        return SummonResult.SPAWNED;
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
        applyMaxHealth(bot, true);
        if (announce) {
            say(bot, "参戦した");
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
    private String personalName(String player, UnitType type) {
        String suffix = plugin.settings().unit(type).nameSuffix;
        int room = Math.max(1, 16 - suffix.length()); // 名前は 16 文字まで
        String base = player.length() > room ? player.substring(0, room) : player;
        String name = base + suffix;
        for (int i = 2; get(name) != null; i++) {
            name = base.substring(0, Math.min(base.length(), room - 1)) + i + suffix;
        }
        return name;
    }

    /** config.yml を読み直した後に、移動・攻撃の設定を全ボットに入れ直す。 */
    public void reapplySettings() {
        for (SoldierBot b : bots.values()) {
            applyNavigatorSettings(b.npc, b.type);
            applyMaxHealth(b, false);
        }
    }

    /** 兵種の最大の体力（units.&lt;兵種&gt;.max-health）を入れる。full なら満タンにする（出したとき・参戦したとき）。 */
    private void applyMaxHealth(SoldierBot bot, boolean full) {
        if (!(bot.npc.getEntity() instanceof LivingEntity le)) {
            return;
        }
        double max = plugin.settings().unit(bot.type).maxHealth;
        org.bukkit.attribute.AttributeInstance a = le.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (a == null) {
            return;
        }
        if (a.getBaseValue() != max) {
            a.setBaseValue(max);
        }
        if (full) {
            le.setHealth(a.getValue());
        } else if (le.getHealth() > a.getValue()) {
            le.setHealth(a.getValue());
        }
    }

    private void applyNavigatorSettings(NPC npc, UnitType type) {
        Settings s = plugin.settings();
        Settings.Unit unit = s.unit(type);
        NavigatorParameters p = npc.getNavigator().getDefaultParameters();
        p.range(s.pathRange);
        // 落ちてよい高さ。Citizens は既定（-1）だと高い所から降りる経路を探さない
        p.fallDistance(s.maxFallDistance);
        p.speedModifier(unit.speed);
        p.attackRange(unit.attackRange);
        p.attackDelayTicks(unit.attackDelayTicks);
        // 動けないときに目的地へ瞬間移動しない（その場で経路を取り直す）
        p.stuckAction((n, nav) -> false);
        try {
            p.pathfinderType(PathfinderType.valueOf(s.pathfinder));
        } catch (IllegalArgumentException e) {
            p.pathfinderType(PathfinderType.CITIZENS);
        }
    }

    /** 装備のアイテム（設定のアイテムを写したもの）。盾は壊れないようにする（構えるたびに耐久が減るため）。 */
    private static ItemStack equipmentItem(ItemStack template) {
        ItemStack item = template.clone();
        if (item.getType() == Material.SHIELD) {
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
                bot.retreatUntilTick = 0; // 着いても知らせない
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
        if (target != null && bot.type == UnitType.ARCHER) {
            if (archerMove(bot, nav, self, target, anchor, s, now)) {
                return;
            }
        } else if (target != null && bot.type == UnitType.SPEARMAN) {
            spearMove(bot, nav, self, target, s);
            return;
        } else if (target != null) {
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

    /**
     * 弓兵の動き（撃つのは {@link Archers} が 2 tick ごとに行う）。
     * <ul>
     *   <li>見えていて units.archer.range 以内: 止まって撃つ。min-distance より近ければ、keep-distance まで下がる。</li>
     *   <li>見えない・遠い: 近づく（殴らない）。ただし敵が持ち場から leash（構えで決まる）の外なら近づかない。</li>
     * </ul>
     *
     * @return 動きを決めたら true。false なら持ち場へ戻る動き（敵がいないときと同じ）に任せる
     */
    private boolean archerMove(SoldierBot bot, Navigator nav, LivingEntity self, LivingEntity target, Location anchor,
                               Settings s, long now) {
        Location here = self.getLocation();
        Location t = target.getLocation();
        double d = here.distance(t);
        boolean los = self.hasLineOfSight(target);
        if (los && d <= s.archerRange) {
            if (d < s.archerMinDistance) {
                Location away = retreatSpot(here, t, s.archerKeepDistance - d);
                if (away != null) {
                    Location dest = nav.isNavigating() ? nav.getTargetAsLocation() : null;
                    if (!bot.backingOff || dest == null || dest.distanceSquared(away) > 4.0) {
                        nav.setTarget(away);
                        bot.backingOff = true;
                    }
                    return true;
                }
                // 下がる場所がなければ、その場で撃つ
            }
            // 下がっている途中なら keep-distance まで下がり切る。それ以外の歩き（近づく・持ち場へ）はやめて撃つ
            boolean keepBacking = bot.backingOff && nav.isNavigating() && d < s.archerKeepDistance;
            if (!keepBacking) {
                bot.backingOff = false;
                if (nav.isNavigating()) {
                    nav.cancelNavigation();
                }
            }
            return true;
        }
        bot.backingOff = false;
        double leash = bot.order != null && bot.order.stance() == Order.Stance.PUSH ? s.pushLeash : s.guardLeash;
        if (anchor != null && t.distanceSquared(anchor) > leash * leash) {
            return false; // 持ち場から離れすぎるので追わない（見えて届けば撃つ）
        }
        EntityTarget cur = nav.getEntityTarget();
        if (cur == null || cur.getTarget() != target || cur.isAggressive() || !nav.isNavigating()) {
            nav.setTarget(target, false); // 近づくだけで殴らない
        }
        return true;
    }

    /**
     * 槍兵の動き（突くのと、近づいて届いたら止まるのは {@link Spearmen} が 2 tick ごとに行う）。
     * <ul>
     *   <li>届かない・見えない: 近づく（Citizens の追跡。殴らない）。</li>
     *   <li>届く: 止まる。届く距離より back-off-inside 以上内側まで寄られたら、届くぎりぎりまで下がる。</li>
     * </ul>
     * 届くかは、目から相手の当たり判定の箱までの距離（{@link #reachDistance}）で比べる（プレイヤーのリーチと同じ測り方）。
     */
    private void spearMove(SoldierBot bot, Navigator nav, LivingEntity self, LivingEntity target, Settings s) {
        if (bot.shieldUp) {
            return; // 盾で矢を防いでいる間は動かない（Shields が止めて矢の方を向かせている）
        }
        if (webBlocked(bot, target, Bukkit.getCurrentTick())) {
            // 追う道に蜘蛛の巣がある敵: 近づかない（届けば Spearmen がその場で突く。pickTarget は届くときだけこの敵を選ぶ）
            if (nav.getEntityTarget() != null) {
                nav.cancelNavigation();
            }
            bot.backingOff = false;
            return;
        }
        double reach = s.unit(UnitType.SPEARMAN).attackRange;
        double d = reachDistance(self, target);
        if (d <= reach && self.hasLineOfSight(target)) {
            if (d < reach - s.spearBackOffInside) {
                Location away = retreatSpot(self.getLocation(), target.getLocation(), reach - s.spearStopInside - d);
                if (away != null) {
                    Location dest = nav.isNavigating() ? nav.getTargetAsLocation() : null;
                    if (!bot.backingOff || dest == null || dest.distanceSquared(away) > 4.0) {
                        nav.setTarget(away);
                        bot.backingOff = true;
                    }
                    return;
                }
                // 下がる場所がなければ、その場で突く
            }
            boolean keepBacking = bot.backingOff && nav.isNavigating() && d < reach - s.spearStopInside;
            if (!keepBacking) {
                bot.backingOff = false;
                if (nav.isNavigating()) {
                    nav.cancelNavigation();
                }
            }
            return;
        }
        bot.backingOff = false;
        EntityTarget cur = nav.getEntityTarget();
        if (cur == null || cur.getTarget() != target || cur.isAggressive() || !nav.isNavigating()) {
            nav.setTarget(target, false); // 近づくだけ。突くのは Spearmen
        }
    }

    /** 追う道に蜘蛛の巣があったので、今は追わない敵か。 */
    static boolean webBlocked(SoldierBot bot, LivingEntity e, long now) {
        Long until = bot.webBlocked.get(e.getUniqueId());
        return until != null && now < until;
    }

    /** 追う道に巣があったので今は追わず、届く距離にもいない敵か（狙いに選ばない）。 */
    private boolean chaseBlocked(SoldierBot bot, LivingEntity self, LivingEntity e, long now) {
        return webBlocked(bot, e, now) && reachDistance(self, e) > plugin.settings().unit(bot.type).attackRange;
    }

    /**
     * 槍兵が敵を追っている道（Citizens が引いた経路。近い相手で経路がなければ相手までの直線）に蜘蛛の巣があれば、
     * 追うのをやめて、その敵を {@link #WEB_BLOCK_TICKS} の間は追わない。{@link #CHASE_CHECK_PERIOD} tick ごとに呼ぶ。
     */
    public void checkChasePaths() {
        Settings s = plugin.settings();
        long now = Bukkit.getCurrentTick();
        for (SoldierBot bot : bots.values()) {
            bot.webBlocked.values().removeIf(until -> until <= now);
            if (!s.spearAvoidCobweb || bot.type != UnitType.SPEARMAN || !bot.npc.isSpawned()
                    || !(bot.npc.getEntity() instanceof LivingEntity self)) {
                continue;
            }
            Navigator nav = bot.npc.getNavigator();
            EntityTarget et = nav.getEntityTarget();
            if (et == null || !nav.isNavigating() || !(et.getTarget() instanceof LivingEntity target)) {
                continue;
            }
            if (pathHasWeb(self, target, nav)) {
                bot.webBlocked.put(target.getUniqueId(), now + WEB_BLOCK_TICKS);
                nav.cancelNavigation();
            }
        }
    }

    /** Citizens が引いた今の経路の節点（足と頭）や斜めにかすめる所、経路がなければ相手までの直線に、蜘蛛の巣があるか。 */
    static boolean pathHasWeb(LivingEntity self, LivingEntity target, Navigator nav) {
        net.citizensnpcs.api.ai.PathStrategy ps = nav.getPathStrategy();
        Iterable<org.bukkit.util.Vector> path = ps == null ? null : ps.getPath();
        if (path == null || !path.iterator().hasNext()) {
            return webOnSegment(self.getLocation(), target.getLocation());
        }
        org.bukkit.World w = self.getWorld();
        org.bukkit.util.Vector prev = null;
        for (org.bukkit.util.Vector v : path) {
            int x = v.getBlockX();
            int y = v.getBlockY();
            int z = v.getBlockZ();
            if (isWeb(w, x, y, z) || isWeb(w, x, y + 1, z)) {
                return true;
            }
            if (prev != null && prev.getBlockX() != x && prev.getBlockZ() != z) {
                int py = Math.max(y, prev.getBlockY());
                if (isWeb(w, prev.getBlockX(), py, z) || isWeb(w, prev.getBlockX(), py + 1, z)
                        || isWeb(w, x, py, prev.getBlockZ()) || isWeb(w, x, py + 1, prev.getBlockZ())) {
                    return true;
                }
            }
            prev = v;
        }
        return false;
    }

    /**
     * a から b へまっすぐ歩いたとき、体（幅 0.6・高さ 1.8）が巣に触れるか。0.25 ブロックごとに調べる。
     * a で既に触れている巣は数えない（巣から抜け出す向きは追ってよい）。
     */
    static boolean webOnSegment(Location a, Location b) {
        org.bukkit.World w = a.getWorld();
        Set<Long> atStart = new java.util.HashSet<>();
        double len = a.distance(b);
        int n = Math.max(1, (int) Math.ceil(len / 0.25));
        for (int i = 0; i <= n; i++) {
            double f = (double) i / n;
            double x = a.getX() + (b.getX() - a.getX()) * f;
            double y = a.getY() + (b.getY() - a.getY()) * f;
            double z = a.getZ() + (b.getZ() - a.getZ()) * f;
            for (double ox : new double[] {-0.3, 0.3}) {
                for (double oz : new double[] {-0.3, 0.3}) {
                    for (double oy : new double[] {0.0, 1.0, 1.79}) {
                        int bx = (int) Math.floor(x + ox);
                        int by = (int) Math.floor(y + oy);
                        int bz = (int) Math.floor(z + oz);
                        if (w.getBlockAt(bx, by, bz).getType() != Material.COBWEB) {
                            continue;
                        }
                        long k = ((long) bx << 38) ^ ((long) (bz & 0x3FFFFFF) << 12) ^ (by & 0xFFF);
                        if (i == 0) {
                            atStart.add(k);
                        } else if (!atStart.contains(k)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean isWeb(org.bukkit.World w, int x, int y, int z) {
        return w.getBlockAt(x, y, z).getType() == Material.COBWEB;
    }

    /** self の目から target の当たり判定の箱までの距離（プレイヤーのリーチの測り方）。 */
    static double reachDistance(LivingEntity self, Entity target) {
        Location eye = self.getEyeLocation();
        org.bukkit.util.BoundingBox b = target.getBoundingBox();
        double x = Math.max(b.getMinX(), Math.min(eye.getX(), b.getMaxX()));
        double y = Math.max(b.getMinY(), Math.min(eye.getY(), b.getMaxY()));
        double z = Math.max(b.getMinZ(), Math.min(eye.getZ(), b.getMaxZ()));
        return Math.sqrt((x - eye.getX()) * (x - eye.getX()) + (y - eye.getY()) * (y - eye.getY())
                + (z - eye.getZ()) * (z - eye.getZ()));
    }

    /**
     * 相手から離れる向きに distance ブロックほど先の、立てる場所。真後ろ・斜め後ろ・横の順に、
     * 段差 ±1 まで探す。見つからなければ null。
     */
    private static Location retreatSpot(Location here, Location from, double distance) {
        double ax = here.getX() - from.getX();
        double az = here.getZ() - from.getZ();
        double len = Math.hypot(ax, az);
        if (len < 1e-6) {
            ax = 1;
            az = 0;
            len = 1;
        }
        ax /= len;
        az /= len;
        double dist = Math.max(2.0, distance);
        for (double deg : new double[] {0, 45, -45, 90, -90}) {
            double r = Math.toRadians(deg);
            double x = ax * Math.cos(r) - az * Math.sin(r);
            double z = ax * Math.sin(r) + az * Math.cos(r);
            Block base = here.getWorld().getBlockAt((int) Math.floor(here.getX() + x * dist), here.getBlockY(),
                    (int) Math.floor(here.getZ() + z * dist));
            for (int dy : new int[] {0, 1, -1}) {
                Block feet = base.getRelative(0, dy, 0);
                if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(BlockFace.UP).isPassable()
                        && feet.getRelative(BlockFace.DOWN).getType().isSolid()) {
                    return feet.getLocation().add(0.5, 0, 0.5);
                }
            }
        }
        return null;
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
        if (bot.type == UnitType.ARCHER) {
            leash = Math.max(leash, s.archerRange); // 弓兵は持ち場から届く敵も狙う（近づくのは leash の内だけ。archerMove）
        }
        boolean currentValid = bot.target != null && validTarget(bot, self, bot.target, anchor, leash)
                && !chaseBlocked(bot, self, bot.target, now);
        if (currentValid && now - bot.lastRetargetTick < s.retargetTicks) {
            return bot.target;
        }
        bot.lastRetargetTick = now;
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : enemies(bot.side)) {
            if (!validTarget(bot, self, e, anchor, leash) || chaseBlocked(bot, self, e, now)) {
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
