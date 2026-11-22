package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class AimTest {

    /** 厳密な計算（浮動小数の丸めだけ）の許容誤差。 */
    private static final double EPS = 1e-9;
    /** 揺れの統計テストで、標本の平均・標準偏差が理論値からずれてよい上限（ノイズの大きさ）。 */
    private static final double STAT_TOLERANCE = 0.1;
    /** 揺れの統計テストで使う size（度）。許容誤差の 20 倍あり、効果がノイズに埋もれない。 */
    private static final double SWAY_SIZE = 2.0;
    private static final int SWAY_STEPS = 200_000;
    private static final long SEED = 12345L;

    // ---- wrap ----

    @Test
    void wrapMapsIntoHalfOpenRange() {
        assertEquals(0.0, Aim.wrap(0), EPS);
        assertEquals(180.0, Aim.wrap(180), EPS);
        assertEquals(180.0, Aim.wrap(-180), EPS);
        assertEquals(-170.0, Aim.wrap(190), EPS);
        assertEquals(170.0, Aim.wrap(-190), EPS);
        assertEquals(180.0, Aim.wrap(540), EPS);
        assertEquals(-1.0, Aim.wrap(359), EPS);
    }

    // ---- yawPitch / dir ----

    @Test
    void yawZeroFacesPlusZ() {
        double[] d = Aim.dir(0, 0);
        assertEquals(0.0, d[0], EPS);
        assertEquals(0.0, d[1], EPS);
        assertEquals(1.0, d[2], EPS);

        double[] yp = Aim.yawPitch(0, 0, 1);
        assertEquals(0.0, yp[0], EPS);
        assertEquals(0.0, yp[1], EPS);
    }

    @Test
    void yaw90FacesMinusXAndYawMinus90FacesPlusX() {
        double[] d = Aim.dir(90, 0);
        assertEquals(-1.0, d[0], EPS);
        assertEquals(0.0, d[1], EPS);
        assertEquals(0.0, d[2], EPS);
        assertEquals(90.0, Aim.yawPitch(-1, 0, 0)[0], EPS);

        double[] e = Aim.dir(-90, 0);
        assertEquals(1.0, e[0], EPS);
        assertEquals(0.0, e[1], EPS);
        assertEquals(0.0, e[2], EPS);
        assertEquals(-90.0, Aim.yawPitch(1, 0, 0)[0], EPS);
    }

    @Test
    void positivePitchLooksDown() {
        double[] down = Aim.dir(0, 90);
        assertEquals(-1.0, down[1], EPS);
        double[] up = Aim.dir(0, -90);
        assertEquals(1.0, up[1], EPS);
        double[] d45 = Aim.dir(0, 45);
        assertTrue(d45[1] < 0, "pitch +45 は下向き y=" + d45[1]);
        // 真下を向く向きは pitch +90
        assertEquals(90.0, Aim.yawPitch(0, -1, 0)[1], EPS);
        assertEquals(-90.0, Aim.yawPitch(0, 1, 0)[1], EPS);
    }

    @Test
    void dirReturnsUnitVectors() {
        for (int yaw = -180; yaw <= 180; yaw += 15) {
            for (int pitch = -90; pitch <= 90; pitch += 15) {
                double[] d = Aim.dir(yaw, pitch);
                double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                assertEquals(1.0, len, EPS, "yaw=" + yaw + " pitch=" + pitch);
            }
        }
    }

    @Test
    void yawPitchInvertsDir() {
        for (int yaw = -170; yaw <= 170; yaw += 10) {
            for (int pitch = -80; pitch <= 80; pitch += 10) {
                double[] d = Aim.dir(yaw, pitch);
                double[] yp = Aim.yawPitch(d[0], d[1], d[2]);
                assertEquals(yaw, yp[0], 1e-7, "yaw=" + yaw + " pitch=" + pitch);
                assertEquals(pitch, yp[1], 1e-7, "yaw=" + yaw + " pitch=" + pitch);
            }
        }
    }

    // ---- clampPitch ----

    @Test
    void clampPitchLimitsToMinus90To90() {
        assertEquals(90.0, Aim.clampPitch(120), EPS);
        assertEquals(-90.0, Aim.clampPitch(-120), EPS);
        assertEquals(45.5, Aim.clampPitch(45.5), EPS);
        assertEquals(90.0, Aim.clampPitch(90), EPS);
        assertEquals(-90.0, Aim.clampPitch(-90), EPS);
    }

    // ---- turn ----

    @Test
    void turnWithNonPositiveMaxStepSnapsToTarget() {
        double[] a = Aim.turn(10, 20, 100, -30, 0);
        assertEquals(100.0, a[0], EPS);
        assertEquals(-30.0, a[1], EPS);
        double[] b = Aim.turn(10, 20, 100, -30, -5);
        assertEquals(100.0, b[0], EPS);
        assertEquals(-30.0, b[1], EPS);
    }

    @Test
    void turnReachesTargetExactlyWhenCloserThanMaxStep() {
        double[] r = Aim.turn(0, 0, 3, 4, 5.5);
        assertEquals(3.0, r[0], 0.0);
        assertEquals(4.0, r[1], 0.0);
        // ちょうど maxStep の距離でも届く
        double[] s = Aim.turn(0, 0, 3, 4, 5.0);
        assertEquals(3.0, s[0], 0.0);
        assertEquals(4.0, s[1], 0.0);
    }

    @Test
    void turnMovesExactlyMaxStepTowardTargetWhenFarther() {
        double[] r = Aim.turn(0, 0, 30, 40, 5);
        assertEquals(3.0, r[0], EPS);
        assertEquals(4.0, r[1], EPS);
        assertEquals(5.0, Math.hypot(r[0], r[1]), EPS);

        // 負の向き・初期位置が 0 でない場合
        double[] q = Aim.turn(20, 10, -10, -50, 10);
        // 差 (-30, -60) の長さ sqrt(4500)。向きは変わらず長さ 10。
        double dy = q[0] - 20;
        double dp = q[1] - 10;
        assertEquals(10.0, Math.hypot(dy, dp), EPS);
        assertEquals(-30.0 / Math.sqrt(4500) * 10, dy, EPS);
        assertEquals(-60.0 / Math.sqrt(4500) * 10, dp, EPS);
    }

    @Test
    void turnTakesShortestWayAcrossSeam() {
        double[] r = Aim.turn(170, 0, -170, 0, 5);
        assertEquals(175.0, r[0], EPS);
        assertEquals(0.0, r[1], EPS);

        // 逆向き: -170 → 170 は -175 側に回る
        double[] l = Aim.turn(-170, 0, 170, 0, 5);
        assertEquals(-175.0, l[0], EPS);

        // 継ぎ目を越えた結果も (-180, 180] に収まる
        double[] c = Aim.turn(179, 0, -179, 0, 5);
        assertEquals(-179.0, c[0], EPS);
        double[] m = Aim.turn(179.5, 0, -179.5, 0, 0.5);
        assertTrue(m[0] > -180.0 && m[0] <= 180.0, "yaw=" + m[0]);
        double[] n = Aim.turn(178, 0, -178, 0, 3);
        assertEquals(-179.0, n[0], EPS);
        assertTrue(n[0] > -180.0 && n[0] <= 180.0, "yaw=" + n[0]);
    }

    @Test
    void repeatedTurnReachesTargetInExpectedNumberOfCalls() {
        double yaw = 0;
        double pitch = 0;
        int calls = 0;
        while (Math.abs(yaw - 95) > EPS && calls < 100) {
            double[] r = Aim.turn(yaw, pitch, 95, 0, 10);
            yaw = r[0];
            pitch = r[1];
            calls++;
        }
        assertEquals(10, calls);
        assertEquals(95.0, yaw, EPS);
        assertEquals(0.0, pitch, EPS);
    }

    @Test
    void turnClampsPitchTarget() {
        double[] snap = Aim.turn(0, 0, 0, 120, 0);
        assertEquals(90.0, snap[1], EPS);
        double[] snapNeg = Aim.turn(0, 0, 0, -120, 0);
        assertEquals(-90.0, snapNeg[1], EPS);

        // 一度に届く場合も 90 で止まる
        double[] near = Aim.turn(0, 80, 0, 120, 50);
        assertEquals(90.0, near[1], EPS);

        // 届かない場合は 90 を目標として maxStep だけ進む（120 を目標にした長さではない）
        double[] part = Aim.turn(0, 0, 0, 120, 30);
        assertEquals(30.0, part[1], EPS);
    }

    // ---- sway ----

    @Test
    void swayNonPositiveSizeReturnsZero() {
        double[] a = Aim.sway(1.5, -2.5, 0, 1.0, 1.0);
        assertEquals(0.0, a[0], 0.0);
        assertEquals(0.0, a[1], 0.0);
        double[] b = Aim.sway(1.5, -2.5, -1.0, 1.0, 1.0);
        assertEquals(0.0, b[0], 0.0);
        assertEquals(0.0, b[1], 0.0);
    }

    @Test
    void swayDecaysByKeepWithoutNoise() {
        double[] r = Aim.sway(2.0, -4.0, 1.5, 0, 0);
        assertEquals(2.0 * Aim.SWAY_KEEP, r[0], EPS);
        assertEquals(-4.0 * Aim.SWAY_KEEP, r[1], EPS);
    }

    @Test
    void swayAddsGaussianScaledToKeepStationaryStd() {
        double k = SWAY_SIZE * Math.sqrt(1 - Aim.SWAY_KEEP * Aim.SWAY_KEEP);
        double[] r = Aim.sway(0, 0, SWAY_SIZE, 1.0, -2.0);
        assertEquals(k, r[0], EPS);
        assertEquals(-2.0 * k, r[1], EPS);
    }

    @Test
    void swayLongRunHasRequestedStandardDeviationAndZeroMean() {
        Random rnd = new Random(SEED);
        double yaw = 0;
        double pitch = 0;
        // 初期値 0 の影響を捨てる（0.85^1000 は無視できる）
        for (int i = 0; i < 1000; i++) {
            double[] s = Aim.sway(yaw, pitch, SWAY_SIZE, rnd.nextGaussian(), rnd.nextGaussian());
            yaw = s[0];
            pitch = s[1];
        }
        double sumY = 0;
        double sumP = 0;
        double sqY = 0;
        double sqP = 0;
        for (int i = 0; i < SWAY_STEPS; i++) {
            double[] s = Aim.sway(yaw, pitch, SWAY_SIZE, rnd.nextGaussian(), rnd.nextGaussian());
            yaw = s[0];
            pitch = s[1];
            sumY += yaw;
            sumP += pitch;
            sqY += yaw * yaw;
            sqP += pitch * pitch;
        }
        double meanY = sumY / SWAY_STEPS;
        double meanP = sumP / SWAY_STEPS;
        double stdY = Math.sqrt(sqY / SWAY_STEPS - meanY * meanY);
        double stdP = Math.sqrt(sqP / SWAY_STEPS - meanP * meanP);
        assertEquals(0.0, meanY, STAT_TOLERANCE, "yaw 平均");
        assertEquals(0.0, meanP, STAT_TOLERANCE, "pitch 平均");
        assertEquals(SWAY_SIZE, stdY, STAT_TOLERANCE, "yaw 標準偏差");
        assertEquals(SWAY_SIZE, stdP, STAT_TOLERANCE, "pitch 標準偏差");
    }
}
