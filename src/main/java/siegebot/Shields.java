package siegebot;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BlocksAttacks;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.citizensnpcs.api.ai.EntityTarget;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.NavigatorParameters;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

/**
 * ボットの盾（左手）の構え方。プレイヤーと同じく、弓・クロスボウで狙われたときと矢が飛んでくるときに構え、
 * 近接で殴っている最中は構えない。構えている間は遅く歩く。
 *
 * <p>構える・下ろすの判断は {@link #TICK_PERIOD} tick ごと。メインスレッドからだけ呼ぶ。
 */
final class Shields {

    /** 盾の判断の間隔（tick）。 */
    static final int TICK_PERIOD = 2;
    /** 近接で殴っているとみなす距離は、attack-range にこれを足したもの（ブロック）。 */
    private static final double MELEE_MARGIN = 1.0;
    /** これ以下の速さの矢は、飛んできているとみなさない。 */
    private static final double MIN_ARROW_SPEED = 0.3;
    /** 矢の向きと、矢から自分への向きの差がこの角度未満なら、自分に向かっているとみなす。 */
    private static final double COS_ARROW = Math.cos(Math.toRadians(20));
    /** 敵の視線の向きと、敵から自分への向きの差がこの角度未満なら、自分を狙っているとみなす。 */
    private static final double COS_AIM = Math.cos(Math.toRadians(30));

    private final SiegeBotPlugin plugin;

