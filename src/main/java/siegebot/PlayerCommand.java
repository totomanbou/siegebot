package siegebot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * 一般のプレイヤー向けの指示コマンド /sbc。自分のチームのボットにだけ効く。
 * ItemJoin の指揮棒の右クリックで /sbc menu を呼び、{@link CommandMenu} の画面から指示する想定。
 *
 * <ul>
 *   <li>/sbc menu                 … 指揮メニューを開く</li>
 *   <li>/sbc units                … 兵科選択の画面を開く（呼べる回数を使って、兵種を選んで兵を呼ぶ）</li>
 *   <li>/sbc follow               … 自分についてこさせる</li>
 *   <li>/sbc hold                 … 自分の視点の先（見ているブロックの所）を守らせる</li>
 *   <li>/sbc call                 … 自分の所へ瞬間移動させる（方針はそのまま）</li>
 *   <li>/sbc retreat              … 戦闘をやめさせ、持ち場（ついてこいなら自分）へ戻らせる</li>
 *   <li>/sbc stance [push|guard]  … 構えを変える（引数なしなら切り替え）。動きはそのまま</li>
 *   <li>/sbc target               … 指示する相手を切り替える（全員 → 1 体ずつ → 全員）</li>
 *   <li>/sbc status               … ボットの方針の一覧</li>
 * </ul>
 */
