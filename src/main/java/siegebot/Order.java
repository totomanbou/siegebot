package siegebot;

/**
 * 兵士ボット 1 体の方針。座標は持たず、場所は毎 tick、侵攻度から引き直す。
 *
 * @param mode   FRONT: 侵攻度に応じた前線へ / HOLD: 名前付きの場所を守る / FOLLOW: プレイヤーについていく
 * @param place  HOLD のときの場所の正式名（それ以外は null）
 * @param player FOLLOW のときのプレイヤー名（それ以外は null）
 * @param stance PUSH: 広い範囲の敵を追う / GUARD: 持ち場の近くの敵とだけ戦う
 */
public record Order(Mode mode, String place, String player, Stance stance) {

    public enum Mode { FRONT, HOLD, FOLLOW }

    public enum Stance { PUSH, GUARD }

    public Order {
        if (mode == null || stance == null) {
            throw new IllegalArgumentException("mode と stance は必須");
        }
        if (mode == Mode.HOLD && (place == null || place.isBlank())) {
            throw new IllegalArgumentException("HOLD には place が必要");
        }
        if (mode == Mode.FOLLOW && (player == null || player.isBlank())) {
            throw new IllegalArgumentException("FOLLOW には player が必要");
        }
    }

    public static Order front() {
        return new Order(Mode.FRONT, null, null, Stance.PUSH);
    }

    public String describe() {
        String s = switch (mode) {
            case FRONT -> "前線";
            case HOLD -> "場所「" + place + "」";
            case FOLLOW -> player + " に追従";
        };
        return s + (stance == Stance.PUSH ? "・積極的" : "・持ち場優先");
    }
}