    Shields(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    /** 脅威。look は構えたときに向く地点、d2 は自分からの距離の 2 乗。 */
    private record Threat(Location look, double d2) {
    }

    /** 全ボットの盾を判断する。 */
    void tick() {
        Settings s = plugin.settings();
        long now = Bukkit.getCurrentTick();
        Map<Side, List<LivingEntity>> enemyCache = new EnumMap<>(Side.class);
        for (SoldierBot bot : plugin.bots().all()) {
            try {
                tickBot(bot, s, now, enemyCache);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("ボット " + bot.name + " の盾の処理で例外: " + e);
            }
        }
    }

    private void tickBot(SoldierBot bot, Settings s, long now, Map<Side, List<LivingEntity>> enemyCache) {
        Player p = playerOf(bot);
        if (p == null) {
            // 倒れた・消えた。構えたままの遅い歩きと向きの指定を戻す（復活後に持ち越さない）
            if (bot.shieldUp) {
                applyLowered(bot, s);
            }
            return;
        }
        if (!s.shieldEnabled || !hasShield(p) || bot.retreating(now)) {
            lower(bot, p, s); // 退却中は構えない（戦わずに戻る）
            return;
        }
        if (p.hasCooldown(Material.SHIELD)) {
            lower(bot, p, s); // 斧で盾を使えなくされた
            return;
        }
        // 殴り合いの最中は構えない。ただし槍兵は、飛んでくる矢（飛び道具）だけは殴り合いの最中も防ぐ（突くより優先）
        boolean melee = meleeBusy(bot, p, s);
        boolean projectilesOnly = melee && bot.type == UnitType.SPEARMAN;
        if (melee && !projectilesOnly) {
            lower(bot, p, s);
            return;
        }
        Threat threat = findThreat(bot, p, s, enemyCache.computeIfAbsent(bot.side, plugin.bots()::enemies),
                projectilesOnly);
        if (threat != null) {
            bot.lastThreatTick = now;
            bot.blockLook = threat.look();
        }
        boolean using = usingShield(p);
        // 槍兵は脅威がなくなったらすぐ下ろす（units.spearman.shield-lower-after-seconds、既定 0）
        int lowerAfter = bot.type == UnitType.SPEARMAN ? s.spearShieldLowerAfterTicks : s.shieldLowerAfterTicks;
        boolean want = threat != null
                || ((bot.shieldUp || using) && now - bot.lastThreatTick < lowerAfter);
        if (!want) {
            lower(bot, p, s);
            return;
        }
        if (!using) {
            applyBlockDelay(bot, p, s);
            p.startUsingItem(EquipmentSlot.OFF_HAND);
            using = usingShield(p);
        }
        if (using && !bot.shieldUp) {
            applyRaised(bot, s);
        } else if (!using && bot.shieldUp) {
            applyLowered(bot, s); // 斧などで盾が下ろされた。次の判断でもう一度構える
        }
        if (using && bot.blockLook != null && bot.type == UnitType.SPEARMAN) {
            // 槍兵は防ぐのを優先: 歩いていても止まって矢の方を向く（歩いている間は Citizens が歩く向きを向かせるため）
            if (bot.npc.getNavigator().isNavigating()) {
                bot.npc.getNavigator().cancelNavigation();
            }
            bot.npc.faceLocation(bot.blockLook);
        } else if (using && bot.blockLook != null && !bot.npc.getNavigator().isNavigating()) {
            bot.npc.faceLocation(bot.blockLook);
        }
    }

    /** 全ボットの盾を下ろし、速さと向きの指定を元に戻す（設定の読み直しの前に呼ぶ）。 */
    void lowerAll() {
        Settings s = plugin.settings();
        for (SoldierBot bot : plugin.bots().all()) {
            Player p = playerOf(bot);
            if (p != null) {
                lower(bot, p, s);
            } else if (bot.shieldUp) {
                applyLowered(bot, s);
            }
        }
    }

    /** /sb debug の表示用。構え / 構え中 / 下ろす / 使えない / なし。 */
    String describe(SoldierBot bot) {
        Player p = playerOf(bot);
        if (p == null || !hasShield(p)) {
            return "なし";
        }
        String state = usingShield(p) ? (p.isBlocking() ? "構え" : "構え中")
                : p.hasCooldown(Material.SHIELD) ? "使えない" : "下ろす";
        // 残りの耐久（盾は防ぐと削れて、0 で壊れる）
        ItemStack off = p.getInventory().getItemInOffHand();
        int max = off.getType().getMaxDurability();
        int dmg = off.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d ? d.getDamage() : 0;
        return state + "(耐久" + (max - dmg) + "/" + max + ")";
    }

    // ---- 構える・下ろす ----

    private void lower(SoldierBot bot, Player p, Settings s) {
        if (usingShield(p)) {
            p.clearActiveItem();
        }
        if (bot.shieldUp) {
            applyLowered(bot, s);
        }
    }

    /** 構えている間の設定: 遅く歩き、脅威の方を向く。 */
    private void applyRaised(SoldierBot bot, Settings s) {
        bot.shieldUp = true;
        float slow = (float) (s.unit(bot.type).speed * s.shieldMoveSpeedMultiplier);
        Function<Navigator, Location> look = lookFunction(bot);
        Navigator nav = bot.npc.getNavigator();
        NavigatorParameters def = nav.getDefaultParameters();
        def.speedModifier(slow);
        def.lookAtFunction(look);
        if (nav.isNavigating()) {
            NavigatorParameters local = nav.getLocalParameters();
            if (local != null) {
                local.speedModifier(slow);
                local.lookAtFunction(look);
            }
        }
    }

    /**
     * 下ろしたときの設定: 速さを戻し、既定の向きの指定（なし）に戻す。
     * 歩いている最中の経路には、今の相手・目的地の方を向く関数を残す（Citizens は関数があると
     * 経路が終わるまでその向きを使い続けるため）。
     */
    private void applyLowered(SoldierBot bot, Settings s) {
        bot.shieldUp = false;
        bot.blockLook = null;
        Navigator nav = bot.npc.getNavigator();
        NavigatorParameters def = nav.getDefaultParameters();
        def.speedModifier(s.unit(bot.type).speed);
        def.lookAtFunction(null);
        NavigatorParameters local = nav.getLocalParameters();
        if (local != null) {
            local.speedModifier(s.unit(bot.type).speed);
            if (!nav.isNavigating()) {
                local.lookAtFunction(null);
            }
        }
    }

    /**
     * Citizens の lookAtFunction。構えている間は脅威の地点、そうでなければ歩いている先（敵・目的地）。
     * Citizens の既定は null（関数なし）なので包む相手はいない。null を返すと Citizens が壊れるので、必ず場所を返す。
     */
    private static Function<Navigator, Location> lookFunction(SoldierBot bot) {
        return nav -> {
            Location look = bot.blockLook;
            if (bot.shieldUp && look != null) {
                return look;
            }
            EntityTarget t = nav.getEntityTarget();
            if (t != null && t.getTarget() != null) {
                Entity e = t.getTarget();
                return e instanceof LivingEntity le ? le.getEyeLocation() : e.getLocation();
            }
            Location dest = nav.getTargetAsLocation();
            if (dest != null) {
                return dest;
            }
            if (bot.npc.getEntity() instanceof LivingEntity self) {
                Location here = self.getEyeLocation();
                return here.clone().add(here.getDirection());
            }
            return bot.npc.getStoredLocation();
        };
    }

    /**
     * 盾の「構えてから防げるようになるまでの時間」（アイテムの blocks_attacks の block_delay_seconds。バニラは 0.25 秒）を
     * shield.blocking-delay-ticks に合わせる。ボットは気づいてから構えるので、0.25 秒待つと速い矢（引き切った弓）に
     * 間に合わず、盾を上げているのに当たって下がる。この版の防ぐまでの遅れはエンティティではなくアイテムが持つ。
     */
    private static void applyBlockDelay(SoldierBot bot, Player p, Settings s) {
        ItemStack shield = p.getInventory().getItemInOffHand();
        BlocksAttacks ba = shield.getData(DataComponentTypes.BLOCKS_ATTACKS);
        float want = s.shieldBlockingDelayTicks / 20f;
        if (ba == null || Math.abs(ba.blockDelaySeconds() - want) < 1e-4) {
            return;
        }
        BlocksAttacks.Builder b = BlocksAttacks.blocksAttacks()
                .blockDelaySeconds(want)
                .disableCooldownScale(ba.disableCooldownScale())
                .damageReductions(ba.damageReductions());
        if (ba.itemDamage() != null) {
            b.itemDamage(ba.itemDamage());
        }
        if (ba.bypassedBy() != null) {
            b.bypassedBy(ba.bypassedBy());
        }
        if (ba.blockSound() != null) {
            b.blockSound(ba.blockSound());
        }
        if (ba.disableSound() != null) {
            b.disableSound(ba.disableSound());
        }
        ItemStack changed = shield.clone();
        changed.setData(DataComponentTypes.BLOCKS_ATTACKS, b);
        // Citizens の装備にも入れる（入れないと Citizens が元の盾に戻す）
        bot.npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.Equipment.class)
                .set(net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.OFF_HAND, changed);
        p.getInventory().setItemInOffHand(changed);
    }

