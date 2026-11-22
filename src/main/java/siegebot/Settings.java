package siegebot;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** config.yml の内容（読み取り専用）。 */
public final class Settings {

    public final String attackTeam;
    public final String defendTeam;

    /** プレイヤー 1 人に 1 体の専用のボットを出し、指示は自分のボットにだけ効くようにする。 */
    public final boolean personalBots;
    /** 1 人が持てる専用のボットの上限（1〜9）。 */
    public final int maxBotsPerPlayer;
    /** 「視点の先を守れ」で狙える最大の距離（ブロック）。 */
    public final double holdMaxDistance;
    /** 「呼び寄せる」を続けて使えない時間（tick）。0 なら制限なし。 */
    public final int callCooldownTicks;
    /** CombatLog で戦闘中のプレイヤーは「呼び寄せる」を使えない。 */
    public final boolean callBlockedInCombat;
    /** 「退却」で戻る途中、この時間（tick）たっても着かなければ退却をやめる。 */
    public final int retreatTimeoutTicks;

    /** true: ボットの頭の上の名前を同じ陣営のプレイヤーにだけ見せる。false: 全員に見せる（Citizens の表示）。 */
    public final boolean nameTagTeamOnly;

    public final double engageRadius;
    public final double pushLeash;
    public final double guardLeash;
    public final double followDistance;
    public final double arriveRadius;
    public final float pathRange;
    /** 経路探索で飛び降りてよい高さ（ブロック）。 */
    public final int maxFallDistance;
    /** false ならボットは落下のダメージを受けない。 */
    public final boolean fallDamage;
    /** 自動の回復: 1 回に回復する体力（1.0 でハート半分）。0 なら回復しない。 */
    public final double regenAmount;
    /** 自動の回復の間隔（tick）。 */
    public final int regenIntervalTicks;
    /** ダメージを受けてから自動の回復を始めるまでの時間（tick）。 */
    public final int regenDelayTicks;
    public final float speed;
    /** 狙いを選び直す間隔（tick）。 */
    public final int retargetTicks;
    /** 火力: ボットが与えるダメージの倍率（1.0 でそのまま）。 */
    public final double damageMultiplier;
    /** 防御力: ボットが受けるダメージをこの値で割る（1.0 でそのまま。0 以下にはしない）。 */
    public final double defenseMultiplier;
    public final double attackRange;
    public final int attackDelayTicks;
    public final String pathfinder;

    /** false なら盾を構えない（左手の盾も持たせない）。 */
    public final boolean shieldEnabled;
    /** 狙っている敵・飛んでくる矢に気づく距離（ブロック）。 */
    public final double shieldDetectRadius;
    /** 構えている間の移動の速さ（speed に掛ける倍率）。 */
    public final double shieldMoveSpeedMultiplier;
    /** 脅威がなくなってから盾を下ろすまでの時間（tick。最小 2）。 */
    public final int shieldLowerAfterTicks;
    /** 盾を構えてから防げるようになるまでの時間（tick。バニラのプレイヤーは 5）。 */
    public final int shieldBlockingDelayTicks;

    /** 兵種ごとの設定（全兵種が入っている）。 */
    public final Map<UnitType, Unit> units = new java.util.EnumMap<>(UnitType.class);
    /** /sb revive・/sb spawn で兵種を省いたときに新しく出す兵種。 */
    public final UnitType defaultUnit;

    /** 弓兵: 矢を撃つ最大の距離（ブロック）。これより遠い・見えない敵へは近づく。 */
    public final double archerRange;
    /** 弓兵: 近すぎる敵から下がるときは、敵からこの距離まで離れる（ブロック）。 */
    public final double archerKeepDistance;
    /** 弓兵: 敵がこれより近いと下がって距離を取る（ブロック）。 */
    public final double archerMinDistance;
    /** 弓兵: 弓を引き絞る時間（tick。20 で引き切り）。 */
    public final int archerDrawTicks;
    /** 弓兵: 撃ってから次に引き始めるまでの時間（tick）。 */
    public final int archerShotIntervalTicks;
    /** 弓兵: 矢の速さ（ブロック/tick。バニラの弓を引き切ると 3.0）。 */
    public final double archerArrowSpeed;
    /** 弓兵: 矢の基本ダメージ（既定 3.0。バニラは 2.0。当たったときのダメージは速さ × これ。damage-multiplier も掛かる）。 */
    public final double archerArrowDamage;
    /** 弓兵: 狙いのぶれ（バニラのプレイヤーの弓は 1.0。0 で必ず狙ったとおりに飛ぶ）。 */
    public final double archerInaccuracy;

