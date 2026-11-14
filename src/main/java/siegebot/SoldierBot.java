package siegebot;

import java.util.UUID;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.LivingEntity;

/** 兵士ボット 1 体の状態。動きの計算は {@link BotManager} が行う。 */
public final class SoldierBot {

    final String name;
    final Side side;
    final NPC npc;
    /** 持ち主のプレイヤー（専用のボット）。管理者が出したボットは null。 */
    final UUID owner;

    Order order = Order.front();
    /** true の間は「初期指示」で動いている（味方の指示を受けると false）。 */
    boolean usingDefault = true;
    /** 次にリスポーンする時刻（サーバーの tick）。0 は未予約。 */
    long respawnAtTick;
    LivingEntity target;
    /** 最後に狙いを選び直した時刻（サーバーの tick）。 */
    long lastRetargetTick;

    SoldierBot(String name, Side side, NPC npc, UUID owner) {
        this.name = name;
        this.side = side;
        this.npc = npc;
        this.owner = owner;
    }

    public UUID owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public Side side() {
        return side;
    }

    public Order order() {
        return order;
    }

    public boolean alive() {
        return npc.isSpawned();
    }
}
