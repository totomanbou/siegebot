package siegebot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * data.yml の内容：場所・門・段階ごとの前線・リスポーン地点・ボットの一覧。
 * ゲーム内コマンドで書き換え、そのたびに {@link #save()} する。メインスレッドからだけ触る。
 */
public final class SiegeData {

    /** 門の範囲の体積の上限（毎秒数えるので大きすぎる範囲は受け付けない）。 */
    public static final int MAX_GATE_VOLUME = 32768;

    public record Place(String name, String world, double x, double y, double z, List<String> aliases) {
        public Location toLocation() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }
    }

    /** 門の範囲。min ≤ max に正規化済み。 */
    public record Gate(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public int volume() {
            return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    /** ある段階での攻め・守りの前線の場所名（未設定は null）。 */
    public record StagePlaces(String attack, String defend) {
        public String of(Side side) {
            return side == Side.ATTACK ? attack : defend;
        }
    }

    private final File file;
    private final Map<String, Place> places = new LinkedHashMap<>();
    private final Map<String, Place> temporaryPlaces = new LinkedHashMap<>();
    private final Map<String, Gate> gates = new LinkedHashMap<>();
    private final List<StagePlaces> stages = new ArrayList<>();
    private final Map<Side, String> spawns = new EnumMap<>(Side.class);
    private final Map<String, Side> bots = new LinkedHashMap<>();

    public SiegeData(File file) {
        this.file = file;
    }

    // ---- 場所 ----

    public Map<String, Place> places() {
        return places;
    }

    /** 正式名（大文字小文字は問わない）で場所を探す。一時的な場所（「ここを守れ」の地点）も探す。 */
    public Place place(String name) {
        if (name == null) {
            return null;
        }
        for (Place p : places.values()) {
            if (p.name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        for (Place p : temporaryPlaces.values()) {
            if (p.name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 一時的な場所を置く（同名なら置き換え）。data.yml には保存せず、別名の一覧や LLM に渡す場所の一覧にも入れない。
     */
    public void putTemporaryPlace(String name, Location loc) {
        temporaryPlaces.put(name, new Place(name, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                List.of()));
    }

    public void putPlace(String name, Location loc) {
        Place old = place(name);
        List<String> aliases = old == null ? new ArrayList<>() : new ArrayList<>(old.aliases());
        if (old != null) {
            places.remove(old.name());
        }
        places.put(name, new Place(name, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), aliases));
    }

    public boolean removePlace(String name) {
        Place p = place(name);
        return p != null && places.remove(p.name()) != null;
    }

    /** 場所に別名を足す。場所がなければ false。 */
    public boolean addAlias(String placeName, String alias) {
        Place p = place(placeName);
        if (p == null) {
            return false;
        }
        List<String> aliases = new ArrayList<>(p.aliases());
        if (!aliases.contains(alias)) {
            aliases.add(alias);
        }
        places.put(p.name(), new Place(p.name(), p.world(), p.x(), p.y(), p.z(), aliases));
        return true;
    }

    /** 小文字にした場所名・別名 → 正式名。 */
    public Map<String, String> aliasMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Place p : places.values()) {
            m.put(p.name().toLowerCase(Locale.ROOT), p.name());
        }
        for (Place p : places.values()) {
            for (String a : p.aliases()) {
                m.putIfAbsent(a.toLowerCase(Locale.ROOT), p.name());
            }
        }
        return m;
    }

    // ---- 門 ----

    /** 攻め手が通る順（登録順）の門。 */
    public List<Gate> gates() {
        return new ArrayList<>(gates.values());
    }

    /**
     * 門を登録する（同名なら置き換え、順番は保つ）。
     *
     * @throws IllegalArgumentException 体積が {@link #MAX_GATE_VOLUME} を超える
     */
    public void putGate(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        Gate g = new Gate(name, world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        // int の掛け算はあふれるので、上限の判定は long で行う
        long volume = ((long) g.maxX() - g.minX() + 1) * ((long) g.maxY() - g.minY() + 1)
                * ((long) g.maxZ() - g.minZ() + 1);
        if (volume > MAX_GATE_VOLUME) {
            throw new IllegalArgumentException("門の範囲が大きすぎます（" + volume + " > " + MAX_GATE_VOLUME + "）");
        }
        gates.put(name, g);
    }

    public boolean removeGate(String name) {
        return gates.remove(name) != null;
    }

    // ---- 段階ごとの前線 ----

    public List<StagePlaces> stages() {
        return stages;
    }

    /** 段階 stage（0 始まり）の前線の場所名を設定する。足りない段階は空で埋める。 */
    public void setStagePlace(int stage, Side side, String placeName) {
        if (stage < 0) {
            throw new IllegalArgumentException("stage=" + stage);
        }
        while (stages.size() <= stage) {
            stages.add(new StagePlaces(null, null));
        }
        StagePlaces old = stages.get(stage);
        stages.set(stage, side == Side.ATTACK
                ? new StagePlaces(placeName, old.defend())
                : new StagePlaces(old.attack(), placeName));
    }

    /**
     * 段階 stage での前線の場所名。その段階が未設定なら、設定のある直近の手前の段階を使う。どこにもなければ null。
     */
    public String stagePlace(int stage, Side side) {
        for (int i = Math.min(stage, stages.size() - 1); i >= 0; i--) {
            String n = stages.get(i).of(side);
            if (n != null && !n.isBlank()) {
                return n;
            }
        }
        return null;
    }

    // ---- リスポーン地点・ボット ----

    public Map<Side, String> spawns() {
        return spawns;
    }

    public Map<String, Side> bots() {
        return bots;
    }

    // ---- 読み書き ----

    public void load() {
        places.clear();
        gates.clear();
        stages.clear();
        spawns.clear();
        bots.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : y.getMapList("places")) {
            String name = str(m.get("name"));
            String world = str(m.get("world"));
            if (name == null || world == null) {
                continue;
            }
            List<String> aliases = new ArrayList<>();
            if (m.get("aliases") instanceof List<?> l) {
                for (Object o : l) {
                    aliases.add(String.valueOf(o));
                }
            }
            places.put(name, new Place(name, world, num(m.get("x")), num(m.get("y")), num(m.get("z")), aliases));
        }
        for (Map<?, ?> m : y.getMapList("gates")) {
            String name = str(m.get("name"));
            String world = str(m.get("world"));
            if (name == null || world == null
                    || !(m.get("min") instanceof List<?> mn) || !(m.get("max") instanceof List<?> mx)
                    || mn.size() != 3 || mx.size() != 3) {
                continue;
            }
            try {
                putGate(name, world, (int) num(mn.get(0)), (int) num(mn.get(1)), (int) num(mn.get(2)),
                        (int) num(mx.get(0)), (int) num(mx.get(1)), (int) num(mx.get(2)));
            } catch (IllegalArgumentException e) {
                Bukkit.getLogger().warning("[SiegeBot] 門「" + name + "」を無視しました: " + e.getMessage());
            }
        }
        for (Map<?, ?> m : y.getMapList("stages")) {
            stages.add(new StagePlaces(str(m.get("attack")), str(m.get("defend"))));
        }
        for (Side s : Side.values()) {
            String n = y.getString("spawns." + s.key());
            if (n != null && !n.isBlank()) {
                spawns.put(s, n);
            }
        }
        for (Map<?, ?> m : y.getMapList("bots")) {
            String name = str(m.get("name"));
            Side side = Side.parse(str(m.get("side")));
            if (name != null && side != null) {
                bots.put(name, side);
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, Object>> pl = new ArrayList<>();
        for (Place p : places.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("world", p.world());
            m.put("x", p.x());
            m.put("y", p.y());
            m.put("z", p.z());
            m.put("aliases", new ArrayList<>(p.aliases()));
            pl.add(m);
        }
        y.set("places", pl);
        List<Map<String, Object>> gl = new ArrayList<>();
        for (Gate g : gates.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", g.name());
            m.put("world", g.world());
            m.put("min", List.of(g.minX(), g.minY(), g.minZ()));
            m.put("max", List.of(g.maxX(), g.maxY(), g.maxZ()));
            gl.add(m);
        }
        y.set("gates", gl);
        List<Map<String, Object>> sl = new ArrayList<>();
        for (StagePlaces s : stages) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("attack", s.attack() == null ? "" : s.attack());
            m.put("defend", s.defend() == null ? "" : s.defend());
            sl.add(m);
        }
        y.set("stages", sl);
        for (Map.Entry<Side, String> e : spawns.entrySet()) {
            y.set("spawns." + e.getKey().key(), e.getValue());
        }
        List<Map<String, Object>> bl = new ArrayList<>();
        for (Map.Entry<String, Side> e : bots.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("side", e.getValue().key());
            bl.add(m);
        }
        y.set("bots", bl);
        try {
            y.save(file);
        } catch (IOException e) {
            Bukkit.getLogger().severe("[SiegeBot] data.yml を保存できません: " + e.getMessage());
        }
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
