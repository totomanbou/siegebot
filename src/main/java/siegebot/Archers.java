package siegebot;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.citizensnpcs.api.ai.EntityTarget;
import net.citizensnpcs.api.ai.Navigator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * 弓兵が矢を撃つ。{@link #TICK_PERIOD} tick ごとに、狙い（{@link SoldierBot#target}）が見えて届く弓兵について:
 * 弓を引き（draw-ticks）、引き切ったら、狙いの動きを見越して矢が届く仰角を {@link Ballistics} で求めて撃つ。
 * 間に味方がいるときは撃たずに引いたまま待つ。退却中・狙いがないときは弓を下ろす。
 * 先読みは units.archer.aim.lead-factor 倍に弱められ、狙いの向きには反応の遅れ（aim.reaction-ticks）が掛かる
 * （{@link Aim}。弾道は {@link #TICK_PERIOD} tick ごとの更新なので、発射時の実効の遅れは 2〜4 tick）。
 *
 * <p>近づく・止まる・下がるの動き（{@link #tickCombatMove}）は {@link BotManager} から 10 tick ごとに呼ぶ。メインスレッドからだけ呼ぶ。
 */
final class Archers implements UnitCombat {
    static final int TICK_PERIOD = 2;
    /** 引き切ったあと味方が射線にいる状態がこれ（tick）続いたら、その敵を一時的に狙わず、別の狙いを探す。 */
    static final int ALLY_BLOCK_TIMEOUT_TICKS = 16;
    /** 味方の射線被りで外した敵を、狙いに選ばない時間（tick）。 */
    static final int LINE_BLOCK_COOLDOWN_TICKS = 30;
    /** 弾道を計算する最長の時間（tick）。これより長く飛ぶ所は届かないとみなす。 */
    private static final int MAX_FLIGHT_TICKS = 100;
    /** 狙う高さ（相手の背の高さに対する割合）。胴の真ん中あたり。 */
    private static final double AIM_HEIGHT = 0.6;
    /** 味方を気にせず撃つ、自分のすぐそばの距離（ブロック）。 */
    private static final double ALLY_IGNORE_NEAR = 1.2;

    private final SiegeBotPlugin plugin;

    Archers(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    void tick() {
        long now = Bukkit.getCurrentTick();
        for (SoldierBot bot : plugin.bots().all()) {
            if (bot.type != UnitType.ARCHER || !bot.npc.isSpawned() || !(bot.npc.getEntity() instanceof Player p)) {
                continue;
            }
            try {
                tickBot(bot, p, plugin.settings(bot.side), now);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("弓兵 " + bot.name + " の処理で例外: " + e);
            }
        }
    }

    /** 弓を下ろす（全員。設定の読み直しのとき）。 */
    void lowerAll() {
        for (SoldierBot bot : plugin.bots().all()) {
            if (bot.npc.getEntity() instanceof Player p) {
                lower(bot, p);
            }
        }
    }

    private void tickBot(SoldierBot bot, Player p, Settings s, long now) {
        LivingEntity t = bot.target;
        trackVelocity(bot, t, now);
        boolean ok = t != null && t.isValid() && !t.isDead() && t.getWorld() == p.getWorld() && !bot.retreating(now)
                && p.getLocation().distance(t.getLocation()) <= s.archerRange + 1.0 && p.hasLineOfSight(t)
                && !isLineBlocked(bot, t, now);
        // 近すぎる敵から下がっている途中は撃たない。プレイヤーの姿の NPC は向いている方へ歩くので、
        // 敵の方を向かせると下がるはずが敵へ歩いてしまう（試験で確かめた）
        if (bot.backingOff && bot.npc.getNavigator().isNavigating()) {
            ok = false;
        }
        Vector dir = ok ? aim(bot, p, t, s) : null;
        bot.aimWant = dir; // 照準（Aim）はこの向きへ回る
        bot.aimWantTarget = dir == null ? null : t;
        if (!ok || dir == null) {
            bot.allyBlockedSinceTick = -1;
        }
        if (dir == null) {
            lower(bot, p);
            return;
        }
        Vector sight = Aim.direction(bot, p, true); // 揺れを含めた照準
        bot.npc.faceLocation(p.getEyeLocation().add(sight.clone().multiply(8)));
        if (bot.drawStartTick < 0) {
            bot.allyBlockedSinceTick = -1;
            if (now >= bot.nextDrawTick) {
                p.startUsingItem(EquipmentSlot.HAND);
                bot.drawStartTick = now;
            }
            return;
        }
        // 反応の遅れを掛けた向き（相手が切り替わった直後は今の向き）。照準がこれに来ていて、遅れによるずれも小さいときだけ撃つ
        Vector targetDir = Aim.archerTargetDir(bot.aimTargetDir, bot.aimHistoryTarget, t, dir);
        if (now - bot.drawStartTick < s.archerDrawTicks) {
            bot.allyBlockedSinceTick = -1;
            return; // 引いている途中
        }
        boolean allyBlocked = allyInLine(bot, p, t, sight) || allyInLine(bot, p, t, dir);
        if (allyBlocked) {
            // 間に味方がいる: 引いたまま待つが、長く続けば、この敵を少しのあいだ外して別の狙いを探す
            if (bot.allyBlockedSinceTick < 0) {
                bot.allyBlockedSinceTick = now;
            }
            if (shouldReleaseAllyBlock(bot.allyBlockedSinceTick, now)) {
                bot.lineBlockedTargets.put(t.getUniqueId(), now + LINE_BLOCK_COOLDOWN_TICKS);
                lower(bot, p);
                bot.allyBlockedSinceTick = -1;
                bot.lastRetargetTick = 0;
                plugin.bots().navigationWatch().recordSimple(bot, NavigationWatch.Reason.ALLY_LINE,
                        "target=" + t.getName(), now);
            }
            return;
        }
        bot.allyBlockedSinceTick = -1;
        if (Aim.shouldHoldFire(Aim.error(bot, p, targetDir), Aim.angleDeg(targetDir, dir))) {
            return; // 照準が狙いに来ていない（引いたまま待つ）
        }
        shoot(p, sight, s);
        if (plugin.combatDebug().isEnabled()) { // /sb debug kb の間は、撃った矢もログに出す
            plugin.combatDebug().logShoot(bot, t, p.getLocation().distance(t.getLocation()), now);
        }
        lower(bot, p);
        bot.nextDrawTick = now + s.archerShotIntervalTicks;
    }

    /** 味方の射線被りで、今は狙いに選ばない敵か。 */
    static boolean isLineBlocked(SoldierBot bot, LivingEntity enemy, long now) {
        Long until = bot.lineBlockedTargets.get(enemy.getUniqueId());
        return until != null && now < until;
    }

    /** 味方が射線にいる状態が {@link #ALLY_BLOCK_TIMEOUT_TICKS} 続いたか（blockedSinceTick が -1 なら起きていない）。 */
    static boolean shouldReleaseAllyBlock(long blockedSinceTick, long now) {
        return blockedSinceTick >= 0 && now - blockedSinceTick >= ALLY_BLOCK_TIMEOUT_TICKS;
    }

    private static void lower(SoldierBot bot, Player p) {
        if (bot.drawStartTick >= 0) {
            bot.drawStartTick = -1;
            if (p.hasActiveItem() && p.getActiveItemHand() == EquipmentSlot.HAND) {
                p.clearActiveItem(); // 矢を持たせていないので、下ろしても勝手には撃たない
            }
        }
    }

    /**
     * 狙いの動きを見積もる（{@link #TICK_PERIOD} ごとの位置の差から）。上下の動き（跳ぶ・落ちる）は見越さない。
     * プレイヤーは getVelocity がサーバーではほぼ 0 なので、位置の差で測る。
     */
    private static void trackVelocity(SoldierBot bot, LivingEntity t, long now) {
        if (t == null) {
            bot.aimLastTarget = null;
            bot.aimVelocity = new Vector();
            return;
        }
        Location cur = t.getLocation();
        long dt = now - bot.aimLastTick;
        if (bot.aimLastTarget == t && bot.aimLastPos != null && bot.aimLastPos.getWorld() == cur.getWorld()
                && dt > 0 && dt <= 10) {
            Vector v = cur.toVector().subtract(bot.aimLastPos.toVector()).multiply(1.0 / dt).setY(0);
            if (v.lengthSquared() > 1.0) {
                v = new Vector(); // 1 tick に 1 ブロックより速いのは瞬間移動とみなす
            }
            bot.aimVelocity = bot.aimVelocity.multiply(0.5).add(v.multiply(0.5));
        } else {
            bot.aimVelocity = new Vector();
        }
        bot.aimLastTarget = t;
        bot.aimLastPos = cur;
        bot.aimLastTick = now;
    }

    /**
     * 矢を放つ向き（単位ベクトル）。届かなければ null。飛ぶ時間の分だけ狙いの動きを見越すが、
     * 見越す量は units.archer.aim.lead-factor 倍（1.0 で正確、0.0 で見越さない）。
     * ここで求めた向きに反応の遅れ（aim.reaction-ticks）を掛けるのは {@link Aim} で、発射時は
     * {@link Aim#archerTargetDir} と {@link Aim#FIRE_MAX_LAG_ANGLE} で確かめる。
     */
    private static Vector aim(SoldierBot bot, Player p, LivingEntity t, Settings s) {
        Vector from = p.getEyeLocation().toVector();
        Vector body = t.getLocation().toVector().add(new Vector(0, t.getHeight() * AIM_HEIGHT, 0));
        double ticks = 0;
        Ballistics.Solution sol = null;
        Vector point = body;
        for (int i = 0; i < 3; i++) {
            point = body.clone().add(bot.aimVelocity.clone().multiply(ticks * s.archerLeadFactor));
            double dx = Math.hypot(point.getX() - from.getX(), point.getZ() - from.getZ());
            sol = Ballistics.solve(dx, point.getY() - from.getY(), s.archerArrowSpeed, MAX_FLIGHT_TICKS);
            if (sol == null) {
                return null;
            }
            ticks = sol.ticks();
        }
        double[] d = Ballistics.direction(point.getX() - from.getX(), point.getZ() - from.getZ(), sol.pitch());
        return new Vector(d[0], d[1], d[2]);
    }

    /**
     * 狙いまでのまっすぐな線の近くに味方（同じ陣営のプレイヤー・ボット）がいるか。
     * 自分のすぐそば（{@link #ALLY_IGNORE_NEAR} 以内）の味方は数えない（隣や同じ所に立つ味方で撃てなくならないように）。
     */
    private boolean allyInLine(SoldierBot bot, Player p, LivingEntity t, Vector dir) {
        Location eye = p.getEyeLocation();
        double dist = eye.distance(t.getLocation());
        if (dist <= ALLY_IGNORE_NEAR) {
            return false;
        }
        Location start = eye.clone().add(dir.clone().normalize().multiply(ALLY_IGNORE_NEAR));
        RayTraceResult hit = p.getWorld().rayTraceEntities(start, dir, dist - ALLY_IGNORE_NEAR, 0.4,
                e -> e != p && e != t && e instanceof LivingEntity && plugin.teams().sideOf(e) == bot.side
                        && e.getLocation().distanceSquared(p.getLocation()) > ALLY_IGNORE_NEAR * ALLY_IGNORE_NEAR);
        return hit != null && hit.getHitEntity() != null;
    }

    private static void shoot(Player p, Vector dir, Settings s) {
        // バニラの弓と同じぶれ: 向きの各成分にガウス分布 × 0.0075 × inaccuracy を足す
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double k = 0.0075 * s.archerInaccuracy;
        Vector v = dir.clone().normalize()
                .add(new Vector(r.nextGaussian() * k, r.nextGaussian() * k, r.nextGaussian() * k))
                .multiply(s.archerArrowSpeed);
        Arrow arrow = p.launchProjectile(Arrow.class, v);
        arrow.setDamage(s.archerArrowDamage);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED); // 拾って使えない
        arrow.setCritical(s.archerDrawTicks >= 20); // プレイヤーの弓と同じく、引き切った矢は会心（ダメージが少し増える）
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0f,
                (float) (1.0 / (r.nextDouble() * 0.4 + 1.2) + 0.5));
    }

    /**
     * 弓兵の動き（撃つのは {@link #tick} が 2 tick ごとに行う）。
     * <ul>
     *   <li>見えていて units.archer.range 以内: 止まって撃つ。min-distance より近ければ、keep-distance まで下がる。</li>
     *   <li>見えない・遠い: 近づく（殴らない）。ただし敵が持ち場から leash（構えで決まる）の外なら近づかない。</li>
     * </ul>
     *
     * @return 動きを決めたら true。false なら持ち場へ戻る動き（敵がいないときと同じ）に任せる
     */
    @Override
    public boolean tickCombatMove(SoldierBot bot, Navigator nav, LivingEntity self, LivingEntity target, Location anchor,
                                  Settings s, long now) {
        Location here = self.getLocation();
        Location t = target.getLocation();
        double d = here.distance(t);
        boolean los = self.hasLineOfSight(target);
        if (los && d <= s.archerRange) {
            // 近すぎる敵から下がるのは積極的のときだけ。持ち場優先では逃げずに、その場で撃ち続ける
            boolean guard = bot.order != null && bot.order.stance() == Order.Stance.GUARD;
            boolean canBackOff = now >= bot.backoffCooldownUntilTick;
            if (canBackOff && d < s.archerMinDistance && !guard) {
                Location away = Formation.retreatSpot(here, t, s.archerKeepDistance - d,
                        plugin.bots().aliveLocations(List.of(bot)), s.separationEnabled ? s.separationMinDistance : 0.0, bot.name);
                if (away != null) {
                    if (!bot.backingOff || !nav.isNavigating()) {
                        bot.backoffStartTick = now;
                    }
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
            boolean keepBacking = canBackOff && bot.backingOff && nav.isNavigating() && d < s.archerKeepDistance;
            if (!keepBacking) {
                bot.backingOff = false;
                bot.backoffStartTick = 0;
                if (nav.isNavigating()) {
                    nav.cancelNavigation();
                }
            }
            return true;
        }
        bot.backingOff = false;
        bot.backoffStartTick = 0;
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

    /** 味方の射線被りで一時的に外している敵は、狙いに選ばない。 */
    @Override
    public boolean isChaseBlocked(SoldierBot bot, LivingEntity self, LivingEntity enemy, long now, Settings s) {
        return isLineBlocked(bot, enemy, now);
    }

    /** /sb debug の表示用。引いている / 待つ / なし。 */
    String describe(SoldierBot bot) {
        if (bot.type != UnitType.ARCHER) {
            return null;
        }
        long now = Bukkit.getCurrentTick();
        if (bot.drawStartTick >= 0) {
            // using: 弓を使っている状態がゲームの側にも見えているか（相手の盾はこれを見て構える）
            boolean using = bot.npc.getEntity() instanceof Player p && p.hasActiveItem();
            return "引いている(" + (now - bot.drawStartTick) + "t,using=" + using + ")";
        }
        return bot.target == null ? "なし" : "待つ";
    }
}
