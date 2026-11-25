package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import siegebot.NavigationWatch.Kind;

/** 詰まりの見張り（{@link NavigationWatch}）・弓兵の射線被りの解除・槍兵の盾の停止上限の純粋な判定の試験。 */
class NavigationWatchTest {

    private static final double INF = Double.POSITIVE_INFINITY;

    // ---- classify ----

    @Test
    void classifyNotNavigatingIsNull() {
        assertNull(NavigationWatch.classify(false, true, true, true));
    }

    @Test
    void classifyPriorityBackoffChaseFineWalk() {
        assertEquals(Kind.BACKOFF, NavigationWatch.classify(true, true, true, true));
        assertEquals(Kind.CHASE, NavigationWatch.classify(true, false, true, true));
        assertEquals(Kind.FINE, NavigationWatch.classify(true, false, false, true));
        assertEquals(Kind.WALK, NavigationWatch.classify(true, false, false, false));
    }

    // ---- madeProgress ----

    @Test
    void progressByHorizontalMove() {
        assertTrue(NavigationWatch.madeProgress(Kind.WALK, 0.95, 0, 0, 10, 10));
        assertFalse(NavigationWatch.madeProgress(Kind.WALK, 0.9, 0, 0, 10, 10)); // ちょうど閾値は進みではない
        assertTrue(NavigationWatch.madeProgress(Kind.WALK, 0.7, 0, 0.7, 10, 10)); // hypot ≈ 0.99
    }

    @Test
    void progressByVerticalMoveForLadders() {
        assertTrue(NavigationWatch.madeProgress(Kind.WALK, 0, 1.0, 0, 10, 10));
        assertTrue(NavigationWatch.madeProgress(Kind.CHASE, 0, -1.2, 0, 10, 10));
        assertFalse(NavigationWatch.madeProgress(Kind.WALK, 0, 0.5, 0, 10, 10));
    }

    @Test
    void progressByGoalShrink() {
        assertTrue(NavigationWatch.madeProgress(Kind.WALK, 0.1, 0, 0.1, 9.6, 10.0));
        assertFalse(NavigationWatch.madeProgress(Kind.WALK, 0.1, 0, 0.1, 9.7, 10.0));
        assertTrue(NavigationWatch.madeProgress(Kind.WALK, 0, 0, 0, 5, INF)); // 基準が無限大なら最初は進み
    }

    @Test
    void fineIgnoresPositionMoveAndUsesShrinkOnly() {
        // 押し合いで 1 ブロック動いても、持ち場に近づいていなければ進みではない
        assertFalse(NavigationWatch.madeProgress(Kind.FINE, 1.5, 0, 0, 1.0, 1.0));
        assertTrue(NavigationWatch.madeProgress(Kind.FINE, 0, 0, 0, 0.7, 1.0));
        assertFalse(NavigationWatch.madeProgress(Kind.FINE, 0, 0, 0, 0.75, 1.0)); // 0.25 しか縮んでいない
    }

    @Test
    void separationSimulatedStuckStaysStuckForWalk() {
        // Separation に押されて 0.5 ブロックずつ行ったり来たりしても、0.9 を超えず目標も縮まなければ進みではない
        double best = 10.0;
        for (double dx : new double[] {0.5, -0.4, 0.3, -0.5}) {
            assertFalse(NavigationWatch.madeProgress(Kind.WALK, dx, 0, 0, 10.0, best));
        }
    }

    // ---- 空経路 ----

    @Test
    void emptyPathResetConditions() {
        assertTrue(NavigationWatch.shouldResetEmptyPath(true, true, false, true, false, true, false));
        assertFalse(NavigationWatch.shouldResetEmptyPath(false, true, false, true, false, true, false)); // CITIZENS 系
        assertFalse(NavigationWatch.shouldResetEmptyPath(true, false, false, true, false, true, false));
        assertFalse(NavigationWatch.shouldResetEmptyPath(true, true, true, true, false, true, false)); // 直線
        assertFalse(NavigationWatch.shouldResetEmptyPath(true, true, false, false, false, true, false)); // 経路あり
        assertFalse(NavigationWatch.shouldResetEmptyPath(true, true, false, true, true, true, false)); // 登攀中
        assertFalse(NavigationWatch.shouldResetEmptyPath(true, true, false, true, false, false, false)); // 空中
        assertFalse(NavigationWatch.shouldResetEmptyPath(true, true, false, true, false, true, true)); // 正常な停止
    }

