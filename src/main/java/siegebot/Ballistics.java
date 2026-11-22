package siegebot;

/**
 * 矢の弾道の計算（Bukkit に依存しない）。
 *
 * <p>1 tick ごとに「位置 += 速度」「速度 *= DRAG（3 成分とも）」「速度.y -= GRAVITY」をこの順に行う。
 * 発射位置は原点で、発射速度は大きさ speed（ブロック/tick）、仰角 θ（ラジアン、上向きが正）、
 * 鉛直面内で vx = speed * cos θ（水平）、vy = speed * sin θ とする。
 */
final class Ballistics {

    /** 1 tick ごとの速度の減衰（空気抵抗）。 */
    static final double DRAG = 0.99;
    /** 1 tick ごとの重力による速度.y の減少。 */
    static final double GRAVITY = 0.05;

    /** 解: 発射の仰角（ラジアン、上向きが正）と、水平距離 dx に届くまでの tick 数（小数。線形補間）。 */
    record Solution(double pitch, double ticks) {}

    /** 仰角を探す範囲の下限（ラジアン）。 */
    private static final double MIN_PITCH = Math.toRadians(-80.0);
    /** 最大高さの仰角を探す範囲の上限（ラジアン）。 */
    private static final double MAX_SCAN_PITCH = Math.toRadians(89.0);
    /** 粗い走査の刻み（ラジアン）。 */
    private static final double SCAN_STEP = Math.toRadians(0.5);
    /** 二分法を止める仰角の幅（ラジアン）。 */
    private static final double PITCH_EPS = 1e-10;
    private static final double GOLDEN = (Math.sqrt(5.0) - 1.0) / 2.0;

    private Ballistics() {}

