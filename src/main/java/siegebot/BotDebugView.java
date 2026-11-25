package siegebot;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * /sb debug・/sb debug units の表示。各ボットの状態と、兵種ごとの設定値を 1 行ずつ送り手に見せる（調査・設定の確かめ用）。
 * 表示だけを行い、ボットの状態は変えない。
 */
final class BotDebugView {

    private final SiegeBotPlugin plugin;

    BotDebugView(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    /** /sb debug units: 兵種ごとに、今使っている値（combat の値に落ちたものも含む）と装備。設定の確かめ用。 */
    void debugUnits(CommandSender sender, Side filter) {
        MapProfiles profiles = plugin.profiles();
        StringBuilder mapLine = new StringBuilder("map=").append(profiles.activeMap() == null ? "default" : profiles.activeMap());
        if (!profiles.extendsChain().isEmpty()) {
            mapLine.append(" (extends: ").append(String.join(" → ", profiles.extendsChain())).append(')');
        }
        String saved = plugin.data().mapName();
        if (profiles.isFallback(saved)) {
            mapLine.append(" (data.yml の ").append(saved).append(" が読めないため default)");
        }
        SiegeBotCommand.msg(sender, mapLine.toString());
        if (filter != null) {
            debugUnitsOf(sender, filter.key(), plugin.settings(filter));
        } else if (!profiles.hasSideDifferences()) {
            debugUnitsOf(sender, null, plugin.settings());
        } else {
            debugUnitsOf(sender, Side.ATTACK.key(), plugin.settings(Side.ATTACK));
            debugUnitsOf(sender, Side.DEFEND.key(), plugin.settings(Side.DEFEND));
        }
    }

    /** 1 つの設定（header があれば [header] の行を先に出す）の兵種ごとの値と、共通の値。 */
    private void debugUnitsOf(CommandSender sender, String header, Settings st) {
        if (header != null) {
            SiegeBotCommand.msg(sender, "[" + header + "]");
        }
        SiegeBotCommand.msg(sender, "units.default=" + st.defaultUnit.key());
        for (UnitType t : UnitType.values()) {
            Settings.Unit u = st.unit(t);
            List<String> eq = new ArrayList<>();
            for (Map.Entry<String, ItemStack> e : u.equipment.entrySet()) {
                eq.add(e.getKey() + "=" + e.getValue().getType().getKey().getKey() + modelSuffix(e.getValue()));
            }
            SiegeBotCommand.msg(sender, String.format(Locale.ROOT,
                    "%s: suffix=%s speed=%.2f damage=%.2f defense=%.2f max-health=%.1f attack-range=%.2f attack-delay=%d "
                            + "aim.turn-speed=%.1f aim.sway=%.2f aim.turn-sway=%.2f aim.reaction=%dt shield-durability=%s equipment=[%s]",
                    t.key(), u.nameSuffix, u.speed, u.damageMultiplier, u.defenseMultiplier, u.maxHealth, u.attackRange,
                    u.attackDelayTicks, u.aimTurnSpeed, u.aimSway, u.aimTurnSway,
                    u.aimReactionTicks, u.shieldDurability > 0 ? String.valueOf(u.shieldDurability) : "盾のまま", String.join(" ", eq)));
        }
        SiegeBotCommand.msg(sender, String.format(Locale.ROOT,
                "spearman: stop-inside=%.2f back-off-inside=%.2f shield-lower=%dt / archer: range=%.1f min=%.1f keep=%.1f "
                        + "draw=%dt interval=%dt speed=%.2f damage=%.2f inaccuracy=%.2f lead=%.2f",
                st.spearStopInside, st.spearBackOffInside, st.spearShieldLowerAfterTicks, st.archerRange,
                st.archerMinDistance, st.archerKeepDistance, st.archerDrawTicks, st.archerShotIntervalTicks,
                st.archerArrowSpeed, st.archerArrowDamage, st.archerInaccuracy, st.archerLeadFactor));
        SiegeBotCommand.msg(sender, String.format(Locale.ROOT,
                "combat: engage=%.1f push-leash=%.1f guard-leash=%.1f follow=%.1f arrive=%.1f path=%.0f fall-damage=%b "
                        + "regen=%.1f/%dt delay=%dt / shield: enabled=%b lower=%dt / test-bots=%b max=%d / teams=%s,%s",
                st.engageRadius, st.pushLeash, st.guardLeash, st.followDistance, st.arriveRadius, st.pathRange, st.fallDamage,
                st.regenAmount, st.regenIntervalTicks, st.regenDelayTicks, st.shieldEnabled, st.shieldLowerAfterTicks,
                st.testBots, st.maxBotsPerPlayer, st.attackTeam, st.defendTeam)
                + String.format(Locale.ROOT, " / separation: enabled=%b min=%.2f", st.separationEnabled,
                        st.separationMinDistance));
    }

    /** /sb debug: 各ボットの位置・体力・持ち場・狙い・方針など。 */
    void debug(CommandSender sender) {
        if (plugin.bots().all().isEmpty()) {
            SiegeBotCommand.msg(sender, "ボットはいません。");
        }
        for (SoldierBot b : plugin.bots().all()) {
            StringBuilder sb = new StringBuilder(b.name()).append(" [").append(b.side().key()).append("・")
                    .append(b.type().key()).append("] ");
            if (!b.alive()) {
                SiegeBotCommand.msg(sender, sb.append("倒れている").toString());
                continue;
            }
            Location l = b.npc.getEntity().getLocation();
            sb.append(String.format(Locale.ROOT, "pos=(%.1f, %.1f, %.1f)", l.getX(), l.getY(), l.getZ()));
            if (b.npc.getEntity() instanceof LivingEntity le) {
                sb.append(String.format(Locale.ROOT, " hp=%.1f", le.getHealth()));
            }
            sb.append(anchorText(plugin.formation().anchorFor(b)));
            sb.append(" target=").append(b.target == null ? "なし" : b.target.getName());
            long webNow = Bukkit.getCurrentTick();
            long blocked = b.webBlocked.values().stream().filter(u -> u > webNow).count();
            if (blocked > 0) {
                sb.append(" 巣で追わない=").append(blocked);
            }
            boolean navOn = b.npc.getNavigator().isNavigating();
            sb.append(" nav=").append(navOn);
            if (navOn) {
                net.citizensnpcs.api.ai.PathStrategy ps = b.npc.getNavigator().getPathStrategy();
                Iterable<org.bukkit.util.Vector> path = ps == null ? null : ps.getPath();
                int nodes = NavigationWatch.countNodes(path);
                if (nodes == 0) {
                    sb.append(" path=空");
                } else if (nodes < 0) {
                    sb.append(" path=直線");
                }
            }
            if (b.backingOff) {
                sb.append(" backoff");
            }
            if (b.shieldStopping) {
                sb.append(" shieldStop");
            }
            long watchNow = Bukkit.getCurrentTick();
            if (b.stuckPos != null && navOn && watchNow - b.stuckTick >= 10) {
                sb.append(" stuck=").append(watchNow - b.stuckTick).append('t');
            }
            long unreach = b.unreachableTargets.values().stream().filter(u -> u > watchNow).count();
            if (unreach > 0) {
                sb.append(" unreach=").append(unreach);
            }
            long lineBlk = b.lineBlockedTargets.values().stream().filter(u -> u > watchNow).count();
            if (lineBlk > 0) {
                sb.append(" lineBlk=").append(lineBlk);
            }
            if (b.npc.getNavigator().isNavigating() && b.npc.getEntity() instanceof LivingEntity dirEntity) {
                sb.append(" sepDir=").append(switch (Separation.resolveDirection(b, dirEntity).source()) {
                    case PATH -> "path";
                    case VELOCITY -> "vel";
                    case NONE -> "none";
                });
            }
            sb.append(" order=").append(b.order().describe());
            java.util.UUID cmd = plugin.bots().commanderOf(b);
            if (cmd != null && !cmd.equals(b.owner())) {
                sb.append(" cmd=").append(Bukkit.getOfflinePlayer(cmd).getName());
            }
            long nowTick = Bukkit.getCurrentTick();
            sb.append(" retreat=").append(b.retreating(nowTick) ? (b.retreatUntilTick - nowTick + 19) / 20 + "s" : "なし");
            if (b.npc.getEntity() instanceof Player bp) {
                int c = plugin.combat().secondsLeft(bp); // CombatLog で戦闘中なら残りの秒数
                sb.append(" combat=").append(c > 0 ? c + "s" : "なし");
                sb.append(" shield=").append(plugin.shields().describe(b));
                sb.append(" aim=").append(Aim.describe(b, bp));
                ItemStack hand = bp.getInventory().getItemInMainHand();
                sb.append(" hand=").append(hand.getType().getKey().getKey()).append(modelSuffix(hand));
                String bow = plugin.archers().describe(b);
                if (bow != null) {
                    sb.append(" bow=").append(bow);
                }
            }
            SiegeBotCommand.msg(sender, sb.toString());
        }
        Separation.SpacingStats st = plugin.separation().measureAll();
        if (st.pairs() == 0) {
            SiegeBotCommand.msg(sender, "間隔: 対象なし");
        } else {
            SiegeBotCommand.msg(sender, String.format(Locale.ROOT, "間隔: 最小 %.2f / 重なり(<0.6) %d組",
                    st.minDistance(), st.overlappingCount()));
        }
        SiegeBotCommand.msg(sender, plugin.bots().navigationWatch().describeStats());
    }

    /** 持ち場の表示（" anchor=なし" か " anchor=(x, y, z)"）。 */
    private static String anchorText(Formation.Anchor anchor) {
        return anchor == null ? " anchor=なし" : String.format(Locale.ROOT, " anchor=(%.1f, %.1f, %.1f)",
                anchor.location().getX(), anchor.location().getY(), anchor.location().getZ());
    }

    /** アイテムの CustomModelData の文字列（なければ空。あれば "(model=a,b)"）。 */
    private static String modelSuffix(ItemStack item) {
        CustomModelData cmd = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        return cmd == null || cmd.strings().isEmpty() ? "" : "(model=" + String.join(",", cmd.strings()) + ")";
    }
}
