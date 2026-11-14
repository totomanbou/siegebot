package siegebot;

/**
 * 兵士ボット 1 体の方針。動きは「追従」か「地点を守る」の 2 つで、それぞれに構えがある。
 *
 * @param mode   FOLLOW: player についていく / HOLD: point を守る
 * @param player FOLLOW のときの相手のプレイヤー名（HOLD では null）
 * @param point  HOLD のときの地点（FOLLOW では null）
 * @param stance PUSH: 広い範囲の敵を追う / GUARD: 持ち場の近くの敵とだけ戦う
 */
public record Order(Mode mode, String player, Point point, Stance stance) {

    public enum Mode { FOLLOW, HOLD }

    public enum Stance { PUSH, GUARD }

    public Order {
        if (mode == null || stance == null) {
            throw new IllegalArgumentException("mode と stance は必須");
        }
        if (mode == Mode.FOLLOW && (player == null || player.isBlank())) {
            throw new IllegalArgumentException("FOLLOW には player が必要");
        }
        if (mode == Mode.HOLD && point == null) {
            throw new IllegalArgumentException("HOLD には point が必要");
        }
        if (mode == Mode.FOLLOW) {
            point = null;
        } else {
            player = null;
        }
    }

    public static Order follow(String player, Stance stance) {
        return new Order(Mode.FOLLOW, player, null, stance);
    }

    public static Order hold(Point point, Stance stance) {
        return new Order(Mode.HOLD, null, point, stance);
    }

    /** 動きはそのままで、構えだけを変えたもの。 */
    public Order withStance(Stance s) {
        return new Order(mode, player, point, s);
    }

    public String describe() {
        String s = mode == Mode.FOLLOW ? player + " に追従" : "地点 " + point.coords() + " を守る";
        return s + (stance == Stance.PUSH ? "・積極的" : "・持ち場優先");
    }
}
