package siegebot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 兵種の固定属性と Settings の読み込み。ItemStack は未起動のサーバーでは作れないので、
 * アイテムを読む関数を差し替え（null を返して渡された文字列だけ記録する）、装備は seen / warnings で確かめる。
 */
class SettingsTest {

    private static final File JAR_CONFIG = new File("src/main/resources/config.yml");
    private static final double EPS = 1e-9;

    private static YamlConfiguration jarConfig() {
        return YamlConfiguration.loadConfiguration(JAR_CONFIG);
    }

    /** サーバーの config.yml の中身（yaml）に、jar の既定を defaults として付けた設定。 */
    private static YamlConfiguration serverConfig(String yaml) throws InvalidConfigurationException {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        c.setDefaults(jarConfig());
        return c;
    }

    private static Settings load(YamlConfiguration c, List<String> seen) {
        return new Settings(c, spec -> {
            seen.add(spec);
            return null;
        });
    }

    // ---- 1. UnitType ----

    @Test
    void unitTypeOrderIsUnchanged() {
        assertArrayEquals(new UnitType[] {UnitType.SOLDIER, UnitType.ARCHER, UnitType.SPEARMAN}, UnitType.values());
    }

    @Test
    void unitTypeFixedAttributes() {
        assertFalse(UnitType.SOLDIER.blocksProjectilesInMelee());
        assertFalse(UnitType.ARCHER.blocksProjectilesInMelee());
        assertTrue(UnitType.SPEARMAN.blocksProjectilesInMelee());

        assertFalse(UnitType.SOLDIER.stopsToBlock());
        assertFalse(UnitType.ARCHER.stopsToBlock());
        assertTrue(UnitType.SPEARMAN.stopsToBlock());

        assertFalse(UnitType.SOLDIER.usesCustomAim());
        assertTrue(UnitType.ARCHER.usesCustomAim());
        assertFalse(UnitType.SPEARMAN.usesCustomAim());

        assertEquals(Material.IRON_SWORD, UnitType.SOLDIER.defaultIcon());
        assertEquals(Material.BOW, UnitType.ARCHER.defaultIcon());
        assertEquals(Material.IRON_AXE, UnitType.SPEARMAN.defaultIcon());
    }

    // ---- 2. 既定の config.yml ----

    @Test
    void defaultConfigValues() {
        List<String> seen = new ArrayList<>();
        Settings s = load(jarConfig(), seen);

        assertEquals(10, s.shieldLowerAfterTicks(UnitType.SOLDIER));
        assertEquals(10, s.shieldLowerAfterTicks(UnitType.ARCHER));
        assertEquals(0, s.shieldLowerAfterTicks(UnitType.SPEARMAN));
        assertEquals(0.0, s.rangedEngageRadius(UnitType.SOLDIER), EPS);
        assertEquals(32.0, s.rangedEngageRadius(UnitType.ARCHER), EPS);
        assertEquals(0.0, s.rangedEngageRadius(UnitType.SPEARMAN), EPS);

        assertUnit(s.unit(UnitType.SOLDIER), "の兵", 1.5f, 1.2, 1.2, 20.0, 3.0, 12, 15.0, 5.5, 0.8, 2, 0);
        assertUnit(s.unit(UnitType.ARCHER), "の弓兵", 1.5f, 1.2, 1.2, 20.0, 3.0, 12, 9.0, 1.8, 1.0, 2, 0);
        assertUnit(s.unit(UnitType.SPEARMAN), "の槍兵", 1.5f, 1.2, 1.2, 20.0, 4.5, 22, 12.0, 3.8, 0.7, 2, 168);

        assertEquals(UnitType.SOLDIER, s.defaultUnit);
        assertEquals(0.2, s.spearStopInside, EPS);
        assertEquals(1.5, s.spearBackOffInside, EPS);
        assertTrue(s.spearAvoidCobweb);
        assertEquals(0, s.spearShieldLowerAfterTicks);
        assertEquals(32.0, s.archerRange, EPS);
        assertEquals(5.0, s.archerMinDistance, EPS);
        assertEquals(10.0, s.archerKeepDistance, EPS);
        assertEquals(20, s.archerDrawTicks);
        assertEquals(10, s.archerShotIntervalTicks);
        assertEquals(3.0, s.archerArrowSpeed, EPS);
        assertEquals(3.0, s.archerArrowDamage, EPS);
        assertEquals(1.0, s.archerInaccuracy, EPS);
        assertEquals(0.7, s.archerLeadFactor, EPS);

        // 装備は soldier → archer → spearman の順に読み、各部位の文字列がそのまま渡される
        assertEquals(List.of(
                "IRON_SWORD", "SHIELD", "IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS",
                "BOW", "CHAINMAIL_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS",
                "minecraft:iron_axe[custom_model_data={strings:[\"1\"]}]", "SHIELD", "IRON_HELMET",
                "IRON_CHESTPLATE", "CHAINMAIL_LEGGINGS", "IRON_BOOTS"), seen);
    }

