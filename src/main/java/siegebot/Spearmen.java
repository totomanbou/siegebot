package siegebot;

import java.util.List;
import java.util.Set;
import net.citizensnpcs.api.ai.EntityTarget;
import net.citizensnpcs.api.ai.Navigator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * 槍兵が届くぎりぎりの所から突く。毎 tick、狙い（{@link SoldierBot#target}）について:
 * 届けば（目から相手の箱まで units.spearman.attack-range 以内）相手を向いて attack-delay-ticks ごとに突き、
 * 近づいている途中なら、届く距離より stop-inside だけ内側に入った所で止める（行動の判断の 10 tick を待つと寄りすぎる）。
 * 下がっている途中・退却中・盾を構えている間は突かない（盾は {@link Shields}。槍兵は殴り合いの最中も飛んでくる矢を防ぐ）。
 *
 * <p>近づく・下がるの判断（{@link #tickCombatMove}）は {@link BotManager} から 10 tick ごとに呼ぶ。メインスレッドからだけ呼ぶ。
 */
final class Spearmen implements UnitCombat {
    /** 毎 tick（2 tick ごとだと、歩く速さで 0.5 ブロックほど寄りすぎてから止まった）。 */
    static final int TICK_PERIOD = 1;
    /** 蜘蛛の巣のある道を追っていないか調べる間隔（tick）。 */
    static final int CHASE_CHECK_PERIOD = 2;
    static final int WEB_BLOCK_TICKS = 40;

    private final SiegeBotPlugin plugin;

    Spearmen(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    void tick() {
        long now = Bukkit.getCurrentTick();
        for (SoldierBot bot : plugin.bots().all()) {
            if (bot.type != UnitType.SPEARMAN || !bot.npc.isSpawned() || !(bot.npc.getEntity() instanceof Player p)) {
                continue;
            }
            try {
                tickBot(bot, p, plugin.settings(bot.side), now);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("槍兵 " + bot.name + " の処理で例外: " + e);
            }
        }
    }

    private void tickBot(SoldierBot bot, Player p, Settings s, long now) {
        LivingEntity t = bot.target;
        if (t == null || !t.isValid() || t.isDead() || t.getWorld() != p.getWorld() || bot.retreating(now)) {
            return;
        }
        Navigator nav = bot.npc.getNavigator();
        if (bot.backingOff && nav.isNavigating()) {
            return; // 下がっている途中は向かない（プレイヤーの姿の NPC は向いている方へ歩く）
        }
        if (bot.shieldUp || (p.hasActiveItem() && p.getActiveItemHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND)) {
            return; // 盾で防いでいる間は突かない・相手を向かない（盾は向いている側しか防げない）
        }
        Settings.Unit unit = s.unit(UnitType.SPEARMAN);
        double d = Formation.reachDistance(p, t);
        if (d > unit.attackRange || !p.hasLineOfSight(t)) {
            return;
        }
        if (nav.isNavigating() && nav.getEntityTarget() != null && d <= unit.attackRange - s.spearStopInside) {
            nav.cancelNavigation(); // 届くぎりぎりの所で止まる
        }
        // 照準（Aim。回る速さに上限があり、揺れる）の方を向く
        bot.npc.faceLocation(p.getEyeLocation().add(Aim.direction(bot, p, true).multiply(4)));
        if (now - bot.lastSpearAttackTick >= unit.attackDelayTicks) {
            bot.lastSpearAttackTick = now;
            p.swingMainHand();
            boolean hit = Aim.meleeHits(bot, p, t, unit.attackRange + 0.3);
            if (hit) {
                net.citizensnpcs.util.NMS.attack(p, t);
            }
            plugin.combatDebug().logThrust(bot, t, hit, d, p, now); // /sb debug kb の間は、突いたこともログに出す
        }
    }

    /**
     * 槍兵の動き（突くのと、近づいて届いたら止まるのは {@link #tick} が 2 tick ごとに行う）。
     * <ul>
     *   <li>届かない・見えない: 近づく（Citizens の追跡。殴らない）。</li>
     *   <li>届く: 止まる。届く距離より back-off-inside 以上内側まで寄られたら、届くぎりぎりまで下がる。</li>
     * </ul>
     * 届くかは、目から相手の当たり判定の箱までの距離（{@link Formation#reachDistance}）で比べる（プレイヤーのリーチと同じ測り方）。
     */
    @Override
    public boolean tickCombatMove(SoldierBot bot, Navigator nav, LivingEntity self, LivingEntity target,
                                  Location anchor, Settings s, long now) {
        if (bot.shieldStopping) {
            return true; // 盾で矢を防いでいる間は動かない（Shields が止めて矢の方を向かせている）
        }
        if (webBlocked(bot, target, Bukkit.getCurrentTick())) {
            // 追う道に蜘蛛の巣がある敵: 近づかない（届けば Spearmen がその場で突く。pickTarget は届くときだけこの敵を選ぶ）
            if (nav.getEntityTarget() != null) {
                nav.cancelNavigation();
            }
            bot.backingOff = false;
            bot.backoffStartTick = 0;
            return true;
        }
        double reach = s.unit(UnitType.SPEARMAN).attackRange;
        double d = Formation.reachDistance(self, target);
        if (d <= reach && self.hasLineOfSight(target)) {
            boolean canBackOff = now >= bot.backoffCooldownUntilTick;
            if (canBackOff && d < reach - s.spearBackOffInside) {
                Location away = Formation.retreatSpot(self.getLocation(), target.getLocation(), reach - s.spearStopInside - d,
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
                // 下がる場所がなければ、その場で突く
            }
            boolean keepBacking = canBackOff && bot.backingOff && nav.isNavigating() && d < reach - s.spearStopInside;
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
        EntityTarget cur = nav.getEntityTarget();
        if (cur == null || cur.getTarget() != target || cur.isAggressive() || !nav.isNavigating()) {
            nav.setTarget(target, false); // 近づくだけ。突くのは Spearmen
        }
        return true;
    }

    /** 追う道に蜘蛛の巣があったので、今は追わない敵か。 */
    static boolean webBlocked(SoldierBot bot, LivingEntity e, long now) {
        Long until = bot.webBlocked.get(e.getUniqueId());
        return until != null && now < until;
    }

    /** 追う道に巣があったので今は追わず、届く距離にもいない敵か（狙いに選ばない）。 */
    @Override
    public boolean isChaseBlocked(SoldierBot bot, LivingEntity self, LivingEntity enemy, long now, Settings s) {
        return webBlocked(bot, enemy, now) && Formation.reachDistance(self, enemy) > s.unit(bot.type).attackRange;
    }

    /**
     * 槍兵が敵を追っている道（Citizens が引いた経路。近い相手で経路がなければ相手までの直線）に蜘蛛の巣があれば、
     * 追うのをやめて、その敵を {@link #WEB_BLOCK_TICKS} の間は追わない。{@link #CHASE_CHECK_PERIOD} tick ごとに呼ぶ。
     */
    void checkChasePaths() {
        long now = Bukkit.getCurrentTick();
        for (SoldierBot bot : plugin.bots().all()) {
            bot.webBlocked.values().removeIf(until -> until <= now);
            if (!plugin.settings(bot.side).spearAvoidCobweb || bot.type != UnitType.SPEARMAN || !bot.npc.isSpawned()
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
}
