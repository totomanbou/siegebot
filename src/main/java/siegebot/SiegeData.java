package siegebot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * data.yml の内容：陣営ごとのリスポーン地点と、管理者が /sb spawn で出したボットの一覧。
 * ゲーム内コマンドで書き換え、そのたびに {@link #save()} する。メインスレッドからだけ触る。
 */
public final class SiegeData {

    private final File file;
    private final Logger log;
    private final Map<Side, Point> spawns = new EnumMap<>(Side.class);
    private final Map<String, Side> bots = new LinkedHashMap<>();
    private final Map<String, UnitType> botTypes = new LinkedHashMap<>();
    /** 適用中のマップ名（null = 既定。maps/&lt;名前&gt;.yml）。 */
    private String mapName;

    public SiegeData(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    /** 陣営 → リスポーン地点（未設定の陣営は入っていない）。 */
    public Map<Side, Point> spawns() {
        return spawns;
    }

    /** /sb spawn で出したボットの名前 → 陣営。 */
    public Map<String, Side> bots() {
        return bots;
    }

    /** /sb spawn で出したボットの名前 → 兵種（書いていなければ入っていない。0.6 までの data.yml は兵種なし）。 */
    public Map<String, UnitType> botTypes() {
        return botTypes;
    }

    /** 適用中のマップ名（null = 既定）。 */
    public String mapName() {
        return mapName;
    }

    public void setMapName(String mapName) {
        this.mapName = mapName == null || mapName.isBlank() ? null : mapName.trim();
    }

    public void load() {
        spawns.clear();
        bots.clear();
        botTypes.clear();
        mapName = null;
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        String savedMap = y.getString("map");
        if (savedMap != null && !savedMap.isBlank()) {
            mapName = savedMap.trim();
        }
        for (Side s : Side.values()) {
            String key = "spawns." + s.key();
            ConfigurationSection sec = y.getConfigurationSection(key);
            if (sec == null) {
                if (y.contains(key)) {
                    // 0.1 の形式（場所の名前）は読めない
                    log.warning("data.yml の " + key + " は古い形式です。/sb spawnpoint " + s.key() + " で設定し直してください。");
                }
                continue;
            }
            String world = sec.getString("world");
            if (world != null && !world.isBlank()) {
                spawns.put(s, new Point(world, sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z")));
            }
        }
        for (Map<?, ?> m : y.getMapList("bots")) {
            Object name = m.get("name");
            Side side = Side.parse(m.get("side") == null ? null : String.valueOf(m.get("side")));
            if (name != null && !String.valueOf(name).isBlank() && side != null) {
                bots.put(String.valueOf(name), side);
                UnitType t = UnitType.parse(m.get("type") == null ? null : String.valueOf(m.get("type")));
                if (t != null) {
                    botTypes.put(String.valueOf(name), t);
                }
            }
        }
    }

    /** data.yml に書く。書けたら true、書けなければログに出して false。 */
    public boolean save() {
        YamlConfiguration y = new YamlConfiguration();
        if (mapName != null) {
            y.set("map", mapName);
        }
        for (Map.Entry<Side, Point> e : spawns.entrySet()) {
            String key = "spawns." + e.getKey().key();
            Point p = e.getValue();
            y.set(key + ".world", p.world());
            y.set(key + ".x", p.x());
            y.set(key + ".y", p.y());
            y.set(key + ".z", p.z());
        }
        List<Map<String, Object>> bl = new ArrayList<>();
        for (Map.Entry<String, Side> e : bots.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("side", e.getValue().key());
            UnitType t = botTypes.get(e.getKey());
            if (t != null) {
                m.put("type", t.key());
            }
            bl.add(m);
        }
        y.set("bots", bl);
        try {
            y.save(file);
            return true;
        } catch (IOException e) {
            log.severe("data.yml を保存できません: " + e.getMessage());
            return false;
        }
    }
}