    private static void assertUnit(Settings.Unit u, String suffix, float speed, double damage, double defense,
                                   double maxHealth, double attackRange, int attackDelay, double turnSpeed,
                                   double sway, double turnSway, int reactionTicks, int shieldDurability) {
        assertEquals(suffix, u.nameSuffix);
        assertEquals(speed, u.speed, 1e-6f);
        assertEquals(damage, u.damageMultiplier, EPS);
        assertEquals(defense, u.defenseMultiplier, EPS);
        assertEquals(maxHealth, u.maxHealth, EPS);
        assertEquals(attackRange, u.attackRange, EPS);
        assertEquals(attackDelay, u.attackDelayTicks);
        assertEquals(turnSpeed, u.aimTurnSpeed, EPS);
        assertEquals(sway, u.aimSway, EPS);
        assertEquals(turnSway, u.aimTurnSway, EPS);
        assertEquals(reactionTicks, u.aimReactionTicks);
        assertEquals(shieldDurability, u.shieldDurability);
    }

    // ---- 3. 上書きと contains(path, true) ----

    @Test
    void overridesApplyOnlyToWrittenUnitsAndIgnoreJarDefaults() throws InvalidConfigurationException {
        YamlConfiguration c = serverConfig("""
                combat:
                  speed: 2.0
                shield:
                  lower-after-seconds: 0.75
                units:
                  soldier:
                    speed: 1.8
                  spearman:
                    shield-lower-after-seconds: 0.2
                  archer:
                    range: 40
                """);
        // jar の既定に units.archer.speed があっても、サーバー側に書いていなければ combat.speed へ落ちる
        ((YamlConfiguration) c.getDefaults()).set("units.archer.speed", 9.0);
        ((YamlConfiguration) c.getDefaults()).set("units.spearman.speed", 9.0);
        assertEquals(9.0, c.getDouble("units.archer.speed"), EPS); // 既定は getDouble には見える
        assertFalse(c.contains("units.archer.speed", true));

        Settings s = load(c, new ArrayList<>());
        assertEquals(1.8f, s.unit(UnitType.SOLDIER).speed, 1e-6f);
        assertEquals(2.0f, s.unit(UnitType.ARCHER).speed, 1e-6f);
        assertEquals(2.0f, s.unit(UnitType.SPEARMAN).speed, 1e-6f);

        assertEquals(15, s.shieldLowerAfterTicks(UnitType.SOLDIER)); // shield.lower-after-seconds 0.75 × 20
        assertEquals(15, s.shieldLowerAfterTicks(UnitType.ARCHER));
        assertEquals(4, s.shieldLowerAfterTicks(UnitType.SPEARMAN)); // 0.2 × 20
        assertEquals(40.0, s.rangedEngageRadius(UnitType.ARCHER), EPS);
        assertEquals(0.0, s.rangedEngageRadius(UnitType.SOLDIER), EPS);
        assertEquals(0.0, s.rangedEngageRadius(UnitType.SPEARMAN), EPS);
    }

    // ---- 3b. エイムの反応の遅れ・先読み ----

