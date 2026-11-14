package siegebot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OrderTest {

    @Test
    void rejectsNullMode() {
        assertThrows(IllegalArgumentException.class, () -> new Order(null, null, null, Order.Stance.PUSH));
    }

    @Test
    void rejectsNullStance() {
        assertThrows(IllegalArgumentException.class, () -> new Order(Order.Mode.FRONT, null, null, null));
    }

    @Test
    void holdRequiresPlace() {
        assertThrows(IllegalArgumentException.class, () -> new Order(Order.Mode.HOLD, null, null, Order.Stance.GUARD));
        assertThrows(IllegalArgumentException.class, () -> new Order(Order.Mode.HOLD, "  ", null, Order.Stance.GUARD));
    }

    @Test
    void followRequiresPlayer() {
        assertThrows(IllegalArgumentException.class, () -> new Order(Order.Mode.FOLLOW, null, null, Order.Stance.GUARD));
        assertThrows(IllegalArgumentException.class, () -> new Order(Order.Mode.FOLLOW, null, "", Order.Stance.GUARD));
    }

    @Test
    void validOrdersAreAccepted() {
        assertDoesNotThrow(() -> new Order(Order.Mode.FRONT, null, null, Order.Stance.PUSH));
        assertDoesNotThrow(() -> new Order(Order.Mode.HOLD, "gate", null, Order.Stance.GUARD));
        assertDoesNotThrow(() -> new Order(Order.Mode.FOLLOW, null, "Steve", Order.Stance.GUARD));
    }

    @Test
    void frontFactory() {
        Order o = Order.front();
        assertEquals(Order.Mode.FRONT, o.mode());
        assertEquals(Order.Stance.PUSH, o.stance());
        assertNull(o.place());
        assertNull(o.player());
    }
}
