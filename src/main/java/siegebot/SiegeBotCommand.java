package siegebot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** 管理用の /siegebot（/sb）コマンド。ボットの出し入れ、リスポーン地点、方針の直接設定、試験用の道具。 */
public final class SiegeBotCommand implements TabExecutor {

    private static final String PREFIX = "[SiegeBot] ";
    private static final Pattern BOT_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final List<String> SUBS = List.of(
            "help", "revive", "picks", "spawn", "remove", "list", "spawnpoint", "set", "tp", "protect", "map", "debug", "reload");
    private static final List<String> SIDES = List.of("attack", "defend");
    private static final List<String> STANCES = List.of("push", "guard");

    private final SiegeBotPlugin plugin;
    private final BotDebugView debugView;

    public SiegeBotCommand(SiegeBotPlugin plugin) {
        this.plugin = plugin;
        this.debugView = new BotDebugView(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "spawn" -> spawn(sender, args);
            case "revive" -> revive(sender, args);
            case "picks" -> picks(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            case "spawnpoint" -> spawnpoint(sender, args);
            case "set" -> set(sender, args);
            case "tp" -> tp(sender, args);
            case "protect" -> protect(sender, args);
            case "map" -> map(sender, args);
            case "debug" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("units")) {
                    Side filter = args.length >= 3 ? Side.parse(args[2]) : null;
                    if (args.length > 3 || (args.length == 3 && filter == null)) {
                        msg(sender, "使い方: /sb debug units [attack|defend]");
                    } else {
                        debugView.debugUnits(sender, filter);
                    }
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("kb")) {
                    boolean on = plugin.combatDebug().toggle();
                    msg(sender, "ボットのダメージ・ノックバックのログを" + (on ? "出します" : "止めます")
                            + "（サーバーのログに出る）。");
                } else {
                    debugView.debug(sender);
                }
            }
            case "reload" -> {
                List<String> warnings = plugin.reloadAll();
                msg(sender, "config.yml・maps/・data.yml を読み直しました（適用中のマップ: " + plugin.profiles().describeActive() + "）。");
                warnings.forEach(w -> msg(sender, "警告: " + w));
            }
            default -> {
                msg(sender, "不明なサブコマンドです: " + args[0]);
                help(sender);
            }
        }
        return true;
    }

    /** 「[SiegeBot] 」を付けて送り手に 1 行送る。 */
    static void msg(CommandSender sender, String text) {
        sender.sendMessage(PREFIX + text);
    }

    private void help(CommandSender sender) {
        msg(sender, "コマンド一覧:");
        msg(sender, "/sb revive <プレイヤー|@p|@a|bots|all> [数] [soldier|spearman|archer] - ボットを参戦させる（倒れていれば戻し、足りなければ出す。"
                + "コマンドブロック用。数は 1 人あたりの体数。兵種を書くとその兵種だけを数えて出す）");
        msg(sender, "/sb picks <プレイヤー|@p|@a|all> [数|add 数] - 兵科選択で兵を呼べる回数を決める・足す・見る（コマンドブロック用）");
        if (anySideTestBots()) {
            msg(sender, "/sb spawn <名前> <attack|defend> [soldier|spearman|archer] - 試験用: 持ち主のいないボットを出す（出した地点を守る）");
        }
        msg(sender, "/sb remove <名前> - ボットを消す");
        msg(sender, "/sb list - ボットの一覧");
        msg(sender, "/sb spawnpoint <attack|defend> [clear | x y z] - リスポーン地点を今いる場所（または座標）にする。clear で消す");
        msg(sender, "/sb set <ボット|*> follow <プレイヤー> | hold | stance <push|guard> - 方針を直接設定する");
        msg(sender, "/sb tp <ボット> [x y z] - ボットを瞬間移動させる（座標なしなら自分の所へ）");
        msg(sender, "/sb protect <ボット> <on|off> - ボットを無敵にする・戻す");
        msg(sender, "/sb map [list|default|reset|<マップ名>] - マップ別の設定を切り替える・一覧を見る");
        msg(sender, "/sb debug - 各ボットの位置・体力・狙い・方針");
        msg(sender, "/sb reload - 設定の読み直し");
    }

    // ---- ボットの出し入れ ----

    private void spawn(CommandSender sender, String[] args) {
        Side side = args.length == 3 || args.length == 4 ? Side.parse(args[2]) : null;
        UnitType type = args.length == 4 ? UnitType.parse(args[3]) : null;
        if (side == null || (args.length == 4 && type == null)) {
            if (!anySideTestBots()) {
                msg(sender, "持ち主のいないボット（/sb spawn）は試験用です。使うには config.yml に test-bots.enabled: true を書いてください。");
            } else {
                msg(sender, "使い方: /sb spawn <名前> <attack|defend> [soldier|spearman|archer]");
            }
            return;
        }
        if (!plugin.settings(side).testBots) {
            msg(sender, "持ち主のいないボット（/sb spawn）は試験用です。使うには config.yml に test-bots.enabled: true を書いてください"
                    + "（" + side.label() + "の設定で false になっています）。");
            return;
        }
        String name = args[1];
        if (!BOT_NAME.matcher(name).matches()) {
            msg(sender, "名前は半角英数字と _ の 1〜16 文字にしてください。");
            return;
        }
        Location fallback = sender instanceof Player p ? p.getLocation() : null;
        SoldierBot bot = plugin.bots().spawn(name, side, fallback, type);
        if (bot == null) {
            msg(sender, "同名のボットがいるか、出す場所がありません（リスポーン地点を設定するか、ゲーム内で実行してください）。");
            return;
        }
        plugin.data().bots().keySet().removeIf(k -> k.equalsIgnoreCase(name));
        plugin.data().botTypes().keySet().removeIf(k -> k.equalsIgnoreCase(name));
        plugin.data().bots().put(name, side);
        plugin.data().botTypes().put(name, bot.type());
        plugin.data().save();
        msg(sender, "ボット " + name + "（" + side.label() + "・" + bot.type().label() + "）を出しました。" + bot.order().describe());
    }

    /**
     * ボットを出す・蘇生する。コマンドブロックのボタンから呼ぶ想定（ボットは自動では出ない・復活しない）。
     * <ul>
     *   <li>プレイヤー名・セレクタ（@p、@a、@a[team=atk] など）: その人の専用のボットをそろえる（{@link BotManager#revive}）</li>
     *   <li>bots: /sb spawn で出した持ち主のいないボットのうち、倒れて消えたものを出し直す（data.yml の一覧から）</li>
     *   <li>all: オンラインの全員の専用のボットと、持ち主のいないボット</li>
     * </ul>
     * 数を書くと、1 人あたりその数までそろえる（足りなければ新しく出す）。省くと持っている全部を蘇生する。
     * 兵種（soldier / archer）を書くと、その兵種だけを数えてそろえる（{@link BotManager#revive}）。数と兵種はどちらが先でもよい。
     */
    private void revive(CommandSender sender, String[] args) {
        String usage = "使い方: /sb revive <プレイヤー|@p|@a|bots|all> [1人あたりの数] [soldier|spearman|archer]";
        if (args.length < 2 || args.length > 4) {
            msg(sender, usage);
            return;
        }
        Integer count = null;
        UnitType type = null;
        for (int i = 2; i < args.length; i++) {
            UnitType t = UnitType.parse(args[i]);
            Integer c = parseInt(args[i]);
            if (t != null && type == null) {
                type = t;
            } else if (c != null && count == null) {
                count = c;
            } else {
                msg(sender, "「" + args[i] + "」が分かりません。数は 1 以上の整数、兵種は soldier（剣兵）・spearman（槍兵）・archer（弓兵）です。" + usage);
                return;
            }
        }
        if (count != null) {
            if (count < 1) {
                msg(sender, "数は 1 以上の整数で書いてください。" + usage);
                return;
            }
            // 上限（personal-bots.max-per-player）は陣営ごとに違いうるので、BotManager#revive が陣営ごとに数える
        }
        String arg = args[1];
        boolean all = arg.equalsIgnoreCase("all");
        List<Player> players = new ArrayList<>(); // bots は持ち主のいないボットだけを戻すので、プレイヤーは選ばない
        if (!arg.equalsIgnoreCase("bots")) {
            players = selectPlayers(sender, arg);
            if (players == null) {
                return; // 理由は selectPlayers が伝えた
            }
        }

        List<String> parts = new ArrayList<>();
        if ((count != null || type != null) && arg.equalsIgnoreCase("bots")) {
            // bots は /sb spawn で出した持ち主のいないボットだけ。数・兵種は専用のボットのためのものなので使わない
            String extra = (count != null ? " " + count : "") + (type != null ? " " + type.key() : "");
            parts.add("bots は持ち主のいないボット（/sb spawn）を戻すだけで、数と兵種は使いません。"
                    + "自分のボットをそろえるなら /sb revive @s" + extra);
        }
        int capped = 0;
        int spawned = 0;
        int alive = 0;
        Map<BotManager.ReviveReport.Status, List<String>> failed = new LinkedHashMap<>();
        for (Player p : players) {
            BotManager.ReviveReport r = plugin.bots().revive(p, count, type);
            if (r.status() == BotManager.ReviveReport.Status.OK) {
                spawned += r.spawned();
                alive += r.alive();
                capped += r.capped();
            } else if (!(all && r.status() == BotManager.ReviveReport.Status.NO_TEAM)) {
                // all のときチーム外の人は数えない
                failed.computeIfAbsent(r.status(), k -> new ArrayList<>()).add(p.getName());
            }
        }
        if (spawned + alive > 0) {
            parts.add((type == null ? "" : type.label() + "が") + "参戦 " + spawned + "体・もともと参戦中 " + alive + "体");
        }
        if (capped > 0) {
            parts.add("1 人あたりの上限（personal-bots.max-per-player: " + maxPerPlayerLabel()
                    + "）に届いたので " + capped + "体は出せませんでした（ほかの兵種のボットも数に入ります）");
        }
        for (Map.Entry<BotManager.ReviveReport.Status, List<String>> e : failed.entrySet()) {
            String label = switch (e.getKey()) {
                case NO_TEAM -> "チームなし";
                case NO_PLACE -> "出す場所がない";
                case OK -> "";
            };
            parts.add(label + " " + e.getValue().size() + "人（" + String.join("、", e.getValue()) + "）");
        }
        // 持ち主のいないボット（/sb spawn）は試験用（test-bots.enabled のときだけ）
        if (anySideTestBots() && (all || arg.equalsIgnoreCase("bots"))) {
            int adminRevived = plugin.spawnSavedBots(); // 倒れて消えた /sb spawn のボットを、data.yml から出し直す
            parts.add("持ち主のいないボットが参戦 " + adminRevived + "体");
        } else if (arg.equalsIgnoreCase("bots")) {
            parts.add("持ち主のいないボット（bots）は試験用です。使うには config.yml に test-bots.enabled: true を書いてください");
        }
        msg(sender, parts.isEmpty() ? "対象のプレイヤーがいません。" : String.join(" / ", parts));
    }

    /**
     * 兵科選択（/sbc units の画面）で兵を呼べる回数を決める。コマンドブロックから呼ぶ想定。
     * <ul>
     *   <li>/sb picks &lt;対象&gt; &lt;数&gt;     … 残りの回数をその数にする（0 で呼べなくする）</li>
     *   <li>/sb picks &lt;対象&gt; add &lt;数&gt; … 残りの回数に足す（負の数で減らす）</li>
     *   <li>/sb picks &lt;対象&gt;          … 残りの回数を見る</li>
     * </ul>
     * 数は持ち主本人の分（譲渡中でも本人の分として残る）。表示には、譲渡先と、預かっている分も添える。
     * 対象はプレイヤー名・セレクタ（@p、@a[team=Attack] など）・all（オンラインの全員）。
     */
    private void picks(CommandSender sender, String[] args) {
        String usage = "使い方: /sb picks <プレイヤー|@p|@a|all> [数|add 数]";
        if (args.length < 2 || args.length > 4) {
            msg(sender, usage);
            return;
        }
        Integer set = null;
        Integer add = null;
        if (args.length == 3) {
            set = parseInt(args[2]);
            if (set == null || set < 0) {
                msg(sender, "数は 0 以上の整数で書いてください。" + usage);
                return;
            }
        } else if (args.length == 4) {
            add = args[2].equalsIgnoreCase("add") ? parseInt(args[3]) : null;
            if (add == null) {
                msg(sender, usage);
                return;
            }
        }
        List<Player> players = selectPlayers(sender, args[1]);
        if (players == null) {
            return; // 理由は selectPlayers が伝えた
        }
        if (players.isEmpty()) {
            msg(sender, "対象のプレイヤーがいません。");
            return;
        }
        List<String> parts = new ArrayList<>();
        for (Player p : players) {
            java.util.UUID id = p.getUniqueId();
            int now = plugin.picks().ownPicks(id);
            int next = set != null ? set : add != null ? Math.max(0, now + add) : now;
            if (set != null) {
                plugin.picks().setPicks(id, next);
            } else if (add != null) {
                plugin.picks().addPicks(id, add);
            }
            parts.add(p.getName() + " " + next + "回" + picksNote(p));
            if (next != now && plugin.teams().sideOf(p) != null) {
                p.sendMessage(net.kyori.adventure.text.Component.text("[SiegeBot] ", net.kyori.adventure.text.format.NamedTextColor.GOLD)
                        .append(net.kyori.adventure.text.Component.text("兵科選択で兵を呼べる回数: " + next + " 回",
                                net.kyori.adventure.text.format.NamedTextColor.WHITE)));
                // 譲渡中なら、代わりに使える指揮者（オンラインで同じチーム）にも知らせる
                Delegations.Entry del = plugin.delegations().entryOf(id);
                Player cmd = del == null ? null : Bukkit.getPlayer(del.commander());
                if (cmd != null && cmd.isOnline() && plugin.teams().sideOf(cmd) == plugin.teams().sideOf(p)) {
                    cmd.sendMessage(net.kyori.adventure.text.Component.text("[SiegeBot] ", net.kyori.adventure.text.format.NamedTextColor.GOLD)
                            .append(net.kyori.adventure.text.Component.text(p.getName() + " の兵科選択の回数が " + next
                                    + " 回になりました（あなたが代わりに使えます）。", net.kyori.adventure.text.format.NamedTextColor.WHITE)));
                }
            }
        }
        msg(sender, "兵科選択で呼べる回数: " + String.join("、", parts));
    }

    /** /sb picks の表示の添え書き: 譲渡中なら「（Bobに譲渡中）」、預かりがあれば「（預かり: Alice 3回）」。なければ空。 */
    private String picksNote(Player p) {
        List<String> notes = new ArrayList<>();
        Delegations.Entry del = plugin.delegations().entryOf(p.getUniqueId());
        if (del != null) {
            notes.add(del.commanderName() + "に譲渡中");
        }
        List<String> held = new ArrayList<>();
        for (java.util.Map.Entry<java.util.UUID, Integer> e : plugin.picks().delegatedBreakdown(p, plugin.teams().sideOf(p)).entrySet()) {
            held.add(Bukkit.getOfflinePlayer(e.getKey()).getName() + " " + e.getValue() + "回");
        }
        if (!held.isEmpty()) {
            notes.add("預かり: " + String.join("、", held));
        }
        return notes.isEmpty() ? "" : "（" + String.join("、", notes) + "）";
    }

    /** 対象（プレイヤー名・セレクタ・all）からオンラインのプレイヤー（ボットを除く）。書き方が誤りなら伝えて null。 */
    private List<Player> selectPlayers(CommandSender sender, String arg) {
        List<Player> players = new ArrayList<>();
        if (arg.equalsIgnoreCase("all")) {
            players.addAll(Bukkit.getOnlinePlayers());
        } else if (arg.startsWith("@")) {
            try {
                for (org.bukkit.entity.Entity e : Bukkit.selectEntities(sender, arg)) {
                    if (e instanceof Player p) {
                        players.add(p);
                    }
                }
            } catch (IllegalArgumentException e) {
                msg(sender, "セレクタが正しくありません: " + arg);
                return null;
            }
        } else {
            Player p = Bukkit.getPlayerExact(arg);
            if (p == null) {
                msg(sender, "プレイヤー " + arg + " はオンラインではありません。");
                return null;
            }
            players.add(p);
        }
        players.removeIf(p -> plugin.bots().byEntity(p) != null);
        return players;
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length != 2) {
            msg(sender, "使い方: /sb remove <名前>");
            return;
        }
        String name = args[1];
        boolean removed = plugin.bots().remove(name);
        boolean saved = plugin.data().bots().keySet().removeIf(k -> k.equalsIgnoreCase(name));
        plugin.data().botTypes().keySet().removeIf(k -> k.equalsIgnoreCase(name));
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
            java.util.UUID cmd = plugin.bots().commanderOf(b);
            if (cmd != null && !cmd.equals(b.owner())) {
                owner += " → 指揮 " + Bukkit.getOfflinePlayer(cmd).getName();
            }
            msg(sender, b.name() + " [" + b.side().label() + "・" + b.type().label() + "・" + owner + "] " + b.order().describe()
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
                Point point = plugin.formation().lookPoint(p);
                if (point == null || args.length > 4) {
                    msg(sender, point == null ? "視点の先にブロックがありません。" : usage);
                    return;
                }
                Order.Stance st = parseStance(args, 3);
                plugin.bots().setHoldFacing(targets, p.getLocation().getYaw()); // 横隊は指示した人が見ていた向きを向く
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
        Settings st = plugin.settings(b.side());
        Location spot = st.separationEnabled
                ? plugin.separation().findOpenSpot(to, plugin.bots().aliveLocations(List.of(b)), st.separationMinDistance)
                : to;
        plugin.bots().teleport(b, spot); // 移す前の経路を捨て、名前の表示を乗せたまま移す
        msg(sender, b.name() + " を移しました。");
    }

    /** どちらかの陣営の設定で test-bots.enabled が true か。 */
    private boolean anySideTestBots() {
        return plugin.settings(Side.ATTACK).testBots || plugin.settings(Side.DEFEND).testBots;
    }

    /** 1 人あたりの上限の表示: 両陣営で同じなら数、違えば「攻め X / 守り Y」。 */
    private String maxPerPlayerLabel() {
        int atk = plugin.settings(Side.ATTACK).maxBotsPerPlayer;
        int def = plugin.settings(Side.DEFEND).maxBotsPerPlayer;
        return atk == def ? String.valueOf(atk) : "攻め " + atk + " / 守り " + def;
    }

    // ---- マップ ----

    /**
     * /sb map: 適用中のマップを見る・一覧を見る・切り替える。同じマップへの切り替えは何もしない（再読み込みは /sb reload）。
     */
    private void map(CommandSender sender, String[] args) {
        MapProfiles profiles = plugin.profiles();
        if (args.length == 1) {
            StringBuilder sb = new StringBuilder("適用中のマップ: " + profiles.describeActive());
            if (profiles.isFallback(plugin.data().mapName())) {
                sb.append("（data.yml の ").append(plugin.data().mapName()).append(" が読めないため default）");
            }
            if (profiles.hasSideDifferences()) {
                sb.append("（攻守別設定あり）");
            }
            msg(sender, sb.toString());
            return;
        }
        if (args.length > 2) {
            msg(sender, "使い方: /sb map [list|default|reset|<マップ名>]");
            return;
        }
        String arg = args[1];
        if (arg.equalsIgnoreCase("list")) {
            List<String> maps = profiles.listMaps();
            if (maps.isEmpty()) {
                msg(sender, "maps/ にマップファイルがありません。");
                return;
            }
            msg(sender, "マップ一覧（maps/）:");
            for (String m : maps) {
                msg(sender, "  " + m + (m.equals(profiles.activeMap()) ? "（適用中）" : ""));
            }
            return;
        }
        SiegeBotPlugin.SwitchResult r = plugin.switchMap(arg);
        if (!r.ok) {
            msg(sender, r.error);
            return;
        }
        if (r.alreadyActive) {
            msg(sender, "すでにマップ " + r.label + " が適用中です（再読み込みは /sb reload）。");
            return;
        }
        msg(sender, "マップを " + r.label + " に切り替えました。");
        r.warnings.forEach(w -> msg(sender, "警告: " + w));
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
            case "spawn" -> n == 3 ? SIDES : n == 4 ? TYPES : List.of();
            case "remove", "tp" -> n == 2 ? botNames(false) : List.of();
            // bots は数を使わないので、数の候補は出さない
            case "revive" -> n == 2 ? reviveTargets()
                    : (n == 3 || n == 4) && !args[1].equalsIgnoreCase("bots") ? reviveExtras(args) : List.of();
            case "protect" -> n == 2 ? botNames(false) : n == 3 ? List.of("on", "off") : List.of();
            case "picks" -> n == 2 ? picksTargets() : n == 3 ? List.of("1", "2", "3", "add", "0") : List.of();
            case "spawnpoint" -> n == 2 ? SIDES : n == 3 ? List.of("clear") : List.of();
            case "set" -> completeSet(args);
            case "map" -> n == 2 ? mapCandidates() : List.of();
            case "debug" -> n == 2 ? List.of("units", "kb")
                    : n == 3 && args[1].equalsIgnoreCase("units") ? SIDES : List.of();
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

    private List<String> mapCandidates() {
        List<String> r = new ArrayList<>(List.of("default", "reset", "list"));
        r.addAll(plugin.profiles().listMaps());
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

    /** タブ補完に出す兵種の順（剣兵・槍兵・弓兵。enum の宣言順とは違う）。 */
    private static final List<String> TYPES = Stream.of(UnitType.SOLDIER, UnitType.SPEARMAN, UnitType.ARCHER)
            .map(UnitType::key).toList();

    /** revive の 3・4 番目: まだ書いていない方（数・兵種）の候補。 */
    private static List<String> reviveExtras(String[] args) {
        boolean hasType = false;
        boolean hasCount = false;
        for (int i = 2; i < args.length - 1; i++) {
            hasType |= UnitType.parse(args[i]) != null;
            hasCount |= parseInt(args[i]) != null;
        }
        List<String> r = new ArrayList<>();
        if (!hasCount) {
            r.addAll(List.of("1", "2", "3"));
        }
        if (!hasType) {
            r.addAll(TYPES);
        }
        return r;
    }

    private List<String> picksTargets() {
        List<String> r = new ArrayList<>(List.of("@p", "@a", "all"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            r.add(p.getName());
        }
        return r;
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
