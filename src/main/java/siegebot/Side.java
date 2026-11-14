package siegebot;

import java.util.Locale;

/** 攻城戦の陣営。 */
public enum Side {
    ATTACK, DEFEND;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Side opposite() {
        return this == ATTACK ? DEFEND : ATTACK;
    }

    public String label() {
        return this == ATTACK ? "攻め" : "守り";
    }

    /** "attack" / "defend"（大文字小文字は問わない）を読む。それ以外は null。 */
    public static Side parse(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "attack", "attacker", "attackers", "攻め", "攻撃" -> ATTACK;
            case "defend", "defender", "defenders", "守り", "防衛" -> DEFEND;
            default -> null;
        };
    }
}
