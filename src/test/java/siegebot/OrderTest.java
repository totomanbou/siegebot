package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OrderTest {

    private static final Point P = new Point("world", 12.5, 64.0, -30.5);

    @Test
    void requiredFieldsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new Order(null, "Steve", null, Order.Stance.GUARD));
        assertThrows(IllegalArgumentException.class, () -> new Order(Order.Mode.FOLLOW, "Steve", null, null));
        assertThrows(IllegalArgumentException.class, () -> Order.follow(null, Order.Stance.GUARD));
        assertThrows(IllegalArgumentException.class, () -> Order.follow(" ", Order.Stance.GUARD));
        assertThrows(IllegalArgumentException.class, () -> Order.hold(null, Order.Stance.GUARD));
    }

    @Test
    void followDropsPointAndHoldDropsPlayer() {
        Order f = new Order(Order.Mode.FOLLOW, "Steve", P, Order.Stance.PUSH);
        assertNull(f.point());
        assertEquals("Steve", f.player());
        Order h = new Order(Order.Mode.HOLD, "Steve", P, Order.Stance.GUARD);
        assertNull(h.player());
        assertEquals(P, h.point());
    }

    @Test
    void withStanceKeepsTheMove() {
        Order f = Order.follow("Steve", Order.Stance.GUARD).withStance(Order.Stance.PUSH);
        assertEquals(Order.Mode.FOLLOW, f.mode());
        assertEquals("Steve", f.player());
        assertEquals(Order.Stance.PUSH, f.stance());
        Order h = Order.hold(P, Order.Stance.PUSH).withStance(Order.Stance.GUARD);
        assertEquals(Order.Mode.HOLD, h.mode());
        assertEquals(P, h.point());
        assertEquals(Order.Stance.GUARD, h.stance());
    }

    @Test
    void describe() {
        assertEquals("Steve に追従・持ち場優先", Order.follow("Steve", Order.Stance.GUARD).describe());
        assertEquals("地点 (12, 64, -31) を守る・積極的", Order.hold(P, Order.Stance.PUSH).describe());
    }

    @Test
    void pointCoordsFloorNegativeValues() {
        assertEquals("(0, -60, -1)", new Point("w", 0.5, -60.0, -0.5).coords());
        assertThrows(IllegalArgumentException.class, () -> new Point("", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Point(null, 0, 0, 0));
    }
}
