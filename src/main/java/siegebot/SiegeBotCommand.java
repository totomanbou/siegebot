package siegebot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** /siegebot（/sb）コマンド。ボット・場所・門・前線・リスポーン地点・段階の管理と、指示の送信。 */
public final class SiegeBotCommand implements TabExecutor {

    private static final String PREFIX = "[SiegeBot] ";
    private static final Pattern BOT_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final int MAX_STAGE = 64;
    private static final List<String> SUBS = List.of(
            "help", "spawn", "remove", "list", "place", "gate", "front", "spawnpoint", "stage", "order", "set", "reload", "debug", "tp", "protect");
    private static final List<String> SIDES = List.of("attack", "defend");

    /** 登録の途中の門（pos1・pos2 を別々のコマンドで受け取るため）。 */
    private static final class PendingGate {
        final String[] worlds = new String[2];
        final int[][] corners = new int[2][];
    }

    private final SiegeBotPlugin plugin;
    /** プレイヤー → 門の名前 → 登録の途中の角。 */
    private final Map<UUID, Map<String, PendingGate>> pendingGates = new HashMap<>();

    public SiegeBotCommand(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- ディスパッチ ----

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "spawn" -> spawn(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            case "place" -> place(sender, args);
            case "gate" -> gate(sender, args);
            case "front" -> front(sender, args);
            case "spawnpoint" -> spawnpoint(sender, args);
            case "stage" -> stage(sender, args);
            case "order" -> order(sender, args);
            case "reload" -> reload(sender);
            case "debug" -> debug(sender);
            case "tp" -> tp(sender, args);
            case "protect" -> protect(sender, args);
            case "set" -> set(sender, args);
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

    // ---- help ----

    private void help(CommandSender sender) {
        msg(sender, "コマンド一覧:");
        msg(sender, "/siegebot help - この一覧を表示");
        msg(sender, "/siegebot spawn <名前> <attack|defend> - ボットを出す");
        msg(sender, "/siegebot remove <名前> - ボットを消す");
        msg(sender, "/siegebot list - ボットの一覧");
        msg(sender, "/siegebot place <set|remove|alias|list> - 場所の管理");
        msg(sender, "/siegebot gate <pos1|pos2|remove|list> - 門の管理");
        msg(sender, "/siegebot front <段階> <attack|defend> <場所> / front list - 段階ごとの前線");
        msg(sender, "/siegebot spawnpoint <attack|defend> <場所> - リスポーン地点");
        msg(sender, "/siegebot stage [番号|auto] - 侵攻度の表示・固定");
        msg(sender, "/siegebot order <attack|defend> <文章> - チャットの代わりに指示を送る");
        msg(sender, "/siegebot reload - 設定の読み直し");
    }

    // ---- ボット ----

    private void spawn(CommandSender sender, String[] args) {
        Side side = args.length == 3 ? Side.parse(args[2]) : null;
        if (args.length != 3 || side == null) {
            msg(sender, "使い方: /siegebot spawn <名前> <attack|defend>");
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
            msg(sender, "同名のボットがいるか、出す場所がありません（リスポーン地点を設定するか、ゲーム内で実行してください）");
            return;
        }
        plugin.data().bots().keySet().removeIf(k -> k.equalsIgnoreCase(name));
        plugin.data().bots().put(name, side);
        plugin.data().save();
        msg(sender, "ボット " + name + "（" + side.label() + "）を出しました。");
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length != 2) {
            msg(sender, "使い方: /siegebot remove <名前>");
            return;
        }
        String name = args[1];
        boolean removed = plugin.bots().remove(name);
        boolean saved = plugin.data().bots().keySet().removeIf(k -> k.equalsIgnoreCase(name));
        if (saved) {
            plugin.data().save();
        }
        if (removed || saved) {
            msg(sender, "ボット " + name + " を消しました。");
        } else {
            msg(sender, "ボット " + name + " は見つかりません。");
        }
    }

    private void list(CommandSender sender) {
        if (plugin.bots().all().isEmpty()) {
            msg(sender, "ボットはいません。");
            return;
        }
        msg(sender, "ボット一覧:");
        for (SoldierBot b : plugin.bots().all()) {
            msg(sender, b.name() + " [" + b.side().label() + "] " + b.order().describe()
                    + " / " + (b.alive() ? "生存" : "復活待ち"));
        }
    }

    // ---- 場所 ----

    private void place(CommandSender sender, String[] args) {
        String sub = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set" -> placeSet(sender, args);
            case "remove" -> placeRemove(sender, args);
            case "alias" -> placeAlias(sender, args);
            case "list" -> placeList(sender);
            default -> msg(sender, "使い方: /siegebot place <set <名前>|remove <名前>|alias <名前> <別名>|list>");
        }
    }

