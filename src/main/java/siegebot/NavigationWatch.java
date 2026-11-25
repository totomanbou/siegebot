package siegebot;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.citizensnpcs.api.ai.EntityTarget;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.PathStrategy;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

/**
 * 歩いているのに進まなくなった（詰まった）ボットを、種別ごとの時間で見つけて自動で復旧する見張り。
 * 毎回の行動（{@link BotManager}）の最初に {@link #check} を呼ぶ。メインスレッドからだけ呼ぶ。
 *
 * <p>種別ごとの詰まりの時間（{@link #stuckThreshold}）: 後退・横隊の直線寄せは 1 秒、歩き・追跡は 1.5 秒。
 * 後退は詰まりとは別に、始めてから {@link #BACKOFF_MAX_TICKS} で打ち切る。
 * 追跡が同じ敵で 2 回続けて詰まったら、その敵を一定時間（40 → 80 → 160 tick）狙いから外す。
 * 判定の式は Bukkit・Citizens に依存しない静的メソッドにしてあり、単体テストで確かめる。
 */
final class NavigationWatch {

    /** 水平または上下にこれより動いたら、進んだとみなす（ブロック）。 */
    static final double MOVE_PROGRESS_THRESHOLD = 0.9;
    /** 目標までの水平距離が最短よりこれ以上縮んだら、進んだとみなす（ブロック）。 */
    static final double GOAL_SHRINK_THRESHOLD = 0.4;
    /** 横隊の直線寄せでは、目標までの距離がこれ以上縮んだときだけ進んだとみなす（ブロック）。 */
    static final double FINE_SHRINK_THRESHOLD = 0.3;
    /** 目標の位置がこれより動いたら、進捗の基準を取り直す（ブロック）。 */
    static final double GOAL_CHANGE_RESET = 2.0;
    static final int BACKOFF_STUCK_TICKS = 20;
    static final int BACKOFF_MAX_TICKS = 40;
    static final int BACKOFF_COOLDOWN_TICKS = 30;
    static final int FINE_STUCK_TICKS = 20;
    static final int FINE_BYPASS_TICKS = 60;
    static final int WALK_STUCK_TICKS = 30;
    static final int CHASE_STUCK_TICKS = 30;
    /** 同じ敵への追跡が進捗なく続けて詰まったとき、到達不能とみなす回数。 */
    static final int CHASE_UNREACHABLE_STRIKES = 2;
    static final int UNREACHABLE_BASE_TICKS = 40;
    static final int UNREACHABLE_MAX_TICKS = 160;
    /** 同じボット・同じ理由のログを間引く間隔（tick）。 */
    static final int LOG_THROTTLE_TICKS = 200;

    /** 歩きの種類。 */
    enum Kind { BACKOFF, FINE, WALK, CHASE }

    /** 復旧の理由（/sb debug の集計とログに使う）。 */
    enum Reason { EMPTY_PATH, BACKOFF_STUCK, BACKOFF_CAP, FINE, WALK, CHASE, UNREACHABLE, ALLY_LINE, SHIELD_CAP }

    private final SiegeBotPlugin plugin;
    private final Map<Reason, Integer> counts = new EnumMap<>(Reason.class);
    private final Map<String, Long> lastLogByBotReason = new HashMap<>();

