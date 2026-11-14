package siegebot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** LLM の出力を検証して、ボットごとの {@link Order} に直す。Bukkit に依存しない。 */
public final class OrderResolver {

    private OrderResolver() {
    }

    /**
     * @param orders   ボットの正式名 → 新しい方針（変更のあるボットだけ）
     * @param warnings 無視した項目の説明
     * @param reply    ボットの返事（なければ空文字）
     */
    public record Result(Map<String, Order> orders, List<String> warnings, String reply) {
    }

    /**
     * @param decision     LLM の出力（null なら変更なし）
     * @param botNames     この陣営のボットの正式名
     * @param placeAliases 小文字にした場所名・別名 → 場所の正式名
     * @param teammates    この陣営のオンラインのプレイヤー名
     * @param speaker      最後に発言したプレイヤー名（FOLLOW で相手が空のときに使う。null 可）
     */
    public static Result resolve(LlmDecision decision, Collection<String> botNames,
            Map<String, String> placeAliases, Collection<String> teammates, String speaker) {
        Map<String, Order> out = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (decision == null) {
            return new Result(out, warnings, "");
        }
        String reply = decision.reply() == null ? "" : decision.reply().trim();
        if (decision.orders() == null) {
            return new Result(out, warnings, reply);
        }
        for (LlmDecision.BotOrder bo : decision.orders()) {
            if (bo == null) {
                continue;
            }
            List<String> targets = matchBots(bo.bot(), botNames);
            if (targets.isEmpty()) {
                warnings.add("不明なボット: " + bo.bot());
                continue;
            }
            Order order = toOrder(bo, placeAliases, teammates, speaker, warnings);
            if (order == null) {
                continue;
            }
            for (String t : targets) {
                out.put(t, order);
            }
        }
        return new Result(out, warnings, reply);
    }

    private static List<String> matchBots(String name, Collection<String> botNames) {
        List<String> r = new ArrayList<>();
        if (name == null) {
            return r;
        }
        String n = name.trim();
        if (n.equals("*") || n.equalsIgnoreCase("all") || n.equals("全員")) {
            r.addAll(botNames);
            return r;
        }
        for (String b : botNames) {
            if (b.equalsIgnoreCase(n)) {
                r.add(b);
            }
        }
        return r;
    }

    private static Order toOrder(LlmDecision.BotOrder bo, Map<String, String> placeAliases,
            Collection<String> teammates, String speaker, List<String> warnings) {
        String mode = norm(bo.mode());
        Order.Stance stance = switch (norm(bo.stance())) {
            case "push" -> Order.Stance.PUSH;
            case "guard" -> Order.Stance.GUARD;
            default -> null;
        };
        switch (mode) {
            case "front" -> {
                return new Order(Order.Mode.FRONT, null, null, stance != null ? stance : Order.Stance.PUSH);
            }
            case "hold" -> {
                String place = placeAliases.get(norm(bo.place()));
                if (place == null) {
                    warnings.add("不明な場所: " + bo.place());
                    return null;
                }
                return new Order(Order.Mode.HOLD, place, null, stance != null ? stance : Order.Stance.GUARD);
            }
            case "follow" -> {
                String want = bo.player() == null ? "" : bo.player().trim();
                if (want.isEmpty() || want.equalsIgnoreCase("me")) {
                    want = speaker == null ? "" : speaker;
                }
                String found = null;
                for (String t : teammates) {
                    if (t.equalsIgnoreCase(want)) {
                        found = t;
                    }
                }
                if (found == null) {
                    warnings.add("不明なプレイヤー: " + bo.player());
                    return null;
                }
                return new Order(Order.Mode.FOLLOW, null, found, stance != null ? stance : Order.Stance.GUARD);
            }
            default -> {
                warnings.add("不明なモード: " + bo.mode());
                return null;
            }
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
