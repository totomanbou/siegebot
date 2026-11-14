package siegebot;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/** 退出したプレイヤーのボットの片付けと、同じ陣営どうしの攻撃の無効化。 */
public final class GameListener implements Listener {

    private final SiegeBotPlugin plugin;

    public GameListener(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }


    /** 抜けたプレイヤーの専用のボットを消す。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        plugin.bots().removeOwned(event.getPlayer().getUniqueId());
    }

    /** ボットが関わる攻撃で、攻撃した側と受けた側が同じ陣営なら無効にする。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) {
            attacker = shooter;
        }
        Entity victim = event.getEntity();
        BotManager bots = plugin.bots();
        if (bots.byEntity(attacker) == null && bots.byEntity(victim) == null) {
            return;
        }
        Side a = bots.sideOf(attacker);
        if (a != null && a == bots.sideOf(victim)) {
            event.setCancelled(true);
        }
    }
}