    // ---- 状態の判定 ----

    /** 生きて出ているボットのプレイヤーエンティティ。倒れている・出ていなければ null。 */
    private static Player playerOf(SoldierBot bot) {
        if (bot.npc.isSpawned() && bot.npc.getEntity() instanceof Player p && !p.isDead()) {
            return p;
        }
        return null;
    }

    private static boolean hasShield(Player p) {
        return p.getInventory().getItemInOffHand().getType() == Material.SHIELD;
    }

    /** 左手の盾を使っている（構えている）最中か。 */
    private static boolean usingShield(Player p) {
        return p.hasActiveItem() && p.getActiveItemHand() == EquipmentSlot.OFF_HAND
                && p.getActiveItem().getType() == Material.SHIELD;
    }

    /** 狙っている敵が、攻撃の届く距離 + 1 ブロック以内にいる（殴っている最中）。 */
    private static boolean meleeBusy(SoldierBot bot, Player p, Settings s) {
        LivingEntity t = bot.target;
        if (t == null || !t.isValid() || t.isDead() || t.getWorld() != p.getWorld()) {
            return false;
        }
        double r = s.unit(bot.type).attackRange + MELEE_MARGIN;
        return t.getLocation().distanceSquared(p.getLocation()) <= r * r;
    }

    // ---- 脅威の検出 ----

