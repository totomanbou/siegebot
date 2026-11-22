package siegebot;

import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * ボットのエイム（照準）。ボットごとに見えない照準（{@link SoldierBot#aimYaw}・{@link SoldierBot#aimPitch}）を持ち、
 * 毎 tick、狙う向きへ units.&lt;兵種&gt;.aim.turn-speed（度/tick）までしか回らない。照準は units.&lt;兵種&gt;.aim.sway（度）
 * ほどの大きさでゆっくり揺れ、照準を大きく回した tick ほど大きく揺れる（回した角度 × aim.turn-sway を足す。
 * 揺れは数 tick かけて元の大きさに戻るので、大きく振り向いた直後の攻撃・矢は外れやすい）。
 *
 * <ul>
 *   <li>剣兵・槍兵: 攻撃のたびに、揺れを含めた照準の線が相手の当たり判定の箱に入っていれば当たり、外れていれば空振り。
 *       相手が素早く横に回り込むと、照準が追いつくまで空振りする。</li>
 *   <li>弓兵: 引き切っても、照準が弾道の向きから {@link #FIRE_TOLERANCE} 度以内に来るまで撃たない。
 *       撃つ向きは揺れを含めた照準（その上にバニラの弓と同じぶれ）。</li>
 * </ul>
 * 狙う相手がいないときは、照準は体の向きに合わせる（新しい相手が後ろにいれば振り向くのに時間がかかる）。
 * メインスレッドからだけ呼ぶ。
 */
final class Aim {
    static final int TICK_PERIOD = 1;
    /** 揺れの残り方（1 tick ごとに前の揺れをこの割合だけ残す）。大きいほどゆっくり揺れる。 */
    static final double SWAY_KEEP = 0.85;
    /** 弓兵が撃つのは、照準（揺れを除く）が弾道の向きからこの角度（度）以内に来てから。 */
    static final double FIRE_TOLERANCE = 1.5;
    /** 剣兵・槍兵が照準を相手に向け続ける距離（ブロック）。これより遠い相手は体の向きに合わせる。 */
    static final double MELEE_TRACK_DISTANCE = 16.0;
    /** 当たり判定の箱を広げる量（ブロック。サーバーの位置の更新の粗さの分）。 */
    static final double BOX_MARGIN = 0.1;

    private final SiegeBotPlugin plugin;

    Aim(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    void tick() {
        Settings s = plugin.settings();
        for (SoldierBot bot : plugin.bots().all()) {
            if (!bot.npc.isSpawned() || !(bot.npc.getEntity() instanceof Player p)) {
                bot.aimSet = false;
                continue;
            }
            update(bot, p, s.unit(bot.type));
        }
    }

    private static void update(SoldierBot bot, Player p, Settings.Unit u) {
        Location eye = p.getEyeLocation();
        if (!bot.aimSet) {
            bot.aimYaw = eye.getYaw();
            bot.aimPitch = eye.getPitch();
            bot.aimSet = true;
        }
        Vector want = wantDirection(bot, p, eye);
        double moved = 0; // 狙って回した角度（相手がいないときに体の向きへ合わせるのは数えない）
        if (want == null) {
            bot.aimYaw = eye.getYaw(); // 狙う相手がいなければ体の向きに合わせる
            bot.aimPitch = eye.getPitch();
        } else {
            double[] w = yawPitch(want.getX(), want.getY(), want.getZ());
            double[] next = turn(bot.aimYaw, bot.aimPitch, w[0], w[1], u.aimTurnSpeed);
            moved = Math.hypot(wrap(next[0] - bot.aimYaw), next[1] - bot.aimPitch);
            bot.aimYaw = next[0];
            bot.aimPitch = next[1];
        }
        bot.aimMoved = moved;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double[] sway = sway(bot.swayYaw, bot.swayPitch, swaySize(u.aimSway, u.aimTurnSway, moved),
                r.nextGaussian(), r.nextGaussian());
        bot.swayYaw = sway[0];
        bot.swayPitch = sway[1];
    }

    /** 照準を向けたい向き（目から）。盾を構えている間は脅威、弓兵は弾道の向き、剣兵・槍兵は相手の胴。なければ null。 */
    private static Vector wantDirection(SoldierBot bot, Player p, Location eye) {
        if (bot.shieldUp && bot.blockLook != null && bot.blockLook.getWorld() == eye.getWorld()) {
            return bot.blockLook.toVector().subtract(eye.toVector());
        }
        if (bot.type == UnitType.ARCHER) {
            return bot.aimWant == null ? null : bot.aimWant.clone();
        }
        LivingEntity t = bot.target;
        if (t == null || !t.isValid() || t.isDead() || t.getWorld() != p.getWorld()
                || t.getLocation().distanceSquared(p.getLocation()) > MELEE_TRACK_DISTANCE * MELEE_TRACK_DISTANCE) {
            return null;
        }
        return t.getLocation().toVector().add(new Vector(0, t.getHeight() * 0.5, 0)).subtract(eye.toVector());
    }

    /** 照準の向き（単位ベクトル）。withSway なら揺れを含める。照準がまだなければ体の向き。 */
    static Vector direction(SoldierBot bot, Player p, boolean withSway) {
        if (!bot.aimSet) {
            return p.getEyeLocation().getDirection();
        }
        double yaw = bot.aimYaw + (withSway ? bot.swayYaw : 0);
        double pitch = clampPitch(bot.aimPitch + (withSway ? bot.swayPitch : 0));
        double[] d = dir(yaw, pitch);
        return new Vector(d[0], d[1], d[2]);
    }

    /** 照準（揺れを除く）と want の向きの角度（度）。 */
    static double error(SoldierBot bot, Player p, Vector want) {
        Vector a = direction(bot, p, false);
        return Math.toDegrees(a.angle(want));
    }

    /**
     * 近接の攻撃が当たるか: 揺れを含めた照準の線が、reach（目から）以内で相手の箱（{@link #BOX_MARGIN} だけ広げる）に入るか。
     * 目が箱の中にあれば当たり。
     */
    static boolean meleeHits(SoldierBot bot, Player p, LivingEntity t, double reach) {
        BoundingBox box = t.getBoundingBox().clone().expand(BOX_MARGIN);
        Vector eye = p.getEyeLocation().toVector();
        if (box.contains(eye)) {
            return true;
        }
        return box.rayTrace(eye, direction(bot, p, true), reach) != null;
    }

    /** /sb debug の表示用。狙いからのずれ（度）と今の揺れ。 */
    static String describe(SoldierBot bot, Player p) {
        if (!bot.aimSet) {
            return "なし";
        }
        Location eye = p.getEyeLocation();
        Vector want = wantDirection(bot, p, eye);
        String err = want == null || want.lengthSquared() < 1e-9 ? "相手なし"
                : String.format(java.util.Locale.ROOT, "ずれ%.1f°", error(bot, p, want));
        return err + String.format(java.util.Locale.ROOT, "(揺れ%.1f°,回した%.1f°)",
                Math.hypot(bot.swayYaw, bot.swayPitch), bot.aimMoved);
    }

    // ---- 計算だけの部分（テストする） ----

    /** この tick の揺れの大きさ（度）: もとの揺れ + 回した角度 × 1 度あたりの揺れ。負にはしない。 */
    static double swaySize(double sway, double perDegree, double moved) {
        return Math.max(0.0, sway + perDegree * Math.max(0.0, moved));
    }

    /** 角度を (-180, 180] に。 */
    static double wrap(double deg) {
        double d = deg % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        } else if (d <= -180.0) {
            d += 360.0;
        }
        return d;
    }

    static double clampPitch(double pitch) {
        return Math.max(-90.0, Math.min(90.0, pitch));
    }

    /** 向き (x, y, z) → Minecraft の {yaw, pitch}（度。yaw 0 が +z、90 が -x。pitch は下向きが正）。 */
    static double[] yawPitch(double x, double y, double z) {
        double yaw = Math.toDegrees(Math.atan2(-x, z));
        double pitch = -Math.toDegrees(Math.atan2(y, Math.hypot(x, z)));
        return new double[] {yaw, pitch};
    }

    /** {yaw, pitch}（度）→ 単位ベクトル {x, y, z}。 */
    static double[] dir(double yaw, double pitch) {
        double ry = Math.toRadians(yaw);
        double rp = Math.toRadians(pitch);
        double h = Math.cos(rp);
        return new double[] {-Math.sin(ry) * h, -Math.sin(rp), Math.cos(ry) * h};
    }

    /**
     * 照準を want へ回す。1 回に回る角度は maxStep（度。yaw と pitch の差を合わせた長さ）まで。
     * maxStep が 0 以下なら一度に向く。yaw は近い回り方で回り、(-180, 180] に収める。
     */
    static double[] turn(double yaw, double pitch, double wantYaw, double wantPitch, double maxStep) {
        double dy = wrap(wantYaw - yaw);
        double dp = clampPitch(wantPitch) - pitch;
        double len = Math.hypot(dy, dp);
        if (maxStep <= 0 || len <= maxStep) {
            return new double[] {wrap(yaw + dy), clampPitch(wantPitch)};
        }
        double k = maxStep / len;
        return new double[] {wrap(yaw + dy * k), clampPitch(pitch + dp * k)};
    }

    /**
     * 揺れを 1 tick 進める（yaw・pitch それぞれ、前の揺れを {@link #SWAY_KEEP} だけ残して正規分布の乱数を足す）。
     * 長く見ると、揺れの各成分の標準偏差が size（度）になる。size が 0 以下なら揺れない。
     */
    static double[] sway(double swayYaw, double swayPitch, double size, double gaussYaw, double gaussPitch) {
        if (size <= 0) {
            return new double[] {0, 0};
        }
        double k = size * Math.sqrt(1 - SWAY_KEEP * SWAY_KEEP);
        return new double[] {swayYaw * SWAY_KEEP + gaussYaw * k, swayPitch * SWAY_KEEP + gaussPitch * k};
    }
}
