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
    /** 動けなくなったかの見張り: 最後に 1 ブロック以上動いたと確かめた位置と時刻。 */
    org.bukkit.Location stuckPos;
    long stuckTick;
    /** 最後にダメージを受けた時刻（サーバーの tick）。自動の回復はこの後 regen-delay-seconds 待つ。 */
    long lastDamagedTick = Long.MIN_VALUE / 2;
    /** 盾を構えている（遅い歩きと向きの指定を入れている）間は true。{@link Shields} が決める。 */
    boolean shieldUp;
    /** 構えている間に向く地点（脅威の位置）。構えていなければ null。 */
    org.bukkit.Location blockLook;
    /** 最後に脅威を見つけた時刻（サーバーの tick）。盾はこの後 lower-after-seconds 待って下ろす。 */
    long lastThreatTick = Long.MIN_VALUE / 2;

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
