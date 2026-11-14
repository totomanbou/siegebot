package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiegeDataTest {

    @TempDir
    Path dir;

    private static Logger logger(List<String> sink) {
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                sink.add(r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return log;
    }

    @Test
    void missingFileLoadsEmpty() {
        SiegeData d = new SiegeData(dir.resolve("data.yml").toFile(), logger(new ArrayList<>()));
        d.load();
        assertTrue(d.spawns().isEmpty());
        assertTrue(d.bots().isEmpty());
    }

    @Test
    void saveAndLoadRoundTrip() {
        File f = dir.resolve("data.yml").toFile();
        SiegeData d = new SiegeData(f, logger(new ArrayList<>()));
        d.spawns().put(Side.ATTACK, new Point("world", 1.5, -60.0, 2.25));
        d.bots().put("A1", Side.ATTACK);
        d.bots().put("D1", Side.DEFEND);
        d.save();

        SiegeData back = new SiegeData(f, logger(new ArrayList<>()));
        back.load();
        assertEquals(new Point("world", 1.5, -60.0, 2.25), back.spawns().get(Side.ATTACK));
        assertFalse(back.spawns().containsKey(Side.DEFEND));
        assertEquals(List.of("A1", "D1"), new ArrayList<>(back.bots().keySet()));
        assertEquals(Side.DEFEND, back.bots().get("D1"));
    }

    @Test
    void loadClearsPreviousState() throws Exception {
        File f = dir.resolve("data.yml").toFile();
        Files.writeString(f.toPath(), "bots: []\n", StandardCharsets.UTF_8);
        SiegeData d = new SiegeData(f, logger(new ArrayList<>()));
        d.spawns().put(Side.DEFEND, new Point("world", 0, 0, 0));
        d.bots().put("X", Side.ATTACK);
        d.load();
        assertTrue(d.spawns().isEmpty());
        assertTrue(d.bots().isEmpty());
    }

    @Test
    void oldFormatSpawnIsIgnoredWithWarning() throws Exception {
        // 0.1 の data.yml（リスポーン地点が場所の名前、places・gates・stages あり）
        File f = dir.resolve("data.yml").toFile();
        Files.writeString(f.toPath(), String.join("\n",
                "places:",
                "- name: 攻め陣地",
                "  world: world",
                "  x: 0.5",
                "  y: -60.0",
                "  z: 0.5",
                "gates: []",
                "stages: []",
                "spawns:",
                "  attack: 攻め陣地",
                "bots:",
                "- name: A1",
                "  side: attack",
                "- name: bad",
                "  side: nowhere",
                ""), StandardCharsets.UTF_8);
        List<String> warnings = new ArrayList<>();
        SiegeData d = new SiegeData(f, logger(warnings));
        d.load();
        assertTrue(d.spawns().isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("spawns.attack"));
        assertEquals(List.of("A1"), new ArrayList<>(d.bots().keySet()));
    }
}
