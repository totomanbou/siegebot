package siegebot;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 戦闘の調査用ログ（/sb debug kb で切り替える）。有効な間、ボットの攻撃・ダメージ・ノックバックを
 * サーバーのログに「[kb] …」の形式で出す。
 */
public final class CombatDebug {

    private final SiegeBotPlugin plugin;
    /** 有効の間は true。ログを出す側（メインスレッド）と切り替える側が違うことがあるので volatile。 */
    private volatile boolean enabled;

    public CombatDebug(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    /** 有効・無効を切り替える。切り替えた後の状態を返す。 */
    public boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 剣兵が殴った（または空振りした）ことを出す。 */
    void logMelee(SoldierBot bot, LivingEntity target, boolean hit, Player attacker) {
        if (!enabled) {
            return;
        }
        plugin.getLogger().info(String.format(Locale.ROOT, "[kb] t=%d %s %s -> %s %s",
                Bukkit.getCurrentTick(), hit ? "HIT" : "MISS", bot.name, target.getName(), Aim.describe(bot, attacker)));
    }

    /** 槍兵が突いた（または空振りした）ことを出す。reach は目から相手の箱までの距離。 */
    void logThrust(SoldierBot bot, LivingEntity target, boolean hit, double reach, Player attacker, long now) {
        if (!enabled) {
            return;
        }
        plugin.getLogger().info(String.format(Locale.ROOT, "[kb] t=%d THRUST %s %s -> %s reach=%.2f %s",
                now, hit ? "HIT" : "MISS", bot.name, target.getName(), reach, Aim.describe(bot, attacker)));
    }

    /** 弓兵が矢を撃ったことを出す。 */
    void logShoot(SoldierBot bot, LivingEntity target, double dist, long now) {
        if (!enabled) {
            return;
        }
        plugin.getLogger().info(String.format(Locale.ROOT, "[kb] t=%d SHOOT %s -> %s dist=%.1f",
                now, bot.name, target.getName(), dist));
    }

    /** ボットが受けたダメージを出す（ボット以外のダメージは出さない）。 */
    void logDamage(EntityDamageEvent event) {
        if (!enabled || plugin.bots().byEntity(event.getEntity()) == null) {
            return;
        }
        String by = event instanceof EntityDamageByEntityEvent e ? describe(e.getDamager()) : "-";
        plugin.getLogger().info(String.format(Locale.ROOT,
                "[kb] t=%d DMG %s cause=%s by=%s %s raw=%.2f final=%.9f cancelled=%s",
                Bukkit.getCurrentTick(), event.getEntity().getName(), event.getCause(), by,
                shieldState(event.getEntity()), event.getDamage(), event.getFinalDamage(), event.isCancelled()));
    }

    /** ボットが受けたノックバックを出す（ボット以外のノックバックは出さない）。 */
    void logKnockback(EntityKnockbackEvent event) {
        if (!enabled || plugin.bots().byEntity(event.getEntity()) == null) {
            return;
        }
        String by = event instanceof EntityPushedByEntityAttackEvent e ? describe(e.getPushedBy()) : "-";
        plugin.getLogger().info(String.format(Locale.ROOT,
                "[kb] t=%d KB  %s cause=%s by=%s %s vec=%s cancelled=%s",
                Bukkit.getCurrentTick(), event.getEntity().getName(), event.getCause(), by,
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
        if (!(e instanceof HumanEntity h)) {
            return "";
        }
        return "blocking=" + h.isBlocking() + " using=" + h.hasActiveItem() + "(" + h.getActiveItemUsedTime() + "t)"
                + " yaw=" + Math.round(e.getLocation().getYaw());
    }
}
