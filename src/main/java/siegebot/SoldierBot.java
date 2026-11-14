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

    /** 今の方針。出すときに {@link BotManager} が決める（持ち主への追従か、出した地点を守る）。 */
    Order order;
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