    NavigationWatch(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- 純粋な判定（単体テスト用） ----

    /** 歩きの種類。歩いていなければ null。後退・敵を追う・横隊の直線寄せ・ふつうの歩きの順に決める。 */
    static Kind classify(boolean navigating, boolean backingOff, boolean hasEntityTarget, boolean straightLine) {
        if (!navigating) {
            return null;
        }
        if (backingOff) {
            return Kind.BACKOFF;
        }
        if (hasEntityTarget) {
            return Kind.CHASE;
        }
        return straightLine ? Kind.FINE : Kind.WALK;
    }

    /**
     * 進んだか。直線寄せは目標までの距離が縮んだときだけ。それ以外は、水平か上下に動いたか、目標までの距離が縮んだとき
     * （はしごや段差の縦の動きも進みとみなす）。
     */
    static boolean madeProgress(Kind kind, double dx, double dy, double dz, double goalDist, double bestGoalDist) {
        if (kind == Kind.FINE) {
            return goalDist <= bestGoalDist - FINE_SHRINK_THRESHOLD;
        }
        return Math.hypot(dx, dz) > MOVE_PROGRESS_THRESHOLD || Math.abs(dy) > MOVE_PROGRESS_THRESHOLD
                || goalDist <= bestGoalDist - GOAL_SHRINK_THRESHOLD;
    }

    /** 経路が空のまま歩いていることにして、ナビゲーションを取り消してよいか（MINECRAFT の経路探索のときだけ）。 */
    static boolean shouldResetEmptyPath(boolean minecraftPathfinder, boolean navigating, boolean straightLine,
                                        boolean pathEmpty, boolean climbing, boolean canRepathSurface, boolean exemptStop) {
        return minecraftPathfinder && navigating && !straightLine && pathEmpty && !climbing && canRepathSurface
                && !exemptStop;
    }

    /** 詰まりとみなすまでの時間（tick）。 */
    static int stuckThreshold(Kind kind) {
        return switch (kind) {
            case BACKOFF -> BACKOFF_STUCK_TICKS;
            case FINE -> FINE_STUCK_TICKS;
            case WALK -> WALK_STUCK_TICKS;
            case CHASE -> CHASE_STUCK_TICKS;
        };
    }

    /** strike 回目の到達不能で狙いから外す時間（tick）。40 → 80 → 160。 */
    static long backoffTicksForStrike(int strike) {
        return Math.min(UNREACHABLE_MAX_TICKS, (long) UNREACHABLE_BASE_TICKS << Math.min(2, Math.max(0, strike - 1)));
    }

    /** 横隊の直線寄せで詰まったとき、そのまま持ち場にいるとみなすか（持ち場までの距離が出る半径以内）。 */
    static boolean shouldHoldFineSlot(double distToAnchor, double exitRadius) {
        return distToAnchor <= exitRadius;
    }

    /** 同じ理由のログを間引くか。lastLogTick は未記録なら Long.MIN_VALUE / 2 などを渡す。 */
    static boolean shouldThrottleLog(long lastLogTick, long now) {
        return now - lastLogTick < LOG_THROTTLE_TICKS;
    }

    /** 目標が動いたか（片方だけ null・ワールドが違う・{@link #GOAL_CHANGE_RESET} より動いた）。 */
    static boolean goalChanged(Location old, Location cur) {
        if (old == null || cur == null) {
            return old != cur;
        }
        if (old.getWorld() != cur.getWorld()) {
            return true;
        }
        return old.distanceSquared(cur) > GOAL_CHANGE_RESET * GOAL_CHANGE_RESET;
    }

    /** 経路の節点の数。経路がなければ（直線）-1。 */
    static int countNodes(Iterable<?> path) {
        if (path == null) {
            return -1;
        }
        int n = 0;
        for (Object ignored : path) {
            n++;
        }
        return n;
    }

    // ---- 追跡状態 ----

    /** 詰まりの追跡をすべて捨てる（歩いていない・復旧した・移された）。 */
    static void clearTracking(SoldierBot bot) {
        bot.stuckKind = null;
        bot.stuckTargetId = null;
        bot.stuckGoalPos = null;
        bot.stuckPos = null;
        bot.stuckTick = 0;
        bot.stuckBestGoalDist = Double.POSITIVE_INFINITY;
        bot.consecutiveChaseStucks = 0;
    }

    private static void resetProgress(SoldierBot bot, Kind kind, UUID targetId, Location goalLoc, Location here, long now,
                                      double goalDist) {
        bot.stuckKind = kind;
        bot.stuckTargetId = targetId;
        bot.stuckGoalPos = goalLoc == null ? null : goalLoc.clone();
        bot.stuckPos = here == null ? null : here.clone();
        bot.stuckTick = now;
        bot.stuckBestGoalDist = goalDist;
    }

    // ---- 見張り ----

    void check(SoldierBot bot, Navigator nav, LivingEntity self, Location anchor, boolean inFormation, Settings s,
               long now) {
        if (!nav.isNavigating()) {
            clearTracking(bot);
            return;
        }
        // 正常な停止: 盾で止まっている間、剣兵が殴り合っている間は詰まりではない
        EntityTarget cur = nav.getEntityTarget();
        if (bot.shieldStopping || (cur != null && cur.isAggressive() && cur.getTarget() instanceof LivingEntity enemy
                && enemy.getWorld() == self.getWorld()
                && self.getLocation().distanceSquared(enemy.getLocation())
                        <= Math.pow(s.unit(bot.type).attackRange + 0.5, 2)
                && self.hasLineOfSight(enemy))) {
            clearTracking(bot);
            return;
        }
        PathStrategy ps = nav.getPathStrategy();
        Iterable<Vector> path = ps == null ? null : ps.getPath();
        boolean straightLine = path == null;
        int pathNodes = countNodes(path);
        boolean pathEmpty = !straightLine && pathNodes == 0;
        EntityTarget et = nav.getEntityTarget();
        Kind kind = classify(true, bot.backingOff, et != null, straightLine);

        Location here = self.getLocation();
        Entity targetEnt = et != null ? et.getTarget() : null;
        Location goalLoc = targetEnt != null ? targetEnt.getLocation() : nav.getTargetAsLocation();
        double goalDist = goalLoc != null && goalLoc.getWorld() == here.getWorld()
                ? Math.hypot(here.getX() - goalLoc.getX(), here.getZ() - goalLoc.getZ()) : 0.0;
        UUID targetId = targetEnt != null ? targetEnt.getUniqueId() : null;

        // はしご登攀中: 基準だけ今に合わせる（詰まりとも空経路とも見ない）
        if (self.isClimbing()) {
            bot.stuckKind = kind;
            bot.stuckTargetId = targetId;
            bot.stuckGoalPos = goalLoc == null ? null : goalLoc.clone();
            bot.stuckPos = here.clone();
            bot.stuckTick = now;
            bot.stuckBestGoalDist = goalDist;
            return;
        }

        boolean worldDiff = bot.stuckPos == null || bot.stuckPos.getWorld() != here.getWorld();
        boolean kindChanged = bot.stuckKind != kind;
        boolean targetChanged = kind == Kind.CHASE && !Objects.equals(bot.stuckTargetId, targetId);
        boolean goalMoved = (kind == Kind.WALK || kind == Kind.FINE || kind == Kind.BACKOFF)
                && goalChanged(bot.stuckGoalPos, goalLoc);
        if (kindChanged || worldDiff || targetChanged) {
            if (kindChanged || targetChanged) {
                bot.consecutiveChaseStucks = 0;
            }
            resetProgress(bot, kind, targetId, goalLoc, here, now, goalDist);
        } else if (goalMoved) {
            resetProgress(bot, kind, targetId, goalLoc, here, now, goalDist); // backoffStartTick は変えない
        }

        // 後退の総時間の上限（進んでいても打ち切る）
        if (kind == Kind.BACKOFF && bot.backoffStartTick > 0 && now - bot.backoffStartTick >= BACKOFF_MAX_TICKS) {
            long elapsed = now - bot.stuckTick;
            bot.backingOff = false;
            bot.backoffStartTick = 0;
            bot.backoffCooldownUntilTick = now + BACKOFF_COOLDOWN_TICKS;
            nav.cancelNavigation();
            clearTracking(bot);
            record(bot, Reason.BACKOFF_CAP, here, goalLoc, kind, elapsed, self.isOnGround(), pathNodes, now);
            return;
        }

        if (madeProgress(kind, here.getX() - bot.stuckPos.getX(), here.getY() - bot.stuckPos.getY(),
                here.getZ() - bot.stuckPos.getZ(), goalDist, bot.stuckBestGoalDist)) {
            resetProgress(bot, kind, targetId, goalLoc, here, now, goalDist);
            if (kind == Kind.CHASE) {
                bot.consecutiveChaseStucks = 0;
                if (targetId != null) {
                    bot.unreachableStrikes.remove(targetId);
                }
            }
        }

        // 経路が空のまま歩いている（MINECRAFT の経路探索）: 進捗の時計は動かさず、取り消して引き直させる
        boolean canSurface = self.isOnGround() || self.isInWater() || self.isInsideVehicle();
        if (shouldResetEmptyPath("MINECRAFT".equals(s.pathfinder), true, straightLine, pathEmpty, false, canSurface, false)
                && now - bot.stuckTick < stuckThreshold(kind)) {
            nav.cancelNavigation();
            record(bot, Reason.EMPTY_PATH, here, goalLoc, kind, now - bot.stuckTick, self.isOnGround(), pathNodes, now);
            return;
        }

        long elapsed = now - bot.stuckTick;
        if (elapsed < stuckThreshold(kind)) {
            return;
        }
        switch (kind) {
            case BACKOFF -> {
                bot.backingOff = false;
                bot.backoffStartTick = 0;
                bot.backoffCooldownUntilTick = now + BACKOFF_COOLDOWN_TICKS;
                nav.cancelNavigation();
                clearTracking(bot);
                record(bot, Reason.BACKOFF_STUCK, here, goalLoc, kind, elapsed, self.isOnGround(), pathNodes, now);
            }
            case FINE -> {
                nav.cancelNavigation();
                double exitRadius = Math.max(Formation.FORMATION_ARRIVE + 0.4,
                        s.separationEnabled ? s.separationMinDistance : 0.0);
                double anchorDist = anchor != null && anchor.getWorld() == here.getWorld()
                        ? here.distance(anchor) : Double.POSITIVE_INFINITY;
                if (inFormation && shouldHoldFineSlot(anchorDist, exitRadius)) {
                    bot.holdingSlot = true;
                } else {
                    bot.fineBypassUntilTick = now + FINE_BYPASS_TICKS;
                }
                clearTracking(bot);
                record(bot, Reason.FINE, here, goalLoc, kind, elapsed, self.isOnGround(), pathNodes, now);
            }
            case WALK -> {
                nav.cancelNavigation();
                clearTracking(bot);
                record(bot, Reason.WALK, here, goalLoc, kind, elapsed, self.isOnGround(), pathNodes, now);
            }
            case CHASE -> {
                nav.cancelNavigation();
                bot.consecutiveChaseStucks++;
                if (bot.consecutiveChaseStucks >= CHASE_UNREACHABLE_STRIKES && targetId != null) {
                    int strike = bot.unreachableStrikes.getOrDefault(targetId, 0) + 1;
                    bot.unreachableStrikes.put(targetId, strike);
                    bot.unreachableTargets.put(targetId, now + backoffTicksForStrike(strike));
                    bot.consecutiveChaseStucks = 0;
                    bot.lastRetargetTick = 0;
                    record(bot, Reason.UNREACHABLE, here, goalLoc, kind, elapsed, self.isOnGround(), pathNodes, now);
                } else {
                    record(bot, Reason.CHASE, here, goalLoc, kind, elapsed, self.isOnGround(), pathNodes, now);
                }
                resetProgress(bot, kind, targetId, goalLoc, here, now, goalDist); // 次の詰まりはさらに 30 tick 後
            }
        }
    }

    // ---- 記録 ----

    private void record(SoldierBot bot, Reason reason, Location here, Location goal, Kind kind, long stuckTicks,
                        boolean onGround, int pathNodes, long now) {
        String detail = String.format(Locale.ROOT, "kind=%s pos=(%.1f,%.1f,%.1f) goal=%s stuck=%dt ground=%b path=%s",
                kind, here.getX(), here.getY(), here.getZ(),
                goal == null ? "なし" : String.format(Locale.ROOT, "(%.1f,%.1f,%.1f)", goal.getX(), goal.getY(), goal.getZ()),
                stuckTicks, onGround, pathNodes < 0 ? "直線" : String.valueOf(pathNodes));
        recordSimple(bot, reason, detail, now);
    }

    /** 復旧を 1 回数え、同じボット・同じ理由は {@link #LOG_THROTTLE_TICKS} に 1 行だけログに出す。 */
    void recordSimple(SoldierBot bot, Reason reason, String detail, long now) {
        counts.merge(reason, 1, Integer::sum);
        String key = bot.name().toLowerCase(Locale.ROOT) + "\0" + reason.name();
        long last = lastLogByBotReason.getOrDefault(key, Long.MIN_VALUE / 2);
        if (shouldThrottleLog(last, now)) {
            return;
        }
        lastLogByBotReason.put(key, now);
        plugin.getLogger().info("復旧[" + reason.name().toLowerCase(Locale.ROOT) + "]: " + bot.name() + "("
                + bot.type().key() + ") " + detail);
    }

    void clearStats() {
        counts.clear();
        lastLogByBotReason.clear();
    }

    /** /sb debug 用の復旧の集計。1 件もなければ「復旧: なし」。 */
    String describeStats() {
        StringBuilder sb = new StringBuilder();
        for (Reason r : Reason.values()) {
            int n = counts.getOrDefault(r, 0);
            if (n > 0) {
                sb.append(sb.length() == 0 ? "" : " ").append(label(r)).append('=').append(n);
            }
        }
        return sb.length() == 0 ? "復旧: なし" : "復旧: " + sb;
    }

    private static String label(Reason r) {
        return switch (r) {
            case EMPTY_PATH -> "empty";
            case BACKOFF_STUCK -> "backoff";
            case BACKOFF_CAP -> "backoffcap";
            case FINE -> "fine";
            case WALK -> "walk";
            case CHASE -> "chase";
            case UNREACHABLE -> "unreach";
            case ALLY_LINE -> "allyline";
            case SHIELD_CAP -> "shieldcap";
        };
    }
}
