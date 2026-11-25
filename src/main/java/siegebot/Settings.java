package siegebot;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

/** config.yml の内容（読み取り専用）。 */
public final class Settings {

    public final String attackTeam;
    public final String defendTeam;

    /** 試験用: 持ち主のいないボット（/sb spawn・/sb revive bots・data.yml の一覧）を使う。本サーバーでは false のまま。 */
    public final boolean testBots;
    /** 1 人が持てる専用のボットの上限（1〜30）。 */
    public final int maxBotsPerPlayer;
    /** 「視点の先を守れ」で狙える最大の距離（ブロック）。 */
    public final double holdMaxDistance;
    /** 「呼び寄せる」を続けて使えない時間（tick）。0 なら制限なし。 */
    public final int callCooldownTicks;
    /** CombatLog で戦闘中のプレイヤーは「呼び寄せる」を使えない。 */
    public final boolean callBlockedInCombat;
    /** 「退却」で戻る途中、この時間（tick）たっても着かなければ退却をやめる。 */
    public final int retreatTimeoutTicks;

    /** true: ボットの頭の上の名前を同じ陣営のプレイヤーにだけ見せる。false: 全員に見せる。 */
    public final boolean nameTagTeamOnly;

    public final double engageRadius;
    public final double pushLeash;
    public final double guardLeash;
    public final double followDistance;
    public final double arriveRadius;
    /** ボットどうしが重ならないように押し分ける（separation.enabled）。 */
    public final boolean separationEnabled;
    /** 押し分けるときの最小の水平距離（ブロック。0.6〜1.5）。 */
    public final double separationMinDistance;
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
    /** /sb revive で兵種を省いたときに新しく出す兵種。 */
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
    /** 弓兵: 移動している敵への先読みの割合（1.0 で正確に先読み、0.0 で先読みしない。0.0〜1.5）。units.archer.aim.lead-factor。 */
    public final double archerLeadFactor;

    /** 槍兵: 届くぎりぎりから突くとき、届く距離からこれだけ（ブロック）内側に入ったら止まる。 */
    public final double spearStopInside;
    /** 槍兵: 敵が届く距離よりこれだけ（ブロック）内側まで寄ってきたら、下がって間合いを取り直す。 */
    public final double spearBackOffInside;

    /** 槍兵: 敵を追う道に蜘蛛の巣があれば追わない。 */
    public final boolean spearAvoidCobweb;
    /** 槍兵: 脅威がなくなってから盾を下ろすまでの時間（tick。既定 0 = すぐ下ろす）。 */
    public final int spearShieldLowerAfterTicks;

    /** config.yml の読み込みで見つかった問題（読めないアイテムなど）。プラグインがログに出す。 */
    public final java.util.List<String> warnings = new java.util.ArrayList<>();

    /** 兵種 1 つ分の設定。 */
    public static final class Unit {
        /** 専用のボットの名前の後ろに付ける文字（「名前の兵」の「の兵」）。 */
        public final String nameSuffix;
        /** 装備の部位（hand, off_hand, helmet, chestplate, leggings, boots）→ アイテム（出すたびに写して使う）。 */
        public final Map<String, ItemStack> equipment;
        /** 攻撃の届く距離（ブロック）。剣兵と弓兵は combat.attack-range（弓兵は殴らない）、槍兵は units.spearman.attack-range。 */
        public final double attackRange;
        /** 攻撃の間隔（tick）。 */
        public final int attackDelayTicks;
        /** 移動の速さの倍率（書いていなければ combat.speed）。 */
        public final float speed;
        /** 火力（書いていなければ combat.damage-multiplier）。 */
        public final double damageMultiplier;
        /** 防御力（書いていなければ combat.defense-multiplier）。 */
        public final double defenseMultiplier;
        /** 最大の体力（書いていなければ 20 = ハート 10 個）。 */
        public final double maxHealth;
        /** エイム: 照準が回る速さの上限（度/tick。0 以下なら一瞬で向く）。aim.turn-speed。 */
        public final double aimTurnSpeed;
        /** エイム: 照準の揺れの大きさ（度。0 なら揺れない）。aim.sway。 */
        public final double aimSway;
        /** エイム: 照準を 1 tick に 1 度回すごとに、その tick の揺れに足す大きさ（度）。aim.turn-sway。 */
        public final double aimTurnSway;
        /** エイム: 反応の遅れ（tick。0 で遅れなし。0〜20）。aim.reaction-ticks。 */
        public final int aimReactionTicks;
        /** 盾の耐久（防げる量。0 なら盾のアイテムのまま = バニラは 336）。shield-durability。 */
        public final int shieldDurability;