    private void placeSet(CommandSender sender, String[] args) {
        if (args.length != 3) {
            msg(sender, "使い方: /siegebot place set <名前>");
            return;
        }
        if (!(sender instanceof Player p)) {
            msg(sender, "このコマンドはゲーム内のプレイヤーだけが使えます。");
            return;
        }
        plugin.data().putPlace(args[2], p.getLocation());
        plugin.data().save();
        msg(sender, "場所「" + args[2] + "」を現在地に設定しました。");
    }

    private void placeRemove(CommandSender sender, String[] args) {
        if (args.length != 3) {
            msg(sender, "使い方: /siegebot place remove <名前>");
            return;
        }
        if (plugin.data().removePlace(args[2])) {
            plugin.data().save();
            msg(sender, "場所「" + args[2] + "」を削除しました。");
        } else {
            msg(sender, "場所「" + args[2] + "」は見つかりません。");
        }
    }

    private void placeAlias(CommandSender sender, String[] args) {
        if (args.length != 4) {
            msg(sender, "使い方: /siegebot place alias <名前> <別名>");
            return;
        }
        if (plugin.data().addAlias(args[2], args[3])) {
            plugin.data().save();
            msg(sender, "場所「" + args[2] + "」に別名「" + args[3] + "」を足しました。");
        } else {
            msg(sender, "場所「" + args[2] + "」は見つかりません。");
        }
    }

    private void placeList(CommandSender sender) {
        if (plugin.data().places().isEmpty()) {
            msg(sender, "場所は登録されていません。");
            return;
        }
        msg(sender, "場所一覧:");
        for (SiegeData.Place p : plugin.data().places().values()) {
            String aliases = p.aliases().isEmpty() ? "なし" : String.join(", ", p.aliases());
            msg(sender, p.name() + " : " + p.world() + " (" + Math.round(p.x()) + ", " + Math.round(p.y())
                    + ", " + Math.round(p.z()) + ") 別名: " + aliases);
        }
    }

    // ---- 門 ----