    /** いちばん近い脅威（飛んでくる矢・自分を狙う敵）。projectilesOnly なら飛んでくる矢だけ。なければ null。 */
    private Threat findThreat(SoldierBot bot, Player p, Settings s, List<LivingEntity> enemies,
                              boolean projectilesOnly) {
        double r = s.shieldDetectRadius;
        double r2 = r * r;
        Location here = p.getLocation();
        Location eye = p.getEyeLocation();
        Threat best = null;

        for (AbstractArrow a : p.getWorld().getNearbyEntitiesByType(AbstractArrow.class, here, r)) {
            if (a.isInBlock() || a.isOnGround()) {
                continue;
            }
            Location al = a.getLocation();
            double d2 = al.distanceSquared(here);
            if (d2 > r2 || (best != null && d2 >= best.d2())) {
                continue;
            }
            ProjectileSource src = a.getShooter();
            if (src instanceof Entity se
                    && (se.getUniqueId().equals(p.getUniqueId()) || plugin.bots().sideOf(se) == bot.side)) {
                continue; // 自分か味方が撃った矢
            }
            Vector vel = a.getVelocity();
            double speed = vel.length();
            if (speed <= MIN_ARROW_SPEED) {
                continue;
            }
            Vector toBot = eye.toVector().subtract(al.toVector());
            double dist = toBot.length();
            if (dist < 1e-6 || vel.dot(toBot) / (speed * dist) <= COS_ARROW) {
                continue;
            }
            // 撃った者が生き物ならその方を向く（盾は向いている側の攻撃しか防げない）。いなければ矢の位置
            Location look = src instanceof LivingEntity shooter ? shooter.getEyeLocation() : al;
            best = new Threat(look, d2);
        }

        for (LivingEntity e : projectilesOnly ? List.<LivingEntity>of() : enemies) {
            if (e.getWorld() != p.getWorld() || !e.isValid() || e.isDead()) {
                continue;
            }
            double d2 = e.getLocation().distanceSquared(here);
            if (d2 > r2 || (best != null && d2 >= best.d2())) {
                continue;
            }
            if (!isAimingBow(e)) {
                continue;
            }
            Location enemyEye = e.getEyeLocation();
            Vector toBot = eye.toVector().subtract(enemyEye.toVector());
            double dist = toBot.length();
            if (dist < 1e-6 || enemyEye.getDirection().dot(toBot) / dist <= COS_AIM) {
                continue;
            }
            if (!e.hasLineOfSight(p)) {
                continue;
            }
            best = new Threat(enemyEye, d2);
        }
        return best;
    }

    /** 弓・クロスボウ・トライデントを構えている、または装填済みのクロスボウを持っている。 */
    private static boolean isAimingBow(LivingEntity e) {
        if (e.hasActiveItem() && e.isHandRaised()) {
            Material m = e.getActiveItem().getType();
            if (m == Material.BOW || m == Material.CROSSBOW || m == Material.TRIDENT) {
                return true;
            }
        }
        EntityEquipment eq = e.getEquipment();
        return eq != null && (isLoadedCrossbow(eq.getItemInMainHand()) || isLoadedCrossbow(eq.getItemInOffHand()));
    }

    private static boolean isLoadedCrossbow(ItemStack item) {
        return item != null && item.getType() == Material.CROSSBOW
                && item.getItemMeta() instanceof CrossbowMeta cm && cm.hasChargedProjectiles();
    }
}