        Unit(String nameSuffix, Map<String, ItemStack> equipment, double attackRange, int attackDelayTicks,
             float speed, double damageMultiplier, double defenseMultiplier, double maxHealth,
             double aimTurnSpeed, double aimSway, double aimTurnSway, int aimReactionTicks, int shieldDurability) {
            this.nameSuffix = nameSuffix;
            this.equipment = equipment;
            this.attackRange = attackRange;
            this.attackDelayTicks = attackDelayTicks;
            this.speed = speed;
            this.damageMultiplier = damageMultiplier;
            this.defenseMultiplier = defenseMultiplier;
            this.maxHealth = maxHealth;
            this.aimTurnSpeed = aimTurnSpeed;
            this.aimSway = aimSway;
            this.aimTurnSway = aimTurnSway;
            this.aimReactionTicks = aimReactionTicks;
            this.shieldDurability = shieldDurability;
        }
    }

    /**
     * 兵種の数値の上書き。サーバーの config.yml に units.&lt;兵種&gt;.&lt;key&gt; が書いてあればその値、
     * なければ fallback（combat の値）。jar の既定の config.yml の値は見ない（contains(path, true)）。
     */
    private static double over(FileConfiguration c, String unit, String key, double fallback) {
        String path = "units." + unit + "." + key;
        return c.contains(path, true) ? c.getDouble(path, fallback) : fallback;
    }

    /**
     * turnSpeed・sway・turnSway・reactionTicks・shieldDurability は、その兵種の
     * aim.turn-speed・aim.sway・aim.turn-sway・aim.reaction-ticks・shield-durability の既定。
     */
    private Unit unit(FileConfiguration c, String unit, String suffix, Map<String, ItemStack> eq,
                      double attackRange, int attackDelayTicks, double turnSpeed, double sway, double turnSway,
                      int reactionTicks, int shieldDurability) {
        return new Unit(suffix, eq, attackRange, attackDelayTicks,
                (float) over(c, unit, "speed", speed),
                Math.max(0.0, over(c, unit, "damage-multiplier", damageMultiplier)),
                Math.max(0.1, over(c, unit, "defense-multiplier", defenseMultiplier)),
                Math.max(1.0, Math.min(1024.0, over(c, unit, "max-health", 20.0))),
                over(c, unit, "aim.turn-speed", turnSpeed),
                Math.max(0.0, Math.min(45.0, over(c, unit, "aim.sway", sway))),
                Math.max(0.0, Math.min(5.0, over(c, unit, "aim.turn-sway", turnSway))),
                (int) Math.max(0, Math.min(20, Math.round(over(c, unit, "aim.reaction-ticks", reactionTicks)))),
                (int) Math.max(0, Math.min(100000, Math.round(over(c, unit, "shield-durability", shieldDurability)))));
    }

    /** アイテムの文字列を読む関数（本番は {@link #parseItem}。試験では ItemStack を作らない関数に替える）。 */
    private final Function<String, ItemStack> itemParser;

    public Settings(FileConfiguration c) {
        this(c, Settings::parseItem);
    }

