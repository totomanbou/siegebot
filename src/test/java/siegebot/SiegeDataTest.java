package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SiegeDataTest {

    private SiegeData data;

    @BeforeEach
    void setUp() {
        data = new SiegeData(new File("unused.yml"));
    }

    private void addPlace(String name, String... aliases) {
        data.places().put(name, new SiegeData.Place(name, "world", 1, 2, 3, new ArrayList<>(List.of(aliases))));
    }

    // ---- 門 ----

    @Test
    void putGateNormalisesMinMaxAndComputesVolume() {
        data.putGate("g", "world", 10, 70, -5, 7, 64, -8);
        SiegeData.Gate g = data.gates().get(0);
        assertEquals(7, g.minX());
        assertEquals(64, g.minY());
        assertEquals(-8, g.minZ());
        assertEquals(10, g.maxX());
        assertEquals(70, g.maxY());
        assertEquals(-5, g.maxZ());
        assertEquals(4 * 7 * 4, g.volume());
    }

    @Test
    void putGateRejectsVolumeAboveLimit() {
        // 32 * 32 * 33 = 33792 > 32768
        assertThrows(IllegalArgumentException.class, () -> data.putGate("big", "world", 0, 0, 0, 31, 31, 32));
        assertTrue(data.gates().isEmpty());
    }

    @Test
    void putGateAcceptsVolumeExactlyAtLimit() {
        // 32 * 32 * 32 = 32768
        data.putGate("max", "world", 0, 0, 0, 31, 31, 31);
        assertEquals(SiegeData.MAX_GATE_VOLUME, data.gates().get(0).volume());
    }

    @Test
    void putGateRejectsVolumeThatOverflowsInt() {
        // 65536 * 1 * 65536 = 2^32 は int だと 0 に丸まり、上限の検査をすり抜けてしまう
        assertThrows(IllegalArgumentException.class, () -> data.putGate("huge", "world", 0, 0, 0, 65535, 0, 65535));
        assertTrue(data.gates().isEmpty());
    }

    @Test
    void rePuttingGateKeepsItsPosition() {
        data.putGate("a", "world", 0, 0, 0, 1, 1, 1);
        data.putGate("b", "world", 0, 0, 0, 1, 1, 1);
        data.putGate("c", "world", 0, 0, 0, 1, 1, 1);
        data.putGate("a", "world", 5, 5, 5, 6, 6, 6);
        List<SiegeData.Gate> gates = data.gates();
        assertEquals(List.of("a", "b", "c"), gates.stream().map(SiegeData.Gate::name).toList());
        assertEquals(5, gates.get(0).minX());
    }

    @Test
    void removeGate() {
        data.putGate("a", "world", 0, 0, 0, 1, 1, 1);
        assertTrue(data.removeGate("a"));
        assertFalse(data.removeGate("a"));
    }

    // ---- 段階ごとの前線 ----

    @Test
    void setStagePlacePadsMissingStagesAndKeepsOtherSide() {
        data.setStagePlace(2, Side.ATTACK, "A2");
        assertEquals(3, data.stages().size());
        assertNull(data.stages().get(0).attack());
        assertNull(data.stages().get(1).defend());
        assertEquals("A2", data.stages().get(2).attack());
        assertNull(data.stages().get(2).defend());

        data.setStagePlace(2, Side.DEFEND, "D2");
        assertEquals("A2", data.stages().get(2).attack());
        assertEquals("D2", data.stages().get(2).defend());

        data.setStagePlace(2, Side.ATTACK, "A2b");
        assertEquals("A2b", data.stages().get(2).attack());
        assertEquals("D2", data.stages().get(2).defend());
    }

    @Test
    void setStagePlaceRejectsNegativeStage() {
        assertThrows(IllegalArgumentException.class, () -> data.setStagePlace(-1, Side.ATTACK, "x"));
    }

    @Test
    void stagePlaceFallsBackToNearestEarlierStage() {
        data.setStagePlace(0, Side.ATTACK, "A0");
        data.setStagePlace(3, Side.ATTACK, "A3");
        assertEquals("A0", data.stagePlace(0, Side.ATTACK));
        assertEquals("A0", data.stagePlace(1, Side.ATTACK));
        assertEquals("A0", data.stagePlace(2, Side.ATTACK));
        assertEquals("A3", data.stagePlace(3, Side.ATTACK));
    }

    @Test
    void stagePlaceClampsStageBeyondList() {
        data.setStagePlace(1, Side.DEFEND, "D1");
        assertEquals("D1", data.stagePlace(99, Side.DEFEND));
    }

    @Test
    void stagePlaceIsNullWhenNothingIsSet() {
        data.setStagePlace(2, Side.ATTACK, "A2");
        assertNull(data.stagePlace(1, Side.ATTACK));
        assertNull(data.stagePlace(2, Side.DEFEND));
        assertNull(data.stagePlace(5, Side.DEFEND));
    }

    @Test
    void stagePlaceIsNullForEmptyList() {
        assertNull(data.stagePlace(0, Side.ATTACK));
        assertNull(data.stagePlace(10, Side.DEFEND));
    }

    @Test
    void stagePlaceSkipsBlankNames() {
        data.setStagePlace(0, Side.ATTACK, "A0");
        data.setStagePlace(1, Side.ATTACK, "  ");
        assertEquals("A0", data.stagePlace(1, Side.ATTACK));
    }

    // ---- 場所・別名 ----

    @Test
    void aliasMapUsesLowercaseKeys() {
        addPlace("Gate", "East Gate", "MAIN");
        Map<String, String> m = data.aliasMap();
        assertEquals("Gate", m.get("gate"));
        assertEquals("Gate", m.get("east gate"));
        assertEquals("Gate", m.get("main"));
        assertNull(m.get("Gate"));
    }

    @Test
    void placeOwnNameWinsOverOtherPlacesAlias() {
        // 先に登録した場所の別名が、後の場所の正式名と同じでも、正式名が優先される
        addPlace("First", "Second");
        addPlace("Second");
        assertEquals("Second", data.aliasMap().get("second"));
    }

    @Test
    void earlierAliasWinsOverLaterIdenticalAlias() {
        addPlace("One", "x");
        addPlace("Two", "X");
        assertEquals("One", data.aliasMap().get("x"));
    }

    @Test
    void placeLookupIgnoresCase() {
        addPlace("Keep");
        assertEquals("Keep", data.place("kEEp").name());
        assertNull(data.place("nowhere"));
        assertNull(data.place(null));
    }

    @Test
    void addAliasOnUnknownPlaceReturnsFalse() {
        assertFalse(data.addAlias("ghost", "g"));
        assertTrue(data.places().isEmpty());
    }

    @Test
    void addAliasAddsOnceAndDoesNotDuplicate() {
        addPlace("Keep");
        assertTrue(data.addAlias("keep", "castle"));
        assertTrue(data.addAlias("Keep", "castle"));
        assertEquals(List.of("castle"), data.place("Keep").aliases());
        assertEquals("Keep", data.aliasMap().get("castle"));
        assertEquals(1, data.places().size());
    }
}
