package siegebot;

import java.util.List;

/** 侵攻度（開いた門の数）の計算。Bukkit に依存しない。 */
public final class StageLogic {

    private StageLogic() {
    }

    /**
     * 門の範囲のうち空気ブロックの割合が openAirRatio 以上なら「開いた」とみなす。
     *
     * @param airBlocks    範囲内の空気ブロックの数（0 以上 totalBlocks 以下）
     * @param totalBlocks  範囲内のブロックの総数（1 以上）
     * @param openAirRatio しきい値（0 より大きく 1 以下）
     * @throws IllegalArgumentException 前提を満たさない引数
     */
    public static boolean gateOpen(int airBlocks, int totalBlocks, double openAirRatio) {
        if (totalBlocks < 1 || airBlocks < 0 || airBlocks > totalBlocks) {
            throw new IllegalArgumentException("air=" + airBlocks + " total=" + totalBlocks);
        }
        if (!(openAirRatio > 0.0 && openAirRatio <= 1.0)) {
            throw new IllegalArgumentException("openAirRatio=" + openAirRatio);
        }
        return airBlocks >= openAirRatio * totalBlocks;
    }

    /**
     * 侵攻度 = 先頭から連続して開いている門の数。
     * 門は攻め手が通る順に並んでいる前提で、途中の門が閉じていれば、その先の門が開いていても数えない。
     */
    public static int stage(List<Boolean> gatesOpenInOrder) {
        int n = 0;
        for (Boolean open : gatesOpenInOrder) {
            if (!Boolean.TRUE.equals(open)) {
                break;
            }
            n++;
        }
        return n;
    }

    /** 段階の番号を 0〜gateCount に収める。 */
    public static int clamp(int stage, int gateCount) {
        if (gateCount < 0) {
            throw new IllegalArgumentException("gateCount=" + gateCount);
        }
        return Math.max(0, Math.min(stage, gateCount));
    }
}