    @Test
    void reactionTicksAndLeadFactorOverrides() throws InvalidConfigurationException {
        Settings s = load(serverConfig("""
                units:
                  soldier:
                    aim:
                      reaction-ticks: 5
                  archer:
                    aim:
                      reaction-ticks: 7
                      lead-factor: 1.0
                """), new ArrayList<>());
        assertEquals(5, s.unit(UnitType.SOLDIER).aimReactionTicks);
        assertEquals(7, s.unit(UnitType.ARCHER).aimReactionTicks);
        assertEquals(2, s.unit(UnitType.SPEARMAN).aimReactionTicks); // 書いていない兵種は既定
        assertEquals(1.0, s.archerLeadFactor, EPS);
    }

    @Test
    void reactionTicksAndLeadFactorAreClamped() throws InvalidConfigurationException {
        Settings low = load(serverConfig("""
                units:
                  soldier:
                    aim:
                      reaction-ticks: -3
                  archer:
                    aim:
                      lead-factor: -0.5
                """), new ArrayList<>());
        assertEquals(0, low.unit(UnitType.SOLDIER).aimReactionTicks);
        assertEquals(0.0, low.archerLeadFactor, EPS);

        Settings high = load(serverConfig("""
                units:
                  soldier:
                    aim:
                      reaction-ticks: 99
                  archer:
                    aim:
                      lead-factor: 9.0
                """), new ArrayList<>());
        assertEquals(20, high.unit(UnitType.SOLDIER).aimReactionTicks);
        assertEquals(1.5, high.archerLeadFactor, EPS);
    }

    @Test
    void oldConfigWithoutNewKeysFallsBackToDefaultsEvenWithJarDefaults() throws InvalidConfigurationException {
        // 旧 config.yml: aim はあるが reaction-ticks・lead-factor はない。jar の既定に別の値があっても見ない
        YamlConfiguration c = serverConfig("""
                units:
                  soldier:
                    aim:
                      turn-speed: 25
                  archer:
                    aim:
                      turn-speed: 12
                """);
        YamlConfiguration jar = (YamlConfiguration) c.getDefaults();
        jar.set("units.soldier.aim.reaction-ticks", 9);
        jar.set("units.spearman.aim.reaction-ticks", 9);
        jar.set("units.archer.aim.reaction-ticks", 9);
        assertFalse(c.contains("units.soldier.aim.reaction-ticks", true));

        Settings s = load(c, new ArrayList<>());
        assertEquals(2, s.unit(UnitType.SOLDIER).aimReactionTicks);
        assertEquals(2, s.unit(UnitType.ARCHER).aimReactionTicks);
        assertEquals(2, s.unit(UnitType.SPEARMAN).aimReactionTicks);
        assertEquals(25.0, s.unit(UnitType.SOLDIER).aimTurnSpeed, EPS); // 書いてある値は使う
        assertEquals(0.7, s.archerLeadFactor, EPS);
    }

    // ---- 4. 旧 0.6 equipment ----

    @Test
    void oldTopLevelEquipmentFallsBackForSoldierEvenWithJarDefaults() throws InvalidConfigurationException {
        YamlConfiguration c = serverConfig("""
                equipment:
                  hand: DIAMOND_SWORD
                """);
        List<String> seen = new ArrayList<>();
        Settings s = load(c, seen);

        // 剣兵が最初に読まれる: 旧 equipment の hand と、自動補完の盾（off_hand）だけ
        assertEquals(List.of("DIAMOND_SWORD", "SHIELD"), seen.subList(0, 2));
        List<String> soldierWarnings = s.warnings.stream().filter(w -> w.startsWith("units.soldier.")).toList();
        assertEquals(2, soldierWarnings.size());
        assertTrue(soldierWarnings.get(0).contains("equipment.hand") && soldierWarnings.get(0).contains("DIAMOND_SWORD"));
        assertTrue(soldierWarnings.get(1).contains("equipment.off_hand") && soldierWarnings.get(1).contains("SHIELD"));
        // 弓兵・槍兵は jar の既定を見ず、組み込みの既定（BOW など）のまま
        assertEquals("BOW", seen.get(2));
        assertEquals(2 + 5 + 6, seen.size());
    }

