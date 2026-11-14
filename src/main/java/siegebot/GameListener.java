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


    /** 持ち主が倒れたら、その専用のボットも倒す（自動では復活しない）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        if (plugin.bots().byEntity(event.getEntity()) != null) {
            return; // ボット自身（プレイヤーの姿の NPC）の死では何もしない
        }
        plugin.bots().killOwnedBot(event.getEntity().getUniqueId());
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
            return;
        }
        // 火力: ボットが与えるダメージに damage-multiplier を掛ける
        // 防御力: ボットが受けるダメージを defense-multiplier で割る（ボットどうしなら両方が効く）
        Settings s = plugin.settings();
        double damage = event.getDamage();
        if (bots.byEntity(attacker) != null) {
            damage *= s.damageMultiplier;
        }
        if (bots.byEntity(victim) != null) {
            damage /= s.defenseMultiplier;
        }
        if (damage != event.getDamage()) {
            event.setDamage(damage);
        }
    }
}