public final class PlayerCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("menu", "units", "follow", "hold", "call", "retreat", "stance",
            "target", "status");

    private final SiegeBotPlugin plugin;
    private final CommandMenu menu;
    /** プレイヤー → 指示する相手のボット名（選んだ順。無ければ全員）。メニューでシフト+左クリックすると複数選べる。 */
    private final Map<UUID, LinkedHashSet<String>> selected = new HashMap<>();
    /** プレイヤー → 最後に呼び寄せた時刻（サーバーの tick）。 */
    private final Map<UUID, Long> lastCall = new HashMap<>();

    public PlayerCommand(SiegeBotPlugin plugin) {
        this.plugin = plugin;
        this.menu = new CommandMenu(plugin, this);
    }

    /** 画面のクリックを受けるリスナー（プラグインの起動時に登録する）。 */
    public CommandMenu menu() {
        return menu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("[SiegeBot] このコマンドはゲーム内のプレイヤーだけが使えます。");
            return true;
        }
        Side side = plugin.teams().sideOf(p);
        if (side == null) {
            tell(p, "攻め・守りのどちらのチームにも入っていません。", NamedTextColor.RED);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("units")) {
            menu.openUnits(p, side); // ボットがまだ 1 体もいなくても開ける
            return true;
        }
        if (targets(p, side).isEmpty()) {
            Delegations.Entry del = plugin.delegations().entryOf(p.getUniqueId());
            if (del != null) {
                tell(p, "あなたのボットの指揮権は " + del.commanderName() + " に譲渡中です（戻すには /sbc units）。",
                        NamedTextColor.RED);
            } else {
                String keeper = null;
                for (SoldierBot b : plugin.bots().ownedBy(p.getUniqueId())) {
                    if (b.custodian != null) {
                        keeper = b.custodianName;
                        break;
                    }
                }
                tell(p, keeper != null
                        ? "あなたのボットは " + keeper + " が預かっています（戻すには /sbc units の本から「自分に戻す」）。"
                        : "あなたのボットはいません（兵科選択か、コマンドブロックで呼んでください）。", NamedTextColor.RED);
            }
            return true;
        }
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu" -> menu.open(p, side);
            case "follow" -> follow(p, side);
            case "hold" -> holdLookPoint(p, side);
            case "call" -> callToMe(p, side);
            case "retreat" -> retreat(p, side);
            case "stance" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("push")) {
                    setStance(p, side, Order.Stance.PUSH);
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("guard")) {
                    setStance(p, side, Order.Stance.GUARD);
                } else {
                    toggleStance(p, side);
                }
            }
            case "target" -> cycleTarget(p, side);
            case "status" -> status(p, side);
            default -> tell(p, "使い方: /sbc <" + String.join("|", SUBS) + ">", NamedTextColor.YELLOW);
        }
        return true;
    }

    // ---- 指示する相手 ----

    /** 自分の専用のボット（このチームのもの、出した順）。なければ空。 */
    List<SoldierBot> ownBots(Player p, Side side) {
        List<SoldierBot> r = new ArrayList<>();
        for (SoldierBot b : plugin.bots().ownedBy(p.getUniqueId())) {
            if (b.side() == side) {
                r.add(b);
            }
        }
        return r;
    }

    /** 指示する相手に選べるボット: 今自分が指揮しているボット（自分のボットと、譲られて預かっているボット）。 */
    List<SoldierBot> candidates(Player p, Side side) {
        List<SoldierBot> r = new ArrayList<>();
        for (SoldierBot b : plugin.bots().commandedBy(p.getUniqueId())) {
            if (b.side() == side) {
                r.add(b);
            }
        }
        return r;
    }

    /** 消えたボットを、誰の「指示する相手」の選択からも外す。 */
    void forgetBot(String botName) {
        if (botName == null) {
            return;
        }
        selected.values().removeIf(names -> {
            names.removeIf(n -> n.equalsIgnoreCase(botName));
            return names.isEmpty();
        });
    }

    /**
     * 選んでいる相手のボット名（選んだ順）。空なら全員。消えた・選べなくなったボットは外す
     * （倒れて消えたボットも外れる。全部外れたら全員に戻る）。
     */
    Set<String> selectedNames(Player p, Side side) {
        LinkedHashSet<String> names = selected.get(p.getUniqueId());
        if (names == null) {
            return Set.of();
        }
        List<SoldierBot> can = candidates(p, side);
        names.removeIf(n -> {
            SoldierBot b = plugin.bots().get(n);
            return b == null || !can.contains(b);
        });
        if (names.isEmpty()) {
            selected.remove(p.getUniqueId());
            return Set.of();
        }
        return Collections.unmodifiableSet(names);
    }

    /** 1 体だけ選んでいればその名前。全員・複数なら null。 */
    String selectedName(Player p, Side side) {
        Set<String> names = selectedNames(p, side);
        return names.size() == 1 ? names.iterator().next() : null;
    }

    /** 指示する相手を、そのボット 1 体だけにする（null で全員）。 */
    void select(Player p, String botName) {
        if (botName == null) {
            selected.remove(p.getUniqueId());
        } else {
            selected.put(p.getUniqueId(), new LinkedHashSet<>(List.of(botName)));
        }
    }

    /** 指示する相手にそのボットを足す（選んでいれば外す）。メニューのシフト+左クリック。 */
    void toggle(Player p, String botName) {
        LinkedHashSet<String> names = selected.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashSet<>());
        if (!names.remove(botName)) {
            names.add(botName);
        }
        if (names.isEmpty()) {
            selected.remove(p.getUniqueId());
        }
    }

    /** 指示する相手の書き方（全員・名前・N 体）。 */
    String describeSelection(Player p, Side side) {
        Set<String> names = selectedNames(p, side);
        return names.isEmpty() ? "全員" : names.size() == 1 ? names.iterator().next()
                : names.size() + " 体（" + String.join("・", names) + "）";
    }

    /** 指示が効くボット: 選んだ相手（全員か、選んだ 1 体・複数体）。自分のボットの中から選ぶ。 */
    List<SoldierBot> targets(Player p, Side side) {
        Set<String> names = selectedNames(p, side);
        if (names.isEmpty()) {
            return candidates(p, side);
        }
        List<SoldierBot> r = new ArrayList<>();
        for (SoldierBot b : candidates(p, side)) {
            if (names.contains(b.name())) {
                r.add(b);
            }
        }
        return r;
    }

    // ---- 指示 ----

    /** 自分についてこさせる。構えは今のまま。 */
    void follow(Player p, Side side) {
        apply(p, side, b -> Order.follow(p.getName(), b.order().stance()));
    }

    /**
     * 自分の所（足元の地面）へ瞬間移動させる。方針はそのまま（「守れ」のままなら、守る地点へ歩いて戻る）。
     * 倒れているボットは呼べない。orders.call-cooldown-seconds が 0 より大きければ、その間は続けて呼べない。
     * orders.call-blocked-in-combat が true なら、CombatLog で戦闘中のあいだは呼べない。
     */
    void callToMe(Player p, Side side) {
        Settings st = plugin.settings(side);
        int cooldown = st.callCooldownTicks;
        long now = Bukkit.getCurrentTick();
        Long last = lastCall.get(p.getUniqueId());
        if (cooldown > 0 && last != null && now - last < cooldown) {
            long left = (cooldown - (now - last) + 19) / 20;
            tell(p, "呼び寄せはあと " + left + " 秒使えません。", NamedTextColor.RED);
            return;
        }
        if (st.callBlockedInCombat) {
            int inCombat = plugin.combat().secondsLeft(p);
            if (inCombat > 0) {
                tell(p, "戦闘中は呼び寄せられません（あと " + inCombat + " 秒）。", NamedTextColor.RED);
                return;
            }
        }
        Location to = Formation.groundBelow(p.getLocation());
        List<SoldierBot> moved = new ArrayList<>();
        List<String> down = new ArrayList<>();
        List<SoldierBot> toMove = new ArrayList<>();
        for (SoldierBot b : targets(p, side)) {
            if (!b.alive()) {
                down.add(b.name());
            } else {
                toMove.add(b);
            }
        }
        // 呼ぶ人の足元と、移さないボットを避けて並べる（足元が空いていなければ、プレイヤーは避けずにボットどうしだけ避ける）
        List<Location> occupied = new ArrayList<>(plugin.bots().aliveLocations(toMove));
        for (SoldierBot b : toMove) {
            Location spot = to;
            if (st.separationEnabled) {
                double minDist = st.separationMinDistance;
                List<Location> withPlayer = new ArrayList<>(occupied);
                withPlayer.add(to);
                spot = plugin.separation().findOpenSpot(to, withPlayer, minDist);
                if (overlapsAny(spot, withPlayer, minDist)) {
                    spot = plugin.separation().findOpenSpot(to, occupied, minDist);
                }
            }
            occupied.add(spot);
            plugin.bots().teleport(b, spot); // 名前の表示を乗せているので、乗客を残したまま移す
            moved.add(b);
        }
        // 1 体でも呼べたなら、倒れているボットのことは言わない（全員倒れているときだけ知らせる）
        if (moved.isEmpty() && !down.isEmpty()) {
            tell(p, String.join("・", down) + " は倒れているので呼べません（コマンドブロックで参戦させてください）。",
                    NamedTextColor.RED);
        }
        if (!moved.isEmpty()) {
            plugin.bots().say(moved.get(0), moved.size() == 1 ? "到着した" : moved.size() + " 体到着した");
            lastCall.put(p.getUniqueId(), now);
        }
    }

    /** at が、others のどれかと（縦に重なる範囲で）水平に minDist 未満か。 */
    private static boolean overlapsAny(Location at, List<Location> others, double minDist) {
        for (Location o : others) {
            if (o.getWorld() == at.getWorld() && Math.abs(o.getY() - at.getY()) < Separation.VERTICAL_OVERLAP
                    && Math.hypot(o.getX() - at.getX(), o.getZ() - at.getZ()) < minDist) {
                return true;
            }
        }
        return false;
    }

    /** 戦闘をやめさせ、持ち場（ついてこいなら自分）へ戻らせる。着いたら元の動きに戻る。方針・構えは変えない。 */
    void retreat(Player p, Side side) {
        plugin.bots().retreat(targets(p, side));
    }

    /** 視点の先（見ているブロックの、見ている面の手前）を守らせる。構えは今のまま。 */
    void holdLookPoint(Player p, Side side) {
        Point point = plugin.formation().lookPoint(p);
        if (point == null) {
            tell(p, "視点の先にブロックがありません（" + Math.round(plugin.settings(side).holdMaxDistance)
                    + " ブロック以内の地面や壁を見てください）。", NamedTextColor.RED);
            return;
        }
        plugin.bots().setHoldFacing(targets(p, side), p.getLocation().getYaw()); // 横隊は指示した人が見ていた向きを向く
        apply(p, side, b -> Order.hold(point, b.order().stance()));
    }

    /** 構えだけを変える。動き（追従・地点を守る）はそのまま。 */
    void setStance(Player p, Side side, Order.Stance stance) {
        apply(p, side, b -> b.order().withStance(stance));
    }

    private void toggleStance(Player p, Side side) {
        boolean allPush = true;
        for (SoldierBot b : targets(p, side)) {
            allPush &= b.order().stance() == Order.Stance.PUSH;
        }
        setStance(p, side, allPush ? Order.Stance.GUARD : Order.Stance.PUSH);
    }

    private void apply(Player p, Side side, Function<SoldierBot, Order> make) {
        Map<SoldierBot, Order> orders = new LinkedHashMap<>();
        for (SoldierBot b : targets(p, side)) {
            orders.put(b, make.apply(b));
        }
        plugin.bots().applyOrders(orders);
    }

    private void cycleTarget(Player p, Side side) {
        List<SoldierBot> mine = candidates(p, side);
        String cur = selectedName(p, side);
        int idx = -1;
        for (int i = 0; i < mine.size(); i++) {
            if (mine.get(i).name().equals(cur)) {
                idx = i;
            }
        }
        String next = idx + 1 < mine.size() ? mine.get(idx + 1).name() : null;
        select(p, next);
        Component msg = Component.text("指示する相手: ", NamedTextColor.GRAY)
                .append(Component.text(describeSelection(p, side), NamedTextColor.AQUA));
        p.sendActionBar(msg);
        p.sendMessage(Component.text("[SiegeBot] ", NamedTextColor.GOLD).append(msg));
    }

    private void status(Player p, Side side) {
        List<SoldierBot> all = candidates(p, side);
        if (all.size() > 1) {
            tell(p, "指示する相手: " + describeSelection(p, side), NamedTextColor.GRAY);
        }
        for (SoldierBot b : all) {
            tell(p, b.name() + "：" + b.order().describe() + (b.alive() ? "" : "（倒れている）"), NamedTextColor.WHITE);
        }
    }

    private static void tell(Player p, String text, NamedTextColor color) {
        p.sendMessage(Component.text("[SiegeBot] ", NamedTextColor.GOLD).append(Component.text(text, color)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> candidates = args.length == 1 ? SUBS
                : args.length == 2 && args[0].equalsIgnoreCase("stance") ? List.of("push", "guard") : List.of();
        List<String> r = new ArrayList<>();
        String t = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        for (String s : candidates) {
            if (s.startsWith(t)) {
                r.add(s);
            }
        }
        return r;
    }
}
