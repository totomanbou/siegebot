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

    public final String apiKey;
    public final String model;
    public final int llmTimeoutSeconds;

    public final boolean listenPublicChat;
    public final boolean listenTeamMsg;
    public final String chatPrefix;
    /** 指示の理解のしかた: auto / keyword / llm。 */
    public final String parserMode;

    /** プレイヤー 1 人に 1 体の専用のボットを出し、指示は自分のボットにだけ効くようにする。 */
    public final boolean personalBots;

    public final String defaultAttackOrder;
    public final String defaultDefendOrder;

    public final double engageRadius;
    public final double pushLeash;
    public final double guardLeash;
    public final double followDistance;
    public final double arriveRadius;
    public final int respawnSeconds;
    public final float pathRange;
    public final float speed;
    /** 狙いを選び直す間隔（tick）。 */
    public final int retargetTicks;
    public final double attackRange;
    public final int attackDelayTicks;
    public final String pathfinder;

    public final int gatePeriodTicks;
    public final double openAirRatio;
    public final boolean announceStage;

    /** 装備の部位（hand, off_hand, helmet, chestplate, leggings, boots）→ アイテム。 */
    public final Map<String, Material> equipment = new LinkedHashMap<>();

    public Settings(FileConfiguration c) {
        attackTeam = c.getString("teams.attack", "attack");
        defendTeam = c.getString("teams.defend", "defend");

        String key = c.getString("llm.api-key", "");
        if (key == null || key.isBlank()) {
            key = System.getenv("ANTHROPIC_API_KEY");
        }
        apiKey = key == null ? "" : key.trim();
        model = c.getString("llm.model", "claude-haiku-5-5");
        llmTimeoutSeconds = Math.max(5, c.getInt("llm.timeout-seconds", 20));

        listenPublicChat = c.getBoolean("chat.listen-public-chat", true);
        listenTeamMsg = c.getBoolean("chat.listen-teammsg", true);
        chatPrefix = c.getString("chat.prefix", "");
        String mode = String.valueOf(c.getString("chat.parser", "auto")).trim().toLowerCase(Locale.ROOT);
        parserMode = mode.equals("keyword") || mode.equals("llm") ? mode : "auto";

        personalBots = c.getBoolean("personal-bots.enabled", true);

        defaultAttackOrder = c.getString("default-orders.attack", "");
        defaultDefendOrder = c.getString("default-orders.defend", "");

        engageRadius = c.getDouble("combat.engage-radius", 16);
        pushLeash = c.getDouble("combat.push-leash", 24);
        guardLeash = c.getDouble("combat.guard-leash", 8);
        followDistance = c.getDouble("combat.follow-distance", 4);
        arriveRadius = c.getDouble("combat.arrive-radius", 3);
        respawnSeconds = Math.max(0, c.getInt("combat.respawn-seconds", 10));
        pathRange = (float) c.getDouble("combat.path-range", 96);
        speed = (float) c.getDouble("combat.speed", 1.95);
        retargetTicks = Math.max(BotManager.TICK_PERIOD, (int) Math.round(c.getDouble("combat.retarget-seconds", 2.0) * 20));
        attackRange = c.getDouble("combat.attack-range", 3.0);
        attackDelayTicks = Math.max(1, c.getInt("combat.attack-delay-ticks", 12));
        pathfinder = c.getString("combat.pathfinder", "CITIZENS").toUpperCase(Locale.ROOT);

        gatePeriodTicks = Math.max(1, c.getInt("gates.check-period-ticks", 20));
        double ratio = c.getDouble("gates.open-air-ratio", 0.9);
        openAirRatio = (ratio > 0 && ratio <= 1) ? ratio : 0.9;
        announceStage = c.getBoolean("gates.announce", true);

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

    public String defaultOrderText(Side side) {
        return side == Side.ATTACK ? defaultAttackOrder : defaultDefendOrder;
    }

    public boolean llmEnabled() {
        return !apiKey.isEmpty();
    }
}
