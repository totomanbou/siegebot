package siegebot;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** 退出したプレイヤーのボットの片付けと、同じ陣営どうしの攻撃の無効化。 */
public final class GameListener implements Listener {

    private final SiegeBotPlugin plugin;
    /** これ未満の残りのダメージは 0 とみなす（盾で防いだかの判定）。 */
    static final double BLOCKED_EPSILON = 0.01;

    public GameListener(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }


    /**
     * 持ち主が倒れたら、その専用のボットも倒す（自動では復活しない）。指揮もリセットする: 方針は「ついてこい・持ち場優先」に、
     * 指示する相手は「全員」に戻す。ただし指揮権を同じチームの生きている人に譲っていれば、兵は倒さずその人が預かり続ける。
     * 譲渡（回数を譲ること）は必ず解除する（次の命の回数が前の譲渡先に渡らないように。{@link BotManager#onOwnerDeath}）。
     * 譲られていた兵は倒して、その分の回数を持ち主に戻す（持ち主の未使用の回数と指揮権も持ち主に戻る。{@link BotManager#onCommanderDeath}）。
     * 倒れた人の兵科選択の回数（自分の分。譲っていた分も含む）は 0 にする。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerDeath(PlayerDeathEvent event) {
        if (plugin.bots().byEntity(event.getEntity()) != null) {
            return; // ボット自身（プレイヤーの姿の NPC）の死では何もしない
        }
        Player dead = event.getEntity();
        UUID id = dead.getUniqueId();
        plugin.bots().recordPlayerDeath(id);
        plugin.bots().onCommanderDeath(id, dead.getName());
        int before = plugin.picks().ownPicks(id);
        plugin.bots().onOwnerDeath(dead); // 譲渡を解除する。譲渡中だった兵は譲渡先が預かり続け、それ以外は倒す
        plugin.picks().setPicks(id, 0); // 死んだら兵科選択の回数は 0（譲渡中の自分の分も）
        plugin.getLogger().info("死亡: " + dead.getName() + " の兵科選択の回数を " + before + " → 0 にしました");
        if (plugin.playerCommand() != null) {
            plugin.playerCommand().select(dead, null);
            plugin.playerCommand().menu().resetPage(id);
        }
    }

    /**
     * ボットの死亡メッセージを全員に流さず、持ち主にだけ送る（持ち主のいないボットは同じ陣営の全員）。
     * サーバーのログには残す。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBotDeath(PlayerDeathEvent event) {
        SoldierBot bot = plugin.bots().byEntity(event.getEntity());
        if (bot == null) {
            return;
        }
        Component msg = event.deathMessage();
        event.deathMessage(null);
        if (msg != null) {
            plugin.bots().sendToAudience(bot, msg);
            plugin.getLogger().info("死亡: " + PlainTextComponentSerializer.plainText().serialize(msg));
        }
    }

    /** ボットの盾が削れたら、Citizens の装備にも写す（写さないと元の盾に戻る）。1 tick 後にもう一度、実際の左手を写す。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotItemDamage(PlayerItemDamageEvent event) {
        SoldierBot bot = plugin.bots().byEntity(event.getPlayer());
        if (bot == null || event.getItem().getType() != Material.SHIELD) {
            return;
        }
        plugin.bots().syncOffHand(bot, event.getItem(), event.getDamage());
        Bukkit.getScheduler().runTask(plugin, () -> plugin.bots().syncOffHand(bot, null, null));
    }

    /** ボットの盾が壊れたら、Citizens の装備からも外す（補充しない）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBotItemBreak(PlayerItemBreakEvent event) {
        SoldierBot bot = plugin.bots().byEntity(event.getPlayer());
        if (bot != null && event.getBrokenItem().getType() == Material.SHIELD) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.bots().syncOffHand(bot, null, null));
        }
    }

    /** 倒れたボットはすぐ消す（蘇生しない。指揮メニューの一覧からも外れる）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBotDied(PlayerDeathEvent event) {
        SoldierBot bot = plugin.bots().byEntity(event.getEntity());
        if (bot != null) {
            plugin.bots().onBotDied(bot);
        }
    }

    /** ボットがダメージを受けた時刻を覚える（自動の回復を regen-delay-seconds 止めるため）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotDamaged(EntityDamageEvent event) {
        SoldierBot bot = plugin.bots().byEntity(event.getEntity());
        if (bot == null) {
            return;
        }
        // 盾で防ぐと残りのダメージは 0 になるはずだが、浮動小数の端数（6.67 - 6.6666… など）が残ることがあるので、
        // 0 ちょうどではなく BLOCKED_EPSILON 未満で比べる（ちょうど 0 で比べると、防いだのにノックバックが消えなかった）
        if (event.getFinalDamage() >= BLOCKED_EPSILON) {
            bot.lastDamagedTick = Bukkit.getCurrentTick();
        } else if (event.getEntity() instanceof HumanEntity h && h.isBlocking()) {
            bot.lastBlockedTick = Bukkit.getCurrentTick(); // 盾で防いだ（同じ tick のノックバックを消す）
        }
    }

    /**
     * 盾で防いだときのノックバックを消す。防いだ攻撃のダメージの直後、同じ tick にノックバック
     * （原因 ENTITY_ATTACK）が来るので、防いだ tick と同じ tick のノックバックだけを取り消す。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockedKnockback(EntityKnockbackEvent event) {
        SoldierBot bot = plugin.bots().byEntity(event.getEntity());
        if (bot != null && bot.lastBlockedTick == Bukkit.getCurrentTick()) {
            event.setCancelled(true);
        }
    }

    /** /sb debug kb の間、ボットが受けたダメージをサーバーのログに出す（原因の調査用。出し方は {@link CombatDebug}）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void logBotDamage(EntityDamageEvent event) {
        plugin.combatDebug().logDamage(event);
    }

    /** /sb debug kb の間、ボットが受けたノックバックをサーバーのログに出す（原因の調査用）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void logBotKnockback(EntityKnockbackEvent event) {
        plugin.combatDebug().logKnockback(event);
    }

    /** 抜けたプレイヤーの専用のボットを消す。譲られていた指揮権は持ち主に戻し、譲っていた指揮権は解除する。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player quit = event.getPlayer();
        UUID id = quit.getUniqueId();
        plugin.bots().returnDelegatedFromCommander(id, quit.getName(), "退出した");
        plugin.bots().clearDelegationOfOwner(id, quit.getName(), "退出した");
        if (plugin.playerCommand() != null) {
            plugin.playerCommand().select(quit, null);
            plugin.playerCommand().menu().resetPage(id);
        }
        plugin.bots().removeOwned(id);
        plugin.bots().forgetPlayerDeath(id);
    }

    /** combat.fall-damage が false なら、ボットの落下のダメージを無効にする。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        SoldierBot bot = plugin.bots().byEntity(event.getEntity());
        if (bot != null && !plugin.settings(bot.side).fallDamage) {
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
        Side a = plugin.teams().sideOf(attacker);
        if (a != null && a == plugin.teams().sideOf(victim)) {
            event.setCancelled(true);
            return;
        }
        // 火力: ボットが与えるダメージに damage-multiplier を掛ける
        // 防御力: ボットが受けるダメージを defense-multiplier で割る（ボットどうしなら両方が効く）
        // どちらも兵種ごと（units.<兵種>.damage-multiplier など。書いていなければ combat の値）
        double damage = event.getDamage();
        SoldierBot by = bots.byEntity(attacker);
        SoldierBot hit = bots.byEntity(victim);
        if (by != null) {
            damage *= plugin.settings(by.side).unit(by.type).damageMultiplier;
        }
        if (hit != null) {
            damage /= plugin.settings(hit.side).unit(hit.type).defenseMultiplier;
        }
        if (damage != event.getDamage()) {
            event.setDamage(damage);
        }
    }
}