    @Test
    void countNodes() {
        assertEquals(-1, NavigationWatch.countNodes(null));
        assertEquals(0, NavigationWatch.countNodes(List.of()));
        assertEquals(3, NavigationWatch.countNodes(List.of(1, 2, 3)));
    }

    // ---- 閾値・指数バックオフ ----

    @Test
    void stuckThresholds() {
        assertEquals(20, NavigationWatch.stuckThreshold(Kind.BACKOFF));
        assertEquals(20, NavigationWatch.stuckThreshold(Kind.FINE));
        assertEquals(30, NavigationWatch.stuckThreshold(Kind.WALK));
        assertEquals(30, NavigationWatch.stuckThreshold(Kind.CHASE));
    }

    @Test
    void unreachableBackoffDoublesUpToCap() {
        assertEquals(40, NavigationWatch.backoffTicksForStrike(0));
        assertEquals(40, NavigationWatch.backoffTicksForStrike(1));
        assertEquals(80, NavigationWatch.backoffTicksForStrike(2));
        assertEquals(160, NavigationWatch.backoffTicksForStrike(3));
        assertEquals(160, NavigationWatch.backoffTicksForStrike(10));
    }

    @Test
    void chaseStrikesFollowBackoffSequence() {
        // 追跡の詰まり 2 回で到達不能になり、続けるたびに 40 → 80 → 160
        int strikes = 0;
        long[] got = new long[4];
        for (int i = 0; i < 4; i++) {
            strikes++;
            got[i] = NavigationWatch.backoffTicksForStrike(strikes);
        }
        assertEquals(List.of(40L, 80L, 160L, 160L), List.of(got[0], got[1], got[2], got[3]));
        assertEquals(2, NavigationWatch.CHASE_UNREACHABLE_STRIKES);
    }

    // ---- 横隊の直線寄せ ----

    @Test
    void fineHoldSlotUsesExitRadius() {
        double exit = Math.max(Formation.FORMATION_ARRIVE + 0.4, 0.9); // 1.0 と 0.9 の大きい方
        assertEquals(1.0, exit, 1e-9);
        assertTrue(NavigationWatch.shouldHoldFineSlot(0.5, exit));
        assertTrue(NavigationWatch.shouldHoldFineSlot(1.0, exit)); // ちょうど半径
        assertFalse(NavigationWatch.shouldHoldFineSlot(1.01, exit));
        assertFalse(NavigationWatch.shouldHoldFineSlot(Double.POSITIVE_INFINITY, exit));
    }

    // ---- ログの間引き ----

    @Test
    void logThrottle() {
        assertTrue(NavigationWatch.shouldThrottleLog(1000, 1199));
        assertFalse(NavigationWatch.shouldThrottleLog(1000, 1200));
        assertFalse(NavigationWatch.shouldThrottleLog(Long.MIN_VALUE / 2, 5)); // 未記録
    }

    // ---- Archers ----

    @Test
    void allyBlockReleaseAfterTimeout() {
        assertFalse(Archers.shouldReleaseAllyBlock(-1, 1000)); // 起きていない
        assertFalse(Archers.shouldReleaseAllyBlock(1000, 1000));
        assertFalse(Archers.shouldReleaseAllyBlock(1000, 1000 + Archers.ALLY_BLOCK_TIMEOUT_TICKS - 1));
        assertTrue(Archers.shouldReleaseAllyBlock(1000, 1000 + Archers.ALLY_BLOCK_TIMEOUT_TICKS));
        assertTrue(Archers.shouldReleaseAllyBlock(0, Archers.ALLY_BLOCK_TIMEOUT_TICKS)); // 時刻 0 から始まった場合
    }

    // ---- Shields.evaluateStop ----

    private static final int LOWER = 0;
    private static final long NEVER = Long.MIN_VALUE / 2;

    @Test
    void noStopWhenNotStoppingTypeOrNotUsing() {
        Shields.StopDecision d = Shields.evaluateStop(false, true, true, 100, 100, LOWER, 50, 7);
        assertFalse(d.stopping());
        assertEquals(-1, d.nextAimStopStartTick());
        assertEquals(7, d.nextCooldownUntilTick());
        assertFalse(Shields.evaluateStop(true, false, true, 100, 100, LOWER, 50, 7).stopping());
    }

