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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.HashMap;
import io.papermc.paper.entity.TeleportFlag;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.PathfinderType;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 兵士ボットの生成・破棄と、毎回の行動の決定。
 *
 * <p>行動の決め方（{@link #TICK_PERIOD} tick ごと）:
 * <ol>
 *   <li>方針から「持ち場」を引く（追従なら相手の足元、地点を守るならその地点。{@link Formation#anchorFor}）。</li>
 *   <li>持ち場の周りにいる敵のうち、見えている最も近い敵を狙う（{@link #pickTarget}）。</li>
 *   <li>敵がいなければ持ち場へ歩く。</li>
 * </ol>
 * 「退却」中（{@link SoldierBot#retreating}）は 2 を飛ばして、戦わずに持ち場へ戻る（{@link #retreat}）。
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
    /** 最後に自動で回復させた時刻（サーバーの tick）。 */
    private final Map<Side, Long> lastRegenTick = new java.util.EnumMap<>(Side.class);
    /** プレイヤーが最後に倒れた時刻（サーバーの tick）。同じ tick の同時死亡で回数を二重に戻さないために使う。 */
    private final Map<UUID, Long> lastDeathTick = new HashMap<>();
    /** プレイヤーが倒れた回数（{@link #ownerLife}）。兵を出したときの値と比べて、回数を戻してよい兵かを決める。 */
    private final Map<UUID, Integer> ownerLives = new HashMap<>();

    private final NameTags nameTags;
    private final NavigationWatch navigationWatch;

    public BotManager(SiegeBotPlugin plugin) {
        this.plugin = plugin;
        this.registry = CitizensAPI.createInMemoryNPCRegistry("siegebot");
        this.nameTags = new NameTags(plugin);
        this.navigationWatch = new NavigationWatch(plugin);
    }

    /** 詰まりの見張りと復旧の集計。 */
    NavigationWatch navigationWatch() {
        return navigationWatch;
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
     * 持ち主のいないボット（試験用。test-bots.enabled のときだけ /sb spawn から出す）を作って出す。出した地点を守る。
     *
     * @param fallback リスポーン地点が未設定のときに出す場所（null 可）
     * @return 作ったボット。同名がいる・出す場所がない場合は null
     */
    public SoldierBot spawn(String name, Side side, Location fallback, UnitType type) {
        return spawn(name, side, fallback, null, null, type);
    }

    /**
     * 持ち主のボットを作って出す。持ち主に追従する（持ち場優先）。
     *
     * @param fallback  リスポーン地点が未設定のときに出す場所（null 可）
     * @param owner     持ち主の UUID
     * @param ownerName 持ち主の名前
     * @param type      兵種（null なら units.default）
     * @return 作ったボット。同名がいる・出す場所がない場合は null
     */
    public SoldierBot spawn(String name, Side side, Location fallback, UUID owner, String ownerName, UnitType type) {
        Settings s = plugin.settings(side);
        if (type == null) {
            type = s.defaultUnit;
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
        // 先にいるボットと重ならない所に出す（方針の持ち場は元の at のまま）
        Location spawnAt = s.separationEnabled
                ? plugin.separation().findOpenSpot(at, aliveLocations(null), s.separationMinDistance)
                : at;
        NPC npc = registry.createNPC(EntityType.PLAYER, name);
        npc.setProtected(false);
        npc.data().set(NPC.Metadata.DISABLE_DEFAULT_STUCK_ACTION, true);
        npc.data().set(NPC.Metadata.KEEP_CHUNK_LOADED, true);
        applyEquipment(npc, side, type);
        applyNavigatorSettings(npc, side, type);
        SoldierBot bot = new SoldierBot(name, side, npc, owner, type);
        bot.ownerLife = ownerLife(owner);
        bot.order = owner != null && ownerName != null
                ? Order.follow(ownerName, Order.Stance.GUARD)
                : Order.hold(Point.of(at), Order.Stance.GUARD);
        bots.put(name.toLowerCase(Locale.ROOT), bot);
        npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false); // Citizens の名前を一瞬も出さない（NameTags の表示を使う）
        npc.spawn(spawnAt, SpawnReason.PLUGIN);
        applyMaxHealth(bot, true);
        return bot;
    }

    /**
     * 兵種の装備を入れる（陣営の設定の units.&lt;兵種&gt;.equipment）。先に全部位を空にするので、設定にない部位は何も持たない。
     * shield.enabled が false なら盾は持たせない。
     */
    private void applyEquipment(NPC npc, Side side, UnitType type) {
        Settings s = plugin.settings(side);
        Equipment eq = npc.getOrAddTrait(Equipment.class);
        for (Equipment.EquipmentSlot slot : new Equipment.EquipmentSlot[] {
                Equipment.EquipmentSlot.HAND, Equipment.EquipmentSlot.OFF_HAND, Equipment.EquipmentSlot.HELMET,
                Equipment.EquipmentSlot.CHESTPLATE, Equipment.EquipmentSlot.LEGGINGS, Equipment.EquipmentSlot.BOOTS}) {
            eq.set(slot, null);
        }
        for (Map.Entry<String, ItemStack> e : s.unit(type).equipment.entrySet()) {
            Equipment.EquipmentSlot slot = slotOf(e.getKey());
            if (slot == null || (e.getValue().getType() == Material.SHIELD && !s.shieldEnabled)) {
                continue; // shield.enabled が false なら盾は持たせない
            }
            eq.set(slot, equipmentItem(e.getValue(), s.unit(type)));
        }
    }

    /** 出ているボット（exclude に入っているものを除く）の今の位置。exclude は null 可。 */
    List<Location> aliveLocations(Collection<SoldierBot> exclude) {
        List<Location> r = new ArrayList<>();
        for (SoldierBot b : bots.values()) {
            if (b.alive() && (exclude == null || !exclude.contains(b))) {
                r.add(b.npc.getEntity().getLocation());
            }
        }
        return r;
    }

    public boolean remove(String name) {
        SoldierBot b = name == null ? null : bots.remove(name.toLowerCase(Locale.ROOT));
        if (b == null) {
            return false;
        }
        if (plugin.playerCommand() != null) {
            plugin.playerCommand().forgetBot(b.name);
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
     * 指揮権の譲渡も合わせる（持ち主がチームを離れた・譲渡先がいなくなった／別のチームになったら解除して兵と回数を戻す）。
     * 新しく出すことはしない（出すのは {@link #revive} だけ。コマンドブロックの /sb revive から呼ぶ）。
     * 兵科選択のアイテム（紙）は、回数・譲渡が変わったとき（{@link UnitPicks#syncPlayers}）と参加・リスポーンのときだけ配る
     * （ここで毎回配ると、箱にしまった紙が配り直されて増える）。
     */
    public void syncPersonalBots() {
        Map<UUID, Side> onlineSides = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            onlineSides.put(p.getUniqueId(), plugin.teams().sideOf(p));
        }
        for (Map.Entry<UUID, Delegations.Entry> e : plugin.delegations().snapshot().entrySet()) {
            UUID ownerId = e.getKey();
            Delegations.Entry del = e.getValue();
            Side ownerSide = onlineSides.get(ownerId);
            if (ownerSide == null) {
                // 持ち主がオフラインか、どのチームにもいない
                String ownerName = Bukkit.getOfflinePlayer(ownerId).getName();
                clearDelegationOfOwner(ownerId, ownerName != null ? ownerName : "持ち主", "チームを離れた");
            } else if (onlineSides.get(del.commander()) != ownerSide) {
                // 譲渡先がオフラインか、別のチーム
                plugin.delegations().clear(ownerId);
                Player op = Bukkit.getPlayer(ownerId);
                if (op != null) {
                    retarget(ownedBy(ownerId), del.commanderName(), op.getName());
                    tellPlayer(op, del.commanderName() + " がチームを離れたため、あなたの兵と残り回数の指揮権が戻りました。");
                }
                plugin.picks().syncPlayers(ownerId, del.commander());
            }
        }
        for (SoldierBot b : new ArrayList<>(bots.values())) {
            if (b.owner != null && onlineSides.get(b.owner) != b.side) {
                remove(b.name);
            } else if (b.custodian != null && onlineSides.get(b.custodian) != b.side) {
                // 預かり手がオフラインか、別のチーム: 兵は持ち主に戻る
                String keeperName = b.custodianName;
                Player op = Bukkit.getPlayer(b.owner);
                releaseCustody(b, op != null ? op.getName() : null);
                if (op != null) {
                    tellPlayer(op, keeperName + " がチームを離れたため、預けていた兵 " + b.name + " が戻りました。");
                }
            }
        }
    }

    /** 持ち主 ownerId の専用のボットのうち、別のチームになったもの・倒れて出ていないものを消す。 */
    private void cleanupInvalidOwned(UUID ownerId, Side side) {
        for (SoldierBot old : ownedBy(ownerId)) {
            if (old.side != side || !old.npc.isSpawned()) {
                remove(old.name); // チームを移った後の古いボットと、倒れた（消えそこねた）ボット
            }
        }
    }

    // ---- 指揮権の譲渡 ----

    /** id のプレイヤーがオンラインで side のチームにいるか。 */
    public boolean isOnlineSameSide(UUID id, Side side) {
        Player p = Bukkit.getPlayer(id);
        return p != null && p.isOnline() && side != null && plugin.teams().sideOf(p) == side;
    }

    /** owner の兵を今指揮する人（譲渡先がオンラインで同じチームならその人、そうでなければ owner 自身）。 */
    public UUID effectiveCommander(UUID owner, Side side) {
        if (owner == null) {
            return null;
        }
        return plugin.delegations().commanderOf(owner, id -> isOnlineSameSide(id, side));
    }

    /** そのボットを今指揮する人。持ち主のいないボットは null。 */
    public UUID commanderOf(SoldierBot bot) {
        if (bot.owner == null) {
            return null;
        }
        if (bot.custodian != null && isOnlineSameSide(bot.custodian, bot.side)) {
            return bot.custodian; // 持ち主が倒れた後も預かっている兵
        }
        return effectiveCommander(bot.owner, bot.side);
    }

    /** 預かり手（{@link SoldierBot#custodian}）を外し、その人についていっていたなら持ち主についていかせる（持ち主がいれば）。 */
    private void releaseCustody(SoldierBot b, String ownerName) {
        String old = b.custodianName;
        b.custodian = null;
        b.custodianName = null;
        if (ownerName != null) {
            retarget(List.of(b), old, ownerName);
        }
    }

    /** commander が今指揮しているボット（出した順）。 */
    public List<SoldierBot> commandedBy(UUID commander) {
        List<SoldierBot> r = new ArrayList<>();
        if (commander == null) {
            return r;
        }
        for (SoldierBot b : bots.values()) {
            if (commander.equals(commanderOf(b))) {
                r.add(b);
            }
        }
        return r;
    }

    /**
     * oldLeaderName についてこさせていたボットだけ、newLeaderName についてこさせる（構えはそのまま）。
     * 持ち場を守っているボットや、別の人を追っているボットには触らない。
     */
    void retarget(Collection<SoldierBot> targetBots, String oldLeaderName, String newLeaderName) {
        if (oldLeaderName == null || newLeaderName == null) {
            return;
        }
        for (SoldierBot b : targetBots) {
            if (b.order != null && b.order.mode() == Order.Mode.FOLLOW && b.order.player() != null
                    && b.order.player().equalsIgnoreCase(oldLeaderName)) {
                setOrder(b, Order.follow(newLeaderName, b.order.stance()));
            }
        }
    }

    /**
     * owner の指揮権（兵と残り回数）を同じチームの target に譲る。すでに誰かに譲っていれば付け替える。
     *
     * @return 譲れたら true（相手がオンラインでない・倒れている・同じチームでない・ボット・自分自身なら false）
     */
    public boolean delegateCommand(Player owner, Player target) {
        if (owner == null || target == null || !owner.isOnline() || !target.isOnline() || target.isDead()
                || owner.getUniqueId().equals(target.getUniqueId()) || byEntity(target) != null) {
            return false;
        }
        Side side = plugin.teams().sideOf(owner);
        if (side == null || side != plugin.teams().sideOf(target)) {
            return false;
        }
        UUID ownerId = owner.getUniqueId();
        UUID targetId = target.getUniqueId();
        Delegations.Entry oldEntry = plugin.delegations().entryOf(ownerId);
        String oldLeaderName = oldEntry != null ? oldEntry.commanderName() : owner.getName();
        plugin.delegations().set(ownerId, targetId, target.getName());
        List<SoldierBot> myBots = ownedBy(ownerId);
        for (SoldierBot b : myBots) {
            if (b.custodian != null) {
                // 前の命から預けたままの兵も、新しい譲渡先に渡す
                String keeperName = b.custodianName;
                b.custodian = null;
                b.custodianName = null;
                retarget(List.of(b), keeperName, target.getName());
            }
        }
        retarget(myBots, oldLeaderName, target.getName());
        UUID oldCmdId = oldEntry != null ? oldEntry.commander() : null;
        plugin.picks().syncPlayers(ownerId, targetId, oldCmdId);
        int left = plugin.picks().ownPicks(ownerId);
        tellPlayer(owner, target.getName() + " に指揮権を譲渡しました（兵 " + myBots.size() + " 体・残り " + left
                + " 回。あなたが倒れても渡した兵は残りますが、残り回数は 0 になります）。");
        tellPlayer(target, owner.getName() + " から指揮権を譲り受けました（兵 " + myBots.size() + " 体・残り " + left + " 回）。");
        if (oldCmdId != null && !oldCmdId.equals(targetId)) {
            Player oldCmd = Bukkit.getPlayer(oldCmdId);
            if (oldCmd != null && oldCmd.isOnline()) {
                tellPlayer(oldCmd, owner.getName() + " が指揮権の譲渡先を " + target.getName() + " に変更しました。");
            }
        }
        return true;
    }

    /**
     * owner が譲っていた指揮権と、前の命から預けたままの兵を自分に戻す。どちらもなければ false。
     */
    public boolean reclaimCommand(Player owner) {
        UUID ownerId = owner.getUniqueId();
        Delegations.Entry prev = plugin.delegations().clear(ownerId);
        Set<UUID> keepers = new LinkedHashSet<>();
        for (SoldierBot b : ownedBy(ownerId)) {
            if (b.custodian != null) {
                keepers.add(b.custodian);
                releaseCustody(b, owner.getName());
            }
        }
        if (prev == null && keepers.isEmpty()) {
            return false;
        }
        if (prev != null) {
            retarget(ownedBy(ownerId), prev.commanderName(), owner.getName());
            plugin.picks().syncPlayers(ownerId, prev.commander());
            keepers.add(prev.commander());
        }
        tellPlayer(owner, "指揮権を自分に戻しました。");
        for (UUID k : keepers) {
            Player cmd = Bukkit.getPlayer(k);
            if (cmd != null && cmd.isOnline()) {
                tellPlayer(cmd, owner.getName() + " が指揮権を自分に戻しました。");
            }
        }
        return true;
    }

    /**
     * commanderId に譲られていた指揮権をすべて持ち主に戻す（指揮者が倒れた・退出した・チームが変わったとき）。
     * 持ち主がオンラインで同じチームにいれば、その兵を持ち主についてこさせ直して知らせる。
     *
     * @param reason 「倒れた」「退出した」など（「…ため」に続く）
     */
    public void returnDelegatedFromCommander(UUID commanderId, String commanderName, String reason) {
        Map<UUID, Delegations.Entry> cleared = plugin.delegations().clearByCommander(commanderId);
        for (UUID ownerId : cleared.keySet()) {
            Player op = Bukkit.getPlayer(ownerId);
            Side ownerSide = op != null && op.isOnline() ? plugin.teams().sideOf(op) : null;
            if (op != null && op.isOnline() && !op.isDead() && ownerSide != null) {
                List<SoldierBot> aliveOwned = new ArrayList<>();
                for (SoldierBot b : ownedBy(ownerId)) {
                    if (b.side == ownerSide && b.alive()) {
                        aliveOwned.add(b);
                    }
                }
                retarget(aliveOwned, commanderName, op.getName());
                tellPlayer(op, commanderName + " が" + reason + "ため、あなたの兵（" + aliveOwned.size() + " 体）と残り "
                        + plugin.picks().ownPicks(ownerId) + " 回の指揮権が戻りました。");
            }
            plugin.picks().syncPlayers(ownerId, commanderId);
        }
        // 持ち主が倒れた後も預かっていた兵も、生きたまま持ち主に戻す
        Map<UUID, Integer> returned = new LinkedHashMap<>();
        for (SoldierBot b : bots.values()) {
            if (commanderId.equals(b.custodian)) {
                Player op = Bukkit.getPlayer(b.owner);
                releaseCustody(b, op != null && op.isOnline() ? op.getName() : null);
                returned.merge(b.owner, 1, Integer::sum);
            }
        }
        for (Map.Entry<UUID, Integer> e : returned.entrySet()) {
            Player op = Bukkit.getPlayer(e.getKey());
            if (op != null && op.isOnline() && !cleared.containsKey(e.getKey())) {
                tellPlayer(op, commanderName + " が" + reason + "ため、預けていた兵 " + e.getValue() + " 体が戻りました。");
            }
        }
    }

    /**
     * プレイヤーが倒れた時刻と回数を記録する（{@link #onCommanderDeath} の同時死亡の判定と、
     * 回数を戻してよい兵の判定 {@link #refundable} に使う）。
     */
    public void recordPlayerDeath(UUID player) {
        if (player != null) {
            lastDeathTick.put(player, (long) Bukkit.getCurrentTick());
            ownerLives.merge(player, 1, Integer::sum);
        }
    }

    /** そのプレイヤーが（サーバーを起動してから）倒れた回数。持ち主のいないボットは 0。退出しても数え直さない。 */
    int ownerLife(UUID player) {
        return player == null ? 0 : ownerLives.getOrDefault(player, 0);
    }

    /**
     * 指揮官が倒れて道連れになった兵の分を、持ち主の回数に戻してよいか: 兵科選択の回数を使って呼んだ兵で（paidWithPick）、
     * 兵を出してから持ち主が一度も倒れていないときだけ。
     * 回数を使わずに出た兵（/sb revive）や、持ち主の死を越えて生き残った兵（預かり兵）を回数に変えられないようにする。
     */
    static boolean refundable(boolean paidWithPick, int ownerLifeAtSpawn, int ownerLifeNow) {
        return paidWithPick && ownerLifeAtSpawn == ownerLifeNow;
    }

    /**
     * 持ち主が倒れたときの、その人の兵の扱い。
     * <ul>
     *   <li>指揮権を同じチームの生きている人に譲っていれば、出ている兵は倒さず、その人が預かり続ける（{@link SoldierBot#custodian}）。
     *       兵の持ち主は変えないので、上限 max-per-player の枠もそのまま使う。</li>
     *   <li>譲渡（{@link Delegations}）そのものは解除する。譲渡が続くと、次の命でもらった回数まで前の譲渡先が使えてしまうため
     *       （回数を譲るのは、その命で譲ったときだけ）。</li>
     *   <li>前の命から預けたままの兵（預かり手が有効）は、そのまま。</li>
     *   <li>それ以外の兵（譲っていない・譲渡先が無効）は、方針を戻して倒す。</li>
     * </ul>
     * 回数を 0 にするのは呼び出し元。
     */
    /**
     * 持ち主が倒れたときの譲渡の扱い: 譲渡（回数を譲ること）は必ず解除する。解除前の譲渡先を返す（なければ null）。
     * 解除しないと、次の命でもらった回数まで前の譲渡先が使えてしまう（兵は {@link SoldierBot#custodian} で預かり続ける）。
     */
    static Delegations.Entry endDelegationOnDeath(Delegations delegations, UUID owner) {
        return delegations.clear(owner);
    }

    public void onOwnerDeath(Player dead) {
        UUID id = dead.getUniqueId();
        Side side = plugin.teams().sideOf(dead);
        Delegations.Entry del = endDelegationOnDeath(plugin.delegations(), id);
        Player cmd = del == null ? null : Bukkit.getPlayer(del.commander());
        boolean keep = del != null && side != null && cmd != null && !cmd.isDead() && isOnlineSameSide(del.commander(), side);
        int kept = 0;
        for (SoldierBot b : ownedBy(id)) {
            if (!b.alive()) {
                continue;
            }
            if (b.custodian != null && isOnlineSameSide(b.custodian, b.side)) {
                continue; // 前の命から預けたままの兵は、預かり手のところに残す
            }
            if (keep && b.side == side) {
                b.custodian = cmd.getUniqueId();
                b.custodianName = cmd.getName();
                retarget(List.of(b), dead.getName(), cmd.getName()); // 倒れた持ち主についていっていた兵は、預かり手についていく
                retarget(List.of(b), del.commanderName(), cmd.getName());
                kept++;
            } else {
                b.custodian = null;
                b.custodianName = null;
                setOrder(b, Order.follow(dead.getName(), Order.Stance.GUARD));
                b.target = null;
                killBot(b);
            }
        }
        if (del != null) {
            plugin.picks().syncPlayers(id, del.commander());
        }
        if (keep) {
            tellPlayer(cmd, dead.getName() + " が倒れましたが、預かっている兵 " + kept + " 体はそのまま指揮できます（"
                    + dead.getName() + " の残り回数は 0 になり、これからもらう回数はもう譲られません）。");
        } else if (del != null && cmd != null && cmd.isOnline()) {
            tellPlayer(cmd, dead.getName() + " が倒れたため、預かっていた指揮権が解除されました。");
        }
    }

    /** 倒れた時刻の記録を捨てる（退出したとき）。 */
    public void forgetPlayerDeath(UUID player) {
        if (player != null) {
            lastDeathTick.remove(player);
        }
    }

    /**
     * 指揮官が倒れたときの後始末。預かっていた指揮権を元の持ち主に戻し、その持ち主の出ている兵を倒して、
     * killOwnedAlive が返した数（回数を戻してよい体数）だけ持ち主の回数に足す（持ち主が同じ tick に倒れていた・退出した・
     * 陣営が変わったときは足さず通知もしない）。
     * 加算と通知は 1 tick 後に行う。順序: 委任を外す → 預かり分の紙を更新 → 兵を倒す → （次の tick）加算・通知。
     *
     * @param killOwnedAlive 持ち主の出ている兵を倒し、そのうち回数を戻してよい体数（{@link #refundable}）を返す
     * @param ownerEligibleNextTick 1 tick 後に持ち主へ加算・通知してよいか
     * @param deferNextTick 処理を 1 tick 後に回す
     * @param notifyOwner 持ち主に知らせる（持ち主, 倒れた体数）
     */
    static void handleCommanderDeath(
            Delegations delegations,
            UnitPicks picks,
            UUID commanderId,
            ToIntFunction<UUID> killOwnedAlive,
            Predicate<UUID> ownerEligibleNextTick,
            Consumer<Runnable> deferNextTick,
            BiConsumer<UUID, Integer> notifyOwner) {
        Map<UUID, Delegations.Entry> cleared = delegations.clearByCommander(commanderId);
        if (cleared.isEmpty()) {
            return;
        }
        Map<UUID, Integer> killedByOwner = new LinkedHashMap<>();
        for (UUID ownerId : cleared.keySet()) {
            picks.syncPlayers(ownerId, commanderId); // 1 tick 待たず即座に預かり分の紙を更新
            int killed = killOwnedAlive.applyAsInt(ownerId);
            killedByOwner.put(ownerId, killed);
        }
        deferNextTick.accept(() -> {
            for (Map.Entry<UUID, Integer> e : killedByOwner.entrySet()) {
                UUID ownerId = e.getKey();
                if (!ownerEligibleNextTick.test(ownerId)) {
                    continue; // 同時死亡（B→A 順）・退出・陣営変更なら加算も通知もしない
                }
                int killed = e.getValue();
                if (killed > 0) {
                    picks.addPicks(ownerId, killed);
                }
                notifyOwner.accept(ownerId, killed);
            }
        });
    }

    /**
     * 指揮官（commanderId）が倒れたとき、預かっていた兵を倒して、倒れた数だけ元の持ち主の回数に戻す
     * （持ち主が一度倒れた後も生き残っていた兵の分は戻さない。{@link #refundable}）。
     * 退出のとき（兵を生かしたまま返す）は {@link #returnDelegatedFromCommander} を使う。
     */
    public void onCommanderDeath(UUID commanderId, String commanderName) {
        long deathTick = Bukkit.getCurrentTick();
        // 兵が 0 体の持ち主にも 1 tick 後に通知が届くよう、兵の数に関係なく全委任元の陣営を先に記録する
        Map<UUID, Side> ownerSidesAtDeath = new HashMap<>();
        for (UUID ownerId : plugin.delegations().ownersOf(commanderId)) {
            Player op = Bukkit.getPlayer(ownerId);
            Side side = op != null && op.isOnline() ? plugin.teams().sideOf(op) : null;
            if (side != null) {
                ownerSidesAtDeath.put(ownerId, side);
            }
        }
        Map<UUID, Integer> killedByOwner = new HashMap<>(); // 倒れた体数（回数を戻さない兵も含む。知らせる文に使う）
        // 持ち主が倒れた後も預かっていた兵（前の命の兵）は倒れる。持ち主が倒れる前に呼んだ兵なので回数は戻さない
        Map<UUID, Integer> custodialKilled = new LinkedHashMap<>();
        for (SoldierBot b : new ArrayList<>(bots.values())) {
            if (commanderId.equals(b.custodian)) {
                b.custodian = null;
                b.custodianName = null;
                if (killBot(b)) {
                    custodialKilled.merge(b.owner, 1, Integer::sum);
                }
            }
        }
        for (Map.Entry<UUID, Integer> e : custodialKilled.entrySet()) {
            if (ownerSidesAtDeath.containsKey(e.getKey())) {
                killedByOwner.merge(e.getKey(), e.getValue(), Integer::sum); // 譲渡中の持ち主には 1 tick 後にまとめて知らせる
            } else {
                Player op = Bukkit.getPlayer(e.getKey());
                if (op != null && op.isOnline()) {
                    tellPlayer(op, commanderName + " が倒れたため、預けていた兵 " + e.getValue()
                            + " 体が倒れました（あなたが倒れる前に呼んだ兵なので、回数は戻りません）。");
                }
            }
        }
        handleCommanderDeath(
                plugin.delegations(),
                plugin.picks(),
                commanderId,
                ownerId -> {
                    Side ownerSide = ownerSidesAtDeath.get(ownerId);
                    if (ownerSide == null) {
                        return 0;
                    }
                    int killed = 0;
                    int refund = 0;
                    int lifeNow = ownerLife(ownerId);
                    for (SoldierBot b : ownedBy(ownerId)) {
                        // 別の人が預かっている前の命の兵には触らない（その預かり手が倒れたときに倒れる）
                        if (b.side == ownerSide && b.custodian == null && killBot(b)) {
                            killed++;
                            if (refundable(b.paidWithPick, b.ownerLife, lifeNow)) {
                                refund++; // 持ち主の死を越えて生き残った兵は戻さない
                            }
                        }
                    }
                    killedByOwner.merge(ownerId, killed, Integer::sum);
                    return refund;
                },
                ownerId -> {
                    Side expectedSide = ownerSidesAtDeath.get(ownerId);
                    Player op = Bukkit.getPlayer(ownerId);
                    return expectedSide != null && op != null && op.isOnline() && !op.isDead()
                            && plugin.teams().sideOf(op) == expectedSide
                            && lastDeathTick.getOrDefault(ownerId, Long.MIN_VALUE) < deathTick;
                },
                task -> Bukkit.getScheduler().runTask(plugin, task),
                (ownerId, refunded) -> {
                    Player op = Bukkit.getPlayer(ownerId);
                    if (op == null || !op.isOnline()) {
                        return;
                    }
                    int total = plugin.picks().ownPicks(ownerId);
                    int killed = killedByOwner.getOrDefault(ownerId, 0);
                    if (killed > 0 && refunded == killed) {
                        tellPlayer(op, commanderName + " が倒れたため、預けていた兵 " + killed + " 体が倒れ、その分の "
                                + killed + " 回を戻しました（兵科選択: 残り " + total + " 回）。");
                    } else if (killed > 0) {
                        tellPlayer(op, commanderName + " が倒れたため、預けていた兵 " + killed + " 体が倒れました。"
                                + "あなたが倒れる前に呼んだ兵の分は戻らないので、戻したのは " + refunded + " 回です（兵科選択: 残り "
                                + total + " 回）。");
                    } else {
                        tellPlayer(op, commanderName + " が倒れたため、指揮権が戻りました（兵科選択: 残り " + total + " 回）。");
                    }
                });
    }

    /** 持ち主 ownerId が譲っていた指揮権を解除する（持ち主が倒れた・退出した）。預かっていた指揮者に知らせる。 */
    public void clearDelegationOfOwner(UUID ownerId, String ownerName, String reason) {
        Delegations.Entry prev = plugin.delegations().clear(ownerId);
        if (prev == null) {
            return;
        }
        plugin.picks().syncPlayers(ownerId, prev.commander());
        Player prevCmd = Bukkit.getPlayer(prev.commander());
        if (prevCmd != null && prevCmd.isOnline()) {
            tellPlayer(prevCmd, ownerName + " が" + reason + "ため、預かっていた指揮権が解除されました。");
        }
    }

    private static void tellPlayer(Player p, String text) {
        p.sendMessage(Component.text("[SiegeBot] ", NamedTextColor.GOLD)
                .append(Component.text(text, NamedTextColor.WHITE)));
    }

    /**
     * {@link #revive} の結果（1 人分）。
     *
     * @param status  OK なら下の数が意味を持つ
     * @param spawned 新しく出した数
     * @param alive   もともと参戦していた数（何もしなかった）
     * @param capped  1 人あたりの上限（max-per-player）のせいで出せなかった数
     */
    public record ReviveReport(Status status, int spawned, int alive, int capped) {
        public enum Status {
            OK,
            /** 攻め・守りのどちらのチームにも入っていない */
            NO_TEAM,
            /** 出す場所が決まらず、1 体も出せなかった */
            NO_PLACE
        }

        static ReviveReport of(Status s) {
            return new ReviveReport(s, 0, 0, 0);
        }
    }

    /**
     * プレイヤーの専用のボットを count 体そろえる。足りない分を新しく出す（持ち主に追従）。
     * 倒れたボットはすぐ消える（{@link #onBotDied}）ので、蘇生はしない。出す場所は陣営のリスポーン地点、未設定ならプレイヤーのいる所。
     *
     * <p>count が null なら「1 体もいなければ 1 体出す」。count は 1〜max-per-player に収める。
     * count より多く持っているときは、多い分には手を付けない（消さない）。
     *
     * <p>type を指定すると、その兵種だけを数える: その兵種のボットを count 体にそろえる。ほかの兵種のボットには
     * 手を付けない。全兵種の合計は max-per-player を超えない。type が null なら兵種を問わず数え、新しく出すのは units.default の兵種。
     */
    public ReviveReport revive(Player p, Integer count, UnitType type) {
        Side side = plugin.teams().sideOf(p);
        if (side == null) {
            return ReviveReport.of(ReviveReport.Status.NO_TEAM);
        }
        cleanupInvalidOwned(p.getUniqueId(), side);
        List<SoldierBot> all = ownedBy(p.getUniqueId());
        List<SoldierBot> mine = new ArrayList<>();
        for (SoldierBot b : all) {
            if (type == null || b.type == type) {
                mine.add(b);
            }
        }
        Settings st = plugin.settings(side);
        int max = st.maxBotsPerPlayer;
        // max が下がっても、すでにいるボットは消さない（超えた分の指定は capped に数える）
        int want = Math.max(1, count == null ? Math.max(1, mine.size()) : count);
        // 新しく出せるのは、全兵種の合計が上限に届くまで
        int room = Math.max(0, max - all.size());
        int toSpawn = Math.max(0, want - mine.size());
        int capped = Math.max(0, toSpawn - room);
        toSpawn -= capped;

        int spawned = 0;
        int alive = Math.min(want, mine.size());
        SoldierBot voice = null;
        UnitType newType = type == null ? st.defaultUnit : type;
        // 初期の追従先と、出す場所が決まらないときの場所は、今指揮している人（譲渡中ならその人）。上限・名前は持ち主の p のまま
        UUID cmdId = effectiveCommander(p.getUniqueId(), side);
        Player cmd = cmdId == null ? null : Bukkit.getPlayer(cmdId);
        Player follow = cmd != null ? cmd : p;
        for (int i = 0; i < toSpawn; i++) {
            SoldierBot b = spawn(personalName(p.getName(), side, newType), side, follow.getLocation(), p.getUniqueId(),
                    follow.getName(), newType);
            if (b == null) {
                break;
            }
            spawned++;
            voice = voice == null ? b : voice;
        }
        if (voice != null) {
            say(voice, p.getName() + " の兵、" + spawned + " 体参戦した");
        }
        if (spawned + alive == 0 && capped == 0) {
            return ReviveReport.of(ReviveReport.Status.NO_PLACE);
        }
        return new ReviveReport(ReviveReport.Status.OK, spawned, alive, capped);
    }

    // ---- 兵科選択（呼べる回数を使って、兵種を選んで 1 体ずつ呼ぶ） ----

    /** {@link #summon} の結果。 */
    public enum SummonResult {
        /** 新しく出した */
        SPAWNED,
        /** 呼べる回数が残っていない */
        NO_PICKS,
        /** 1 人あたりの上限（max-per-player）に届いている */
        FULL,
        /** 攻め・守りのどちらのチームにも入っていない */
        NO_TEAM,
        /** 出す場所が決まらなかった */
        NO_PLACE,
        /** 前の命から残っている兵の分、回数を使えない（{@link #canUsePick}） */
        RESERVED
    }

    /**
     * 兵科選択: 呼べる回数を 1 使って、その兵種のボットを 1 体新しく出す。自分の分を先に、次に譲られて預かっている分を使う
     * （使った持ち主の上限 max-per-player の枠に出る）。呼べなかったときは回数を使わない。
     */
    public SummonResult summon(Player p, UnitType type) {
        Side side = plugin.teams().sideOf(p);
        if (side == null) {
            return SummonResult.NO_TEAM;
        }
        int max = plugin.settings(side).maxBotsPerPlayer;
        UnitPicks.PickChoice choice = plugin.picks().peekOriginFor(p, side, originId -> {
            cleanupInvalidOwned(originId, side);
            return ownedBy(originId).size() < max && canUsePick(survivorsAlive(originId), plugin.picks().ownPicks(originId));
        });
        if (choice.status() == UnitPicks.PickChoice.Status.NO_PICKS) {
            return SummonResult.NO_PICKS;
        }
        if (choice.status() == UnitPicks.PickChoice.Status.FULL) {
            // 枠に空きがあるのに呼べないなら、前の命の兵の分で回数を使えない
            UnitPicks.PickChoice byCap = plugin.picks().peekOriginFor(p, side, originId -> ownedBy(originId).size() < max);
            return byCap.status() == UnitPicks.PickChoice.Status.OK ? SummonResult.RESERVED : SummonResult.FULL;
        }
        UUID originId = choice.originOwner();
        Player originPlayer = Bukkit.getPlayer(originId);
        String originName = originPlayer != null ? originPlayer.getName() : p.getName();
        SoldierBot b = spawn(personalName(originName, side, type), side, p.getLocation(), originId, p.getName(), type);
        if (b == null) {
            return SummonResult.NO_PLACE;
        }
        plugin.picks().consumeOne(originId); // 出せた後にだけ使う
        b.paidWithPick = true; // 指揮官の道連れで倒れたとき、この兵の分だけ回数に戻せる
        say(b, type.label() + "、参戦した");
        return SummonResult.SPAWNED;
    }

    /** 兵科選択の画面用: p が今 1 体呼ぶとしたらどうなるか（何も変えない。倒れた兵は数えない）。 */
    public UnitPicks.PickChoice checkSummonAvailability(Player p, Side side) {
        int max = plugin.settings(side).maxBotsPerPlayer;
        return plugin.picks().peekOriginFor(p, side, originId -> ownedBy(originId).stream()
                .filter(b -> b.side == side && b.alive()).count() < max
                && canUsePick(survivorsAlive(originId), plugin.picks().ownPicks(originId)));
    }

    /**
     * 持ち主の、前の命から生き残っている兵の数（倒れる前に出して、持ち主が倒れた後も出ている兵。預かり兵も、取り戻した兵も数える）。
     */
    public int survivorsAlive(UUID owner) {
        int life = ownerLife(owner);
        int n = 0;
        for (SoldierBot b : ownedBy(owner)) {
            if (b.alive() && b.ownerLife < life) {
                n++;
            }
        }
        return n;
    }

    /**
     * 兵科選択の回数を 1 使って呼んでよいか: 前の命から生き残っている兵の数より、残りの回数が多いときだけ。
     * 生き残りの兵は、次の命でもらった回数を 1 体につき 1 回ずつ先に使ったものとして扱う
     * （譲渡してから倒れる、を繰り返して兵を増やせないようにする。生き残りが倒れれば、その分また使える）。
     * これで、持ち主の生きている兵は「1 つの命でもらった回数」より多くならない。
     */
    static boolean canUsePick(int survivorsAlive, int ownPicks) {
        return survivorsAlive < ownPicks;
    }

    /**
     * ボットが倒れたら、すぐ（次の tick に）消す。指揮メニューの一覧からも外れる。蘇生はしない
     * （/sb revive・兵科選択で新しく出す）。
     */
    public void onBotDied(SoldierBot bot) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (bots.get(bot.name.toLowerCase(Locale.ROOT)) == bot && !bot.npc.isSpawned()) {
                remove(bot.name);
            }
        });
    }

    /** 持ち主の専用のボットの方針を、出したときと同じ「持ち主についていく・持ち場優先」に戻す。 */
    public void resetOrders(Player owner) {
        for (SoldierBot b : ownedBy(owner.getUniqueId())) {
            setOrder(b, Order.follow(owner.getName(), Order.Stance.GUARD)); // 退却も終わる
            b.target = null;
        }
    }

    /** 持ち主が倒れたら、その専用のボットを全部倒す（倒れたボットはすぐ消える）。 */
    public void killOwnedBots(UUID owner) {
        for (SoldierBot b : ownedBy(owner)) {
            killBot(b);
        }
    }

    /** 出ているボットを 1 体倒す。実際に倒れたら true（イベントのキャンセルなどで生き残れば false）。 */
    private boolean killBot(SoldierBot b) {
        if (!b.alive() || !(b.npc.getEntity() instanceof LivingEntity le) || le.isDead() || le.getHealth() <= 0.0) {
            return false;
        }
        le.setHealth(0.0);
        boolean died = !b.alive() || le.isDead() || le.getHealth() <= 0.0;
        if (died && plugin.playerCommand() != null) {
            plugin.playerCommand().forgetBot(b.name);
        }
        return died;
    }

    /** 専用のボットの名前（「名前の兵」、2 体目からは「名前2の兵」…）。16 文字に収まるよう名前を縮める。 */
    private String personalName(String player, Side side, UnitType type) {
        return uniqueName(player, plugin.settings(side).unit(type).nameSuffix, n -> get(n) != null);
    }

    /**
     * プレイヤー名 + 接尾辞の名前を、16 文字以内で exists に当たらないように決める。重複するときは番号（2 から）を
     * プレイヤー名の後ろ（接尾辞の前）に入れ、足りなければプレイヤー名を縮める。番号だけで 16 文字を超える極端な場合は
     * 接尾辞を縮める。
     */
    static String uniqueName(String player, String suffix, Predicate<String> exists) {
        String s = suffix == null ? "" : suffix;
        if (s.length() >= 16) {
            s = s.substring(0, 15);
        }
        int room = 16 - s.length();
        String pName = player == null ? "" : player;
        String base = pName.length() > room ? pName.substring(0, room) : pName;
        String name = base + s;
        for (int i = 2; exists.test(name); i++) {
            String num = String.valueOf(i);
            if (num.length() <= room) {
                int keep = room - num.length();
                name = base.substring(0, Math.min(base.length(), keep)) + num + s;
            } else {
                int sKeep = Math.max(0, 16 - num.length());
                name = num + s.substring(0, Math.min(s.length(), sKeep));
            }
        }
        return name;
    }

    /**
     * 設定を読み直した（または別のマップに切り替えた）後に、移動・攻撃・体力の設定を全ボットに入れ直す。
     * refreshEquipment が true なら装備も入れ直す（盾の耐久は満タンに戻る）。/sb reload では false（装備は触らない）。
     */
    public void reapplySettings(boolean refreshEquipment) {
        for (SoldierBot b : bots.values()) {
            if (refreshEquipment && b.npc.isSpawned()) {
                applyEquipment(b.npc, b.side, b.type);
            }
            applyNavigatorSettings(b.npc, b.side, b.type);
            applyMaxHealth(b, false);
        }
    }

    /**
     * ボットを to へ移す。移す前の経路は捨てる（残ると古い経路のまま止まる）。
     * 名前の表示を乗せているので、乗客を残したまま移す。倒れている（出ていない）ボットには呼ばない。
     */
    public void teleport(SoldierBot bot, Location to) {
        bot.holdingSlot = false;
        bot.fineBypassUntilTick = 0;
        bot.backingOff = false;
        bot.backoffStartTick = 0;
        NavigationWatch.clearTracking(bot);
        bot.npc.getNavigator().cancelNavigation();
        bot.npc.getEntity().teleport(to, TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    /** 兵種の最大の体力（units.&lt;兵種&gt;.max-health）を入れる。full なら満タンにする（出したとき・参戦したとき）。 */
    private void applyMaxHealth(SoldierBot bot, boolean full) {
        if (!(bot.npc.getEntity() instanceof LivingEntity le)) {
            return;
        }
        double max = plugin.settings(bot.side).unit(bot.type).maxHealth;
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

    private void applyNavigatorSettings(NPC npc, Side side, UnitType type) {
        Settings s = plugin.settings(side);
        Settings.Unit unit = s.unit(type);
        NavigatorParameters p = npc.getNavigator().getDefaultParameters();
        p.range(s.pathRange);
        // 落ちてよい高さ。Citizens は既定（-1）だと高い所から降りる経路を探さない
        p.fallDistance(s.maxFallDistance);
        p.speedModifier(unit.speed);
        p.attackRange(unit.attackRange);
        p.attackDelayTicks(unit.attackDelayTicks);
        // 剣兵の攻撃（Citizens が届く所で attack-delay ごとに呼ぶ）。照準が外れていれば空振り（Aim）
        p.attackStrategy(this::meleeAttack);
        // 動けないときに目的地へ瞬間移動しない（その場で経路を取り直す）
        p.stuckAction((n, nav) -> false);
        try {
            p.pathfinderType(PathfinderType.valueOf(s.pathfinder));
        } catch (IllegalArgumentException e) {
            p.pathfinderType(PathfinderType.CITIZENS);
        }
    }

    /**
     * Citizens の攻撃の代わり（剣兵。殴りながら追う間、届く所で attack-delay ごとに呼ばれる）。
     * 腕を振り、揺れを含めた照準の線が相手の箱に入っていれば殴る（外れていれば空振り）。ボットでなければ Citizens に任せる。
     */
    private boolean meleeAttack(LivingEntity attacker, LivingEntity target) {
        SoldierBot bot = byEntity(attacker);
        if (bot == null || !(attacker instanceof Player p) || target == null) {
            return false;
        }
        p.swingMainHand();
        boolean hit = Aim.meleeHits(bot, p, target, plugin.settings(bot.side).unit(bot.type).attackRange + 1.0);
        if (hit) {
            net.citizensnpcs.util.NMS.attack(attacker, target);
        }
        plugin.combatDebug().logMelee(bot, target, hit, p);
        return true;
    }

    /**
     * 装備のアイテム（設定のアイテムを写したもの）。盾はプレイヤーと同じく、防ぐと削れて壊れる（補充しない）。
     * 兵種の shield-durability が 0 より大きければ、盾の耐久をその値にする。
     */
    private static ItemStack equipmentItem(ItemStack template, Settings.Unit unit) {
        ItemStack item = template.clone();
        if (item.getType() == Material.SHIELD && unit.shieldDurability > 0
                && item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d) {
            d.setMaxDamage(unit.shieldDurability);
            item.setItemMeta(d);
        }
        return item;
    }

    /** アイテムの耐久の上限（max_damage の部品があればその値、なければアイテムの種類の値）。 */
    static int maxDurability(ItemStack item) {
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d && d.hasMaxDamage()) {
            return d.getMaxDamage();
        }
        return item.getType().getMaxDurability();
    }

    /**
     * ボットの左手の盾が削れた・壊れたら、Citizens の装備にも写す（写さないと Citizens が元の盾に戻す）。
     * damage は今回削れる量（null なら左手をそのまま写す）。壊れたら左手は空のまま（補充しない）。
     */
    public void syncOffHand(SoldierBot bot, ItemStack before, Integer damage) {
        if (!(bot.npc.getEntity() instanceof Player p)) {
            return;
        }
        ItemStack now;
        if (before != null && damage != null) {
            now = before.clone();
            if (now.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d) {
                int next = d.getDamage() + damage;
                if (next >= maxDurability(now)) {
                    now = null; // 壊れた
                } else {
                    d.setDamage(next);
                    now.setItemMeta(d);
                }
            }
        } else {
            ItemStack off = p.getInventory().getItemInOffHand();
            now = off.getType() == Material.AIR ? null : off.clone();
        }
        bot.npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.OFF_HAND, now);
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
        bot.holdingSlot = false;
        bot.fineBypassUntilTick = 0;
        bot.backingOff = false;
        bot.backoffStartTick = 0;
        NavigationWatch.clearTracking(bot);
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
            b.retreatUntilTick = now + plugin.settings(b.side).retreatTimeoutTicks;
            b.target = null;
            boolean wasBackingOff = b.backingOff;
            b.backingOff = false;
            b.backoffStartTick = 0;
            Navigator nav = b.npc.getNavigator();
            if (nav.getEntityTarget() != null || wasBackingOff) {
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

    /**
     * ボットの発言を送る。持ち主にだけ（オフラインなら誰にも送らない）。
     * 持ち主のいないボットは（通常はいないが）同じ陣営のプレイヤーに送る。
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
     * ボットに関する知らせを送る。持ち主がいれば持ち主と今の指揮者にだけ（いなければ誰にも送らない）、
     * 持ち主のいないボット（通常はいない）なら同じ陣営のプレイヤー全員に送る。
     */
    public void sendToAudience(SoldierBot bot, Component msg) {
        if (bot.owner != null) {
            Set<UUID> targets = new LinkedHashSet<>();
            targets.add(bot.owner);
            UUID cmd = commanderOf(bot);
            if (cmd != null) {
                targets.add(cmd);
            }
            for (UUID id : targets) {
                Player t = Bukkit.getPlayer(id);
                if (t != null && t.isOnline()) {
                    t.sendMessage(msg);
                }
            }
            return;
        }
        for (Player p : plugin.teams().humans(bot.side)) {
            p.sendMessage(msg);
        }
    }

    // ---- 場所 ----

    /** 陣営のリスポーン地点。未設定なら null。 */
    public Location spawnLocation(Side side) {
        Point p = plugin.data().spawns().get(side);
        return p == null ? null : p.toLocation();
    }

    /** 「守れ」の横隊が向く向きを決める（指示した人が見ていた向き）。 */
    public void setHoldFacing(java.util.Collection<SoldierBot> targets, float yaw) {
        for (SoldierBot b : targets) {
            b.holdYaw = yaw;
        }
    }

    // ---- 毎回の行動 ----

    public void tick() {
        tickCount++;
        if (tickCount % 4 == 0) {
            syncPersonalBots();
        }
        long now = Bukkit.getCurrentTick();
        // 自動の回復: regen-interval-seconds ごとに regen-amount ずつ（最大体力を超えない）。陣営ごとの設定で別々に数える
        Map<Side, Boolean> regenOf = new java.util.EnumMap<>(Side.class);
        for (Side side : Side.values()) {
            Settings rs = plugin.settings(side);
            boolean due = rs.regenAmount > 0 && now - lastRegenTick.getOrDefault(side, 0L) >= rs.regenIntervalTicks;
            if (due) {
                lastRegenTick.put(side, now);
            }
            regenOf.put(side, due);
        }
        TextColor attackColor = plugin.teams().colorOf(Side.ATTACK);
        TextColor defendColor = plugin.teams().colorOf(Side.DEFEND);
        Formation.Layout layout = plugin.formation().computeLayout(); // 全ボットの持ち場を 1 回だけ出す
        for (SoldierBot bot : bots.values()) {
            // 倒れているボットも含めて、期限切れの除外を掃除する（unreachableStrikes は指数の段を保つので残す）
            bot.unreachableTargets.values().removeIf(until -> until <= now);
            bot.lineBlockedTargets.values().removeIf(until -> until <= now);
            try {
                Settings s = plugin.settings(bot.side);
                if (regenOf.get(bot.side) && bot.npc.isSpawned() && bot.npc.getEntity() instanceof LivingEntity le
                        && !le.isDead() && le.getHealth() < le.getMaxHealth()
                        && now - bot.lastDamagedTick >= s.regenDelayTicks) {
                    le.heal(s.regenAmount, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.REGEN);
                }
                tickBot(bot, layout, now);
                nameTags.update(bot, bot.side == Side.ATTACK ? attackColor : defendColor);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("ボット " + bot.name + " の処理で例外: " + e);
            }
        }
    }

    private void tickBot(SoldierBot bot, Formation.Layout layout, long now) {
        NPC npc = bot.npc;
        if (!npc.isSpawned()) {
            bot.target = null;
            bot.retreatUntilTick = 0; // 倒れたら退却も終わる
            bot.holdingSlot = false;
            resetRuntimeState(bot);
            return; // 倒れたボットは自動では復活しない（/sb revive で出し直す）
        }
        if (!(npc.getEntity() instanceof LivingEntity self)) {
            return;
        }
        Settings s = plugin.settings(bot.side);
        Formation.Anchor slot = layout.anchorFor(bot);
        Location anchor = slot != null && slot.location().getWorld() == self.getWorld() ? slot.location() : null;
        boolean inFormation = anchor != null && slot.inFormation();
        Navigator nav = npc.getNavigator();
        navigationWatch.check(bot, nav, self, anchor, inFormation, s, now);

        // 退却中: 持ち場が決められない・着いたら終わり（期限が過ぎれば retreating が false になる）。続くなら戦わずに戻る
        if (bot.retreating(now)) {
            bot.backingOff = false;
            bot.backoffStartTick = 0;
            bot.holdingSlot = false;
            if (anchor == null) {
                bot.retreatUntilTick = 0;
            } else if (arrived(bot, self, anchor, inFormation, s)) {
                bot.retreatUntilTick = 0; // 着いても知らせない
            } else {
                bot.target = null;
                if (nav.getEntityTarget() != null) {
                    nav.cancelNavigation();
                }
                walkTo(bot, nav, self, anchor, inFormation, now);
                return;
            }
        }

        LivingEntity target = pickTarget(bot, self, anchor, now);
        bot.target = target;
        if (target != null) {
            bot.holdingSlot = false;
            if (plugin.combatOf(bot.type).tickCombatMove(bot, nav, self, target, anchor, s, now)) {
                return;
            }
        }
        // 戦闘の動きで抜けなかった = 後退は終わっている（非戦闘の歩きを BACKOFF と誤分類しない）
        bot.backingOff = false;
        bot.backoffStartTick = 0;
        if (nav.getEntityTarget() != null) {
            nav.cancelNavigation();
        }
        if (anchor == null) {
            bot.holdingSlot = false;
            return;
        }
        double distSq = self.getLocation().distanceSquared(anchor);
        if (inFormation) {
            // 横隊: 自分の位置に着いたら止まる。着いたあとは少し離れるまで動き直さない（縁で行ったり来たりしない）
            double enterRadius = Formation.FORMATION_ARRIVE;
            double exitRadius = Math.max(Formation.FORMATION_ARRIVE + 0.4, s.separationEnabled ? s.separationMinDistance : 0.0);
            bot.holdingSlot = updateHoldingSlot(bot.holdingSlot, distSq, enterRadius, exitRadius);
            if (bot.holdingSlot) {
                if (nav.isNavigating()) {
                    nav.cancelNavigation();
                }
                return;
            }
        } else {
            bot.holdingSlot = false;
            if (bot.order.mode() == Order.Mode.FOLLOW) {
                if (distSq <= s.followDistance * s.followDistance) {
                    if (nav.isNavigating()) {
                        nav.cancelNavigation();
                    }
                    return;
                }
            } else {
                // 単独で地点を守る: 着いたとみなす距離は、ボットの間隔より狭くしない。
                // 歩いている途中は止めない（Citizens に持ち場の中心まで歩き切らせる）
                double holdArrive = Math.max(s.arriveRadius, s.separationEnabled ? s.separationMinDistance : 0.0);
                if (distSq <= holdArrive * holdArrive) {
                    return;
                }
            }
        }
        walkTo(bot, nav, self, anchor, inFormation, now);
    }

    /** 倒れた・出ていないボットの、詰まりの見張り・後退・盾の停止まわりの状態を捨てる（復活後に持ち越さない）。 */
    private static void resetRuntimeState(SoldierBot bot) {
        NavigationWatch.clearTracking(bot);
        bot.backingOff = false;
        bot.backoffStartTick = 0;
        bot.backoffCooldownUntilTick = 0;
        bot.fineBypassUntilTick = 0;
        bot.unreachableTargets.clear();
        bot.unreachableStrikes.clear();
        bot.lineBlockedTargets.clear();
        bot.allyBlockedSinceTick = -1;
        bot.shieldStopping = false;
        bot.aimStopStartTick = -1;
        bot.shieldAimStopCooldownUntilTick = 0;
    }

    /**
     * 横隊の自分の位置に着いている状態か。着いていない間は enterRadius 以内に入ったとき、着いている間は
     * exitRadius 以内にいる間だけ true（exitRadius を大きくして、押し出されても歩き直さない）。
     */
    static boolean updateHoldingSlot(boolean currentlyHolding, double distSq, double enterRadius, double exitRadius) {
        double r = currentlyHolding ? exitRadius : enterRadius;
        return distSq <= r * r;
    }

    /** 退却で持ち場に着いたか（追従なら follow-distance 以内、地点を守るなら arrive-radius 以内。横隊なら自分の位置に寄る）。 */
    private static boolean arrived(SoldierBot bot, LivingEntity self, Location anchor, boolean inFormation, Settings s) {
        // 横隊なら自分の位置にきちんと寄る（重ならないように）
        double arrive = inFormation ? Formation.FORMATION_ARRIVE
                : bot.order.mode() == Order.Mode.FOLLOW ? s.followDistance : s.arriveRadius;
        return self.getLocation().distanceSquared(anchor) <= arrive * arrive;
    }

    /** 持ち場へ歩かせる。目的地が変わったときだけ経路を引き直す（詰まりの復旧は {@link NavigationWatch}）。 */
    private void walkTo(SoldierBot bot, Navigator nav, LivingEntity self, Location anchor, boolean inFormation,
                        long now) {
        Location here = self.getLocation();
        Location dest = nav.isNavigating() ? nav.getTargetAsLocation() : null;
        // 横隊の位置のすぐ近く（3 ブロック以内）なら、経路探索を使わずにまっすぐ寄る
        // （経路探索は目的地の 1 ブロックほど手前で「着いた」として止まるので、隣と重なる）
        boolean fine = inFormation && now >= bot.fineBypassUntilTick && here.getWorld() == anchor.getWorld() && here.distanceSquared(anchor) < 9.0;
        double tolerance = fine ? 0.25 : 4.0;
        if (dest == null || dest.getWorld() != anchor.getWorld() || dest.distanceSquared(anchor) > tolerance) {
            if (fine) {
                nav.setStraightLineTarget(anchor);
                nav.getLocalParameters().distanceMargin(0.3);
            } else {
                nav.setTarget(anchor);
            }
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
        Settings s = plugin.settings(bot.side);
        double leash = bot.order != null && bot.order.stance() == Order.Stance.PUSH ? s.pushLeash : s.guardLeash;
        boolean currentValid = bot.target != null && validTarget(bot, self, bot.target, anchor, leash)
                && !isUnreachableBlocked(bot, self, bot.target, now, s)
                && !plugin.combatOf(bot.type).isChaseBlocked(bot, self, bot.target, now, s);
        if (currentValid && now - bot.lastRetargetTick < s.retargetTicks) {
            return bot.target;
        }
        bot.lastRetargetTick = now;
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : plugin.teams().enemies(bot.side)) {
            if (!validTarget(bot, self, e, anchor, leash) || isUnreachableBlocked(bot, self, e, now, s)
                    || plugin.combatOf(bot.type).isChaseBlocked(bot, self, e, now, s)) {
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

    /**
     * 追っても着けなかった敵（{@link SoldierBot#unreachableTargets}）で、今は狙わないか。
     * 期限の間でも、攻撃・射撃が届く位置にいる敵は外さない。
     */
    private static boolean isUnreachableBlocked(SoldierBot bot, LivingEntity self, LivingEntity enemy, long now,
                                                Settings s) {
        Long until = bot.unreachableTargets.get(enemy.getUniqueId());
        if (until == null || now >= until) {
            return false;
        }
        if (!self.hasLineOfSight(enemy)) {
            return true;
        }
        boolean inReach = switch (bot.type) {
            case ARCHER -> self.getLocation().distance(enemy.getLocation()) <= s.archerRange;
            case SPEARMAN -> Formation.reachDistance(self, enemy) <= s.unit(UnitType.SPEARMAN).attackRange;
            default -> self.getLocation().distance(enemy.getLocation()) <= s.unit(UnitType.SOLDIER).attackRange;
        };
        return !inReach;
    }

    private boolean validTarget(SoldierBot bot, LivingEntity self, LivingEntity e, Location anchor, double leash) {
        if (e == self || !e.isValid() || e.isDead() || e.getWorld() != self.getWorld()) {
            return false;
        }
        if (plugin.teams().sideOf(e) != bot.side.opposite()) {
            return false;
        }
        if (e instanceof Player p && byEntity(p) == null
                && (!p.isOnline() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
            return false;
        }
        Settings s = plugin.settings(bot.side);
        double d2 = e.getLocation().distanceSquared(self.getLocation());
        // 弓兵は、見えて units.archer.range 以内の敵なら、構え（積極的・持ち場優先）や持ち場からの距離に関係なく撃つ。
        // それ以外の敵（見えない・遠い）は剣兵と同じ条件で狙い、近づくのは持ち場から leash の内だけ（Archers.tickCombatMove）
        double ranged = s.rangedEngageRadius(bot.type);
        if (ranged > 0 && d2 <= ranged * ranged && self.hasLineOfSight(e)) {
            return true;
        }
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
}
