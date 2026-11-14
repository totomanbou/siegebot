package siegebot;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/** チャットの受け取りと、同じ陣営どうしの攻撃の無効化。 */
public final class GameListener implements Listener {

    private final SiegeBotPlugin plugin;

    public GameListener(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    /** 通常のチャット。非同期で届くので、メインスレッドに移してから処理する。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.settings().listenPublicChat) {
            return;
        }
        Player player = event.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> deliver(player, text));
    }

    /** /teammsg と /tm（チームチャット）はコマンドなので、ここで拾う。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.settings().listenTeamMsg) {
            return;
        }
        String msg = event.getMessage();
        String lower = msg.toLowerCase(Locale.ROOT);
        for (String cmd : new String[] {"/teammsg ", "/tm ", "/minecraft:teammsg ", "/minecraft:tm "}) {
            if (lower.startsWith(cmd)) {
                deliver(event.getPlayer(), msg.substring(cmd.length()).trim());
                return;
            }
        }
    }

    private void deliver(Player player, String text) {
        if (!player.isOnline()) {
            return;
        }
        Side side = plugin.bots().sideOf(player);
        if (side != null) {
            plugin.orders().onMessage(side, player.getName(), text);
        }
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
