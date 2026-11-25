package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * マップ別・陣営別の設定の合成（{@link MapProfiles}）。ItemStack は未起動のサーバーでは作れないので、アイテムを読む関数は
 * 渡された文字列を seen に記録して null を返すものに替え、装備は合成した設定の木（{@link MapProfiles.Snapshot#attackTree} など）と
 * seen で確かめる。
 */
class MapProfilesTest {

    private static final File JAR_CONFIG = new File("src/main/resources/config.yml");
    private static final double EPS = 1e-9;

    @TempDir
    Path dir;

    private File mapsDir;
    private final List<String> seen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mapsDir = dir.resolve("maps").toFile();
        assertTrue(mapsDir.mkdirs());
        seen.clear();
    }

    private static YamlConfiguration jarConfig() {
        return YamlConfiguration.loadConfiguration(JAR_CONFIG);
    }

    /** サーバーの config.yml の中身（yaml）に、jar の既定を defaults として付けた設定（実機の getConfig() と同じ形）。 */
    private static YamlConfiguration serverConfig(String yaml) throws InvalidConfigurationException {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        c.setDefaults(jarConfig());
        return c;
    }

    private MapProfiles profiles() {
        return new MapProfiles(mapsDir, spec -> {
            seen.add(spec);
            return null;
        });
    }

    private void writeMap(String name, String yaml) throws IOException {
        Files.writeString(mapsDir.toPath().resolve(name + ".yml"), yaml, StandardCharsets.UTF_8);
    }

    /** 設定の書き間違いの警告だけ（試験ではアイテムを読む関数が null を返すので、アイテムが読めない警告は除く）。 */
    private static List<String> configWarnings(MapProfiles.Snapshot s) {
        return s.warnings().stream().filter(w -> !w.contains("のアイテム「")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> equip(Map<String, Object> tree, String unit) {
        Object units = tree.get("units");
        if (!(units instanceof Map<?, ?> um)) {
            return null;
        }
        Object u = um.get(unit);
        if (!(u instanceof Map<?, ?> unitMap)) {
            return null;
        }
        return (Map<String, Object>) unitMap.get("equipment");
    }

    private static void assertEngage(MapProfiles.Snapshot s, double base, double attack, double defend) {
        assertEquals(base, s.base().engageRadius, EPS, "base");
        assertEquals(attack, s.attack().engageRadius, EPS, "attack");
        assertEquals(defend, s.defend().engageRadius, EPS, "defend");
    }

    // ---- 層の優先順位 ----

    @Test
    void sixLayerPriority() throws Exception {
        YamlConfiguration cfg = serverConfig("""
                combat:
                  engage-radius: 10
                sides:
                  attack:
                    combat:
                      engage-radius: 11
                """);
        MapProfiles p = profiles();

        // 1・2: config.yml の共通 → config.yml の sides
        assertEngage(p.load(cfg, null), 10, 11, 10);

        // 5: マップの共通は config.yml の sides.attack に勝つ
        writeMap("child", "combat:\n  engage-radius: 12\n");
        assertEngage(p.load(cfg, "child"), 12, 12, 12);

        // 3・4: 親マップの共通 → 親マップの sides。子は何も書かない
        writeMap("parent", """
                combat:
                  engage-radius: 12
                sides:
                  attack:
                    combat:
                      engage-radius: 13
                      guard-leash: 5
                """);
        writeMap("child", "extends: parent\n");
        MapProfiles.Snapshot s = p.load(cfg, "child");
        assertEngage(s, 12, 13, 12);
        assertEquals(5.0, s.attack().guardLeash, EPS, "親の sides の他のキーが子に引き継がれる");
        assertEquals(8.0, s.defend().guardLeash, EPS);
        assertEquals(8.0, s.base().guardLeash, EPS);
        assertEquals(List.of("parent"), s.extendsChain());

        // 5: 子の共通は親の sides.attack に勝つ（親の sides の他のキーは残る）
        writeMap("child", "extends: parent\ncombat:\n  engage-radius: 14\n");
        s = p.load(cfg, "child");
        assertEngage(s, 14, 14, 14);
        assertEquals(5.0, s.attack().guardLeash, EPS);

        // 6: 子の sides.defend が最後
        writeMap("child", """
                extends: parent
                combat:
                  engage-radius: 14
                sides:
                  defend:
                    combat:
                      engage-radius: 15
                """);
        s = p.load(cfg, "child");
        assertEngage(s, 14, 14, 15);
        assertTrue(s.sideDifferences());
    }

    @Test
    void sideOnlyTeamsAndFlags() throws Exception {
        YamlConfiguration cfg = serverConfig("""
                teams:
                  attack: Red
                  defend: Blue
                sides:
                  defend:
                    personal-bots:
                      max-per-player: 2
                    test-bots:
                      enabled: true
                """);
        MapProfiles.Snapshot s = profiles().load(cfg, null);
        assertEquals(5, s.attack().maxBotsPerPlayer);
        assertEquals(2, s.defend().maxBotsPerPlayer);
        assertFalse(s.attack().testBots);
        assertTrue(s.defend().testBots);
        assertEquals("Red", s.attack().teamName(Side.ATTACK));
        assertEquals("Blue", s.defend().teamName(Side.DEFEND));
        assertTrue(s.sideDifferences());
        assertTrue(configWarnings(s).isEmpty(), s.warnings().toString());
    }

    @Test
    void sideDifferencesIsFalseWhenSidesAreTheSame() throws Exception {
        MapProfiles p = profiles();
        assertFalse(p.load(serverConfig("combat:\n  speed: 1.5\n"), null).sideDifferences());
        // sides に書いても、結果が両陣営で同じなら差ではない
        YamlConfiguration same = serverConfig("combat:\n  speed: 1.5\nsides:\n  attack:\n    combat:\n      speed: 1.5\n");
        assertFalse(p.load(same, null).sideDifferences());
        YamlConfiguration diff = serverConfig("combat:\n  speed: 1.5\nsides:\n  attack:\n    combat:\n      speed: 1.6\n");
        assertTrue(p.load(diff, null).sideDifferences());
    }

    // ---- extends ----

    @Test
    void multiLevelExtends() throws Exception {
        writeMap("a", "combat:\n  engage-radius: 20\n  guard-leash: 6\n");
        writeMap("b", "extends: a\ncombat:\n  guard-leash: 7\n");
        writeMap("c", "extends: b\ncombat:\n  push-leash: 30\n");
        MapProfiles.Snapshot s = profiles().load(serverConfig(""), "c");
        assertEquals("c", s.activeMap());
        assertEquals(List.of("b", "a"), s.extendsChain());
        assertEquals(20.0, s.base().engageRadius, EPS);
        assertEquals(7.0, s.base().guardLeash, EPS);
        assertEquals(30.0, s.base().pushLeash, EPS);
        assertEquals("c (extends: b → a)", MapProfiles.describe(s));
    }

    @Test
    void circularExtendsIsAnError() throws Exception {
        writeMap("a", "extends: b\n");
        writeMap("b", "extends: a\n");
        MapProfiles.Result r = profiles().tryBuild(serverConfig(""), "a");
        assertFalse(r.isOk());
        assertTrue(r.error().contains("a -> b -> a"), r.error());

        writeMap("self", "extends: self\n");
        r = profiles().tryBuild(serverConfig(""), "self");
        assertFalse(r.isOk());
        assertTrue(r.error().contains("self -> self"), r.error());
    }

    @Test
    void extendsDepthLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            writeMap("m" + i, i < 9 ? "extends: m" + (i + 1) + "\n" : "combat:\n  engage-radius: 33\n");
        }
        MapProfiles p = profiles();
        MapProfiles.Result tooDeep = p.tryBuild(serverConfig(""), "m0"); // 親が 9 段
        assertFalse(tooDeep.isOk());
        assertTrue(tooDeep.error().contains("深すぎ"), tooDeep.error());
        MapProfiles.Result ok = p.tryBuild(serverConfig(""), "m1"); // 親が 8 段
        assertTrue(ok.isOk(), ok.error());
        assertEquals(8, ok.snapshot().extendsChain().size());
        assertEquals(33.0, ok.snapshot().base().engageRadius, EPS);
    }

    @Test
    void invalidExtends() throws Exception {
        MapProfiles p = profiles();
        writeMap("orphan", "extends: nothing\n");
        MapProfiles.Result r = p.tryBuild(serverConfig(""), "orphan");
        assertFalse(r.isOk());
        assertTrue(r.error().contains("nothing"), r.error());

        for (String bad : new String[] {"5", "\"\"", "\"../x\"", "default", "list", "[a, b]", "\"a b\""}) {
            writeMap("bad", "extends: " + bad + "\n");
            r = p.tryBuild(serverConfig(""), "bad");
            assertFalse(r.isOk(), "extends: " + bad);
            assertTrue(r.error().contains("extends"), r.error());
        }
    }

    // ---- 装備 ----

    @Test
    void equipmentStartsFromPreviousLayerResult() throws Exception {
        YamlConfiguration cfg = serverConfig("""
                sides:
                  attack:
                    units:
                      soldier:
                        equipment:
                          boots: LEATHER_BOOTS
                """);
        writeMap("m", """
                units:
                  soldier:
                    equipment:
                      helmet: DIAMOND_HELMET
                sides:
                  attack:
                    units:
                      soldier:
                        equipment:
                          chestplate: NETHERITE_CHESTPLATE
                """);
        MapProfiles.Snapshot s = profiles().load(cfg, "m");
        Map<String, Object> atk = equip(s.attackTree, "soldier");
        assertEquals(Map.of("hand", "IRON_SWORD", "off_hand", "SHIELD", "helmet", "DIAMOND_HELMET",
                "chestplate", "NETHERITE_CHESTPLATE", "leggings", "IRON_LEGGINGS", "boots", "LEATHER_BOOTS"), atk);
        Map<String, Object> def = equip(s.defendTree, "soldier");
        assertEquals(Map.of("hand", "IRON_SWORD", "off_hand", "SHIELD", "helmet", "DIAMOND_HELMET",
                "chestplate", "IRON_CHESTPLATE", "leggings", "IRON_LEGGINGS", "boots", "IRON_BOOTS"), def);
        assertEquals(def, equip(s.baseTree, "soldier"));
        // 兵種の組み込みの既定は、ほかの兵種にも効く
        MapProfiles.Snapshot s2 = profiles().load(serverConfig(""), null);
        assertNull(equip(s2.baseTree, "archer"));
        writeMap("arch", "units:\n  archer:\n    equipment:\n      hand: CROSSBOW\n");
        Map<String, Object> archer = equip(profiles().load(serverConfig(""), "arch").baseTree, "archer");
        assertEquals(Map.of("hand", "CROSSBOW", "helmet", "CHAINMAIL_HELMET", "chestplate", "LEATHER_CHESTPLATE",
                "leggings", "LEATHER_LEGGINGS", "boots", "LEATHER_BOOTS"), archer);
    }

    @Test
    void equipmentOfConfigIsTheStartingPointWhenWritten() throws Exception {
        YamlConfiguration cfg = serverConfig("units:\n  soldier:\n    equipment:\n      hand: IRON_SWORD\n");
        writeMap("m", "units:\n  soldier:\n    equipment:\n      helmet: DIAMOND_HELMET\n");
        MapProfiles.Snapshot s = profiles().load(cfg, "m");
        assertEquals(Map.of("hand", "IRON_SWORD", "helmet", "DIAMOND_HELMET"), equip(s.baseTree, "soldier"));
    }

    @Test
    void explicitEmptyItemsRemoveSlotsWithoutWarnings() throws Exception {
        writeMap("m", """
                units:
                  soldier:
                    equipment:
                      hand: AIR
                      offhand: minecraft:air
                      helmet: none
                      chestplate: ""
                """);
        MapProfiles.Snapshot s = profiles().load(serverConfig(""), "m");
        assertEquals(Map.of("leggings", "IRON_LEGGINGS", "boots", "IRON_BOOTS"), equip(s.baseTree, "soldier"));
        for (String spec : new String[] {"AIR", "minecraft:air", "none", ""}) {
            assertFalse(seen.contains(spec), "「" + spec + "」はアイテムとして読まない");
        }
        for (String w : s.warnings()) {
            assertFalse(w.contains("AIR") || w.contains("air") || w.contains("none"), w);
        }
    }

    @Test
    void explicitEmptyItemsInConfigAreSkippedBySettings() throws Exception {
        YamlConfiguration cfg = serverConfig("""
                units:
                  archer:
                    equipment:
                      hand: BOW
                      helmet: AIR
                      boots: NONE
                      leggings: ""
                """);
        MapProfiles.Snapshot s = profiles().load(cfg, null);
        assertTrue(seen.contains("BOW"));
        for (String spec : new String[] {"AIR", "NONE", ""}) {
            assertFalse(seen.contains(spec), spec);
        }
        for (String w : s.warnings()) {
            assertFalse(w.contains("「AIR」") || w.contains("「NONE」") || w.contains("「」"), w);
        }
    }

    @Test
    void offhandIsNormalizedToOffHand() throws Exception {
        writeMap("m", "units:\n  soldier:\n    equipment:\n      offhand: BOW\n");
        Map<String, Object> eq = equip(profiles().load(serverConfig(""), "m").baseTree, "soldier");
        assertEquals("BOW", eq.get("off_hand"));
        assertFalse(eq.containsKey("offhand"));
        assertEquals(6, eq.size());
    }

    @Test
    void emptyEquipmentSectionRemovesAllParts() throws Exception {
        MapProfiles p = profiles();
        // 対照: 何も書かなければ剣兵の剣を読む
        p.load(serverConfig(""), null);
        assertTrue(seen.contains("IRON_SWORD"));
        seen.clear();

        writeMap("m", "units:\n  soldier:\n    equipment: {}\n");
        MapProfiles.Snapshot s = p.load(serverConfig(""), "m");
        assertEquals(Map.of(), equip(s.baseTree, "soldier"));
        assertFalse(seen.contains("IRON_SWORD"), "剣兵の装備は 1 つも読まない");
        assertTrue(seen.contains("BOW"), "ほかの兵種はそのまま");
        assertEquals(0, s.base().unit(UnitType.SOLDIER).equipment.size());
        assertTrue(configWarnings(s).isEmpty(), s.warnings().toString());
    }

    @Test
    void emptyEquipmentInOneSideOnly() throws Exception {
        writeMap("m", "sides:\n  attack:\n    units:\n      soldier:\n        equipment: {}\n");
        MapProfiles.Snapshot s = profiles().load(serverConfig(""), "m");
        assertEquals(Map.of(), equip(s.attackTree, "soldier"));
        assertNull(equip(s.defendTree, "soldier"));
        assertTrue(s.sideDifferences());
    }

    @Test
    void emptyEquipmentInConfigIsKept() throws Exception {
        MapProfiles p = profiles();
        MapProfiles.Snapshot s = p.load(serverConfig("units:\n  soldier:\n    equipment: {}\n"), null);
        assertEquals(Map.of(), equip(s.baseTree, "soldier"));
        assertFalse(seen.contains("IRON_SWORD"));
    }

    @Test
    void equipmentAfterEmptySectionStartsFromEmpty() throws Exception {
        writeMap("parent", "units:\n  soldier:\n    equipment: {}\n");
        writeMap("child", "extends: parent\nunits:\n  soldier:\n    equipment:\n      helmet: DIAMOND_HELMET\n");
        Map<String, Object> eq = equip(profiles().load(serverConfig(""), "child").baseTree, "soldier");
        assertEquals(Map.of("helmet", "DIAMOND_HELMET"), eq);
    }

    @Test
    void legacyTopLevelEquipmentIsTheStartForSoldier() throws Exception {
        YamlConfiguration cfg = serverConfig("equipment:\n  hand: DIAMOND_SWORD\n  helmet: IRON_HELMET\n");
        MapProfiles p = profiles();
        // マップなし: 旧形式のまま Settings が読む（左手は盾が補われる）
        p.load(cfg, null);
        assertTrue(seen.contains("DIAMOND_SWORD"));
        assertTrue(seen.contains("SHIELD"));
        seen.clear();

        writeMap("m", "units:\n  soldier:\n    equipment:\n      boots: GOLDEN_BOOTS\n");
        MapProfiles.Snapshot s = p.load(cfg, "m");
        assertEquals(Map.of("hand", "DIAMOND_SWORD", "helmet", "IRON_HELMET", "off_hand", "SHIELD",
                "boots", "GOLDEN_BOOTS"), equip(s.baseTree, "soldier"));
        // 弓兵は組み込みの既定
        writeMap("a", "units:\n  archer:\n    equipment:\n      hand: CROSSBOW\n");
        Map<String, Object> archer = equip(p.load(cfg, "a").baseTree, "archer");
        assertEquals("CROSSBOW", archer.get("hand"));
        assertEquals("CHAINMAIL_HELMET", archer.get("helmet"));
    }

    // ---- 検証・警告 ----

    @Test
    void warningsForUnknownAndDisallowedKeys() throws Exception {
        writeMap("w", """
                combat:
                  spedd: 1
                  speed: 2
                equipment:
                  hand: DIAMOND_SWORD
                personal-bots:
                  enabled: true
                bogus: 1
                sides:
                  foo:
                    combat:
                      speed: 3
                  attack:
                    teams:
                      defend: Zed
                    extends: x
                    sides: {}
                    combat:
                      speed: 4
                  defend:
                    equipment:
                      boots: IRON_BOOTS
                    personal-bots:
                      enabled: false
                """);
        MapProfiles.Snapshot s = profiles().load(serverConfig(""), "w");
        String all = String.join("\n", s.warnings());
        for (String expected : new String[] {"maps/w.yml", "combat.spedd", "equipment.hand", "personal-bots.enabled", "bogus",
                "sides.foo", "teams.defend", "sides.attack.extends", "sides.attack.sides", "equipment.boots"}) {
            assertTrue(all.contains(expected), "警告に「" + expected + "」がない:\n" + all);
        }
        assertEquals((float) 2.0, s.base().speed, EPS);
        assertEquals((float) 4.0, s.attack().speed, EPS);
        assertEquals("Defence", s.attack().teamName(Side.DEFEND), "反対の陣営のチーム名は無視する");
        assertEquals(s.warnings().size(), new HashSet<>(s.warnings()).size(), "警告は重複しない");
    }

    @Test
    void warningsForConfigSides() throws Exception {
        YamlConfiguration cfg = serverConfig("""
                bogus: 1
                sides:
                  nobody: {}
                  defend:
                    teams:
                      attack: Zed
                    combat:
                      nonsense: 1
                """);
        MapProfiles.Snapshot s = profiles().load(cfg, null);
        String all = String.join("\n", s.warnings());
        assertTrue(all.contains("config.yml"), all);
        assertTrue(all.contains("sides.nobody"), all);
        assertTrue(all.contains("teams.attack"), all);
        assertTrue(all.contains("combat.nonsense"), all);
        assertFalse(all.contains("bogus"), "config.yml 本体の未知のキーは警告しない（既存どおり）: " + all);
        assertEquals("Attack", s.defend().teamName(Side.ATTACK));
    }

    @Test
    void duplicateWarningsAreReportedOnce() throws Exception {
        YamlConfiguration cfg = serverConfig("units:\n  soldier:\n    equipment:\n      hand: NO_SUCH_ITEM\n");
        MapProfiles.Snapshot s = profiles().load(cfg, null);
        long n = s.warnings().stream().filter(w -> w.contains("NO_SUCH_ITEM")).count();
        assertEquals(1, n, s.warnings().toString());
    }

    @Test
    void teamNameCollisionFailsSwitchAndFallsBackOnLoad() throws Exception {
        writeMap("same", "teams:\n  attack: Same\n  defend: SAME\n");
        MapProfiles p = profiles();
        YamlConfiguration cfg = serverConfig("");
        MapProfiles.Snapshot before = p.load(cfg, null);

        MapProfiles.Result r = p.tryBuild(cfg, "same");
        assertFalse(r.isOk());
        assertTrue(r.error().contains("チーム名"), r.error());
        assertSame(before, p.snapshot(), "失敗したら今の設定のまま");

        MapProfiles.Snapshot s = p.load(cfg, "same");
        assertNull(s.activeMap(), "default に落ちる");
        assertEquals("Attack", s.base().attackTeam);
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("same") && w.contains("チーム名")), s.warnings().toString());
    }

    @Test
    void teamNameCollisionInConfigIsOnlyAWarning() throws Exception {
        writeMap("plain", "combat:\n  speed: 1.1\n");
        YamlConfiguration cfg = serverConfig("teams:\n  attack: X\n  defend: x\n");
        MapProfiles p = profiles();
        MapProfiles.Snapshot s = p.load(cfg, null);
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("チーム名")), s.warnings().toString());
        MapProfiles.Result r = p.tryBuild(cfg, "plain");
        assertTrue(r.isOk(), "config.yml 自体の衝突はマップのせいではない");
        assertTrue(r.snapshot().warnings().stream().anyMatch(w -> w.contains("チーム名")));
        assertTrue(p.tryBuild(cfg, "default").isOk());
    }

    // ---- 名前・ファイル ----

    @Test
    void mapNameValidation() throws Exception {
        MapProfiles p = profiles();
        writeMap("real", "");
        assertNull(p.checkName("real"));
        assertNull(p.checkName("default"));
        assertNull(p.checkName("RESET"));
        assertNotNull(p.checkName("../evil"));
        assertNotNull(p.checkName("a b"));
        assertNotNull(p.checkName("list"));
        assertNotNull(p.checkName("LIST"));
        assertNotNull(p.checkName("x".repeat(65)));
        assertNotNull(p.checkName("nothing"));
        YamlConfiguration cfg = serverConfig("");
        for (String bad : new String[] {"../evil", "a b", "list", "nothing", "a/b", "..\\x"}) {
            assertFalse(p.tryBuild(cfg, bad).isOk(), bad);
        }
        assertTrue(MapProfiles.isValidName("a-b_C9"));
        assertFalse(MapProfiles.isValidName(""));
        assertFalse(MapProfiles.isValidName("日本語"));
        assertTrue(MapProfiles.isReserved("Default"));
    }

    @Test
    void listMapsSortsAndFilters() throws Exception {
        for (String n : new String[] {"b", "A", "a2", "a10", "list", "DEFAULT", "bad name", "x.y"}) {
            writeMap(n, "");
        }
        Files.writeString(mapsDir.toPath().resolve("notes.txt"), "x", StandardCharsets.UTF_8);
        Files.createDirectory(mapsDir.toPath().resolve("folder.yml"));
        assertEquals(List.of("A", "a10", "a2", "b"), profiles().listMaps());
    }

    @Test
    void resolveUsesCanonicalFileName() throws Exception {
        writeMap("Castle", "combat:\n  engage-radius: 21\n");
        File f = MapProfiles.resolveMapFile(mapsDir, "castle");
        assertNotNull(f);
        assertEquals("Castle.yml", f.getName());
        assertNull(MapProfiles.resolveMapFile(mapsDir, "../Castle"));
        MapProfiles p = profiles();
        MapProfiles.Snapshot s = p.load(serverConfig(""), "CASTLE");
        assertEquals("Castle", s.activeMap());
        assertEquals("Castle", p.canonicalName("castle"));
        assertNull(p.canonicalName("default"));
    }

    @Test
    void resolveIsDeterministicWithCaseVariants() {
        // 大文字小文字だけが違う名前は、ファイルシステムによっては同時に作れないので、名前の一覧で確かめる
        List<String> names = List.of("aB", "a", "Ab", "A", "other");
        List<String> reversed = List.of("other", "A", "Ab", "a", "aB");
        for (List<String> in : List.of(names, reversed)) {
            assertEquals("a", MapProfiles.pickName(in, "a"));
            assertEquals("A", MapProfiles.pickName(in, "A"));
            assertEquals("aB", MapProfiles.pickName(in, "aB"));
            assertEquals("Ab", MapProfiles.pickName(in, "Ab"));
            // 厳密な一致がなければ、並び順（大文字小文字を無視 → 自然順）で最初のもの。入力の順序によらない
            assertEquals("Ab", MapProfiles.pickName(in, "AB"));
            assertEquals("other", MapProfiles.pickName(in, "OTHER"));
            assertNull(MapProfiles.pickName(in, "none"));
        }
        assertEquals("A", MapProfiles.pickName(List.of("a", "A"), "A"));
    }

    @Test
    void ensureMapsDirCreatesTheFolderOnly() {
        File sub = dir.resolve("fresh").resolve("maps").toFile();
        MapProfiles p = new MapProfiles(sub);
        assertFalse(sub.exists());
        p.ensureMapsDir();
        assertTrue(sub.isDirectory());
        String[] children = sub.list();
        assertNotNull(children);
        assertEquals(0, children.length, "サンプルのファイルは置かない");
        assertTrue(p.listMaps().isEmpty());
    }

    // ---- 読み込みの失敗 ----

    @Test
    void yamlSyntaxErrorKeepsOldSettings() throws Exception {
        writeMap("bad", "combat: [unclosed\n  speed: 1\n");
        MapProfiles p = profiles();
        YamlConfiguration cfg = serverConfig("");
        MapProfiles.Snapshot before = p.load(cfg, null);

        MapProfiles.Result r = p.tryBuild(cfg, "bad");
        assertFalse(r.isOk());
        assertTrue(r.error().contains("bad.yml"), r.error());
        assertSame(before, p.snapshot(), "tryBuild は今の状態を変えない");

        MapProfiles.Snapshot s = p.load(cfg, "bad");
        assertNull(s.activeMap());
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("bad")), s.warnings().toString());
        assertTrue(p.isFallback("bad"));
        assertFalse(p.isFallback(null));
        assertFalse(p.isFallback("default"));
    }

    @Test
    void missingMapOnLoadFallsBackToDefault() throws Exception {
        MapProfiles p = profiles();
        MapProfiles.Snapshot s = p.load(serverConfig("combat:\n  engage-radius: 18\n"), "gone");
        assertNull(s.activeMap());
        assertEquals(18.0, s.base().engageRadius, EPS);
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("gone")));
        assertNull(p.load(serverConfig(""), "").activeMap());
        assertNull(p.load(serverConfig(""), "default").activeMap());
        assertFalse(p.isFallback("default"));
    }

    @Test
    void tryBuildDefaultAndApply() throws Exception {
        writeMap("m", "combat:\n  engage-radius: 25\n");
        MapProfiles p = profiles();
        YamlConfiguration cfg = serverConfig("");
        p.load(cfg, null);
        MapProfiles.Result r = p.tryBuild(cfg, "m");
        assertTrue(r.isOk());
        assertNull(p.activeMap(), "apply するまで切り替わらない");
        p.apply(r.snapshot());
        assertEquals("m", p.activeMap());
        assertEquals(25.0, p.base().engageRadius, EPS);
        assertEquals("m", p.describeActive());
        MapProfiles.Result back = p.tryBuild(cfg, "RESET");
        assertTrue(back.isOk());
        assertNull(back.snapshot().activeMap());
        assertEquals(16.0, back.snapshot().base().engageRadius, EPS);
    }

    // ---- 抽出・書き戻し ----

    @Test
    void extractSectionIgnoresJarDefaults() throws Exception {
        YamlConfiguration cfg = serverConfig("combat:\n  speed: 2\nunits:\n  soldier:\n    equipment: {}\n");
        Map<String, Object> tree = MapProfiles.extractSection(cfg);
        assertEquals(Set.of("combat", "units"), tree.keySet());
        @SuppressWarnings("unchecked")
        Map<String, Object> combat = (Map<String, Object>) tree.get("combat");
        assertEquals(Set.of("speed"), combat.keySet());
        assertEquals(Map.of(), equip(tree, "soldier"));
        assertEquals(Set.of("soldier"), ((Map<?, ?>) tree.get("units")).keySet());
    }

    @Test
    void toYamlWritesEmptyMapAsSection() throws Exception {
        Map<String, Object> tree = MapProfiles.extractSection(serverConfig("units:\n  soldier:\n    equipment: {}\n"));
        YamlConfiguration y = MapProfiles.toYaml(tree);
        assertTrue(y.contains("units.soldier.equipment", true));
        assertNotNull(y.getConfigurationSection("units.soldier.equipment"));
        assertTrue(y.getConfigurationSection("units.soldier.equipment").getKeys(false).isEmpty());
        assertNull(y.getDefaults());
    }

    // ---- 後方互換: マップなしの結果が new Settings(c) と完全に一致する ----

    private static void assertUnitEqual(String where, Settings.Unit a, Settings.Unit b) throws IllegalAccessException {
        for (Field f : Settings.Unit.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            assertEquals(f.get(a), f.get(b), where + "." + f.getName());
        }
    }

    private static void assertSettingsEqual(String where, Settings expected, Settings actual) throws IllegalAccessException {
        int checked = 0;
        for (Field f : Settings.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Object e = f.get(expected);
            Object a = f.get(actual);
            if (f.getName().equals("units")) {
                Map<?, ?> em = (Map<?, ?>) e;
                Map<?, ?> am = (Map<?, ?>) a;
                assertEquals(em.keySet(), am.keySet(), where + ".units");
                for (Object k : em.keySet()) {
                    assertUnitEqual(where + ".units." + k, (Settings.Unit) em.get(k), (Settings.Unit) am.get(k));
                }
            } else {
                assertEquals(e, a, where + "." + f.getName());
            }
            checked++;
        }
        assertTrue(checked >= 40, "Settings の全フィールドを比べる: " + checked);
    }

    private void assertSameAsPlainSettings(String label, YamlConfiguration c) throws Exception {
        List<String> expectedSeen = new ArrayList<>();
        Settings expected = new Settings(c, spec -> {
            expectedSeen.add(spec);
            return null;
        });
        seen.clear();
        MapProfiles.Snapshot s = profiles().load(c, null);
        assertNull(s.activeMap());
        assertFalse(s.sideDifferences(), label);
        assertSettingsEqual(label + " base", expected, s.base());
        assertSettingsEqual(label + " attack", expected, s.attack());
        assertSettingsEqual(label + " defend", expected, s.defend());
        List<String> thrice = new ArrayList<>(expectedSeen);
        thrice.addAll(expectedSeen);
        thrice.addAll(expectedSeen);
        assertEquals(thrice, seen, label + ": 読むアイテムと順序");
        assertEquals(new ArrayList<>(new LinkedHashSet<>(expected.warnings)), s.warnings(), label + " warnings");
    }

    @Test
    void jarDefaultConfigMatchesPlainSettings() throws Exception {
        YamlConfiguration c = jarConfig();
        c.setDefaults(jarConfig());
        assertSameAsPlainSettings("jar", c);
    }

    @Test
    void legacyConfigMatchesPlainSettings() throws Exception {
        assertSameAsPlainSettings("0.6", serverConfig("""
                teams:
                  attack: Red
                  defend: Blue
                personal-bots:
                  enabled: true
                  max-per-player: 3
                combat:
                  speed: 1.4
                  engage-radius: 20
                equipment:
                  hand: DIAMOND_SWORD
                  helmet: GOLDEN_HELMET
                """));
    }

    @Test
    void serverLikeConfigMatchesPlainSettings() throws Exception {
        assertSameAsPlainSettings("server", serverConfig("""
                teams:
                  attack: Attack
                  defend: Defence
                personal-bots:
                  enabled: true
                  max-per-player: 12
                test-bots:
                  enabled: true
                combat:
                  speed: 1.5
                  pathfinder: citizens
                units:
                  default: spearman
                  soldier:
                    speed: 1.6
                    damage-multiplier: 1.1
                    defense-multiplier: 1.3
                    max-health: 30
                    attack-range: 3.2
                    attack-delay-ticks: 14
                    equipment:
                      hand: IRON_SWORD
                      offhand: SHIELD
                    aim:
                      turn-speed: 20
                  archer:
                    speed: 1.2
                    range: 40
                    equipment: {}
                  spearman:
                    speed: 1.3
                    attack-range: 5.0
                    stop-inside: 0.3
                    equipment:
                      hand: 'minecraft:iron_axe[custom_model_data={strings:["1"]}]'
                      helmet: AIR
                """));
    }

    @Test
    void emptyConfigMatchesPlainSettings() throws Exception {
        assertSameAsPlainSettings("empty", serverConfig(""));
    }

    // ---- 既知のキー ----

    @Test
    void everyLeafOfTheJarConfigIsKnown() {
        YamlConfiguration jar = jarConfig();
        int leaves = 0;
        for (String key : jar.getKeys(true)) {
            if (jar.isConfigurationSection(key) && !jar.getConfigurationSection(key).getKeys(false).isEmpty()) {
                continue;
            }
            leaves++;
            assertTrue(Settings.isKnownKey(key), "config.yml の " + key + " が isKnownKey で false");
        }
        assertTrue(leaves > 60, "葉の数: " + leaves);
    }

    @Test
    void everyPathLiteralInSettingsSourceIsKnown() throws IOException {
        String src = Files.readString(Path.of("src/main/java/siegebot/Settings.java"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"((?:teams|personal-bots|test-bots|orders|name-tag|combat|separation|shield|units|equipment)"
                + "\\.[A-Za-z0-9_.-]+)\"").matcher(src);
        Set<String> paths = new LinkedHashSet<>();
        while (m.find()) {
            String p = m.group(1);
            if (!p.endsWith(".")) {
                paths.add(p);
            }
        }
        // over(c, "soldier", "aim.turn-speed", ...) のように、兵種と部分に分かれて書かれたもの
        Matcher over = Pattern.compile("over\\(c, \"([a-z]+)\", \"([A-Za-z0-9_.-]+)\"").matcher(src);
        while (over.find()) {
            paths.add("units." + over.group(1) + "." + over.group(2));
        }
        assertTrue(paths.size() > 50, "見つけたパス: " + paths.size());
        for (String p : paths) {
            assertTrue(Settings.isKnownKey(p), "Settings.java の " + p + " が isKnownKey で false");
        }
    }

    @Test
    void knownKeyDetails() {
        assertTrue(Settings.isKnownKey("units.archer.range"));
        assertTrue(Settings.isKnownKey("units.archer.aim.lead-factor"));
        assertTrue(Settings.isKnownKey("units.spearman.stop-inside"));
        assertTrue(Settings.isKnownKey("units.soldier.equipment"));
        assertTrue(Settings.isKnownKey("units.soldier.equipment.OffHand"));
        assertTrue(Settings.isKnownKey("equipment.off_hand"));
        assertFalse(Settings.isKnownKey("units.soldier.range"));
        assertFalse(Settings.isKnownKey("units.soldier.stop-inside"));
        assertFalse(Settings.isKnownKey("units.archer.stop-inside"));
        assertFalse(Settings.isKnownKey("units.knight.speed"));
        assertFalse(Settings.isKnownKey("units.soldier.equipment.tail"));
        assertFalse(Settings.isKnownKey("combat.spedd"));
        assertFalse(Settings.isKnownKey("combat"));
        assertFalse(Settings.isKnownKey(null));
    }

    @Test
    void allowedInOverrideAndSide() {
        assertTrue(Settings.isAllowedInOverride("combat.speed"));
        assertTrue(Settings.isAllowedInOverride("units.soldier.equipment.hand"));
        assertFalse(Settings.isAllowedInOverride("equipment.hand"));
        assertFalse(Settings.isAllowedInOverride("personal-bots.enabled"));
        assertFalse(Settings.isAllowedInOverride("combat.spedd"));

        assertTrue(Settings.isAllowedInSide(Side.ATTACK, "teams.attack"));
        assertFalse(Settings.isAllowedInSide(Side.ATTACK, "teams.defend"));
        assertTrue(Settings.isAllowedInSide(Side.DEFEND, "teams.defend"));
        assertFalse(Settings.isAllowedInSide(Side.DEFEND, "teams.attack"));
        assertFalse(Settings.isAllowedInSide(Side.ATTACK, "equipment.hand"));
        assertFalse(Settings.isAllowedInSide(Side.DEFEND, "personal-bots.enabled"));
        assertTrue(Settings.isAllowedInSide(Side.DEFEND, "combat.speed"));
    }

    @Test
    void settingsHelpers() {
        assertEquals("off_hand", Settings.normalizeSlotKey("OffHand"));
        assertEquals("hand", Settings.normalizeSlotKey("HAND"));
        for (String empty : new String[] {null, "", "  ", "air", "AIR", "Minecraft:Air", "none", "NONE"}) {
            assertTrue(Settings.isExplicitEmptyItem(empty), String.valueOf(empty));
        }
        for (String item : new String[] {"DIAMOND_SWORD", "SHIELD", "minecraft:iron_axe[custom_model_data={strings:[\"1\"]}]"}) {
            assertFalse(Settings.isExplicitEmptyItem(item), item);
        }
        Map<String, String> soldier = Settings.defaultEquipment(UnitType.SOLDIER);
        assertEquals(List.of("hand", "off_hand", "helmet", "chestplate", "leggings", "boots"), new ArrayList<>(soldier.keySet()));
        assertEquals("IRON_SWORD", soldier.get("hand"));
        assertFalse(Settings.defaultEquipment(UnitType.ARCHER).containsKey("off_hand"));
        assertTrue(Settings.defaultEquipment(UnitType.SPEARMAN).get("hand").startsWith("minecraft:iron_axe["));
    }
}
