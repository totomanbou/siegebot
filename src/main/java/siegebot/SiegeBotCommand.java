package siegebot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** 管理用の /siegebot（/sb）コマンド。ボットの出し入れ、リスポーン地点、方針の直接設定、試験用の道具。 */
public final class SiegeBotCommand implements TabExecutor {

    private static final String PREFIX = "[SiegeBot] ";
    private static final Pattern BOT_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final List<String> SUBS = List.of(
            "help", "revive", "spawn", "remove", "list", "spawnpoint", "set", "tp", "protect", "debug", "reload");
    private static final List<String> SIDES = List.of("attack", "defend");
    private static final List<String> STANCES = List.of("push", "guard");

    private final SiegeBotPlugin plugin;

    public SiegeBotCommand(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "spawn" -> spawn(sender, args);
            case "revive" -> revive(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            case "spawnpoint" -> spawnpoint(sender, args);
            case "set" -> set(sender, args);
            case "tp" -> tp(sender, args);
            case "protect" -> protect(sender, args);
            case "debug" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("kb")) {
                    GameListener.logKnockback = !GameListener.logKnockback;
                    msg(sender, "ボットのダメージ・ノックバックのログを" + (GameListener.logKnockback ? "出します" : "止めます")
                            + "（サーバーのログに出る）。");
                } else {
                    debug(sender);
                }
            }
            case "reload" -> {
                plugin.reloadAll();
                msg(sender, "config.yml と data.yml を読み直しました。");
            }
            default -> {
                msg(sender, "不明なサブコマンドです: " + args[0]);
                help(sender);
            }
        }
        return true;
    }

    private static void msg(CommandSender sender, String text) {
        sender.sendMessage(PREFIX + text);
    }

    private void help(CommandSender sender) {
        msg(sender, "コマンド一覧:");
        msg(sender, "/sb revive <プレイヤー|@p|@a|bots|all> [数] - ボットを参戦させる（倒れていれば戻し、足りなければ出す。コマンドブロック用。数は 1 人あたりの体数）");
        msg(sender, "/sb spawn <名前> <attack|defend> - 持ち主のいないボットを出す（出した地点を守る）");
        msg(sender, "/sb remove <名前> - ボットを消す");
        msg(sender, "/sb list - ボットの一覧");
        msg(sender, "/sb spawnpoint <attack|defend> [clear | x y z] - リスポーン地点を今いる場所（または座標）にする。clear で消す");
        msg(sender, "/sb set <ボット|*> follow <プレイヤー> | hold | stance <push|guard> - 方針を直接設定する");
        msg(sender, "/sb tp <ボット> [x y z] - ボットを瞬間移動させる（座標なしなら自分の所へ）");
        msg(sender, "/sb protect <ボット> <on|off> - ボットを無敵にする・戻す");
        msg(sender, "/sb debug - 各ボットの位置・体力・狙い・方針");
        msg(sender, "/sb reload - 設定の読み直し");
    }

    // ---- ボットの出し入れ ----

    private void spawn(CommandSender sender, String[] args) {
        Side side = args.length == 3 ? Side.parse(args[2]) : null;
        if (side == null) {
            msg(sender, "使い方: /sb spawn <名前> <attack|defend>");
            return;
        }
        String name = args[1];
        if (!BOT_NAME.matcher(name).matches()) {
            msg(sender, "名前は半角英数字と _ の 1〜16 文字にしてください。");
            return;
        }
        Location fallback = sender instanceof Player p ? p.getLocation() : null;
        SoldierBot bot = plugin.bots().spawn(name, side, fallback);
        if (bot == null) {
            msg(sender, "同名のボットがいるか、出す場所がありません（リスポーン地点を設定するか、ゲーム内で実行してください）。");
            return;
        }
        plugin.data().bots().keySet().removeIf(k -> k.equalsIgnoreCase(name));
        plugin.data().bots().put(name, side);
        plugin.data().save();
        msg(sender, "ボット " + name + "（" + side.label() + "）を出しました。" + bot.order().describe());
    }

    /**
     * ボットを出す・蘇生する。コマンドブロックのボタンから呼ぶ想定（ボットは自動では出ない・復活しない）。
     * <ul>
     *   <li>プレイヤー名・セレクタ（@p、@a、@a[team=atk] など）: その人の専用のボットをそろえる（{@link BotManager#revive}）</li>
     *   <li>bots: /sb spawn で出した持ち主のいないボットのうち、倒れているものを蘇生する</li>
     *   <li>all: オンラインの全員の専用のボットと、持ち主のいないボット</li>
     * </ul>
     * 最後の引数に数を書くと、1 人あたりその数までそろえる（足りなければ新しく出す）。省くと持っている全部を蘇生する。
     */
    private void revive(CommandSender sender, String[] args) {
        String usage = "使い方: /sb revive <プレイヤー|@p|@a|bots|all> [1人あたりの数]";
        if (args.length != 2 && args.length != 3) {
            msg(sender, usage);
            return;
        }
        Integer count = null;
        if (args.length == 3) {
            count = parseInt(args[2]);
            if (count == null || count < 1) {
                msg(sender, "数は 1 以上の整数で書いてください。" + usage);
                return;
            }
            int max = plugin.settings().maxBotsPerPlayer;
            if (count > max) {
                msg(sender, "1 人あたりの上限（personal-bots.max-per-player）は " + max + " 体なので、" + max + " 体にします。");
                count = max;
            }
        }
        String arg = args[1];
        boolean all = arg.equalsIgnoreCase("all");
        List<Player> players = new ArrayList<>();
        if (all) {
            players.addAll(Bukkit.getOnlinePlayers());
        } else if (arg.startsWith("@")) {
            try {
                for (org.bukkit.entity.Entity e : Bukkit.selectEntities(sender, arg)) {
                    if (e instanceof Player p && plugin.bots().byEntity(p) == null) {
                        players.add(p);
                    }
                }
            } catch (IllegalArgumentException e) {
                msg(sender, "セレクタが正しくありません: " + arg);
                return;
            }
        } else if (!arg.equalsIgnoreCase("bots")) {
            Player p = Bukkit.getPlayerExact(arg);
            if (p == null) {
                msg(sender, "プレイヤー " + arg + " はオンラインではありません。");
                return;
            }
            players.add(p);
        }

        List<String> parts = new ArrayList<>();
        int spawned = 0;
        int revived = 0;
        int alive = 0;
        Map<BotManager.ReviveReport.Status, List<String>> failed = new LinkedHashMap<>();
        for (Player p : players) {
            BotManager.ReviveReport r = plugin.bots().revive(p, count);
            if (r.status() == BotManager.ReviveReport.Status.OK) {
                spawned += r.spawned();
                revived += r.revived();
                alive += r.alive();
            } else if (!(all && r.status() == BotManager.ReviveReport.Status.NO_TEAM)) {
                // all のときチーム外の人は数えない
                failed.computeIfAbsent(r.status(), k -> new ArrayList<>()).add(p.getName());
            }
        }
        if (spawned + revived + alive > 0) {
            parts.add("参戦 " + (spawned + revived) + "体（新しく " + spawned + "・倒れていた " + revived + "）・もともと参戦中 " + alive + "体");
        }
        for (Map.Entry<BotManager.ReviveReport.Status, List<String>> e : failed.entrySet()) {
            String label = switch (e.getKey()) {
                case NO_TEAM -> "チームなし";
                case NO_PLACE -> "出す場所がない";
                case DISABLED -> "専用のボットが無効";
                case OK -> "";
            };
            parts.add(label + " " + e.getValue().size() + "人（" + String.join("、", e.getValue()) + "）");
        }
        int adminRevived = 0;
        if (all || arg.equalsIgnoreCase("bots")) {
            for (SoldierBot b : plugin.bots().all()) {
                if (b.owner() == null && plugin.bots().reviveBot(b)) {
                    adminRevived++;
                }
            }
        }

        if (all || arg.equalsIgnoreCase("bots")) {
            parts.add("持ち主のいないボットが参戦 " + adminRevived + "体");
        }
        msg(sender, parts.isEmpty() ? "対象のプレイヤーがいません。" : String.join(" / ", parts));
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length != 2) {
            msg(sender, "使い方: /sb remove <名前>");
            return;
        }
        String name = args[1];
        boolean removed = plugin.bots().remove(name);
        boolean saved = plugin.data().bots().keySet().removeIf(k -> k.equalsIgnoreCase(name));
        if (saved) {
            plugin.data().save();
        }
        msg(sender, removed || saved ? "ボット " + name + " を消しました。" : "ボット " + name + " は見つかりません。");
    }

    private void list(CommandSender sender) {
        if (plugin.bots().all().isEmpty()) {
            msg(sender, "ボットはいません。");
            return;
        }
        msg(sender, "ボット一覧:");
        for (SoldierBot b : plugin.bots().all()) {
            String owner = b.owner() == null ? "持ち主なし" : "持ち主 " + Bukkit.getOfflinePlayer(b.owner()).getName();
            msg(sender, b.name() + " [" + b.side().label() + "・" + owner + "] " + b.order().describe()
                    + " / " + (b.alive() ? "生存" : "倒れている"));
        }
    }

    // ---- リスポーン地点 ----

    private void spawnpoint(CommandSender sender, String[] args) {
        Side side = args.length >= 2 ? Side.parse(args[1]) : null;
        if (side != null && args.length == 5) {
            // 座標で指定（コンソールからも使える）。ワールドは実行者のいるワールド、コンソールなら最初のワールド
            Double x = parseDouble(args[2]);
            Double y = parseDouble(args[3]);
            Double z = parseDouble(args[4]);
            if (x == null || y == null || z == null) {
                msg(sender, "座標が数字ではありません。");
                return;
            }
            String world = sender instanceof Player p ? p.getWorld().getName() : Bukkit.getWorlds().get(0).getName();
            Point point = new Point(world, x, y, z);
            plugin.data().spawns().put(side, point);
            plugin.data().save();
            msg(sender, side.label() + "のリスポーン地点を " + point.coords() + " にしました。");
            return;
        }
        if (side == null || args.length > 3) {
            msg(sender, "使い方: /sb spawnpoint <attack|defend> [clear | x y z]");
            for (Side s : Side.values()) {
                Point p = plugin.data().spawns().get(s);
                msg(sender, s.label() + ": " + (p == null ? "未設定（持ち主の横に出る）" : p.world() + " " + p.coords()));
            }
            return;
        }
        if (args.length == 3 && args[2].equalsIgnoreCase("clear")) {
            plugin.data().spawns().remove(side);
            plugin.data().save();
            msg(sender, side.label() + "のリスポーン地点を消しました（ボットは持ち主の横に出ます）。");
            return;
        }
        if (!(sender instanceof Player p)) {
            msg(sender, "リスポーン地点の設定は、ゲーム内でその場所に立って実行してください。");
            return;
        }
        Point point = Point.of(p.getLocation());
        plugin.data().spawns().put(side, point);
        plugin.data().save();
        msg(sender, side.label() + "のリスポーン地点を " + point.coords() + " にしました。");
    }

    // ---- 方針の直接設定 ----

    private void set(CommandSender sender, String[] args) {
        String usage = "使い方: /sb set <ボット|*> follow <プレイヤー> [push|guard] | hold [push|guard] | stance <push|guard>"
                + " | retreat";
        if (args.length < 3) {
            msg(sender, usage);
            return;
        }
        List<SoldierBot> targets = new ArrayList<>();
        if (args[1].equals("*")) {
            targets.addAll(plugin.bots().all());
        } else if (plugin.bots().get(args[1]) != null) {
            targets.add(plugin.bots().get(args[1]));
        } else {
            msg(sender, "ボット " + args[1] + " は見つかりません。");
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        if (mode.equals("retreat")) {
            // 退却は方針ではない（着いたら元の方針に戻る）ので、方針の書き換えとは別に扱う
            plugin.bots().retreat(targets);
            msg(sender, targets.size() + " 体に退却を指示しました（倒れているボットは除く）。");
            return;
        }
        Map<SoldierBot, Order> orders = new LinkedHashMap<>();
        switch (mode) {
            case "follow" -> {
                Player who = args.length >= 4 ? Bukkit.getPlayerExact(args[3]) : null;
                if (who == null || args.length > 5) {
                    msg(sender, "追従の相手（オンラインのプレイヤー）を指定してください。" + usage);
                    return;
                }
                Order.Stance st = parseStance(args, 4);
                for (SoldierBot b : targets) {
                    orders.put(b, Order.follow(who.getName(), st != null ? st : b.order().stance()));
                }
            }
            case "hold" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "hold は、ゲーム内で守らせたい所を見て実行してください。");
                    return;
                }
                Point point = plugin.bots().lookPoint(p);
                if (point == null || args.length > 4) {
                    msg(sender, point == null ? "視点の先にブロックがありません。" : usage);
                    return;
                }
                Order.Stance st = parseStance(args, 3);
                for (SoldierBot b : targets) {
                    orders.put(b, Order.hold(point, st != null ? st : b.order().stance()));
                }
            }
            case "stance" -> {
                Order.Stance st = parseStance(args, 3);
                if (st == null) {
                    msg(sender, usage);
                    return;
                }
                for (SoldierBot b : targets) {
                    orders.put(b, b.order().withStance(st));
                }
            }
            default -> {
                msg(sender, usage);
                return;
            }
        }
        plugin.bots().applyOrders(orders);
        for (Map.Entry<SoldierBot, Order> e : orders.entrySet()) {
            msg(sender, e.getKey().name() + " の方針: " + e.getValue().describe());
        }
    }

    /** args[i] が push / guard ならその構え、なければ null。 */
    private static Order.Stance parseStance(String[] args, int i) {
        if (args.length <= i) {
            return null;
        }
        return switch (args[i].toLowerCase(Locale.ROOT)) {
            case "push" -> Order.Stance.PUSH;
            case "guard" -> Order.Stance.GUARD;
            default -> null;
        };
    }

    // ---- 試験・救出用 ----

    private void tp(CommandSender sender, String[] args) {
        SoldierBot b = args.length >= 2 ? plugin.bots().get(args[1]) : null;
        if (b == null || !b.alive() || (args.length != 2 && args.length != 5)) {
            msg(sender, "使い方: /sb tp <ボット> [x y z]（ボットが出ている必要があります）");
            return;
        }
        Location to;
        if (args.length == 5) {
            Double x = parseDouble(args[2]);
            Double y = parseDouble(args[3]);
            Double z = parseDouble(args[4]);
            if (x == null || y == null || z == null) {
                msg(sender, "座標が数字ではありません。");
                return;
            }
            to = new Location(b.npc.getEntity().getWorld(), x, y, z);
        } else if (sender instanceof Player p) {
            to = p.getLocation();
        } else {
            msg(sender, "コンソールからは座標を指定してください。");
            return;
        }
        b.npc.getNavigator().cancelNavigation(); // 移す前の経路を捨てる（残ると古い経路のまま止まる）
        // 名前の表示を乗せているので、乗客を残したまま移す
        b.npc.getEntity().teleport(to, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        msg(sender, b.name() + " を移しました。");
    }

    private void protect(CommandSender sender, String[] args) {
        SoldierBot b = args.length == 3 ? plugin.bots().get(args[1]) : null;
        if (b == null || !(args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) {
            msg(sender, "使い方: /sb protect <ボット> <on|off>");
            return;
        }
        boolean on = args[2].equalsIgnoreCase("on");
        b.npc.setProtected(on);
        msg(sender, b.name() + " を" + (on ? "無敵にしました。" : "無敵でなくしました。"));
    }

    private void debug(CommandSender sender) {
        if (plugin.bots().all().isEmpty()) {
            msg(sender, "ボットはいません。");
        }
        for (SoldierBot b : plugin.bots().all()) {
            StringBuilder sb = new StringBuilder(b.name()).append(" [").append(b.side().key()).append("] ");
            if (!b.alive()) {
                msg(sender, sb.append("倒れている").toString());
                continue;
            }
            Location l = b.npc.getEntity().getLocation();
            sb.append(String.format(Locale.ROOT, "pos=(%.1f, %.1f, %.1f)", l.getX(), l.getY(), l.getZ()));
            if (b.npc.getEntity() instanceof LivingEntity le) {
                sb.append(String.format(Locale.ROOT, " hp=%.1f", le.getHealth()));
            }
            Location anchor = plugin.bots().anchorFor(b);
            sb.append(anchor == null ? " anchor=なし" : String.format(Locale.ROOT, " anchor=(%.1f, %.1f, %.1f)",
                    anchor.getX(), anchor.getY(), anchor.getZ()));
            sb.append(" target=").append(b.target == null ? "なし" : b.target.getName());
            sb.append(" nav=").append(b.npc.getNavigator().isNavigating());
            sb.append(" order=").append(b.order().describe());
            long nowTick = org.bukkit.Bukkit.getCurrentTick();
            sb.append(" retreat=").append(b.retreating(nowTick) ? (b.retreatUntilTick - nowTick + 19) / 20 + "s" : "なし");
            if (b.npc.getEntity() instanceof Player bp) {
                int c = plugin.combat().secondsLeft(bp); // CombatLog で戦闘中なら残りの秒数
                sb.append(" combat=").append(c > 0 ? c + "s" : "なし");
                sb.append(" shield=").append(plugin.shields().describe(b));
            }
            msg(sender, sb.toString());
        }
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- タブ補完 ----

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        int n = args.length;
        if (n == 0) {
            return List.of();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> candidates = switch (n == 1 ? "" : sub) {
            case "" -> SUBS;
            case "spawn" -> n == 3 ? SIDES : List.of();
            case "remove", "tp" -> n == 2 ? botNames(false) : List.of();
            case "revive" -> n == 2 ? reviveTargets() : n == 3 ? List.of("1", "2", "3") : List.of();
            case "protect" -> n == 2 ? botNames(false) : n == 3 ? List.of("on", "off") : List.of();
            case "spawnpoint" -> n == 2 ? SIDES : n == 3 ? List.of("clear") : List.of();
            case "set" -> completeSet(args);
            default -> List.of();
        };
        String t = args[n - 1].toLowerCase(Locale.ROOT);
        List<String> r = new ArrayList<>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(t)) {
                r.add(c);
            }
        }
        return r;
    }

    private List<String> completeSet(String[] args) {
        int n = args.length;
        if (n == 2) {
            return botNames(true);
        }
        if (n == 3) {
            return List.of("follow", "hold", "stance", "retreat");
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        if (mode.equals("follow")) {
            if (n == 4) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                return names;
            }
            return n == 5 ? STANCES : List.of();
        }
        return n == 4 ? STANCES : List.of();
    }

    private List<String> reviveTargets() {
        List<String> r = new ArrayList<>(List.of("@p", "@a", "bots", "all"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            r.add(p.getName());
        }
        return r;
    }

    private List<String> botNames(boolean withAll) {
        List<String> r = new ArrayList<>();
        if (withAll) {
            r.add("*");
        }
        for (SoldierBot b : plugin.bots().all()) {
            r.add(b.name());
        }
        return r;
    }
}
