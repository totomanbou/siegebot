package siegebot;

import net.citizensnpcs.api.ai.Navigator;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * 槍兵が届くぎりぎりの所から突く。毎 tick、狙い（{@link SoldierBot#target}）について:
 * 届けば（目から相手の箱まで units.spearman.attack-range 以内）相手を向いて attack-delay-ticks ごとに突き、
 * 近づいている途中なら、届く距離より stop-inside だけ内側に入った所で止める（行動の判断の 10 tick を待つと寄りすぎる）。
 * 下がっている途中・退却中は突かない。
 *
 * <p>近づく・下がるの判断は {@link BotManager} が行う（10 tick ごと）。メインスレッドからだけ呼ぶ。
 */
final class Spearmen {
    /** 毎 tick（2 tick ごとだと、歩く速さで 0.5 ブロックほど寄りすぎてから止まった）。 */
    static final int TICK_PERIOD = 1;

    private final SiegeBotPlugin plugin;

    Spearmen(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    void tick() {
        Settings s = plugin.settings();
        long now = Bukkit.getCurrentTick();
        for (SoldierBot bot : plugin.bots().all()) {
            if (bot.type != UnitType.SPEARMAN || !bot.npc.isSpawned() || !(bot.npc.getEntity() instanceof Player p)) {
                continue;
            }
            try {
                tickBot(bot, p, s, now);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("槍兵 " + bot.name + " の処理で例外: " + e);
            }
        }
    }

    private void tickBot(SoldierBot bot, Player p, Settings s, long now) {
        LivingEntity t = bot.target;
        if (t == null || !t.isValid() || t.isDead() || t.getWorld() != p.getWorld() || bot.retreating(now)) {
            return;
        }
        Navigator nav = bot.npc.getNavigator();
        if (bot.backingOff && nav.isNavigating()) {
            return; // 下がっている途中は向かない（プレイヤーの姿の NPC は向いている方へ歩く）
        }
        Settings.Unit unit = s.unit(UnitType.SPEARMAN);
        double d = BotManager.reachDistance(p, t);
        if (d > unit.attackRange || !p.hasLineOfSight(t)) {
            return;
        }
        if (nav.isNavigating() && nav.getEntityTarget() != null && d <= unit.attackRange - s.spearStopInside) {
            nav.cancelNavigation(); // 届くぎりぎりの所で止まる
        }
        bot.npc.faceLocation(t.getEyeLocation());
        if (now - bot.lastSpearAttackTick >= unit.attackDelayTicks) {
            bot.lastSpearAttackTick = now;
            p.swingMainHand();
            net.citizensnpcs.util.NMS.attack(p, t);
            if (GameListener.logKnockback) { // /sb debug kb の間は、突いたこともログに出す
                plugin.getLogger().info(String.format(java.util.Locale.ROOT, "[kb] t=%d THRUST %s -> %s reach=%.2f",
                        now, bot.name, t.getName(), d));
            }
        }
    }
}
