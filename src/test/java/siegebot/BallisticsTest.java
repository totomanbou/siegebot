package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BallisticsTest {

    private static final int MAX_TICKS = 200;

    private static double deg(double radians) {
        return Math.toDegrees(radians);
    }

    private static void assertHits(double dx, double dy, double speed) {
        Ballistics.Solution s = Ballistics.solve(dx, dy, speed, MAX_TICKS);
        assertNotNull(s, "dx=" + dx + " dy=" + dy + " は届くはず");
        double h = Ballistics.heightAt(dx, s.pitch(), speed, MAX_TICKS);
        assertEquals(dy, h, 1e-3, "dx=" + dx + " dy=" + dy + " pitch=" + deg(s.pitch()));
        assertTrue(s.ticks() > 0);
    }

    @Test
    void heightAtMatchesHandComputedTicks() {
        // pitch 0, speed 1: tick1 で x=1,y=0 / tick2 で x=1.99,y=-0.05 / tick3 で x=2.9701,y=-0.1495
        assertEquals(0.0, Ballistics.heightAt(1.0, 0.0, 1.0, MAX_TICKS), 1e-12);
        double frac = (2.0 - 1.99) / 0.9801;
        assertEquals(-0.05 + frac * (-0.0995), Ballistics.heightAt(2.0, 0.0, 1.0, MAX_TICKS), 1e-9);
        assertEquals(2.0 + frac, hitTicks(2.0, 0.0, 1.0), 1e-9);
    }

    /** solve を経由せず、tick 数だけを heightAt の結果から確かめるための補助（dy = 高さにして solve で逆算）。 */
    private static double hitTicks(double dx, double pitch, double speed) {
        double h = Ballistics.heightAt(dx, pitch, speed, MAX_TICKS);
        Ballistics.Solution s = Ballistics.solve(dx, h, speed, MAX_TICKS);
        assertNotNull(s);
        assertEquals(pitch, s.pitch(), 1e-6);
        return s.ticks();
    }

    @Test
    void flatTargetGetsSmallPositivePitchAndHits() {
        Ballistics.Solution s = Ballistics.solve(10, 0, 3.0, MAX_TICKS);
        assertNotNull(s);
        assertTrue(s.pitch() > 0, "pitch=" + deg(s.pitch()));
        assertTrue(deg(s.pitch()) < 10, "pitch=" + deg(s.pitch()));
        assertEquals(0.0, Ballistics.heightAt(10, s.pitch(), 3.0, MAX_TICKS), 1e-3);
    }

    @Test
    void higherAndLowerTargetsHitAndOrderPitches() {
        Ballistics.Solution flat = Ballistics.solve(15, 0, 3.0, MAX_TICKS);
        Ballistics.Solution up = Ballistics.solve(15, 5, 3.0, MAX_TICKS);
        Ballistics.Solution down = Ballistics.solve(15, -5, 3.0, MAX_TICKS);
        assertNotNull(flat);
        assertNotNull(up);
        assertNotNull(down);
        assertEquals(5.0, Ballistics.heightAt(15, up.pitch(), 3.0, MAX_TICKS), 1e-3);
        assertEquals(-5.0, Ballistics.heightAt(15, down.pitch(), 3.0, MAX_TICKS), 1e-3);
        assertTrue(down.pitch() < flat.pitch(), "lower target needs a smaller pitch");
        assertTrue(up.pitch() > flat.pitch(), "higher target needs a larger pitch");
    }

    @Test
    void nonRoundValuesHit() {
        assertHits(10 / 3.0, 1 / 3.0, 3.0);
        assertHits(37 / 3.0, -7 / 3.0, 2.7);
        assertHits(0.3, 0.1, 3.0);
    }

    @Test
    void unreachableTargetsReturnNull() {
        // speed 1 では水平方向に 100 * speed 以上は進めないので、200 は届かない。念のため全仰角で確かめる。
        for (double p = -80; p <= 89; p += 0.5) {
            double h = Ballistics.heightAt(200, Math.toRadians(p), 1.0, MAX_TICKS);
            assertTrue(Double.isNaN(h) || h < 0, "pitch=" + p + " h=" + h);
        }
        assertNull(Ballistics.solve(200, 0, 1.0, MAX_TICKS));
        assertNull(Ballistics.solve(5, 100, 3.0, MAX_TICKS));
    }

    @Test
    void maxTicksLimitMakesTargetUnreachable() {
        assertNotNull(Ballistics.solve(30, 0, 3.0, MAX_TICKS));
        assertNull(Ballistics.solve(30, 0, 3.0, 5));
        assertTrue(Double.isNaN(Ballistics.heightAt(30, 0.0, 3.0, 5)));
    }

    @Test
    void ticksGrowWithDistance() {
        Ballistics.Solution near = Ballistics.solve(10, 0, 3.0, MAX_TICKS);
        Ballistics.Solution far = Ballistics.solve(20, 0, 3.0, MAX_TICKS);
        assertNotNull(near);
        assertNotNull(far);
        assertTrue(far.ticks() > near.ticks(), near.ticks() + " vs " + far.ticks());
        // 10 ブロックを速度 3 で飛べば 3 tick 強。
        assertTrue(near.ticks() > 3.0 && near.ticks() < 4.5, "ticks=" + near.ticks());
    }

    @Test
    void lowArcIsChosen() {
        for (double dx : new double[] {5, 10, 10 / 3.0, 20, 30}) {
            Ballistics.Solution s = Ballistics.solve(dx, 0, 3.0, MAX_TICKS);
            assertNotNull(s, "dx=" + dx);
            assertTrue(deg(s.pitch()) < 45, "dx=" + dx + " pitch=" + deg(s.pitch()));
            // 低い弾道では、少し仰角を上げると同じ距離での高さが上がる。
            assertTrue(Ballistics.heightAt(dx, s.pitch() + Math.toRadians(0.1), 3.0, MAX_TICKS) > 0,
                    "dx=" + dx);
        }
    }

    @Test
    void zeroHorizontalDistanceEdgeCases() {
        Ballistics.Solution up = Ballistics.solve(0, 2, 3.0, MAX_TICKS);
        Ballistics.Solution down = Ballistics.solve(0, -2, 3.0, MAX_TICKS);
        Ballistics.Solution same = Ballistics.solve(0, 0, 3.0, MAX_TICKS);
        assertNotNull(up);
        assertNotNull(down);
        assertNotNull(same);
        assertEquals(90.0, deg(up.pitch()), 1e-9);
        assertEquals(-90.0, deg(down.pitch()), 1e-9);
        assertEquals(0.0, same.pitch(), 0.0);
        assertEquals(0.0, same.ticks(), 0.0);
        assertEquals(0.0, Ballistics.heightAt(0, 0.3, 3.0, MAX_TICKS), 0.0);
    }

    @Test
    void invalidInputsReturnNull() {
        assertNull(Ballistics.solve(-1, 0, 3.0, MAX_TICKS));
        assertNull(Ballistics.solve(Double.NaN, 0, 3.0, MAX_TICKS));
        assertNull(Ballistics.solve(10, 0, 0.0, MAX_TICKS));
    }

    @Test
    void directionIsUnitVector() {
        double[][] cases = {{1, 0, 0.3}, {3, 4, -0.7}, {-2.5, 7.1, 1.2}, {0, -5, 0.0}, {1e-3, 1e-3, 0.5}};
        for (double[] c : cases) {
            double[] d = Ballistics.direction(c[0], c[1], c[2]);
            assertEquals(3, d.length);
            assertEquals(1.0, Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), 1e-9);
            assertEquals(Math.sin(c[2]), d[1], 1e-12);
        }
    }

    @Test
    void directionAtZeroPitchIsHorizontalAndKeepsHeading() {
        double[] d = Ballistics.direction(3, 4, 0.0);
        assertEquals(0.0, d[1], 0.0);
        assertEquals(0.6, d[0], 1e-12);
        assertEquals(0.8, d[2], 1e-12);
    }

    @Test
    void directionWithZeroHeadingFallsBackToXAxis() {
        double[] d = Ballistics.direction(0, 0, 0.4);
        for (double v : d) {
            assertFalse(Double.isNaN(v));
        }
        assertEquals(Math.cos(0.4), d[0], 1e-12);
        assertEquals(Math.sin(0.4), d[1], 1e-12);
        assertEquals(0.0, d[2], 0.0);
    }
}
