package siegebot;

import java.util.ArrayDeque;
import java.util.Locale;
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
 * <p>反応の遅れ（units.&lt;兵種&gt;.aim.reaction-ticks）: 照準を回す先を、その tick 前のものにする。近接は相手の胴の座標、
 * 弓兵は弾道の向きを遅らせる（弓兵は {@link Archers} が 2 tick ごとに弾道を更新するので、発射時の実効の遅れは 2〜4 tick）。
 * 盾を構えている間は遅らせず（脅威の向きへすぐ向く）、履歴も消す。相手が変わったときや狙いがなくなったときも履歴を消す。
 * 弓兵はさらに、遅れによるずれが {@link #FIRE_MAX_LAG_ANGLE} 度を超えている間は撃たない。
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
    /** 弓兵の反応の遅れによるずれがこの角度（度）を超えている間は、引き切っていても撃たずに待つ（急な瞬間移動などの安全弁）。 */
    static final double FIRE_MAX_LAG_ANGLE = 20.0;
    /** 剣兵・槍兵が照準を相手に向け続ける距離（ブロック）。これより遠い相手は体の向きに合わせる。 */
    static final double MELEE_TRACK_DISTANCE = 16.0;
    /** 当たり判定の箱を広げる量（ブロック。サーバーの位置の更新の粗さの分）。 */
    static final double BOX_MARGIN = 0.1;

    private final SiegeBotPlugin plugin;

    Aim(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    void tick() {
        for (SoldierBot bot : plugin.bots().all()) {
            if (!bot.npc.isSpawned() || !(bot.npc.getEntity() instanceof Player p)) {
                bot.aimSet = false;
                clearHistory(bot);
                bot.aimWant = null;
                bot.aimWantTarget = null;
                continue;
            }
            update(bot, p, plugin.settings(bot.side).unit(bot.type));
        }
    }

    private static void update(SoldierBot bot, Player p, Settings.Unit u) {
        Location eye = p.getEyeLocation();
        if (!bot.aimSet) {
            bot.aimYaw = eye.getYaw();
            bot.aimPitch = eye.getPitch();
            bot.aimSet = true;
        }
        Vector want = delayedWant(bot, p, eye, u);
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

    /** 盾を構えていて、脅威の向きに照準を向けるべきか。 */
    private static boolean shieldLooking(SoldierBot bot, Location eye) {
        return bot.shieldUp && bot.blockLook != null && bot.blockLook.getWorld() == eye.getWorld();
    }

    /** 剣兵・槍兵が照準を向け続ける相手か（生きていて、同じ世界で、{@link #MELEE_TRACK_DISTANCE} 以内）。 */
    private static boolean validMeleeTarget(Player p, LivingEntity t) {
        return t != null && t.isValid() && !t.isDead() && t.getWorld() == p.getWorld()
                && t.getLocation().distanceSquared(p.getLocation()) <= MELEE_TRACK_DISTANCE * MELEE_TRACK_DISTANCE;
    }

    /** 近接で狙う相手の胴の座標。 */
    private static Vector meleeBody(LivingEntity t) {
        return t.getLocation().toVector().add(new Vector(0, t.getHeight() * 0.5, 0));
    }

    /**
     * 反応の遅れを掛ける前の、照準を向けたい向き（目から）。盾を構えている間は脅威、弓兵は弾道の向き、
     * 剣兵・槍兵は相手の胴。なければ null。履歴は変更しない（/sb debug からも呼べる）。
     */
    private static Vector rawWantDirection(SoldierBot bot, Player p, Location eye) {
        if (shieldLooking(bot, eye)) {
            return bot.blockLook.toVector().subtract(eye.toVector());
        }
        if (bot.type.usesCustomAim()) {
            return bot.aimWant == null || !acceptWant(bot.aimWantTarget, bot.target) ? null : bot.aimWant.clone();
        }
        LivingEntity t = bot.target;
        return validMeleeTarget(p, t) ? meleeBody(t).subtract(eye.toVector()) : null;
    }

    /**
     * 反応の遅れを掛けた、照準を向けたい向き（目から）。なければ null。
     * 盾を構えている間は遅らせず履歴を消す。近接は相手の胴の座標、弓兵は弾道の向きを
     * {@link Settings.Unit#aimReactionTicks} tick 前のものにする。結果は {@link SoldierBot#aimTargetDir} にも残す。
     */
    private static Vector delayedWant(SoldierBot bot, Player p, Location eye, Settings.Unit u) {
        Vector want;
        if (shieldLooking(bot, eye)) {
            clearHistory(bot);
            want = bot.blockLook.toVector().subtract(eye.toVector());
            bot.aimTargetDir = want.clone();
        } else if (bot.type.usesCustomAim()) {
            if (!acceptWant(bot.aimWantTarget, bot.target) || bot.aimWant == null) {
                clearHistory(bot);
                want = null;
            } else {
                if (bot.aimHistoryTarget != bot.aimWantTarget) {
                    bot.aimHistory.clear();
                    bot.aimHistoryTarget = bot.aimWantTarget;
                }
                want = delay(bot.aimHistory, bot.aimWant.clone(), u.aimReactionTicks);
                bot.aimTargetDir = want.clone();
            }
        } else {
            LivingEntity t = bot.target;
            if (!validMeleeTarget(p, t)) {
                clearHistory(bot);
                want = null;
            } else {
                if (bot.aimHistoryTarget != t) {
                    bot.aimHistory.clear();
                    bot.aimHistoryTarget = t;
                }
                Vector delayedBody = delay(bot.aimHistory, meleeBody(t), u.aimReactionTicks);
                want = delayedBody.clone().subtract(eye.toVector());
                bot.aimTargetDir = want.clone();
            }
        }
        return want;
    }

    /** 反応の遅れの履歴と、遅れを掛けた向きを消す。 */
    private static void clearHistory(SoldierBot bot) {
        bot.aimHistory.clear();
        bot.aimHistoryTarget = null;
        bot.aimTargetDir = null;
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

    /**
     * /sb debug の表示用。ずれ = 照準（揺れを除く）と今の want（遅れ前の目標）の差（度）。
     * 遅れ = 遅延後の目標（{@link SoldierBot#aimTargetDir}）と今の want の差（度）。あわせて今の揺れと回した角度。
     * 履歴は変更しない（読み取り専用。{@link #delay} は呼ばない）。
     */
    static String describe(SoldierBot bot, Player p) {
        if (!bot.aimSet) {
            return "なし";
        }
        Vector want = rawWantDirection(bot, p, p.getEyeLocation());
        boolean noWant = want == null || want.lengthSquared() < 1e-9;
        String err = noWant ? "相手なし" : String.format(Locale.ROOT, "ずれ%.1f°", error(bot, p, want));
        double lag = noWant || bot.aimTargetDir == null || bot.aimTargetDir.lengthSquared() < 1e-9 ? 0.0
                : angleDeg(want, bot.aimTargetDir);
        return err + String.format(Locale.ROOT, "(遅れ%.1f°,揺れ%.1f°,回した%.1f°)",
                lag, Math.hypot(bot.swayYaw, bot.swayPitch), bot.aimMoved);
    }

    // ---- 計算だけの部分（テストする） ----

    /**
     * 反応の遅れ: 今の値を履歴の末尾に積み、reactionTicks tick 前の値（履歴の先頭）を返す。
     * 履歴が reactionTicks + 1 件に満たない間（開始直後）は、いちばん古い値を返す。
     * reactionTicks が 0 以下なら履歴を空にして今の値をそのまま返す。設定の読み直しで値が減ったときは古い履歴を切り詰める。
     */
    static <T> T delay(ArrayDeque<T> history, T current, int reactionTicks) {
        if (reactionTicks <= 0) {
            history.clear();
            return current;
        }
        history.addLast(current);
        while (history.size() > reactionTicks + 1) {
            history.removeFirst();
        }
        return history.peekFirst();
    }

    /** 弓兵の弾道の向き（aimWant）を使ってよいか: 計算したときの相手が今の相手と同じ（null どうしは不可）。 */
    static boolean acceptWant(Object wantTarget, Object target) {
        return wantTarget != null && wantTarget == target;
    }

    /**
     * 弓兵が撃つかの判定に使う「遅れを掛けた向き」。履歴の相手が今の相手と同じときだけ delayedDir、
     * 相手が切り替わった直後は今の向き（currentDir）にして、古い相手の向きへ撃たないようにする。
     */
    static Vector archerTargetDir(Vector delayedDir, Object historyTarget, Object currentTarget, Vector currentDir) {
        return delayedDir != null && historyTarget == currentTarget ? delayedDir : currentDir;
    }

    /** 弓兵が撃たずに待つか: 照準の誤差が {@link #FIRE_TOLERANCE} 度を超える、または遅れによるずれが {@link #FIRE_MAX_LAG_ANGLE} 度を超える。 */
    static boolean shouldHoldFire(double aimErrorDeg, double lagAngleDeg) {
        return aimErrorDeg > FIRE_TOLERANCE || lagAngleDeg > FIRE_MAX_LAG_ANGLE;
    }

    /** 2 つのベクトルのなす角（度）。どちらかが null か長さがほぼ 0 なら 0。丸め誤差で acos が NaN にならないよう丸める。 */
    static double angleDeg(Vector a, Vector b) {
        if (a == null || b == null) {
            return 0.0;
        }
        double la = a.lengthSquared();
        double lb = b.lengthSquared();
        if (la < 1e-12 || lb < 1e-12) {
            return 0.0;
        }
        double cos = Math.max(-1.0, Math.min(1.0, a.dot(b) / Math.sqrt(la * lb)));
        return Math.toDegrees(Math.acos(cos));
    }

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