    Settings(FileConfiguration c, Function<String, ItemStack> itemParser) {
        this.itemParser = itemParser;
        attackTeam = c.getString("teams.attack", "Attack");
        defendTeam = c.getString("teams.defend", "Defence");

        if (c.contains("personal-bots.enabled", true) && !c.getBoolean("personal-bots.enabled")) {
            warnings.add("personal-bots.enabled: false は廃止しました（ボットは常にプレイヤーの専用のボットです）。この行は消してください。");
        }
        testBots = c.getBoolean("test-bots.enabled", false);
        maxBotsPerPlayer = Math.max(1, Math.min(30, c.getInt("personal-bots.max-per-player", 5)));
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
        separationEnabled = c.getBoolean("separation.enabled", true);
        // 上限は横隊の間隔より少し小さく（横隊の位置どうしが押し合わないように）
        separationMinDistance = Math.max(0.6, Math.min(Formation.FORMATION_SPACING - 0.1,
                c.getDouble("separation.min-distance", 0.9)));
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
        units.put(UnitType.SOLDIER, unit(c, "soldier",
                c.getString("units.soldier.name-suffix", "の兵"),
                readEquipment("soldier", own(c, "units.soldier.equipment"), oldEq, SOLDIER_EQUIPMENT),
                Math.max(1.0, over(c, "soldier", "attack-range", attackRange)),
                Math.max(1, (int) Math.round(over(c, "soldier", "attack-delay-ticks", attackDelayTicks))),
                15.0, 5.5, 0.8, 2, 0));
        units.put(UnitType.ARCHER, unit(c, "archer",
                c.getString("units.archer.name-suffix", "の弓兵"),
                readEquipment("archer", own(c, "units.archer.equipment"), null, ARCHER_EQUIPMENT),
                attackRange, attackDelayTicks, 9.0, 1.8, 1.0, 2, 0));
        units.put(UnitType.SPEARMAN, unit(c, "spearman",
                c.getString("units.spearman.name-suffix", "の槍兵"),
                readEquipment("spearman", own(c, "units.spearman.equipment"), null, SPEARMAN_EQUIPMENT),
                Math.max(1.0, Math.min(8.0, c.getDouble("units.spearman.attack-range", 4.5))),
                Math.max(1, c.getInt("units.spearman.attack-delay-ticks", 22)),
                12.0, 3.8, 0.7, 2, 168)); // 槍兵の盾はバニラ（336）の半分
        spearStopInside = Math.max(0.0, c.getDouble("units.spearman.stop-inside", 0.2));
        spearAvoidCobweb = c.getBoolean("units.spearman.avoid-cobweb", true);
        spearShieldLowerAfterTicks = (int) Math.round(Math.max(0.0,
                c.getDouble("units.spearman.shield-lower-after-seconds", 0.0)) * 20);
        spearBackOffInside = Math.max(spearStopInside + 0.5, c.getDouble("units.spearman.back-off-inside", 1.5));
        UnitType def = UnitType.parse(c.getString("units.default", "soldier"));
        defaultUnit = def == null ? UnitType.SOLDIER : def;

        archerRange = Math.max(4.0, c.getDouble("units.archer.range", 32));
        archerMinDistance = Math.max(0.0, c.getDouble("units.archer.min-distance", 5));
        archerKeepDistance = Math.max(archerMinDistance + 1, Math.min(archerRange,
                c.getDouble("units.archer.keep-distance", 10)));
        archerDrawTicks = Math.max(3, c.getInt("units.archer.draw-ticks", 20));
        archerShotIntervalTicks = Math.max(0, c.getInt("units.archer.shot-interval-ticks", 10));
        archerArrowSpeed = Math.max(0.5, Math.min(5.0, c.getDouble("units.archer.arrow-speed", 3.0)));
        archerArrowDamage = Math.max(0.0, c.getDouble("units.archer.arrow-damage", 3.0));
        archerInaccuracy = Math.max(0.0, c.getDouble("units.archer.inaccuracy", 1.0));
        archerLeadFactor = Math.max(0.0, Math.min(1.5, c.getDouble("units.archer.aim.lead-factor", 0.7)));
    }

    /** サーバーの config.yml に書いてある節（jar の既定だけにある節は null）。 */
    private static ConfigurationSection own(FileConfiguration c, String path) {
        return c.contains(path, true) ? c.getConfigurationSection(path) : null;
    }

