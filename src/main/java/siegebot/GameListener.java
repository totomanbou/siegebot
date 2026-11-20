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


    /**
     * 持ち主が倒れたら、その専用のボットも倒す（自動では復活しない）。
     * 指揮もリセットする: 方針は「ついてこい・持ち場優先」に、指示する相手は「全員」に戻す。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        if (plugin.bots().byEntity(event.getEntity()) != null) {
            return; // ボット自身（プレイヤーの姿の NPC）の死では何もしない
        }
        plugin.bots().resetOrders(event.getEntity());
        if (plugin.playerCommand() != null) {
            plugin.playerCommand().select(event.getEntity(), null);
        }
        plugin.bots().killOwnedBots(event.getEntity().getUniqueId());
    }

    /**
     * ボットの死亡メッセージを全員に流さず、持ち主にだけ送る（持ち主のいないボットは同じ陣営の全員）。
     * サーバーのログには残す。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBotDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        SoldierBot bot = plugin.bots().byEntity(event.getEntity());
        if (bot == null) {
            return;
        }
        net.kyori.adventure.text.Component msg = event.deathMessage();
        event.deathMessage(null);
        if (msg != null) {
            plugin.bots().sendToAudience(bot, msg);
            plugin.getLogger().info("死亡: " + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(msg));
        }
    }

    /** ボットがダメージを受けた時刻を覚える（自動の回復を regen-delay-seconds 止めるため）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotDamaged(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getFinalDamage() <= 0) {
            return;
        }
        SoldierBot bot = plugin.bots().byEntity(event.getEntity());
        if (bot != null) {
            bot.lastDamagedTick = org.bukkit.Bukkit.getCurrentTick();
        }
    }

    /** 抜けたプレイヤーの専用のボットを消す。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        plugin.bots().removeOwned(event.getPlayer().getUniqueId());
    }

    /** combat.fall-damage が false なら、ボットの落下のダメージを無効にする。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFall(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL
                && !plugin.settings().fallDamage && plugin.bots().byEntity(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    /** ボットが関わる攻撃で、攻撃した側と受けた側が同じ陣営なら無効にする。そうでなければ火力・防御力の倍率を掛ける。 */
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
