package siegebot;

import java.util.Locale;

/** 兵種。剣兵は近づいて斬り、槍兵は届くぎりぎりの所から突き、弓兵は離れて矢を撃つ。兵種ごとの装備などは config.yml の units で決める。 */
public enum UnitType {
    SOLDIER("soldier", "剣兵"),
    ARCHER("archer", "弓兵"),
    SPEARMAN("spearman", "槍兵");

    private final String key;
    private final String label;

    UnitType(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** config.yml・data.yml・コマンドでの名前（soldier / archer / spearman）。 */
    public String key() {
        return key;
    }

    /** 画面に出す名前（剣兵 / 弓兵 / 槍兵）。 */
    public String label() {
        return label;
    }

    /** コマンドの引数から。soldier・sword・剣兵・剣 / archer・bow・弓兵・弓 / spearman・spear・槍兵・槍 を受け付ける。分からなければ null。 */
    public static UnitType parse(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "soldier", "sword", "swordsman", "剣兵", "剣" -> SOLDIER;
            case "archer", "bow", "弓兵", "弓" -> ARCHER;
            case "spearman", "spear", "lancer", "槍兵", "槍" -> SPEARMAN;
            default -> null;
        };
    }
}
