package siegebot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * マップ別の設定（plugins/SiegeBot/maps/&lt;名前&gt;.yml）と、攻め・守り別の設定（sides.attack / sides.defend）。
 * config.yml → （extends の親マップ → ）対象のマップ の順に、ファイルごとに「共通部 → sides.&lt;陣営&gt;」を重ね、
 * 共通（base）・攻め・守りの 3 つの {@link Settings} を作る。メインスレッドからだけ触る。
 */
public final class MapProfiles {

    /** extends でたどれる親の数の上限。 */
    static final int MAX_EXTENDS_DEPTH = 8;

    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Set<String> RESERVED = Set.of("default", "reset", "list");
    private static final Comparator<String> NAME_ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());
    private static final List<String> UNIT_NAMES = List.of("soldier", "archer", "spearman");

    private final File mapsDir;
    private final Function<String, ItemStack> itemParser;
    private Snapshot current;

    public MapProfiles(File mapsDir) {
        this(mapsDir, Settings::parseItem);
    }

    /** 試験用: アイテムを読む関数を差し替える（未起動のサーバーでは ItemStack を作れないため）。 */
    MapProfiles(File mapsDir, Function<String, ItemStack> itemParser) {
        this.mapsDir = mapsDir;
        this.itemParser = itemParser;
    }

    // ---- 結果の型 ----

    /** ある時点の設定一式。 */
    public static final class Snapshot {
        private final String activeMap;
        private final List<String> extendsChain;
        private final Settings base;
        private final Settings attack;
        private final Settings defend;
        private final boolean sideDifferences;
        private final List<String> warnings;
        /** 試験用: 合成した設定の木（共通・攻め・守り）。 */
        final Map<String, Object> baseTree;
        final Map<String, Object> attackTree;
        final Map<String, Object> defendTree;

        public Snapshot(String activeMap, List<String> extendsChain, Settings base, Settings attack, Settings defend,
                        boolean sideDifferences, List<String> warnings) {
            this(activeMap, extendsChain, base, attack, defend, sideDifferences, warnings, Map.of(), Map.of(), Map.of());
        }

        Snapshot(String activeMap, List<String> extendsChain, Settings base, Settings attack, Settings defend,
                 boolean sideDifferences, List<String> warnings,
                 Map<String, Object> baseTree, Map<String, Object> attackTree, Map<String, Object> defendTree) {
            this.activeMap = activeMap;
            this.extendsChain = List.copyOf(extendsChain);
            this.base = base;
            this.attack = attack;
            this.defend = defend;
            this.sideDifferences = sideDifferences;
            this.warnings = List.copyOf(warnings);
            this.baseTree = baseTree;
            this.attackTree = attackTree;
            this.defendTree = defendTree;
        }

        /** 適用中のマップの名前（ファイルの実際の名前。null = 既定 = config.yml だけ）。 */
        public String activeMap() {
            return activeMap;
        }

        /** extends の親（近い順。直接の親が先頭）。 */
        public List<String> extendsChain() {
            return extendsChain;
        }

        public Settings base() {
            return base;
        }

        public Settings attack() {
            return attack;
        }

        public Settings defend() {
            return defend;
        }

        public Settings of(Side side) {
            return side == null ? base : side == Side.DEFEND ? defend : attack;
        }

        public boolean sideDifferences() {
            return sideDifferences;
        }

        public List<String> warnings() {
            return warnings;
        }
    }

    /** {@link #tryBuild} の結果: 成功なら snapshot、失敗なら error（日本語の理由）。 */
    public static final class Result {
        private final Snapshot snapshot;
        private final String error;

        private Result(Snapshot snapshot, String error) {
            this.snapshot = snapshot;
            this.error = error;
        }

        static Result ok(Snapshot s) {
            return new Result(s, null);
        }

        static Result error(String message) {
            return new Result(null, message);
        }

        public boolean isOk() {
            return snapshot != null;
        }

        public Snapshot snapshot() {
            return snapshot;
        }

        public String error() {
            return error;
        }
    }

    /** 1 つのファイルの分（config.yml・マップ 1 つ）。共通部と、陣営ごとの上書き。検証済み。 */
    static final class Layer {
        final String where;
        final Map<String, Object> common = new LinkedHashMap<>();
        final Map<Side, Map<String, Object>> sides = new EnumMap<>(Side.class);

        Layer(String where) {
            this.where = where;
        }
    }

    private static final class MapException extends Exception {
        MapException(String message) {
            super(message);
        }
    }

    // ---- 名前・ファイル ----

    static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    static boolean isReserved(String name) {
        return name != null && RESERVED.contains(name.toLowerCase(Locale.ROOT));
    }

    /** default / reset（大文字小文字は問わない）。 */
    public static boolean isDefaultWord(String name) {
        return name != null && (name.equalsIgnoreCase("default") || name.equalsIgnoreCase("reset"));
    }

    /** maps/ を作る（サンプルのファイルは置かない）。 */
    public void ensureMapsDir() {
        if (!mapsDir.isDirectory()) {
            mapsDir.mkdirs();
        }
    }

    /** maps/ の .yml のファイル名（拡張子なし）を、大文字小文字を無視した順→自然順に並べる。 */
    static List<String> sortedMapNames(File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        List<String> names = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (f.isFile() && n.endsWith(".yml")) {
                    names.add(n.substring(0, n.length() - ".yml".length()));
                }
            }
        }
        names.sort(NAME_ORDER);
        return names;
    }

    /** 使えるマップの一覧（名前が有効で予約語でないものだけ）。 */
    public List<String> listMaps() {
        List<String> r = new ArrayList<>();
        for (String n : sortedMapNames(mapsDir)) {
            if (isValidName(n) && !isReserved(n)) {
                r.add(n);
            }
        }
        return r;
    }

    /**
     * name に当たるファイル。厳密に一致する名前があればそれ、なければ並び順で最初の大文字小文字無視の一致。
     * 名前が無効・ファイルがなければ null。実際の名前（正規名）はファイル名から .yml を除いたもの。
     */
    static File resolveMapFile(File mapsDir, String name) {
        if (!isValidName(name)) {
            return null;
        }
        String hit = pickName(sortedMapNames(mapsDir), name);
        return hit == null ? null : new File(mapsDir, hit + ".yml");
    }

    /**
     * ファイル名（拡張子なし）の一覧から name に当たるものを決める。厳密な一致を先に、なければ
     * 大文字小文字を無視した一致のうち、並び順（大文字小文字を無視 → 自然順）で最初のもの。なければ null。
     */
    static String pickName(Collection<String> names, String name) {
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(NAME_ORDER);
        for (String n : sorted) {
            if (n.equals(name)) {
                return n;
            }
        }
        for (String n : sorted) {
            if (n.equalsIgnoreCase(name)) {
                return n;
            }
        }
        return null;
    }

    private static String canonicalName(File f) {
        String n = f.getName();
        return n.substring(0, n.length() - ".yml".length());
    }

    /** マップ名の形式とファイルの有無を調べる。問題なければ null、あれば理由。default / reset は問題なし。 */
    public String checkName(String requested) {
        if (requested == null || requested.isBlank() || isDefaultWord(requested.trim())) {
            return null;
        }
        String name = requested.trim();
        if (isReserved(name)) {
            return "「" + name + "」は予約語なのでマップの名前にできません。";
        }
        if (!isValidName(name)) {
            return "マップの名前は半角の英数字・_・- の 1〜64 文字で指定してください: " + name;
        }
        if (resolveMapFile(mapsDir, name) == null) {
            return "マップ「" + name + "」がありません（maps/" + name + ".yml）。/sb map list で一覧を見られます。";
        }
        return null;
    }

    /** 実ファイルの名前（正規名）。default / reset・見つからないときは null。 */
    public String canonicalName(String requested) {
        if (requested == null || requested.isBlank() || isDefaultWord(requested.trim())) {
            return null;
        }
        File f = resolveMapFile(mapsDir, requested.trim());
        return f == null ? null : canonicalName(f);
    }

    // ---- 読み込み・切り替え ----

    /**
     * 起動・リロード用。requestedMap が null・空なら既定（config.yml のみ）。
     * 読めなければ理由を警告に入れて既定で動かす（呼び出し元の保存データは変えない）。
     */
    public Snapshot load(ConfigurationSection baseConfig, String requestedMap) {
        String req = requestedMap == null || requestedMap.isBlank() ? null : requestedMap.trim();
        Snapshot s;
        if (req == null || isDefaultWord(req)) {
            s = build(baseConfig, null).snapshot();
        } else {
            Result r = build(baseConfig, req);
            if (r.isOk()) {
                s = r.snapshot();
            } else {
                Snapshot d = build(baseConfig, null).snapshot();
                List<String> w = new ArrayList<>();
                w.add("マップ「" + req + "」を読み込めません: " + r.error() + "（default = config.yml だけで動かします）");
                w.addAll(d.warnings());
                s = new Snapshot(null, List.of(), d.base(), d.attack(), d.defend(), d.sideDifferences(),
                        new ArrayList<>(new LinkedHashSet<>(w)), d.baseTree, d.attackTree, d.defendTree);
            }
        }
        current = s;
        return s;
    }

    /**
     * 切り替え用: 新しい設定一式を作るだけで、今の状態は変えない（差し替えは {@link #apply}）。
     * default / reset は既定。マップが読めない・検証に失敗したら error。
     */
    public Result tryBuild(ConfigurationSection baseConfig, String requestedMap) {
        String req = requestedMap == null || requestedMap.isBlank() ? null : requestedMap.trim();
        if (req == null || isDefaultWord(req)) {
            return build(baseConfig, null);
        }
        String err = checkName(req);
        if (err != null) {
            return Result.error(err);
        }
        return build(baseConfig, req);
    }

    public void apply(Snapshot snapshot) {
        this.current = snapshot;
    }

    // ---- 今の状態 ----

    public Snapshot snapshot() {
        return current;
    }

    /** 共通の設定（陣営を持たない場面。チームの判定は teams で攻め・守りに分ける前のもの）。 */
    public Settings base() {
        return current.base();
    }

    public Settings of(Side side) {
        return current.of(side);
    }

    /** 適用中のマップ名（null = 既定）。 */
    public String activeMap() {
        return current.activeMap();
    }

    public List<String> extendsChain() {
        return current.extendsChain();
    }

    public boolean hasSideDifferences() {
        return current.sideDifferences();
    }

    public List<String> warnings() {
        return current.warnings();
    }

    /** 保存データの map がフォールバック（読めなくて default で動いている）かどうか。 */
    public boolean isFallback(String savedMapName) {
        return savedMapName != null && !savedMapName.isBlank() && !isDefaultWord(savedMapName.trim())
                && current.activeMap() == null;
    }

    /** 「default」「castle_a」「snow_keep (extends: wide_field)」。 */
    public String describeActive() {
        return describe(current);
    }

    static String describe(Snapshot s) {
        if (s.activeMap() == null) {
            return "default";
        }
        if (s.extendsChain().isEmpty()) {
            return s.activeMap();
        }
        return s.activeMap() + " (extends: " + String.join(" → ", s.extendsChain()) + ")";
    }

    // ---- 構築 ----

    private Result build(ConfigurationSection cfg, String requested) {
        Set<String> loaderWarnings = new LinkedHashSet<>();
        Layer configLayer = parseConfigLayer(cfg, loaderWarnings);
        List<Layer> layers = new ArrayList<>();
        layers.add(configLayer);
        String active = null;
        List<String> parents = List.of();
        if (requested != null) {
            try {
                List<String> parentsOut = new ArrayList<>();
                String[] activeOut = new String[1];
                layers.addAll(readChain(requested, parentsOut, activeOut, loaderWarnings));
                active = activeOut[0];
                parents = parentsOut;
            } catch (MapException e) {
                return Result.error(e.getMessage());
            }
        }
        Snapshot snap = compose(layers, active, parents, loaderWarnings);
        if (collides(snap)) {
            String name = snap.attack().teamName(Side.ATTACK);
            boolean configCollides = active == null
                    || collides(compose(List.of(configLayer), null, List.of(), new LinkedHashSet<>()));
            if (!configCollides) {
                return Result.error("攻めと守りのチーム名が同じ「" + name + "」になります（teams.attack と teams.defend は別の名前にしてください）。");
            }
            loaderWarnings.add("config.yml: 攻めと守りのチーム名が同じ「" + name + "」です（teams.attack と teams.defend を別の名前にしてください）");
            snap = compose(layers, active, parents, loaderWarnings);
        }
        return Result.ok(snap);
    }

    private static boolean collides(Snapshot s) {
        return s.attack().teamName(Side.ATTACK).equalsIgnoreCase(s.defend().teamName(Side.DEFEND));
    }

    private Snapshot compose(List<Layer> layers, String active, List<String> parents, Set<String> loaderWarnings) {
        Map<String, Object> baseTree = composeTree(layers, null);
        Map<String, Object> attackTree = composeTree(layers, Side.ATTACK);
        Map<String, Object> defendTree = composeTree(layers, Side.DEFEND);
        Settings base = new Settings(toYaml(baseTree), itemParser);
        Settings attack = new Settings(toYaml(attackTree), itemParser);
        Settings defend = new Settings(toYaml(defendTree), itemParser);
        Set<String> warnings = new LinkedHashSet<>(loaderWarnings);
        warnings.addAll(base.warnings);
        warnings.addAll(attack.warnings);
        warnings.addAll(defend.warnings);
        return new Snapshot(active, parents, base, attack, defend, !attackTree.equals(defendTree),
                new ArrayList<>(warnings), baseTree, attackTree, defendTree);
    }

    /**
     * ファイルごとに「共通部 → sides.&lt;陣営&gt;」の順に重ねた設定の木。side が null なら共通部だけ。
     * 1 つ目（config.yml の共通部）はそのまま写し、それ以降は装備を部位ごとに重ねる。
     */
    static Map<String, Object> composeTree(List<Layer> layers, Side side) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            overlay(merged, merged, l.common, "", i != 0);
            if (side != null && l.sides.containsKey(side)) {
                overlay(merged, merged, l.sides.get(side), "", true);
            }
        }
        return merged;
    }

    /** src を dst に再帰的に重ねる（葉のキーごとに上書き。兄弟のキーは残す）。木は写すので src とは共有しない。 */
    private static void overlay(Map<String, Object> root, Map<String, Object> dst, Map<String, Object> src,
                                String path, boolean equipmentByPart) {
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            String p = path.isEmpty() ? key : path + "." + key;
            if (v instanceof Map<?, ?> m) {
                String unit = unitOfEquipmentPath(p);
                if (equipmentByPart && unit != null) {
                    applyEquipment(root, unit, m);
                    continue;
                }
                Object cur = dst.get(key);
                Map<String, Object> sub;
                if (cur instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cm = (Map<String, Object>) cur;
                    sub = cm;
                } else {
                    sub = new LinkedHashMap<>();
                    dst.put(key, sub);
                }
                overlay(root, sub, castMap(m), p, equipmentByPart);
            } else if (v instanceof List<?> l) {
                dst.put(key, new ArrayList<>(l));
            } else {
                dst.put(key, v);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** units.&lt;兵種&gt;.equipment ならその兵種の名前、そうでなければ null。 */
    private static String unitOfEquipmentPath(String p) {
        String[] parts = p.split("\\.");
        if (parts.length == 3 && parts[0].equals("units") && parts[2].equals("equipment") && UNIT_NAMES.contains(parts[1])) {
            return parts[1];
        }
        return null;
    }

    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object cur = parent.get(key);
        if (cur instanceof Map<?, ?>) {
            return castMap((Map<?, ?>) cur);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        parent.put(key, m);
        return m;
    }

    /**
     * units.&lt;兵種&gt;.equipment の重ね方。直前までの合成結果の装備（なければ 0.6 までの一番上の equipment［剣兵だけ］、
     * それもなければ組み込みの既定）を起点に、書いてある部位だけ変える。AIR・NONE・空は外す。空の節 {} は全部外す。
     */
    private static void applyEquipment(Map<String, Object> root, String unit, Map<?, ?> incoming) {
        Map<String, Object> unitMap = child(child(root, "units"), unit);
        if (incoming.isEmpty()) {
            unitMap.put("equipment", new LinkedHashMap<String, Object>());
            return;
        }
        Map<String, Object> start = new LinkedHashMap<>();
        Object cur = unitMap.get("equipment");
        if (cur instanceof Map<?, ?> cm) {
            for (Map.Entry<?, ?> e : cm.entrySet()) {
                start.put(Settings.normalizeSlotKey(String.valueOf(e.getKey())), e.getValue());
            }
        } else {
            Object legacy = root.get("equipment");
            UnitType type = UnitType.parse(unit);
            if (type == UnitType.SOLDIER && legacy instanceof Map<?, ?> lm) {
                for (Map.Entry<?, ?> e : lm.entrySet()) {
                    start.put(Settings.normalizeSlotKey(String.valueOf(e.getKey())), e.getValue());
                }
                if (!start.containsKey("off_hand")) {
                    start.put("off_hand", Settings.defaultEquipment(UnitType.SOLDIER).get("off_hand"));
                }
            } else {
                start.putAll(Settings.defaultEquipment(type));
            }
        }
        for (Map.Entry<?, ?> e : incoming.entrySet()) {
            String slot = Settings.normalizeSlotKey(String.valueOf(e.getKey()));
            Object v = e.getValue();
            if (v == null || Settings.isExplicitEmptyItem(String.valueOf(v))) {
                start.remove(slot);
            } else {
                start.put(slot, String.valueOf(v));
            }
        }
        unitMap.put("equipment", start);
    }

    // ---- 木 <-> YAML ----

    /**
     * 節の、そのファイルに書いてある値だけを木にする。jar の既定（defaults）は混ぜない。
     * 空の節も空のまま残す。
     */
    static Map<String, Object> extractSection(ConfigurationSection sec) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (String key : sec.getKeys(false)) {
            if (!sec.contains(key, true)) {
                continue;
            }
            Object raw = sec.get(key, null);
            if (raw instanceof ConfigurationSection sub) {
                r.put(key, extractSection(sub));
            } else if (raw != null) {
                r.put(key, raw);
            }
        }
        return r;
    }

    /**
     * 木を YamlConfiguration に書き戻す（defaults は付けない。Settings は contains(path, true) と get(path, 既定) だけを使う）。
     * Map は必ず createSection で節にする（set(key, map) だと節として扱われず、空の {} が既定の装備に戻ってしまう）。
     */
    static YamlConfiguration toYaml(Map<String, Object> tree) {
        YamlConfiguration yaml = new YamlConfiguration();
        writeMap(yaml, tree);
        return yaml;
    }

    private static void writeMap(ConfigurationSection parent, Map<?, ?> map) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (v instanceof Map<?, ?> sub) {
                ConfigurationSection section = parent.createSection(key);
                writeMap(section, sub);
            } else {
                parent.set(key, v);
            }
        }
    }

    // ---- ファイルの読み込みと検証 ----

    private Layer parseConfigLayer(ConfigurationSection cfg, Set<String> warnings) {
        Layer layer = new Layer("config.yml");
        Map<String, Object> full = extractSection(cfg);
        Object sides = full.remove("sides");
        if (full.remove("extends") != null) {
            warnings.add("config.yml: extends はマップのファイルでだけ使えます（無視します）");
        }
        layer.common.putAll(full);
        parseSides(sides, layer, warnings);
        return layer;
    }

    private Layer parseMapLayer(String canonical, YamlConfiguration y, Set<String> warnings) {
        Layer layer = new Layer("maps/" + canonical + ".yml");
        Map<String, Object> full = extractSection(y);
        full.remove("extends");
        Object sides = full.remove("sides");
        layer.common.putAll(filter(full, "", Settings::isAllowedInOverride, layer.where, "", warnings));
        parseSides(sides, layer, warnings);
        return layer;
    }

    private void parseSides(Object sides, Layer layer, Set<String> warnings) {
        if (sides == null) {
            return;
        }
        if (!(sides instanceof Map<?, ?> sm)) {
            warnings.add(layer.where + ": sides は attack / defend の節で書いてください（無視します）");
            return;
        }
        for (Map.Entry<?, ?> e : sm.entrySet()) {
            String k = String.valueOf(e.getKey());
            Side side = k.equalsIgnoreCase("attack") ? Side.ATTACK : k.equalsIgnoreCase("defend") ? Side.DEFEND : null;
            if (side == null) {
                warnings.add(layer.where + ": sides." + k + " は使えません（sides の下は attack と defend だけ。無視します）");
                continue;
            }
            String scope = "sides." + side.key();
            if (!(e.getValue() instanceof Map<?, ?> body)) {
                warnings.add(layer.where + ": " + scope + " は設定の節で書いてください（無視します）");
                continue;
            }
            Map<String, Object> tree = new LinkedHashMap<>(castMap(body));
            for (String reserved : new String[] {"extends", "sides"}) {
                if (tree.remove(reserved) != null) {
                    warnings.add(layer.where + ": " + scope + "." + reserved + " は sides の中には書けません（無視します）");
                }
            }
            Side s = side;
            layer.sides.put(side, filter(tree, "", p -> Settings.isAllowedInSide(s, p), layer.where, scope, warnings));
        }
    }

    /**
     * 木の葉（値、または空の節）のうち allowed でないものを警告して捨てた、新しい木。
     * 空の節が許されるのは units.&lt;兵種&gt;.equipment だけ（全部外す印）。装備は節（部位: アイテム）でだけ書ける。
     */
    private static Map<String, Object> filter(Map<String, Object> tree, String prefix, Predicate<String> allowed,
                                              String where, String scope, Set<String> warnings) {
        Map<String, Object> out = new LinkedHashMap<>();
        String loc = scope.isEmpty() ? where : where + " の " + scope;
        for (Map.Entry<String, Object> e : tree.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            boolean equipmentSection = unitOfEquipmentPath(path) != null;
            if (v instanceof Map<?, ?> m) {
                if (m.isEmpty()) {
                    if (equipmentSection && allowed.test(path)) {
                        out.put(key, new LinkedHashMap<String, Object>());
                    } else {
                        warnings.add(loc + ": 「" + path + "」は空の節なので無視します");
                    }
                } else {
                    Map<String, Object> sub = filter(castMap(m), path, allowed, where, scope, warnings);
                    if (!sub.isEmpty()) {
                        out.put(key, sub);
                    }
                }
            } else if (equipmentSection) {
                warnings.add(loc + ": 「" + path + "」は節（部位: アイテム）で書いてください（無視します）");
            } else if (allowed.test(path)) {
                out.put(key, v instanceof List<?> l ? new ArrayList<>(l) : v);
            } else if (Settings.isKnownKey(path)) {
                warnings.add(loc + ": 「" + path + "」は上書きには書けません（無視します）");
            } else {
                warnings.add(loc + ": 「" + path + "」は未知の項目です（無視します）");
            }
        }
        return out;
    }

    private static YamlConfiguration readYaml(File file, String canonical) throws MapException {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            String msg = String.valueOf(e.getMessage());
            int nl = msg.indexOf('\n');
            throw new MapException("maps/" + canonical + ".yml を読めません: " + (nl < 0 ? msg : msg.substring(0, nl)));
        }
        return y;
    }

    /**
     * 対象のマップと extends の親を読む。戻り値は [最上位の親, ..., 直接の親, 対象] の順。
     * parentsOut には親の名前（直接の親が先頭）、activeOut[0] には対象の実際の名前を入れる。
     */
    private List<Layer> readChain(String requested, List<String> parentsOut, String[] activeOut,
                                  Set<String> warnings) throws MapException {
        File file = resolveMapFile(mapsDir, requested);
        if (file == null) {
            throw new MapException("マップ「" + requested + "」がありません（maps/" + requested + ".yml）");
        }
        String target = canonicalName(file);
        activeOut[0] = target;
        List<Layer> targetFirst = new ArrayList<>();
        List<String> path = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String cur = target;
        File curFile = file;
        path.add(cur);
        seen.add(cur.toLowerCase(Locale.ROOT));
        while (true) {
            YamlConfiguration y = readYaml(curFile, cur);
            targetFirst.add(parseMapLayer(cur, y, warnings));
            Object ext = y.get("extends");
            if (ext == null) {
                break;
            }
            String where = "maps/" + cur + ".yml";
            if (!(ext instanceof String s) || s.isBlank()) {
                throw new MapException(where + ": extends には親マップの名前（文字列）を書いてください");
            }
            String parent = s.trim();
            if (!isValidName(parent) || isReserved(parent)) {
                throw new MapException(where + ": extends の「" + parent + "」はマップの名前として使えません");
            }
            File pf = resolveMapFile(mapsDir, parent);
            if (pf == null) {
                throw new MapException(where + ": extends の親マップ「" + parent + "」がありません（maps/" + parent + ".yml）");
            }
            String pname = canonicalName(pf);
            path.add(pname);
            if (!seen.add(pname.toLowerCase(Locale.ROOT))) {
                throw new MapException("extends が循環しています: " + String.join(" -> ", path));
            }
            parentsOut.add(pname);
            if (parentsOut.size() > MAX_EXTENDS_DEPTH) {
                throw new MapException("extends が深すぎます（親は " + MAX_EXTENDS_DEPTH + " 段まで）: " + String.join(" -> ", path));
            }
            cur = pname;
            curFile = pf;
        }
        Collections.reverse(targetFirst);
        return targetFirst;
    }
}
