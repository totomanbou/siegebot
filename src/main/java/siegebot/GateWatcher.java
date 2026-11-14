package siegebot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * 門の範囲のブロックが消えたかを定期的に調べて、侵攻度（先頭から連続して開いた門の数）を出す。
 * チャンクが読み込まれていない門は、前回の判定をそのまま使う（チャンクを読み込まない）。
 */
public final class GateWatcher {

    private final SiegeBotPlugin plugin;
    private final Map<String, Boolean> open = new HashMap<>();
    private int manualStage = -1;
    private int lastStage = -1;

    public GateWatcher(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    /** 現在の侵攻度（0 = どの門も開いていない）。 */
    public int stage() {
        int gateCount = plugin.data().gates().size();
        if (manualStage >= 0) {
            return StageLogic.clamp(manualStage, gateCount);
        }
        List<Boolean> states = new ArrayList<>();
        for (SiegeData.Gate g : plugin.data().gates()) {
            states.add(open.getOrDefault(g.name(), false));
        }
        return StageLogic.stage(states);
    }

    /** 手動で段階を固定する。負の値で自動判定に戻す。 */
    public void setManualStage(int stage) {
        manualStage = stage;
    }

    public boolean isManual() {
        return manualStage >= 0;
    }

    public boolean isOpen(String gateName) {
        return open.getOrDefault(gateName, false);
    }

    /** 門の状態を調べ直す。段階が変わったら知らせる。 */
    public void check() {
        for (SiegeData.Gate g : plugin.data().gates()) {
            Boolean now = measure(g);
            if (now != null) {
                open.put(g.name(), now);
            }
        }
        int s = stage();
        if (s != lastStage) {
            if (lastStage >= 0) {
                plugin.getLogger().info("侵攻度が " + lastStage + " から " + s + " に変わりました");
                plugin.bots().onStageChanged(s);
            }
            lastStage = s;
        }
    }

    /** @return 開いていれば true。ワールドやチャンクが読み込まれていなければ null。 */
    private Boolean measure(SiegeData.Gate g) {
        World w = Bukkit.getWorld(g.world());
        if (w == null) {
            return null;
        }
        for (int cx = g.minX() >> 4; cx <= g.maxX() >> 4; cx++) {
            for (int cz = g.minZ() >> 4; cz <= g.maxZ() >> 4; cz++) {
                if (!w.isChunkLoaded(cx, cz)) {
                    return null;
                }
            }
        }
        int air = 0;
        for (int x = g.minX(); x <= g.maxX(); x++) {
            for (int y = g.minY(); y <= g.maxY(); y++) {
                for (int z = g.minZ(); z <= g.maxZ(); z++) {
                    if (w.getBlockAt(x, y, z).getType().isAir()) {
                        air++;
                    }
                }
            }
        }
        return StageLogic.gateOpen(air, g.volume(), plugin.settings().openAirRatio);
    }
}
