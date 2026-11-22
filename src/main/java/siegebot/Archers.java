package siegebot;

import java.util.concurrent.ThreadLocalRandom;
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
 *
 * <p>動き（近づく・止まる・下がる）は {@link BotManager} が決める。メインスレッドからだけ呼ぶ。
 */
final class Archers {
    static final int TICK_PERIOD = 2;
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
        Settings s = plugin.settings();
        long now = Bukkit.getCurrentTick();
        for (SoldierBot bot : plugin.bots().all()) {
            if (bot.type != UnitType.ARCHER || !bot.npc.isSpawned() || !(bot.npc.getEntity() instanceof Player p)) {
                continue;
            }
            try {
                tickBot(bot, p, s, now);
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
                && p.getLocation().distance(t.getLocation()) <= s.archerRange + 1.0 && p.hasLineOfSight(t);
        // 近すぎる敵から下がっている途中は撃たない。プレイヤーの姿の NPC は向いている方へ歩くので、
        // 敵の方を向かせると下がるはずが敵へ歩いてしまう（試験で確かめた）
        if (bot.archerBackingOff && bot.npc.getNavigator().isNavigating()) {
            ok = false;
        }
        Vector dir = ok ? aim(bot, p, t, s) : null;
        if (dir == null) {
            lower(bot, p);
            return;
        }
        bot.npc.faceLocation(p.getEyeLocation().add(dir.clone().multiply(8)));
        if (bot.drawStartTick < 0) {
            if (now >= bot.nextDrawTick) {
                p.startUsingItem(EquipmentSlot.HAND);
                bot.drawStartTick = now;
            }
            return;
        }
        if (now - bot.drawStartTick < s.archerDrawTicks || allyInLine(bot, p, t, dir)) {
            return; // 引いている途中か、間に味方がいる（引いたまま待つ）
        }
        shoot(p, dir, s);
        if (GameListener.logKnockback) { // /sb debug kb の間は、撃った矢もログに出す
            plugin.getLogger().info(String.format(java.util.Locale.ROOT, "[kb] t=%d SHOOT %s -> %s dist=%.1f",
                    now, bot.name, t.getName(), p.getLocation().distance(t.getLocation())));
        }
        lower(bot, p);
        bot.nextDrawTick = now + s.archerShotIntervalTicks;
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

    /** 矢を放つ向き（単位ベクトル）。届かなければ null。飛ぶ時間の分だけ狙いの動きを見越す。 */
    private static Vector aim(SoldierBot bot, Player p, LivingEntity t, Settings s) {
        Vector from = p.getEyeLocation().toVector();
        Vector body = t.getLocation().toVector().add(new Vector(0, t.getHeight() * AIM_HEIGHT, 0));
        double ticks = 0;
        Ballistics.Solution sol = null;
        Vector point = body;
        for (int i = 0; i < 3; i++) {
            point = body.clone().add(bot.aimVelocity.clone().multiply(ticks));
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
                e -> e != p && e != t && e instanceof LivingEntity && plugin.bots().sideOf(e) == bot.side
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
