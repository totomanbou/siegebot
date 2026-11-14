package siegebot;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 味方の発言を LLM に渡し、返ってきた方針をボットに反映する。
 *
 * <p>陣営ごとに同時に 1 回だけ LLM を呼ぶ。呼び出し中に届いた発言はためておき、終わったらまとめて次の 1 回で渡す。
 * メインスレッドからだけ呼ぶ（LLM の結果もメインスレッドに戻してから反映する）。
 */
public final class OrderService {

    /** 初期指示を解釈するときに LLM に見せる仮のボット名。 */
    private static final String DEFAULT_BOT = "default";
    private static final int MAX_PENDING = 8;

    private record Line(String speaker, String text) {
    }

    private final SiegeBotPlugin plugin;
    private LlmClient llm;
    private final Map<Side, List<Line>> pending = new EnumMap<>(Side.class);
    private final Map<Side, Boolean> inFlight = new EnumMap<>(Side.class);

    public OrderService(SiegeBotPlugin plugin) {
        this.plugin = plugin;
        for (Side s : Side.values()) {
            pending.put(s, new ArrayList<>());
            inFlight.put(s, false);
        }
    }

    /** config.yml を読んだ後に呼ぶ。LLM の接続を作り直し、初期指示を解釈し直す。 */
    public void configure() {
        shutdown();
        Settings s = plugin.settings();
        if (s.parserMode.equals("keyword")) {
            plugin.getLogger().info("chat.parser が keyword です。指示はキーワードだけで判定します（LLM は使いません）。");
        } else if (!s.llmEnabled()) {
            if (s.parserMode.equals("llm")) {
                plugin.getLogger().warning("chat.parser が llm ですが API キーが未設定です。チャットの指示は理解しません。");
            } else {
                plugin.getLogger().info("API キーが未設定のため、指示はキーワードだけで判定します。");
            }
        } else {
            llm = new LlmClient(s.apiKey, s.model, s.llmTimeoutSeconds);
        }
        for (Side side : Side.values()) {
            interpretDefault(side);
        }
    }

    public void shutdown() {
        if (llm != null) {
            llm.close();
            llm = null;
        }
        for (Side s : Side.values()) {
            pending.get(s).clear();
            inFlight.put(s, false);
        }
    }

    /** 指示を理解する手段がある（キーワード判定か LLM のどちらか）。 */
    public boolean enabled() {
        return !plugin.settings().parserMode.equals("llm") || llm != null;
    }

    /** LLM に接続できる状態か（chat.parser が keyword のとき、または API キーがないときは false）。 */
    public boolean llmActive() {
        return llm != null;
    }