    @Test
    void projectileAlwaysStopsAndResetsAimTimer() {
        Shields.StopDecision d = Shields.evaluateStop(true, true, true, 100, 100, LOWER, 60, 0);
        assertTrue(d.stopping());
        assertEquals(-1, d.nextAimStopStartTick());
        assertFalse(d.hitCap());
    }

    @Test
    void recentProjectileKeepsStoppingWithinLowerAfter() {
        assertTrue(Shields.evaluateStop(true, true, false, 105, 100, 10, -1, 0).stopping());
        // lowerAfter 経過後は未発射の弓扱いになる
        Shields.StopDecision d = Shields.evaluateStop(true, true, false, 110, 100, 10, -1, 0);
        assertTrue(d.stopping());
        assertEquals(110, d.nextAimStopStartTick());
    }

    @Test
    void aimStopStartsThenHitsCap() {
        Shields.StopDecision d = Shields.evaluateStop(true, true, false, 200, NEVER, LOWER, -1, 0);
        assertTrue(d.stopping());
        assertEquals(200, d.nextAimStopStartTick());
        assertFalse(d.hitCap());

        long start = d.nextAimStopStartTick();
        Shields.StopDecision mid = Shields.evaluateStop(true, true, false, start + Shields.AIM_STOP_MAX_TICKS - 1,
                NEVER, LOWER, start, 0);
        assertTrue(mid.stopping());
        assertEquals(start, mid.nextAimStopStartTick());

        long capNow = start + Shields.AIM_STOP_MAX_TICKS;
        Shields.StopDecision cap = Shields.evaluateStop(true, true, false, capNow, NEVER, LOWER, start, 0);
        assertFalse(cap.stopping());
        assertTrue(cap.hitCap());
        assertEquals(-1, cap.nextAimStopStartTick());
        assertEquals(capNow + Shields.AIM_STOP_COOLDOWN_TICKS, cap.nextCooldownUntilTick());
    }

    @Test
    void cooldownBlocksAimStopEvenForNewThreatOrRaise() {
        // 上限超過直後: 別の脅威・再構えでも、休止期間の間は未発射の弓では止まらない
        long cooldownUntil = 300 + Shields.AIM_STOP_COOLDOWN_TICKS;
        for (long now = 300; now < cooldownUntil; now++) {
            Shields.StopDecision d = Shields.evaluateStop(true, true, false, now, NEVER, LOWER, -1, cooldownUntil);
            assertFalse(d.stopping(), "now=" + now);
            assertFalse(d.hitCap());
            assertEquals(-1, d.nextAimStopStartTick());
            assertEquals(cooldownUntil, d.nextCooldownUntilTick());
        }
        // 休止期間が終わったら、また止まり始める
        Shields.StopDecision after = Shields.evaluateStop(true, true, false, cooldownUntil, NEVER, LOWER, -1, cooldownUntil);
        assertTrue(after.stopping());
        assertEquals(cooldownUntil, after.nextAimStopStartTick());
    }

    @Test
    void flyingArrowBypassesCooldown() {
        long cooldownUntil = 340;
        Shields.StopDecision d = Shields.evaluateStop(true, true, true, 310, 310, LOWER, -1, cooldownUntil);
        assertTrue(d.stopping());
        assertEquals(cooldownUntil, d.nextCooldownUntilTick()); // 休止期間は保たれる
        // 矢が消えた直後（lowerAfter 以内）も止まる、過ぎたら休止期間に戻る
        assertTrue(Shields.evaluateStop(true, true, false, 312, 310, 5, -1, cooldownUntil).stopping());
        assertFalse(Shields.evaluateStop(true, true, false, 316, 310, 5, -1, cooldownUntil).stopping());
    }

    @Test
    void capConstants() {
        assertEquals(30, Shields.AIM_STOP_MAX_TICKS);
        assertEquals(40, Shields.AIM_STOP_COOLDOWN_TICKS);
        assertEquals(40, NavigationWatch.BACKOFF_MAX_TICKS);
        assertEquals(30, NavigationWatch.BACKOFF_COOLDOWN_TICKS);
    }
}