    /** 兵種 1 つ分の設定。 */
    public static final class Unit {
        /** 専用のボットの名前の後ろに付ける文字（「名前の兵」の「の兵」）。 */
        public final String nameSuffix;
        /** 装備の部位（hand, off_hand, helmet, chestplate, leggings, boots）→ アイテム。 */
        public final Map<String, Material> equipment;

        Unit(String nameSuffix, Map<String, Material> equipment) {
            this.nameSuffix = nameSuffix;
            this.equipment = equipment;
        }
    }

    public Settings(FileConfiguration c) {
        attackTeam = c.getString("teams.attack", "Attack");
        defendTeam = c.getString("teams.defend", "Defence");

        personalBots = c.getBoolean("personal-bots.enabled", true);
        maxBotsPerPlayer = Math.max(1, Math.min(9, c.getInt("personal-bots.max-per-player", 5)));
        holdMaxDistance = Math.max(4, c.getDouble("orders.hold-max-distance", 48));
        callCooldownTicks = (int) Math.round(Math.max(0, c.getDouble("orders.call-cooldown-seconds", 0)) * 20);
        callBlockedInCombat = c.getBoolean("orders.call-blocked-in-combat", true);
        retreatTimeoutTicks = (int) Math.round(Math.max(1, c.getDouble("orders.retreat-timeout-seconds", 20)) * 20);

        nameTagTeamOnly = !"all".equalsIgnoreCase(c.getString("name-tag.visible-to", "team"));

        engageRadius = c.getDouble("combat.engage-radius", 16);
        pushLeash = c.getDouble("combat.push-leash", 24);
        guardLeash = c.getDouble("combat.guard-leash", 8);
        followDistance = c.getDouble("combat.follow-distance", 4);
        arriveRadius = c.getDouble("combat.arrive-radius", 3);
        pathRange = (float) c.getDouble("combat.path-range", 96);
        maxFallDistance = Math.max(0, c.getInt("combat.max-fall-distance", 384));
        fallDamage = c.getBoolean("combat.fall-damage", true);
        regenAmount = Math.max(0.0, c.getDouble("combat.regen-amount", 1.0));
        regenIntervalTicks = Math.max(BotManager.TICK_PERIOD,
                (int) Math.round(c.getDouble("combat.regen-interval-seconds", 2.0) * 20));
        regenDelayTicks = (int) Math.round(Math.max(0, c.getDouble("combat.regen-delay-seconds", 10.0)) * 20);
        speed = (float) c.getDouble("combat.speed", 1.5);
        damageMultiplier = Math.max(0.0, c.getDouble("combat.damage-multiplier", 1.2));
        defenseMultiplier = Math.max(0.1, c.getDouble("combat.defense-multiplier", 1.2));
        retargetTicks = Math.max(BotManager.TICK_PERIOD,
                (int) Math.round(c.getDouble("combat.retarget-seconds", 2.0) * 20));
        attackRange = c.getDouble("combat.attack-range", 3.0);
        attackDelayTicks = Math.max(1, c.getInt("combat.attack-delay-ticks", 12));
        pathfinder = c.getString("combat.pathfinder", "MINECRAFT").toUpperCase(Locale.ROOT);

        shieldEnabled = c.getBoolean("shield.enabled", true);
        shieldDetectRadius = Math.max(1.0, c.getDouble("shield.detect-radius", 24));
        shieldMoveSpeedMultiplier = Math.max(0.05, c.getDouble("shield.move-speed-multiplier", 0.3));
        shieldLowerAfterTicks = Math.max(Shields.TICK_PERIOD,
                (int) Math.round(c.getDouble("shield.lower-after-seconds", 0.5) * 20));
        shieldBlockingDelayTicks = Math.max(0, c.getInt("shield.blocking-delay-ticks", 0));

        // 兵種。units.soldier.equipment がなければ、0.6 までの書き方（一番上の equipment）を剣兵の装備にする。
        // getConfigurationSection は、サーバーの config.yml にない節でも jar の既定にあれば空の節を作って返すので、
        // 「書いてあるか」は contains(path, true)（既定を見ない）で確かめる
        ConfigurationSection oldEq = own(c, "equipment");
        units.put(UnitType.SOLDIER, new Unit(
                c.getString("units.soldier.name-suffix", "の兵"),
                readEquipment(own(c, "units.soldier.equipment"), oldEq, Map.of(
                        "hand", Material.IRON_SWORD, "off_hand", Material.SHIELD,
                        "helmet", Material.IRON_HELMET, "chestplate", Material.IRON_CHESTPLATE,
                        "leggings", Material.IRON_LEGGINGS, "boots", Material.IRON_BOOTS))));
        units.put(UnitType.ARCHER, new Unit(
                c.getString("units.archer.name-suffix", "の弓兵"),
                readEquipment(own(c, "units.archer.equipment"), null, Map.of(
                        "hand", Material.BOW,
                        "helmet", Material.CHAINMAIL_HELMET, "chestplate", Material.LEATHER_CHESTPLATE,
                        "leggings", Material.LEATHER_LEGGINGS, "boots", Material.LEATHER_BOOTS))));
        UnitType def = UnitType.parse(c.getString("units.default", "soldier"));
        defaultUnit = def == null ? UnitType.SOLDIER : def;

        archerRange = Math.max(4.0, c.getDouble("units.archer.range", 16));
        archerMinDistance = Math.max(0.0, c.getDouble("units.archer.min-distance", 5));
        archerKeepDistance = Math.max(archerMinDistance + 1, Math.min(archerRange,
                c.getDouble("units.archer.keep-distance", 10)));
        archerDrawTicks = Math.max(3, c.getInt("units.archer.draw-ticks", 20));
        archerShotIntervalTicks = Math.max(0, c.getInt("units.archer.shot-interval-ticks", 10));
        archerArrowSpeed = Math.max(0.5, Math.min(5.0, c.getDouble("units.archer.arrow-speed", 3.0)));
        archerArrowDamage = Math.max(0.0, c.getDouble("units.archer.arrow-damage", 3.0));
        archerInaccuracy = Math.max(0.0, c.getDouble("units.archer.inaccuracy", 1.0));
    }