    // ---- 5. 警告とクランプ ----

    @Test
    void personalBotsDisabledAddsDeprecationWarningFirst() throws InvalidConfigurationException {
        Settings off = load(serverConfig("personal-bots:\n  enabled: false\n"), new ArrayList<>());
        assertTrue(off.warnings.get(0).contains("personal-bots.enabled: false は廃止しました"));

        Settings on = load(serverConfig("personal-bots:\n  enabled: true\n"), new ArrayList<>());
        assertTrue(on.warnings.stream().noneMatch(w -> w.contains("personal-bots")));
    }

    @Test
    void spearBackOffInsideIsClampedAboveStopInside() throws InvalidConfigurationException {
        Settings s = load(serverConfig("""
                units:
                  spearman:
                    stop-inside: 2.0
                    back-off-inside: 1.0
                """), new ArrayList<>());
        assertEquals(2.0, s.spearStopInside, EPS);
        assertEquals(2.5, s.spearBackOffInside, EPS);
    }

    // ---- personal-bots.max-per-player（1〜30） ----

    private static int maxPerPlayer(String yaml) throws InvalidConfigurationException {
        return load(serverConfig(yaml), new ArrayList<>()).maxBotsPerPlayer;
    }

    @Test
    void maxBotsPerPlayerDefaultIsFive() throws InvalidConfigurationException {
        assertEquals(5, maxPerPlayer(""));
    }

    @Test
    void maxBotsPerPlayerAcceptsValuesAboveNine() throws InvalidConfigurationException {
        assertEquals(8, maxPerPlayer("personal-bots:\n  max-per-player: 8\n"));
        assertEquals(12, maxPerPlayer("personal-bots:\n  max-per-player: 12\n"));
        assertEquals(30, maxPerPlayer("personal-bots:\n  max-per-player: 30\n"));
    }

    @Test
    void maxBotsPerPlayerIsClampedToOneThroughThirty() throws InvalidConfigurationException {
        assertEquals(30, maxPerPlayer("personal-bots:\n  max-per-player: 100\n"));
        assertEquals(1, maxPerPlayer("personal-bots:\n  max-per-player: 0\n"));
        assertEquals(1, maxPerPlayer("personal-bots:\n  max-per-player: -3\n"));
    }

    // ---- separation（ボットどうしの重なり防止） ----

    @Test
    void separationDefaultsAreEnabledAndPointNine() throws InvalidConfigurationException {
        Settings s = load(serverConfig(""), new ArrayList<>());
        assertTrue(s.separationEnabled);
        assertEquals(0.9, s.separationMinDistance, EPS);
    }

    @Test
    void separationDefaultsApplyToAnOldConfigWithoutTheSection() {
        // jar の既定を付けない（separation の節がない古い config.yml）
        Settings s = load(new YamlConfiguration(), new ArrayList<>());
        assertTrue(s.separationEnabled);
        assertEquals(0.9, s.separationMinDistance, EPS);
    }

    @Test
    void separationValuesAreReadAndClamped() throws InvalidConfigurationException {
        assertFalse(load(serverConfig("separation:" + NL + "  enabled: false" + NL), new ArrayList<>()).separationEnabled);
        assertEquals(1.2, minDistance("1.2"), EPS);
        assertEquals(0.6, minDistance("0.2"), EPS);
        assertEquals(1.5, minDistance("3.0"), EPS);
        assertEquals(Formation.FORMATION_SPACING - 0.1, 1.5, EPS); // 上限は横隊の間隔より少し小さい
    }

    private static final String NL = System.lineSeparator();

    private static double minDistance(String value) throws InvalidConfigurationException {
        return load(serverConfig("separation:" + NL + "  min-distance: " + value + NL), new ArrayList<>()).separationMinDistance;
    }

    @Test
    void jarConfigHasTheSeparationSection() {
        YamlConfiguration c = jarConfig();
        assertTrue(c.getBoolean("separation.enabled"));
        assertEquals(0.9, c.getDouble("separation.min-distance"), EPS);
    }
}
