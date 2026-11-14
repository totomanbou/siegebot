package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class StageLogicTest {

    // ---- gateOpen ----

    @Test
    void gateOpenAtThresholdCountsAsOpen() {
        assertTrue(StageLogic.gateOpen(90, 100, 0.9));
    }

    @Test
    void gateOpenJustBelowThresholdIsClosed() {
        assertFalse(StageLogic.gateOpen(89, 100, 0.9));
    }

    @Test
    void gateOpenRatioOneNeedsAllAir() {
        assertTrue(StageLogic.gateOpen(100, 100, 1.0));
        assertFalse(StageLogic.gateOpen(99, 100, 1.0));
    }

    @Test
    void gateOpenZeroAirIsClosed() {
        assertFalse(StageLogic.gateOpen(0, 100, 0.9));
        assertFalse(StageLogic.gateOpen(0, 1, 1.0));
    }

    @Test
    void gateOpenRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> StageLogic.gateOpen(0, 0, 0.9));
        assertThrows(IllegalArgumentException.class, () -> StageLogic.gateOpen(0, -1, 0.9));
        assertThrows(IllegalArgumentException.class, () -> StageLogic.gateOpen(-1, 10, 0.9));
        assertThrows(IllegalArgumentException.class, () -> StageLogic.gateOpen(11, 10, 0.9));
        assertThrows(IllegalArgumentException.class, () -> StageLogic.gateOpen(5, 10, 0.0));
        assertThrows(IllegalArgumentException.class, () -> StageLogic.gateOpen(5, 10, 1.0001));
        assertThrows(IllegalArgumentException.class, () -> StageLogic.gateOpen(5, 10, Double.NaN));
    }

    // ---- stage ----

    @Test
    void stageOfEmptyListIsZero() {
        assertEquals(0, StageLogic.stage(List.of()));
    }

    @Test
    void stageAllOpen() {
        assertEquals(3, StageLogic.stage(List.of(true, true, true)));
    }

    @Test
    void stageNoneOpen() {
        assertEquals(0, StageLogic.stage(List.of(false, false)));
    }

    @Test
    void stageStopsAtClosedGateEvenIfLaterGatesAreOpen() {
        assertEquals(1, StageLogic.stage(List.of(true, false, true, true)));
    }

    @Test
    void stageTreatsNullAsClosed() {
        assertEquals(1, StageLogic.stage(Arrays.asList(true, null, true)));
        assertEquals(0, StageLogic.stage(new ArrayList<>(Arrays.asList((Boolean) null))));
    }

    // ---- clamp ----

    @Test
    void clampBelowZero() {
        assertEquals(0, StageLogic.clamp(-5, 3));
    }

    @Test
    void clampWithinRange() {
        assertEquals(0, StageLogic.clamp(0, 3));
        assertEquals(2, StageLogic.clamp(2, 3));
        assertEquals(3, StageLogic.clamp(3, 3));
    }

    @Test
    void clampAboveGateCount() {
        assertEquals(3, StageLogic.clamp(4, 3));
        assertEquals(3, StageLogic.clamp(Integer.MAX_VALUE, 3));
    }

    @Test
    void clampWithZeroGates() {
        assertEquals(0, StageLogic.clamp(7, 0));
        assertEquals(0, StageLogic.clamp(-7, 0));
    }

    @Test
    void clampRejectsNegativeGateCount() {
        assertThrows(IllegalArgumentException.class, () -> StageLogic.clamp(0, -1));
    }
}