    /** 追従の相手にできるプレイヤー名: 味方と、チームに入っていないプレイヤー（{@link BotManager#anchorFor} と同じ条件）。 */
    List<String> followCandidates(Side side) {
        List<String> r = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.bots().sideOf(p) != side.opposite()) {
                r.add(p.getName());
            }
        }
        return r;
    }

    /** ボットの正式名 → 今の方針。 */
    Map<String, Order> currentOrders(Side side) {
        Map<String, Order> r = new LinkedHashMap<>();
        for (SoldierBot b : plugin.bots().of(side)) {
            r.put(b.name, b.order());
        }
        return r;
    }

    /** 「下がれ」で向かう場所（リスポーン地点）の正式名。未設定・存在しないなら null。 */
    String retreatPlace(Side side) {
        SiegeData.Place p = plugin.data().place(plugin.data().spawns().get(side));
        return p == null ? null : p.name();
    }

    private List<String> botNames(Side side) {
        List<String> names = new ArrayList<>();
        for (SoldierBot b : plugin.bots().of(side)) {
            names.add(b.name);
        }
        return names;
    }

    /**
     * その発言者が指示できるボットの名前。専用のボットが有効なら、発言者がプレイヤーのときは自分のボットだけ
     * （なければ空）。コンソールなどプレイヤーでない発言者（/sb order）は陣営の全員に指示できる。
     */
    List<String> commandable(Side side, String speaker) {
        Player p = speaker == null ? null : Bukkit.getPlayerExact(speaker);
        if (!plugin.settings().personalBots || p == null) {
            return botNames(side);
        }
        SoldierBot mine = plugin.bots().ownedBy(p.getUniqueId());
        return mine == null || mine.side() != side ? List.of() : List.of(mine.name);
    }

    /** 味方の発言を受け取る。 */
    public void onMessage(Side side, String speaker, String text) {
        if (text == null || text.isBlank() || plugin.bots().of(side).isEmpty()) {
            return;
        }
        String prefix = plugin.settings().chatPrefix;
        if (prefix != null && !prefix.isEmpty()) {
            if (!text.startsWith(prefix)) {
                return;
            }
            text = text.substring(prefix.length()).trim();
            if (text.isEmpty()) {
                return;
            }
        }
        List<String> names = commandable(side, speaker);
        if (names.isEmpty()) {
            return; // 指示できるボットがない（専用のボットがまだ出ていない）
        }
        String mode = plugin.settings().parserMode;
        if (!mode.equals("llm")) {
            // まずキーワードで判定する。読み取れたらその場で反映し、LLM は呼ばない
            LlmDecision d = KeywordParser.parse(text, names, plugin.data().aliasMap(),
                    followCandidates(side), speaker, currentOrders(side), retreatPlace(side));
            OrderResolver.Result r = resolve(names, side, d, speaker);
            if (!r.orders().isEmpty()) {
                applyResult(side, r);
                return;
            }
        }
        if (mode.equals("keyword") || llm == null) {
            return; // 雑談は黙って無視する
        }
        List<Line> q = pending.get(side);
        if (q.size() >= MAX_PENDING) {
            q.remove(0);
        }
        q.add(new Line(speaker, text));
        if (!inFlight.get(side)) {
            fire(side);
        }
    }

    private void fire(Side side) {
        List<Line> lines;
        if (plugin.settings().personalBots && !pending.get(side).isEmpty()) {
            // 専用のボットのときは、1 回に 1 人分の発言だけを渡す（その人のボットだけに効かせるため）
            String first = pending.get(side).get(0).speaker();
            lines = new ArrayList<>();
            for (Line l : pending.get(side)) {
                if (l.speaker().equals(first)) {
                    lines.add(l);
                }
            }
            pending.get(side).removeAll(lines);
        } else {
            lines = new ArrayList<>(pending.get(side));
            pending.get(side).clear();
        }
        if (lines.isEmpty() || llm == null) {
            return;
        }
        String speaker = lines.get(lines.size() - 1).speaker();
        List<String> names = commandable(side, speaker);
        if (names.isEmpty()) {
            if (!pending.get(side).isEmpty()) {
                fire(side);
            }
            return;
        }
        inFlight.put(side, true);
        LlmClient used = llm;
        CompletableFuture<Optional<LlmDecision>> f = used.decide(buildContext(side, lines, names));
        f.whenComplete((decision, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (used != llm) {
                    return; // 設定の読み直しで作り直された
                }
                inFlight.put(side, false);
                if (error != null) {
                    plugin.getLogger().warning("LLM の呼び出しに失敗: " + error);
                    List<SoldierBot> mine = plugin.bots().of(side);
                    if (!mine.isEmpty()) {
                        plugin.bots().say(mine.get(0), "（指示を理解できなかった。もう一度言ってくれ）");
                    }
                } else {
                    apply(side, decision.orElse(null), speaker, commandable(side, speaker));
                }
                if (!pending.get(side).isEmpty()) {
                    fire(side);
                }
            });
        });
    }

    private OrderResolver.Result resolve(List<String> names, Side side, LlmDecision decision, String speaker) {
        return OrderResolver.resolve(decision, names, plugin.data().aliasMap(), followCandidates(side), speaker);
    }

    /** LLM の結果を反映する（names に入っているボットだけ）。 */
    private void apply(Side side, LlmDecision decision, String speaker, List<String> names) {
        if (plugin.bots().of(side).isEmpty() || names.isEmpty()) {
            return;
        }
        applyResult(side, resolve(names, side, decision, speaker));
    }

    /** コマンド（/sbc、ItemJoin のアイテム）からの指示を反映し、ボットに復唱させる。 */
    public void applyDirect(Side side, Map<String, Order> orders) {
        applyResult(side, new OrderResolver.Result(orders, List.of(), ""));
    }

    /** 検証済みの結果をボットに反映し、ボットに復唱させる（LLM でもキーワードでも共通）。 */
    private void applyResult(Side side, OrderResolver.Result r) {
        List<SoldierBot> mine = plugin.bots().of(side);
        if (mine.isEmpty()) {
            return;
        }
        Map<SoldierBot, Order> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Order> e : r.orders().entrySet()) {
            SoldierBot b = plugin.bots().get(e.getKey());
            if (b != null) {
                plugin.bots().setOrder(b, e.getValue());
                changed.put(b, e.getValue());
            }
        }
        for (String w : r.warnings()) {
            plugin.getLogger().info("指示の一部を無視: " + w);
        }
        String reply = r.reply();
        if ((reply == null || reply.isBlank()) && !changed.isEmpty()) {
            reply = describeChange(mine, changed);
        }
        SoldierBot voice = changed.isEmpty() ? mine.get(0) : changed.keySet().iterator().next();
        plugin.bots().say(voice, reply);
    }

    /** 返事が空のときの定型文。全員が同じ方針なら 1 つにまとめ、違うなら 1 体ずつ並べる。 */
    private static String describeChange(List<SoldierBot> mine, Map<SoldierBot, Order> changed) {
        Set<Order> distinct = new LinkedHashSet<>(changed.values());
        if (distinct.size() == 1) {
            StringBuilder sb = new StringBuilder("了解。").append(distinct.iterator().next().describe());
            if (changed.size() != mine.size()) {
                List<String> names = new ArrayList<>();
                for (SoldierBot b : changed.keySet()) {
                    names.add(b.name);
                }
                sb.append("（").append(String.join("・", names)).append("）");
            }
            return sb.toString();
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<SoldierBot, Order> e : changed.entrySet()) {
            parts.add(e.getKey().name + "：" + e.getValue().describe());
        }
        return "了解。" + String.join(" / ", parts);
    }

    private String buildContext(Side side, List<Line> lines, List<String> names) {
        SiegeData data = plugin.data();
        int stage = plugin.gates().stage();
        StringBuilder sb = new StringBuilder();
        sb.append("Our side: ").append(side.key()).append('\n');
        sb.append("Gates opened: ").append(stage).append(" of ").append(data.gates().size()).append('\n');
        String front = data.stagePlace(stage, side);
        sb.append("Current front line: ").append(front == null ? "(not set)" : front).append('\n');
        sb.append("Bots:\n");
        for (SoldierBot b : plugin.bots().of(side)) {
            if (names.contains(b.name)) {
                sb.append("- ").append(b.name).append(" (now: ").append(orderText(b.order)).append(")\n");
            }
        }
        appendPlaces(sb);
        sb.append("Teammates online:");
        for (Player p : plugin.bots().humans(side)) {
            sb.append(' ').append(p.getName());
        }
        sb.append("\nMessages:\n");
        for (Line l : lines) {
            sb.append('[').append(l.speaker()).append("] ").append(l.text()).append('\n');
        }
        return sb.toString();
    }

    private void appendPlaces(StringBuilder sb) {
        sb.append("Places:\n");
        if (plugin.data().places().isEmpty()) {
            sb.append("(none)\n");
        }
        for (SiegeData.Place p : plugin.data().places().values()) {
            sb.append("- ").append(p.name());
            if (!p.aliases().isEmpty()) {
                sb.append(" (aliases: ").append(String.join(", ", p.aliases())).append(')');
            }
            sb.append('\n');
        }
    }

    private static String orderText(Order o) {
        String s = switch (o.mode()) {
            case FRONT -> "front";
            case HOLD -> "hold " + o.place();
            case FOLLOW -> "follow " + o.player();
        };
        return s + ", " + (o.stance() == Order.Stance.PUSH ? "push" : "guard");
    }

    /** LLM を使わずに、初期指示をキーワードで方針に直す（その場で終わる）。 */
    private void interpretDefaultByKeyword(Side side, String text) {
        List<String> bot = List.of(DEFAULT_BOT);
        Map<String, String> aliases = plugin.data().aliasMap();
        LlmDecision d = KeywordParser.parse(text, bot, aliases, List.of(), null, Map.of(), null);
        OrderResolver.Result r = OrderResolver.resolve(d, bot, aliases, List.of(), null);
        Order o = r.orders().get(DEFAULT_BOT);
        if (o != null) {
            plugin.bots().setDefaultOrder(side, o);
            plugin.getLogger().info("初期指示（" + side.label() + "）: " + o.describe());
        } else {
            plugin.getLogger().warning("初期指示（" + side.label() + "）を方針に直せませんでした。前線で戦います。" + r.warnings());
        }
    }

    /** config.yml の初期指示（自由な文章）を方針に直して、その陣営の既定にする。 */
    private void interpretDefault(Side side) {
        String text = plugin.settings().defaultOrderText(side);
        if (text == null || text.isBlank()) {
            return;
        }
        if (llm == null) {
            interpretDefaultByKeyword(side, text);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Our side: ").append(side.key()).append('\n');
        sb.append("Bots:\n- ").append(DEFAULT_BOT).append(" (now: front, push)\n");
        appendPlaces(sb);
        sb.append("Teammates online: (none)\nMessages:\n[commander] ").append(text).append('\n');
        LlmClient used = llm;
        used.decide(sb.toString()).whenComplete((decision, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (used != llm) {
                    return;
                }
                if (error != null) {
                    plugin.getLogger().warning("初期指示（" + side.label() + "）の解釈に失敗: " + error);
                    return;
                }
                OrderResolver.Result r = OrderResolver.resolve(decision.orElse(null), List.of(DEFAULT_BOT),
                        plugin.data().aliasMap(), List.of(), null);
                Order o = r.orders().get(DEFAULT_BOT);
                if (o != null) {
                    plugin.bots().setDefaultOrder(side, o);
                    plugin.getLogger().info("初期指示（" + side.label() + "）: " + o.describe());
                } else {
                    plugin.getLogger().warning("初期指示（" + side.label() + "）を方針に直せませんでした。前線で戦います。" + r.warnings());
                }
            });
        });
    }
}