    /**
     * 装備の節を読む。sec がなければ fallback の節、それもなければ defaults。
     * 節があれば書いてある部位だけを使う（書いていない部位は何も持たない）。
     * アイテムは ID（IRON_SWORD・minecraft:iron_sword）か、/give と同じ部品つきの書き方
     * （minecraft:iron_axe[custom_model_data={strings:["1"]}]）。読めないものは warnings に入れて飛ばす。
     */
    private Map<String, ItemStack> readEquipment(String unit, ConfigurationSection sec, ConfigurationSection fallback,
                                                 Map<String, String> defaults) {
        ConfigurationSection use = sec != null ? sec : fallback;
        Map<String, String> specs = new LinkedHashMap<>();
        if (use == null) {
            for (String slot : SLOTS) {
                if (defaults.containsKey(slot)) {
                    specs.put(slot, defaults.get(slot));
                }
            }
        } else {
            for (String slot : use.getKeys(false)) {
                specs.put(normalizeSlotKey(slot), String.valueOf(use.getString(slot, "")));
            }
            // 0.6 までの書き方では盾を書かずに自動で持たせていたので、古い節を読んだときは左手が未指定なら盾にする
            if (sec == null && fallback != null && !specs.containsKey("off_hand") && defaults.containsKey("off_hand")) {
                specs.put("off_hand", defaults.get("off_hand"));
            }
        }
        Map<String, ItemStack> r = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : specs.entrySet()) {
            if (isExplicitEmptyItem(e.getValue())) {
                continue; // 空・AIR・NONE は「何も持たない」の明示（警告しない）
            }
            ItemStack item = itemParser.apply(e.getValue());
            if (item == null) {
                warnings.add("units." + unit + ".equipment." + e.getKey() + " のアイテム「" + e.getValue() + "」が読めません（飛ばします）");
            } else {
                r.put(e.getKey(), item);
            }
        }
        return r;
    }

    private static final String[] SLOTS = {"hand", "off_hand", "helmet", "chestplate", "leggings", "boots"};

    private static final Map<String, String> SOLDIER_EQUIPMENT = Map.of(
            "hand", "IRON_SWORD", "off_hand", "SHIELD",
            "helmet", "IRON_HELMET", "chestplate", "IRON_CHESTPLATE",
            "leggings", "IRON_LEGGINGS", "boots", "IRON_BOOTS");
    private static final Map<String, String> ARCHER_EQUIPMENT = Map.of(
            "hand", "BOW",
            "helmet", "CHAINMAIL_HELMET", "chestplate", "LEATHER_CHESTPLATE",
            "leggings", "LEATHER_LEGGINGS", "boots", "LEATHER_BOOTS");
    // 槍兵の既定の槍は、本サーバーで槍の見た目になる鉄の斧（custom_model_data の文字 "1"）
    private static final Map<String, String> SPEARMAN_EQUIPMENT = Map.of(
            "hand", "minecraft:iron_axe[custom_model_data={strings:[\"1\"]}]", "off_hand", "SHIELD",
            "helmet", "IRON_HELMET", "chestplate", "IRON_CHESTPLATE",
            "leggings", "CHAINMAIL_LEGGINGS", "boots", "IRON_BOOTS");

    /** 兵種ごとの組み込みの既定の装備（部位 → アイテムの書き方。hand, off_hand, ... の順）。 */
    static Map<String, String> defaultEquipment(UnitType type) {
        Map<String, String> src = type == UnitType.ARCHER ? ARCHER_EQUIPMENT
                : type == UnitType.SPEARMAN ? SPEARMAN_EQUIPMENT : SOLDIER_EQUIPMENT;
        Map<String, String> r = new LinkedHashMap<>();
        for (String slot : SLOTS) {
            if (src.containsKey(slot)) {
                r.put(slot, src.get(slot));
            }
        }
        return r;
    }

    /** 「何も持たない」の明示: null・空白のみ・AIR・minecraft:air・NONE（大文字小文字は問わない）。 */
    static boolean isExplicitEmptyItem(String spec) {
        if (spec == null) {
            return true;
        }
        String t = spec.trim();
        return t.isEmpty() || t.equalsIgnoreCase("AIR") || t.equalsIgnoreCase("minecraft:air") || t.equalsIgnoreCase("NONE");
    }

    /** 装備の部位名を小文字にし、offhand は off_hand にそろえる。 */
    static String normalizeSlotKey(String slot) {
        String k = slot.toLowerCase(Locale.ROOT);
        return k.equals("offhand") ? "off_hand" : k;
    }

    private static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
            "teams.attack", "teams.defend",
            "personal-bots.max-per-player", "personal-bots.enabled",
            "test-bots.enabled",
            "orders.hold-max-distance", "orders.call-cooldown-seconds", "orders.call-blocked-in-combat",
            "orders.retreat-timeout-seconds",
            "name-tag.visible-to",
            "combat.engage-radius", "combat.push-leash", "combat.guard-leash", "combat.follow-distance",
            "combat.arrive-radius", "combat.path-range", "combat.max-fall-distance", "combat.fall-damage",
            "combat.regen-amount", "combat.regen-interval-seconds", "combat.regen-delay-seconds", "combat.speed",
            "combat.damage-multiplier", "combat.defense-multiplier", "combat.retarget-seconds",
            "combat.attack-range", "combat.attack-delay-ticks", "combat.pathfinder",
            "separation.enabled", "separation.min-distance",
            "shield.enabled", "shield.detect-radius", "shield.move-speed-multiplier", "shield.lower-after-seconds",
            "shield.blocking-delay-ticks",
            "units.default");
    private static final java.util.Set<String> UNIT_NAMES = java.util.Set.of("soldier", "spearman", "archer");
    private static final java.util.Set<String> UNIT_KEYS = java.util.Set.of(
            "name-suffix", "speed", "damage-multiplier", "defense-multiplier", "max-health", "attack-range",
            "attack-delay-ticks", "shield-durability",
            "aim.turn-speed", "aim.sway", "aim.turn-sway", "aim.reaction-ticks", "equipment");
    private static final java.util.Set<String> SPEARMAN_KEYS = java.util.Set.of(
            "stop-inside", "back-off-inside", "avoid-cobweb", "shield-lower-after-seconds");
    private static final java.util.Set<String> ARCHER_KEYS = java.util.Set.of(
            "range", "min-distance", "keep-distance", "draw-ticks", "shot-interval-ticks", "arrow-speed",
            "arrow-damage", "inaccuracy", "aim.lead-factor");
    private static final java.util.Set<String> EQUIPMENT_SLOTS = java.util.Set.of(
            "hand", "off_hand", "offhand", "helmet", "chestplate", "leggings", "boots");

    /** config.yml で意味のあるパス（葉。units.&lt;兵種&gt;.equipment の節そのものも含む）か。 */
    static boolean isKnownKey(String path) {
        if (path == null) {
            return false;
        }
        if (KNOWN_KEYS.contains(path)) {
            return true;
        }
        if (path.startsWith("equipment.")) { // 0.6 までの書き方
            return EQUIPMENT_SLOTS.contains(path.substring("equipment.".length()).toLowerCase(Locale.ROOT));
        }
        if (!path.startsWith("units.")) {
            return false;
        }
        String[] parts = path.split("\\.", 3);
        if (parts.length < 3 || !UNIT_NAMES.contains(parts[1])) {
            return false;
        }
        String unit = parts[1];
        String rest = parts[2];
        if (UNIT_KEYS.contains(rest)
                || (unit.equals("spearman") && SPEARMAN_KEYS.contains(rest))
                || (unit.equals("archer") && ARCHER_KEYS.contains(rest))) {
            return true;
        }
        return rest.startsWith("equipment.")
                && EQUIPMENT_SLOTS.contains(rest.substring("equipment.".length()).toLowerCase(Locale.ROOT));
    }

    /** マップ・陣営の上書きに書いてよいパス（旧 0.6 の equipment.* と廃止の personal-bots.enabled は不可）。 */
    static boolean isAllowedInOverride(String path) {
        return isKnownKey(path) && !path.startsWith("equipment.") && !path.equals("personal-bots.enabled");
    }

    /** sides.&lt;side&gt; に書いてよいパス（反対の陣営のチーム名は不可）。 */
    static boolean isAllowedInSide(Side side, String path) {
        if (!isAllowedInOverride(path)) {
            return false;
        }
        return !path.equals(side == Side.ATTACK ? "teams.defend" : "teams.attack");
    }

    /** アイテムの ID か部品つきの書き方から。空・AIR・読めなければ null。 */
    static ItemStack parseItem(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String t = spec.trim();
        if (t.indexOf('[') < 0) {
            Material m = Material.matchMaterial(t);
            return m == null || m == Material.AIR ? null : new ItemStack(m);
        }
        try {
            ItemStack item = org.bukkit.Bukkit.getItemFactory().createItemStack(t);
            return item.getType() == Material.AIR ? null : item;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Unit unit(UnitType t) {
        return units.get(t == null ? defaultUnit : t);
    }

    /** 脅威がなくなってから盾を下ろすまでの時間（tick）。槍兵だけ units.spearman.shield-lower-after-seconds。 */
    public int shieldLowerAfterTicks(UnitType type) {
        return type == UnitType.SPEARMAN ? spearShieldLowerAfterTicks : shieldLowerAfterTicks;
    }

    /** 見えて届く敵なら構えや持ち場に関係なく狙う距離（ブロック）。遠くから撃つ弓兵だけ units.archer.range、それ以外は 0。 */
    public double rangedEngageRadius(UnitType type) {
        return type == UnitType.ARCHER ? archerRange : 0.0;
    }

    public String teamName(Side side) {
        return side == Side.ATTACK ? attackTeam : defendTeam;
    }
}
