package siegebot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
 *   <li>/sbc follow               … 自分についてこさせる</li>
 *   <li>/sbc hold                 … 自分の視点の先（見ているブロックの所）を守らせる</li>
 *   <li>/sbc stance [push|guard]  … 構えを変える（引数なしなら切り替え）。動きはそのまま</li>
 *   <li>/sbc target               … 指示する相手を切り替える（専用のボットが無効のときだけ）</li>
 *   <li>/sbc status               … ボットの方針の一覧</li>
 * </ul>
 */
public final class PlayerCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("menu", "follow", "hold", "stance", "target", "status");

    private final SiegeBotPlugin plugin;
    private final CommandMenu menu;
    /** プレイヤー → 指示する相手のボット名（無ければ全員）。専用のボットが無効のときだけ使う。 */
    private final Map<UUID, String> selected = new HashMap<>();

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
        Side side = plugin.bots().sideOf(p);
        if (side == null) {
            tell(p, "攻め・守りのどちらのチームにも入っていません。", NamedTextColor.RED);
            return true;
        }
        if (targets(p, side).isEmpty()) {
            tell(p, personal() ? "あなたのボットはいません（コマンドブロックで呼び出してください）。"
                    : side.label() + "のボットがいません。", NamedTextColor.RED);
            return true;
        }
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu" -> menu.open(p, side);
            case "follow" -> follow(p, side);
            case "hold" -> holdLookPoint(p, side);
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

    /** 専用のボットが有効か（そのときは自分のボットにだけ指示できる）。 */
    boolean personal() {
        return plugin.settings().personalBots;
    }

    /** 自分の専用のボット。なければ null。 */
    SoldierBot ownBot(Player p, Side side) {
        SoldierBot b = plugin.bots().ownedBy(p.getUniqueId());
        return b != null && b.side() == side ? b : null;
    }

    /** 選んでいる相手のボット名。全員なら null（選んだボットが消えていたら全員に戻す）。 */
    String selectedName(Player p, Side side) {
        String name = selected.get(p.getUniqueId());
        SoldierBot b = name == null ? null : plugin.bots().get(name);
        if (b != null && b.side() == side) {
            return b.name();
        }
        selected.remove(p.getUniqueId());
        return null;
    }

    /** 指示する相手を選ぶ（null で全員）。 */
    void select(Player p, String botName) {
        if (botName == null) {
            selected.remove(p.getUniqueId());
        } else {
            selected.put(p.getUniqueId(), botName);
        }
    }

    /** 指示が効くボット。専用のボットが有効なら自分のボットだけ、そうでなければ選んだ相手（全員か 1 体）。 */
    List<SoldierBot> targets(Player p, Side side) {
        if (personal()) {
            SoldierBot b = ownBot(p, side);
            return b == null ? List.of() : List.of(b);
        }
        String name = selectedName(p, side);
        return name == null ? plugin.bots().of(side) : List.of(plugin.bots().get(name));
    }

    // ---- 指示 ----

    /** 自分についてこさせる。構えは今のまま。 */
    void follow(Player p, Side side) {
        apply(p, side, b -> Order.follow(p.getName(), b.order().stance()));
    }

    /** 視点の先（見ているブロックの、見ている面の手前）を守らせる。構えは今のまま。 */
    void holdLookPoint(Player p, Side side) {
        Point point = plugin.bots().lookPoint(p);
        if (point == null) {
            tell(p, "視点の先にブロックがありません（" + Math.round(plugin.settings().holdMaxDistance)
                    + " ブロック以内の地面や壁を見てください）。", NamedTextColor.RED);
            return;
        }
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
        if (personal()) {
            tell(p, "指示できるのは自分のボットだけです。", NamedTextColor.YELLOW);
            return;
        }
        List<SoldierBot> mine = plugin.bots().of(side);
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
                .append(Component.text(next == null ? "全員" : next, NamedTextColor.AQUA));
        p.sendActionBar(msg);
        p.sendMessage(Component.text("[SiegeBot] ", NamedTextColor.GOLD).append(msg));
    }

    private void status(Player p, Side side) {
        if (!personal()) {
            String cur = selectedName(p, side);
            tell(p, "指示する相手: " + (cur == null ? "全員" : cur), NamedTextColor.GRAY);
        }
        for (SoldierBot b : targets(p, side)) {
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
