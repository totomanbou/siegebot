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
    /** これ未満の残りのダメージは 0 とみなす（盾で防いだかの判定）。 */
    static final double BLOCKED_EPSILON = 0.01;

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
        SoldierBot bot = plugin.bots().byEntity(event.getEntity());
        if (bot == null) {
            return;
        }
        // 盾で防ぐと残りのダメージは 0 になるはずだが、浮動小数の端数（6.67 - 6.6666… など）が残ることがあるので、
        // 0 ちょうどではなく BLOCKED_EPSILON 未満で比べる（ちょうど 0 で比べると、防いだのにノックバックが消えなかった）
        if (event.getFinalDamage() >= BLOCKED_EPSILON) {
            bot.lastDamagedTick = org.bukkit.Bukkit.getCurrentTick();
        } else if (event.getEntity() instanceof org.bukkit.entity.HumanEntity h && h.isBlocking()) {
            bot.lastBlockedTick = org.bukkit.Bukkit.getCurrentTick(); // 盾で防いだ（同じ tick のノックバックを消す）
        }
    }

    /**
     * 盾で防いだときのノックバックを消す。防いだ攻撃のダメージの直後、同じ tick にノックバック
     * （原因 ENTITY_ATTACK）が来るので、防いだ tick と同じ tick のノックバックだけを取り消す。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockedKnockback(io.papermc.paper.event.entity.EntityKnockbackEvent event) {
        SoldierBot bot = plugin.bots().byEntity(event.getEntity());
        if (bot != null && bot.lastBlockedTick == org.bukkit.Bukkit.getCurrentTick()) {
            event.setCancelled(true);
        }
    }

    /** /sb debug kb で切り替える。true の間、ボットのダメージ・ノックバックをサーバーのログに出す（原因の調査用）。 */
    static volatile boolean logKnockback;

    @EventHandler(priority = EventPriority.MONITOR)
    public void logBotDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!logKnockback || plugin.bots().byEntity(event.getEntity()) == null) {
            return;
        }
        String by = event instanceof EntityDamageByEntityEvent e ? describe(e.getDamager()) : "-";
        plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                "[kb] t=%d DMG %s cause=%s by=%s %s raw=%.2f final=%.9f cancelled=%s",
                org.bukkit.Bukkit.getCurrentTick(), event.getEntity().getName(), event.getCause(), by,
                shieldState(event.getEntity()), event.getDamage(), event.getFinalDamage(), event.isCancelled()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void logBotKnockback(io.papermc.paper.event.entity.EntityKnockbackEvent event) {
        if (!logKnockback || plugin.bots().byEntity(event.getEntity()) == null) {
            return;
        }
        String by = event instanceof io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent e
                ? describe(e.getPushedBy()) : "-";
        plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                "[kb] t=%d KB  %s cause=%s by=%s %s vec=%s cancelled=%s",
                org.bukkit.Bukkit.getCurrentTick(), event.getEntity().getName(), event.getCause(), by,
                shieldState(event.getEntity()), event.getKnockback(), event.isCancelled()));
    }

    private static String describe(Entity e) {
        if (e == null) {
            return "null";
        }
        if (e instanceof Projectile pr) {
            return e.getType() + "(shooter=" + (pr.getShooter() instanceof Entity s ? s.getName() : "なし") + ")";
        }
        return e.getType() + ":" + e.getName();
    }

    private static String shieldState(Entity e) {
        if (!(e instanceof org.bukkit.entity.HumanEntity h)) {
            return "";
        }
        return "blocking=" + h.isBlocking() + " using=" + h.hasActiveItem() + "(" + h.getActiveItemUsedTime() + "t)"
                + " yaw=" + Math.round(e.getLocation().getYaw());
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
        // どちらも兵種ごと（units.<兵種>.damage-multiplier など。書いていなければ combat の値）
        Settings s = plugin.settings();
        double damage = event.getDamage();
        SoldierBot by = bots.byEntity(attacker);
        SoldierBot hit = bots.byEntity(victim);
        if (by != null) {
            damage *= s.unit(by.type).damageMultiplier;
        }
        if (hit != null) {
            damage /= s.unit(hit.type).defenseMultiplier;
        }
        if (damage != event.getDamage()) {
            event.setDamage(damage);
        }
    }
}