    private void gate(CommandSender sender, String[] args) {
        String sub = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "pos1" -> gatePos(sender, args, 0);
            case "pos2" -> gatePos(sender, args, 1);
            case "remove" -> gateRemove(sender, args);
            case "list" -> gateList(sender);
            default -> msg(sender, "使い方: /siegebot gate <pos1 <名前>|pos2 <名前>|remove <名前>|list>");
        }
    }

    private void gatePos(CommandSender sender, String[] args, int index) {
        if (args.length != 3) {
            msg(sender, "使い方: /siegebot gate pos" + (index + 1) + " <名前>");
            return;
        }
        if (!(sender instanceof Player p)) {
            msg(sender, "このコマンドはゲーム内のプレイヤーだけが使えます。");
            return;
        }
        String name = args[2];
        Block block = p.getTargetBlockExact(6);
        if (block == null) {
            block = p.getLocation().getBlock();
        }
        PendingGate pending = pendingGates.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(name, k -> new PendingGate());
        pending.worlds[index] = block.getWorld().getName();
        pending.corners[index] = new int[] {block.getX(), block.getY(), block.getZ()};
        msg(sender, "門「" + name + "」の pos" + (index + 1) + " を (" + block.getX() + ", " + block.getY()
                + ", " + block.getZ() + ") にしました。");
        if (pending.corners[0] == null || pending.corners[1] == null) {
            msg(sender, "もう一方の角も指定してください。");
            return;
        }
        finishGate(sender, p, name, pending);
    }

    private void finishGate(CommandSender sender, Player p, String name, PendingGate pending) {
        Map<String, PendingGate> mine = pendingGates.get(p.getUniqueId());
        if (pending.worlds[0].equals(pending.worlds[1])) {
            int[] a = pending.corners[0];
            int[] b = pending.corners[1];
            try {
                plugin.data().putGate(name, pending.worlds[0], a[0], a[1], a[2], b[0], b[1], b[2]);
            } catch (IllegalArgumentException e) {
                msg(sender, e.getMessage());
                mine.remove(name);
                return;
            }
            plugin.data().save();
            int volume = plugin.data().gates().stream()
                    .filter(g -> g.name().equals(name)).findFirst().map(SiegeData.Gate::volume).orElse(0);
            msg(sender, "門「" + name + "」を登録しました（体積 " + volume + " ブロック）。");
        } else {
            msg(sender, "2 つの角が別のワールドです。最初からやり直してください。");
        }
        mine.remove(name);
    }

    private void gateRemove(CommandSender sender, String[] args) {
        if (args.length != 3) {
            msg(sender, "使い方: /siegebot gate remove <名前>");
            return;
        }
        if (plugin.data().removeGate(args[2])) {
            plugin.data().save();
            msg(sender, "門「" + args[2] + "」を削除しました。");
        } else {
            msg(sender, "門「" + args[2] + "」は見つかりません。");
        }
    }

    private void gateList(CommandSender sender) {
        List<SiegeData.Gate> gates = plugin.data().gates();
        if (gates.isEmpty()) {
            msg(sender, "門は登録されていません。");
            return;
        }
        msg(sender, "門一覧（攻め手が通る順）:");
        for (int i = 0; i < gates.size(); i++) {
            SiegeData.Gate g = gates.get(i);
            msg(sender, (i + 1) + ". " + g.name() + " : " + g.world() + " (" + g.minX() + ", " + g.minY()
                    + ", " + g.minZ() + ") - (" + g.maxX() + ", " + g.maxY() + ", " + g.maxZ() + ") 体積 "
                    + g.volume() + " / " + (plugin.gates().isOpen(g.name()) ? "開" : "閉"));
        }
    }

    // ---- 前線・リスポーン地点 ----

    private void front(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            frontList(sender);
            return;
        }
        Side side = args.length == 4 ? Side.parse(args[2]) : null;
        Integer stage = args.length == 4 ? parseInt(args[1]) : null;
        if (side == null || stage == null || stage < 0 || stage > MAX_STAGE) {
            msg(sender, "使い方: /siegebot front <段階 0〜" + MAX_STAGE + "> <attack|defend> <場所> / front list");
            return;
        }
        SiegeData.Place place = plugin.data().place(args[3]);
        if (place == null) {
            msg(sender, "場所「" + args[3] + "」は見つかりません。");
            return;
        }
        plugin.data().setStagePlace(stage, side, place.name());
        plugin.data().save();
        msg(sender, "段階 " + stage + " の" + side.label() + "の前線を「" + place.name() + "」にしました。");
    }

    private void frontList(CommandSender sender) {
        List<SiegeData.StagePlaces> stages = plugin.data().stages();
        if (stages.isEmpty()) {
            msg(sender, "前線は設定されていません。");
            return;
        }
        msg(sender, "段階ごとの前線:");
        for (int i = 0; i < stages.size(); i++) {
            SiegeData.StagePlaces s = stages.get(i);
            msg(sender, "段階 " + i + " : 攻め=" + placeOrUnset(s.attack()) + " / 守り=" + placeOrUnset(s.defend()));
        }
    }

    private static String placeOrUnset(String name) {
        return name == null || name.isBlank() ? "未設定" : name;
    }

    private void spawnpoint(CommandSender sender, String[] args) {
        Side side = args.length == 3 ? Side.parse(args[1]) : null;
        if (side == null) {
            msg(sender, "使い方: /siegebot spawnpoint <attack|defend> <場所>");
            return;
        }
        SiegeData.Place place = plugin.data().place(args[2]);
        if (place == null) {
            msg(sender, "場所「" + args[2] + "」は見つかりません。");
            return;
        }
        plugin.data().spawns().put(side, place.name());
        plugin.data().save();
        msg(sender, side.label() + "のリスポーン地点を「" + place.name() + "」にしました。");
    }

    // ---- 段階 ----

    private void stage(CommandSender sender, String[] args) {
        if (args.length == 1) {
            msg(sender, "侵攻度: " + plugin.gates().stage() + " / 門の数: " + plugin.data().gates().size()
                    + " / " + (plugin.gates().isManual() ? "手動で固定中" : "自動判定"));
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("auto")) {
            plugin.gates().setManualStage(-1);
            msg(sender, "侵攻度を自動判定に戻しました。");
            return;
        }
        Integer n = args.length == 2 ? parseInt(args[1]) : null;
        if (n == null || n < 0) {
            msg(sender, "使い方: /siegebot stage [番号(0以上)|auto]");
            return;
        }
        plugin.gates().setManualStage(n);
        msg(sender, "侵攻度を " + plugin.gates().stage() + " に固定しました。");
    }

    // ---- 指示・読み直し ----

    private void order(CommandSender sender, String[] args) {
        Side side = args.length >= 3 ? Side.parse(args[1]) : null;
        if (side == null) {
            msg(sender, "使い方: /siegebot order <attack|defend> <文章>");
            return;
        }
        if (!plugin.orders().enabled()) {
            msg(sender, "指示を理解する方法がありません（chat.parser が llm で、API キーが未設定です）。");
            return;
        }
        if (plugin.bots().of(side).isEmpty()) {
            msg(sender, side.label() + "のボットがいません。");
            return;
        }
        String text = String.join(" ", List.of(args).subList(2, args.length));
        // chat.prefix を設定していても、このコマンドの指示が捨てられないようにする
        String prefix = plugin.settings().chatPrefix;
        if (prefix != null && !prefix.isEmpty() && !text.startsWith(prefix)) {
            text = prefix + text;
        }
        plugin.orders().onMessage(side, sender.getName(), text);
        msg(sender, side.label() + "のボットに指示を送りました。");
    }

    private void reload(CommandSender sender) {
        plugin.reloadAll();
        msg(sender, "config.yml と data.yml を読み直しました。");
    }

    /** LLM を通さずに方針を直接設定する（API キーがないとき・通信できないときの代わり）。 */
    private void set(CommandSender sender, String[] args) {
        String usage = "使い方: /siegebot set <ボット|*> <front | hold <場所> | follow <プレイヤー>> [push|guard]";
        if (args.length < 3) {
            msg(sender, usage);
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        boolean needsArg = mode.equals("hold") || mode.equals("follow");
        int stanceIndex = needsArg ? 4 : 3;
        if (args.length < stanceIndex || args.length > stanceIndex + 1) {
            msg(sender, usage);
            return;
        }
        String place = mode.equals("hold") ? args[3] : "";
        String player = mode.equals("follow") ? args[3] : "";
        String stance = args.length > stanceIndex ? args[stanceIndex] : "";
        List<String> players = new ArrayList<>();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            players.add(p.getName());
        }
        LlmDecision decision = new LlmDecision(
                List.of(new LlmDecision.BotOrder(args[1], mode, place, player, stance)), "");
        OrderResolver.Result r = OrderResolver.resolve(decision, botNames(), plugin.data().aliasMap(),
                players, sender.getName());
        for (String w : r.warnings()) {
            msg(sender, w);
        }
        for (Map.Entry<String, Order> e : r.orders().entrySet()) {
            SoldierBot b = plugin.bots().get(e.getKey());
            if (b != null) {
                plugin.bots().setOrder(b, e.getValue());
                msg(sender, b.name() + " の方針: " + e.getValue().describe());
            }
        }
    }

    /** ボットを場所か座標へ瞬間移動させる（試験・救出用）。 */
    private void tp(CommandSender sender, String[] args) {
        SoldierBot b = args.length >= 3 ? plugin.bots().get(args[1]) : null;
        if (b == null || !b.alive()) {
            msg(sender, "使い方: /siegebot tp <ボット> <場所 | x y z>（ボットが出ている必要があります）");
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
        } else {
            SiegeData.Place pl = plugin.data().place(args[2]);
            to = pl == null ? null : pl.toLocation();
            if (to == null) {
                msg(sender, "場所「" + args[2] + "」は見つかりません。");
                return;
            }
        }
        b.npc.getEntity().teleport(to);
        msg(sender, b.name() + " を移しました。");
    }

    /** ボットを無敵にする・戻す（試験用）。 */
    private void protect(CommandSender sender, String[] args) {
        SoldierBot b = args.length == 3 ? plugin.bots().get(args[1]) : null;
        if (b == null || !(args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) {
            msg(sender, "使い方: /siegebot protect <ボット> <on|off>");
            return;
        }
        boolean on = args[2].equalsIgnoreCase("on");
        b.npc.setProtected(on);
        msg(sender, b.name() + " を" + (on ? "無敵にしました。" : "無敵でなくしました。"));
    }

    private static Double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 各ボットの位置・狙っている相手・移動中かどうかを表示する（動作の確認用）。 */
    private void debug(CommandSender sender) {
        msg(sender, "侵攻度 " + plugin.gates().stage() + " / 指示の理解: " + plugin.settings().parserMode
                + "（LLM " + (plugin.orders().llmActive() ? "有効" : "無効") + "）");
        for (SoldierBot b : plugin.bots().all()) {
            StringBuilder sb = new StringBuilder(b.name()).append(" [").append(b.side().key()).append("] ");
            if (!b.alive()) {
                msg(sender, sb.append("復活待ち").toString());
                continue;
            }
            Location l = b.npc.getEntity().getLocation();
            sb.append(String.format(Locale.ROOT, "pos=(%.1f, %.1f, %.1f)", l.getX(), l.getY(), l.getZ()));
            if (b.npc.getEntity() instanceof org.bukkit.entity.LivingEntity le) {
                sb.append(String.format(Locale.ROOT, " hp=%.1f", le.getHealth()));
            }
            Location anchor = plugin.bots().anchorFor(b);
            sb.append(anchor == null ? " anchor=なし" : String.format(Locale.ROOT, " anchor=(%.1f, %.1f, %.1f)",
                    anchor.getX(), anchor.getY(), anchor.getZ()));
            sb.append(" target=").append(b.target == null ? "なし" : b.target.getName());
            sb.append(" nav=").append(b.npc.getNavigator().isNavigating());
            sb.append(" order=").append(b.order().describe());
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

    // ---- タブ補完 ----

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        int n = args.length;
        if (n == 0) {
            return List.of();
        }
        String typed = args[n - 1];
        if (n == 1) {
            return filter(SUBS, typed);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String second = args[1].toLowerCase(Locale.ROOT);
        List<String> candidates = switch (sub) {
            case "spawn" -> n == 3 ? SIDES : List.of();
            case "remove" -> n == 2 ? botNames() : List.of();
            case "place" -> completePlace(n, second);
            case "gate" -> completeGate(n, second);
            case "front" -> completeFront(n, second);
            case "spawnpoint" -> n == 2 ? SIDES : n == 3 ? placeNames() : List.of();
            case "stage" -> n == 2 ? stageChoices(true) : List.of();
            case "order" -> n == 2 ? SIDES : List.of();
            default -> List.of();
        };
        return filter(candidates, typed);
    }

    private List<String> completePlace(int n, String second) {
        if (n == 2) {
            return List.of("set", "remove", "alias", "list");
        }
        if (n == 3 && (second.equals("remove") || second.equals("alias"))) {
            return placeNames();
        }
        return List.of();
    }

    private List<String> completeGate(int n, String second) {
        if (n == 2) {
            return List.of("pos1", "pos2", "remove", "list");
        }
        if (n == 3 && second.equals("remove")) {
            List<String> names = new ArrayList<>();
            for (SiegeData.Gate g : plugin.data().gates()) {
                names.add(g.name());
            }
            return names;
        }
        return List.of();
    }

    private List<String> completeFront(int n, String second) {
        if (n == 2) {
            List<String> r = new ArrayList<>();
            r.add("list");
            r.addAll(stageChoices(false));
            return r;
        }
        if (second.equals("list")) {
            return List.of();
        }
        if (n == 3) {
            return SIDES;
        }
        return n == 4 ? placeNames() : List.of();
    }

    private List<String> stageChoices(boolean withAuto) {
        List<String> r = new ArrayList<>();
        if (withAuto) {
            r.add("auto");
        }
        int count = plugin.data().gates().size();
        for (int i = 0; i <= count; i++) {
            r.add(String.valueOf(i));
        }
        return r;
    }

    private List<String> botNames() {
        List<String> r = new ArrayList<>();
        for (SoldierBot b : plugin.bots().all()) {
            r.add(b.name());
        }
        return r;
    }

    private List<String> placeNames() {
        return new ArrayList<>(plugin.data().places().keySet());
    }

    private static List<String> filter(List<String> candidates, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        List<String> r = new ArrayList<>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(t)) {
                r.add(c);
            }
        }
        return r;
    }
}
