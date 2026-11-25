package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link Delegations}（指揮権の譲渡の一覧）。Bukkit なしで確かめる。 */
class DelegationsTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    @Test
    void setRecordsTheEntryAndReturnsThePreviousOne() {
        Delegations d = new Delegations();
        assertNull(d.set(A, B, "Bob"));
        assertEquals(new Delegations.Entry(B, "Bob"), d.entryOf(A));
        assertTrue(d.isDelegated(A));
        assertEquals(new Delegations.Entry(B, "Bob"), d.set(A, C, "Carol")); // 付け替え
        assertEquals(C, d.entryOf(A).commander());
        assertEquals("Carol", d.entryOf(A).commanderName());
    }

    @Test
    void clearRemovesAndReturnsThePreviousEntry() {
        Delegations d = new Delegations();
        d.set(A, B, "Bob");
        assertEquals(new Delegations.Entry(B, "Bob"), d.clear(A));
        assertFalse(d.isDelegated(A));
        assertNull(d.entryOf(A));
        assertNull(d.clear(A));
        assertNull(d.clear(null));
    }

    @Test
    void delegatingToSelfOrNullClears() {
        Delegations d = new Delegations();
        d.set(A, B, "Bob");
        assertEquals(new Delegations.Entry(B, "Bob"), d.set(A, A, "Alice")); // 自分指定は解除
        assertFalse(d.isDelegated(A));

        d.set(A, B, "Bob");
        d.set(A, null, null);
        assertFalse(d.isDelegated(A));

        assertNull(d.set(null, B, "Bob"));
        assertTrue(d.snapshot().isEmpty());
    }

    @Test
    void clearByCommanderRemovesOnlyThoseEntriesAndKeepsRegistrationOrder() {
        Delegations d = new Delegations();
        d.set(A, B, "Bob");
        d.set(C, D, "Dave");
        d.set(D, B, "Bob");
        Map<UUID, Delegations.Entry> removed = d.clearByCommander(B);
        assertEquals(List.of(A, D), List.copyOf(removed.keySet()));
        assertEquals("Bob", removed.get(A).commanderName());
        assertFalse(d.isDelegated(A));
        assertFalse(d.isDelegated(D));
        assertTrue(d.isDelegated(C)); // 別の指揮者への譲渡は残る
        assertTrue(d.clearByCommander(B).isEmpty());
    }

    @Test
    void commanderOfFallsBackToTheOwnerWhenTheTargetIsInvalid() {
        Delegations d = new Delegations();
        assertEquals(A, d.commanderOf(A)); // 譲渡していない
        d.set(A, B, "Bob");
        assertEquals(B, d.commanderOf(A));
        assertEquals(B, d.commanderOf(A, id -> id.equals(B)));
        assertEquals(A, d.commanderOf(A, id -> false)); // 譲渡先が無効
        assertEquals(A, d.commanderOf(A, id -> id.equals(C)));
    }

    @Test
    void ownersOfIsDirectOnlyAndInRegistrationOrder() {
        Delegations d = new Delegations();
        d.set(A, C, "Carol");
        d.set(B, C, "Carol");
        d.set(C, D, "Dave"); // C は D に譲っている（A・B → C → D と連鎖しても A・B は D の持ち主ではない）
        assertEquals(List.of(A, B), d.ownersOf(C));
        assertEquals(List.of(C), d.ownersOf(D));
        assertTrue(d.ownersOf(A).isEmpty());
        // 兵の指揮者は 1 ホップだけ（A の兵は C が指揮し、D へは渡らない）
        assertEquals(C, d.commanderOf(A));
        assertEquals(D, d.commanderOf(C));
    }

    @Test
    void mutualDelegationDoesNotLoop() {
        Delegations d = new Delegations();
        d.set(A, B, "Bob");
        d.set(B, A, "Alice");
        assertEquals(B, d.commanderOf(A));
        assertEquals(A, d.commanderOf(B));
        assertEquals(List.of(B), d.ownersOf(A));
        assertEquals(List.of(A), d.ownersOf(B));
    }

    @Test
    void snapshotIsACopy() {
        Delegations d = new Delegations();
        d.set(A, B, "Bob");
        Map<UUID, Delegations.Entry> snap = d.snapshot();
        d.clear(A);
        assertEquals(1, snap.size()); // 回しながら変えても壊れない
        assertTrue(d.snapshot().isEmpty());
    }
}
