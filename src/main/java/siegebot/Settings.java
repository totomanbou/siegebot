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

    /** 装備の部位（hand, off_hand, helmet, chestplate, leggings, boots）→ アイテム。 */
    public final Map<String, Material> equipment = new LinkedHashMap<>();

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

        ConfigurationSection eq = c.getConfigurationSection("equipment");
        if (eq != null) {
            for (String slot : eq.getKeys(false)) {
                Material m = Material.matchMaterial(String.valueOf(eq.getString(slot, "")));
                if (m != null && m != Material.AIR) {
                    equipment.put(slot.toLowerCase(Locale.ROOT), m);
                }
            }
        }
    }

    public String teamName(Side side) {
        return side == Side.ATTACK ? attackTeam : defendTeam;
    }
}