    /**
     * 水平距離 dx（>= 0）・高さの差 dy の点に当たる低い弾道の仰角を求める。届かなければ null。
     * maxTicks（例 200）を超えても dx に届かない弾道は「届かない」とみなす。
     *
     * <p>dx、speed、maxTicks が不正（dx &lt; 0、NaN、speed &lt;= 0、maxTicks &lt; 1）のときも null。
     * dx == 0 のときは弾道を計算せず、dy &gt; 0 なら仰角 +90°、dy &lt; 0 なら -90°、dy == 0 なら 0 を返す
     * （tick 数はどれも 0。発射位置と同じ水平位置なので「届くまでの時間」は意味を持たない）。
     *
     * <p>dx での高さは、仰角が -80° から最大高さの仰角 θmax まで増え、その後は減ると仮定する。
     * θmax は -80°〜89° を 0.5° 刻みで走査して最大の標本を見つけ、その前後 0.5° を黄金分割法で詰めて求める
     * （dx に届かない仰角は -∞ として扱う）。低い弾道は [-80°, θmax] の二分法で求める。
     */
    static Solution solve(double dx, double dy, double speed, int maxTicks) {
        if (Double.isNaN(dx) || Double.isNaN(dy) || dx < 0 || !(speed > 0) || maxTicks < 1) {
            return null;
        }
        if (dx == 0) {
            if (dy > 0) {
                return new Solution(Math.PI / 2, 0.0);
            }
            if (dy < 0) {
                return new Solution(-Math.PI / 2, 0.0);
            }
            return new Solution(0.0, 0.0);
        }
        double thetaMax = maxHeightPitch(dx, speed, maxTicks);
        if (Double.isNaN(thetaMax)) {
            return null;
        }
        if (score(dx, thetaMax, speed, maxTicks) < dy) {
            return null;
        }
        double lo = MIN_PITCH;
        double hi = thetaMax;
        if (score(dx, lo, speed, maxTicks) > dy) {
            return null;
        }
        for (int i = 0; i < 60 && hi - lo >= PITCH_EPS; i++) {
            double mid = (lo + hi) / 2;
            if (score(dx, mid, speed, maxTicks) < dy) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double pitch = (lo + hi) / 2;
        double[] r = simulate(dx, pitch, speed, maxTicks);
        if (r == null) {
            return null;
        }
        return new Solution(pitch, r[1]);
    }

    /**
     * 仰角 pitch で撃ったとき、水平距離 dx の所での高さ（線形補間）。
     * maxTicks 以内に dx に届かなければ Double.NaN。dx &lt;= 0 のときは 0。
     */
    static double heightAt(double dx, double pitch, double speed, int maxTicks) {
        double[] r = simulate(dx, pitch, speed, maxTicks);
        return r == null ? Double.NaN : r[0];
    }

    /**
     * 単位ベクトル（x, y, z の配列）: 水平の向き (hx, hz)（正規化されていなくてよい、両方 0 なら x 方向）と仰角から。
     */
    static double[] direction(double hx, double hz, double pitch) {
        double n = Math.hypot(hx, hz);
        double ux;
        double uz;
        if (n < 1e-12 || Double.isNaN(n) || Double.isInfinite(n)) {
            ux = 1.0;
            uz = 0.0;
        } else {
            ux = hx / n;
            uz = hz / n;
        }
        double c = Math.cos(pitch);
        return new double[] {c * ux, Math.sin(pitch), c * uz};
    }

    /** dx に届かない（NaN）仰角を -∞ にした高さ。比較用。 */
    private static double score(double dx, double pitch, double speed, int maxTicks) {
        double h = heightAt(dx, pitch, speed, maxTicks);
        return Double.isNaN(h) ? Double.NEGATIVE_INFINITY : h;
    }

    /** dx での高さが最大になる仰角。どの仰角でも dx に届かなければ NaN。 */
    private static double maxHeightPitch(double dx, double speed, int maxTicks) {
        double best = Double.NEGATIVE_INFINITY;
        double bestPitch = Double.NaN;
        for (double p = MIN_PITCH; p <= MAX_SCAN_PITCH + 1e-12; p += SCAN_STEP) {
            double h = score(dx, p, speed, maxTicks);
            if (h > best) {
                best = h;
                bestPitch = p;
            }
        }
        if (Double.isNaN(bestPitch)) {
            return Double.NaN;
        }
        double a = Math.max(MIN_PITCH, bestPitch - SCAN_STEP);
        double b = Math.min(MAX_SCAN_PITCH, bestPitch + SCAN_STEP);
        double c = b - GOLDEN * (b - a);
        double d = a + GOLDEN * (b - a);
        double fc = score(dx, c, speed, maxTicks);
        double fd = score(dx, d, speed, maxTicks);
        for (int i = 0; i < 60 && b - a >= PITCH_EPS; i++) {
            if (fc >= fd) {
                b = d;
                d = c;
                fd = fc;
                c = b - GOLDEN * (b - a);
                fc = score(dx, c, speed, maxTicks);
            } else {
                a = c;
                c = d;
                fc = fd;
                d = a + GOLDEN * (b - a);
                fd = score(dx, d, speed, maxTicks);
            }
        }
        double refined = (a + b) / 2;
        // 黄金分割で詰めた点が走査の最大の標本より低いとき（平らな頂上など）は、標本の方を採る。
        return score(dx, refined, speed, maxTicks) >= best ? refined : bestPitch;
    }

    /**
     * 1 tick ずつ動かし、水平位置が最初に dx 以上になったときの {高さ, tick 数} を返す。
     * 前の位置と今の位置の間を水平方向の割合で線形補間する。maxTicks 以内に届かなければ null。
     * dx &lt;= 0 のときは {0, 0}。
     */
    private static double[] simulate(double dx, double pitch, double speed, int maxTicks) {
        if (Double.isNaN(dx) || Double.isNaN(pitch) || Double.isNaN(speed)) {
            return null;
        }
        if (dx <= 0) {
            return new double[] {0.0, 0.0};
        }
        double x = 0.0;
        double y = 0.0;
        double vx = speed * Math.cos(pitch);
        double vy = speed * Math.sin(pitch);
        for (int t = 1; t <= maxTicks; t++) {
            double px = x;
            double py = y;
            x += vx;
            y += vy;
            vx *= DRAG;
            vy *= DRAG;
            vy -= GRAVITY;
            if (x >= dx) {
                double frac = (dx - px) / (x - px);
                return new double[] {py + frac * (y - py), (t - 1) + frac};
            }
        }
        return null;
    }
}