    /** サーバーの config.yml に書いてある節（jar の既定だけにある節は null）。 */
    private static ConfigurationSection own(FileConfiguration c, String path) {
        return c.contains(path, true) ? c.getConfigurationSection(path) : null;
    }

    /**
     * 装備の節を読む。sec がなければ fallback の節、それもなければ defaults。
     * 節があれば書いてある部位だけを使う（書いていない部位は何も持たない）。
     */
    private static Map<String, Material> readEquipment(ConfigurationSection sec, ConfigurationSection fallback,
                                                       Map<String, Material> defaults) {
        ConfigurationSection use = sec != null ? sec : fallback;
        Map<String, Material> r = new LinkedHashMap<>();
        if (use == null) {
            for (String slot : new String[] {"hand", "off_hand", "helmet", "chestplate", "leggings", "boots"}) {
                if (defaults.containsKey(slot)) {
                    r.put(slot, defaults.get(slot));
                }
            }
            return r;
        }
        for (String slot : use.getKeys(false)) {
            Material m = Material.matchMaterial(String.valueOf(use.getString(slot, "")));
            if (m != null && m != Material.AIR) {
                String k = slot.toLowerCase(Locale.ROOT);
                r.put(k.equals("offhand") ? "off_hand" : k, m);
            }
        }
        // 0.6 までの書き方では盾を書かずに自動で持たせていたので、古い節を読んだときは左手が未指定なら盾にする
        if (sec == null && fallback != null && !r.containsKey("off_hand") && defaults.containsKey("off_hand")) {
            r.put("off_hand", defaults.get("off_hand"));
        }
        return r;
    }

    public Unit unit(UnitType t) {
        return units.get(t == null ? defaultUnit : t);
    }

    public String teamName(Side side) {
        return side == Side.ATTACK ? attackTeam : defendTeam;
    }
}
