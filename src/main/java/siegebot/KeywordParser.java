package siegebot;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM を使わずに、チャットの指示をキーワードで方針に直す。Bukkit に依存しない。
 *
 * <p>手順:
 * <ol>
 *   <li>全角を半角に直し（NFKC）、英字を小文字にする。</li>
 *   <li>「、」「,」「。」「!」などで節に分ける。</li>
 *   <li>節ごとに、場所名・ボット名・番号（「1番」「ボット2」）・プレイヤー名・「全員」「残り」「俺」を探し、
 *       見つけた部分は塗りつぶす（場所名「攻め陣地」の「攻め」を、攻めの言葉と取り違えないため）。</li>
 *   <li>残りの文字から、動きの言葉（追従・守る・前線・下がる）と構えの言葉（突撃・深追いするな）を探して、方針を決める。</li>
 *   <li>主語だけで動きのない節（「Bot全員、」）は、主語を次の節に持ち越す。主語のない節は全員への指示とみなす。</li>
 * </ol>
 * 戻り値の {@link LlmDecision} はボットの正式名だけを使い（"*" は使わない）、{@link OrderResolver} でそのまま検証できる。
 */
public final class KeywordParser {

    private KeywordParser() {
    }

    private static final Pattern CLAUSE_SPLIT = Pattern.compile("[、,。.!?\\n;]+");

    private static final Pattern ALL = Pattern.compile(
            "全員|みんな|皆|全軍|全体|全部|ボットたち|ボット達|\\b(?:all|everyone|everybody)\\b");
    private static final Pattern REST = Pattern.compile(
            "残り|他の|他は|他も|ほかの|ほかは|\\b(?:others|the rest)\\b");
    private static final Pattern SELF = Pattern.compile(
            "俺|おれ|オレ|僕|ぼく|私|わたし|自分|\\b(?:me|us)\\b");
    private static final Pattern NUMBERED = Pattern.compile(
            "(?:ボット|ぼっと|bot)\\s*(\\d{1,2})(?!\\d)|(?<![a-z\\d])(\\d{1,2})\\s*(?:番|号)");

    /** 追従をやめさせる言葉。 */
    private static final Pattern STOP_FOLLOW = Pattern.compile(
            "(?:ついて|付いて|着いて)(?:こな|来な|くるな|来るな)|追従(?:を)?(?:やめ|解除|終了)|\\bstop follow");
    private static final Pattern FOLLOW = Pattern.compile(
            "ついてき|ついてこ|ついて来|ついてい|ついて行|付いて|着いて|追従|護衛|エスコート|"
                    + "\\b(?:follow|escort)\\b|\\bcome with\\b");
    /** プレイヤーの名前と一緒に出たら「その人を守る」＝追従とみなす言葉。 */
    private static final Pattern PROTECT = Pattern.compile("守|護衛|援護|\\b(?:protect|cover|guard)\\b");
    private static final Pattern HOLD = Pattern.compile(
            "守|防衛|防御|待機|待って|待て|待っ|とどま|留ま|固め|死守|キープ|集合|集まれ|集まって|"
                    + "行って|行け|向かえ|向かって|移動|\\b(?:hold|defend|guard|stay|wait|go to|gather)\\b");
    private static final Pattern FRONT = Pattern.compile(
            // 「行って」「出て」は「トイレ行ってくる」「敵が出てきた」に当たるので入れない
            "前線|攻め|攻撃|突撃|突っ込|突入|進め|進んで|進軍|押せ|押して|押し上げ|戦え|戦って|行け|出撃|出ろ|"
                    + "\\b(?:attack|push|charge|go|fight|advance)\\b");
    private static final Pattern RETREAT = Pattern.compile(
            "下がれ|下がって|退却|撤退|引け|引いて|戻れ|戻って|\\b(?:retreat|fall back)\\b");
    private static final Pattern STANCE_PUSH = Pattern.compile(
            "突撃|突っ込|突入|ガンガン|がんがん|全力|積極|攻め込|押せ|押し上げ|\\b(?:push|aggressive|charge)\\b");
    private static final Pattern STANCE_GUARD = Pattern.compile(
            "深追い|無理せず|無理する|無理しない|慎重|待機|離れるな|離れないで|\\b(?:careful|cautious)\\b");
    /** 疑問文でも指示とみなす頼み方（「守ってくれる？」）。 */
    private static final Pattern REQUEST = Pattern.compile("くれ|もらえ|ちょうだい|ほしい|欲しい|お願い|頼");

    /**
     * @param text         発言
     * @param bots         この陣営のボットの正式名（並び順は番号の対応に使う）
     * @param placeAliases 小文字にした場所名・別名 → 場所の正式名
     * @param players      追従の相手にできるプレイヤー名
     * @param speaker      発言者の名前（「俺」「me」に使う。null 可）
     * @param current      ボットの正式名 → 今の方針（構えだけを変える指示に使う。足りないボットは前線とみなす）
     * @param retreatPlace 「下がれ」で向かう場所の正式名（null なら「下がれ」は無視）
     * @return 方針の変更。指示でない発言なら orders は空
     */
    public static LlmDecision parse(String text, List<String> bots, Map<String, String> placeAliases,
            Collection<String> players, String speaker, Map<String, Order> current, String retreatPlace) {
        List<LlmDecision.BotOrder> none = List.of();
        if (text == null || text.isBlank() || bots.isEmpty()) {
            return new LlmDecision(none, "");
        }
        String s = normalize(text);
        // 疑問文は、頼み方でなければ指示とみなさない（「前線どうなってる？」で全員が動かないように）
        if (s.endsWith("?") && !REQUEST.matcher(s).find()) {
            return new LlmDecision(none, "");
        }

        List<String> placeKeys = new ArrayList<>();
        Map<String, String> placeByKey = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : placeAliases.entrySet()) {
            String k = normalize(e.getKey());
            if (!k.isBlank()) {
                placeByKey.putIfAbsent(k, e.getValue());
            }
        }
        placeKeys.addAll(placeByKey.keySet());
        placeKeys.sort((a, b) -> b.length() - a.length());
        List<String> botsByLength = new ArrayList<>(bots);
        botsByLength.sort((a, b) -> b.length() - a.length());
        List<String> playersByLength = new ArrayList<>(players);
        playersByLength.sort((a, b) -> b.length() - a.length());

        Map<String, Order> working = new LinkedHashMap<>();
        for (String b : bots) {
            Order o = current == null ? null : current.get(b);
            working.put(b, o != null ? o : Order.front());
        }
        Map<String, LlmDecision.BotOrder> result = new LinkedHashMap<>();
        Set<String> assigned = new LinkedHashSet<>();
        Set<String> carried = new LinkedHashSet<>();
        boolean carriedAll = false;

        for (String clause : CLAUSE_SPLIT.split(s)) {
            if (clause.isBlank()) {
                continue;
            }
            StringBuilder rest = new StringBuilder(clause);

            String place = null;
            for (String k : placeKeys) {
                if (maskAll(rest, k) && place == null) {
                    place = placeByKey.get(k);
                }
            }
            Set<String> subjects = new LinkedHashSet<>();
            for (String b : botsByLength) {
                if (maskAll(rest, b.toLowerCase(Locale.ROOT))) {
                    subjects.add(b);
                }
            }
            String player = null;
            for (String p : playersByLength) {
                if (maskAll(rest, p.toLowerCase(Locale.ROOT)) && player == null) {
                    player = p;
                }
            }
            Matcher nm = NUMBERED.matcher(rest);
            List<int[]> numSpans = new ArrayList<>();
            while (nm.find()) {
                int n = Integer.parseInt(nm.group(1) != null ? nm.group(1) : nm.group(2));
                String b = botByNumber(bots, n);
                if (b != null) {
                    subjects.add(b);
                }
                numSpans.add(new int[] {nm.start(), nm.end()});
            }
            for (int[] sp : numSpans) {
                mask(rest, sp[0], sp[1]);
            }
            boolean all = maskPattern(rest, ALL);
            boolean others = maskPattern(rest, REST);
            boolean self = maskPattern(rest, SELF);
            String r = rest.toString();

            // ---- 動きを決める ----
            String mode = null;
            String targetPlace = null;
            String targetPlayer = null;
            boolean stanceOnly = false;
            String stance = STANCE_GUARD.matcher(r).find() ? "guard"
                    : STANCE_PUSH.matcher(r).find() ? "push" : "";
            boolean explicitSubject = !subjects.isEmpty() || all || others;

            if (STOP_FOLLOW.matcher(r).find()) {
                mode = "front";
            } else if (FOLLOW.matcher(r).find()
                    || ((player != null || self) && PROTECT.matcher(r).find())) {
                mode = "follow";
                targetPlayer = player != null ? player : speaker;
            } else if (place != null && (explicitSubject || HOLD.matcher(r).find() || FRONT.matcher(r).find()
                    || RETREAT.matcher(r).find() || !stance.isEmpty())) {
                mode = "hold";
                targetPlace = place;
            } else if (RETREAT.matcher(r).find() && retreatPlace != null) {
                mode = "hold";
                targetPlace = retreatPlace;
            } else if (FRONT.matcher(r).find()) {
                mode = "front";
            } else if (!stance.isEmpty()) {
                stanceOnly = true;
            }

            if (mode == null && !stanceOnly) {
                // 動きのない節: 主語だけなら次の節へ持ち越す
                if (explicitSubject) {
                    carried.addAll(subjects);
                    carriedAll |= all;
                    if (others) {
                        carried.addAll(remaining(bots, assigned));
                    }
                }
                continue;
            }
            if (mode != null && mode.equals("follow") && targetPlayer == null) {
                continue; // 追う相手が分からない
            }

            // ---- 対象のボットを決める ----
            Set<String> targets = new LinkedHashSet<>();
            if (all || (carriedAll && !explicitSubject)) {
                targets.addAll(bots);
            }
            targets.addAll(subjects);
            if (others) {
                targets.addAll(remaining(bots, assigned));
            }
            if (!explicitSubject) {
                targets.addAll(carried);
            }
            if (targets.isEmpty()) {
                targets.addAll(bots);
            }
            carried.clear();
            carriedAll = false;

            for (String b : targets) {
                LlmDecision.BotOrder bo;
                if (stanceOnly) {
                    Order cur = working.get(b);
                    bo = new LlmDecision.BotOrder(b, cur.mode().name().toLowerCase(Locale.ROOT),
                            cur.place() == null ? "" : cur.place(), cur.player() == null ? "" : cur.player(), stance);
                } else {
                    bo = new LlmDecision.BotOrder(b, mode, targetPlace == null ? "" : targetPlace,
                            targetPlayer == null ? "" : targetPlayer, stance);
                }
                result.put(b, bo);
                assigned.add(b);
                working.put(b, toOrder(bo, working.get(b)));
            }
        }
        return new LlmDecision(new ArrayList<>(result.values()), "");
    }

    /** 全角英数字を半角にし、英字を小文字にする。 */
    static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
    }

    /** 番号 n のボット: 名前の末尾の数字が n のボット。なければ n 番目のボット。 */
    private static String botByNumber(List<String> bots, int n) {
        Pattern tail = Pattern.compile("(\\d+)$");
        for (String b : bots) {
            Matcher m = tail.matcher(b);
            if (m.find() && Integer.parseInt(m.group(1)) == n) {
                return b;
            }
        }
        return n >= 1 && n <= bots.size() ? bots.get(n - 1) : null;
    }

    private static List<String> remaining(List<String> bots, Set<String> assigned) {
        List<String> r = new ArrayList<>();
        for (String b : bots) {
            if (!assigned.contains(b)) {
                r.add(b);
            }
        }
        return r;
    }

    private static Order toOrder(LlmDecision.BotOrder bo, Order fallback) {
        try {
            Order.Stance st = switch (bo.stance()) {
                case "push" -> Order.Stance.PUSH;
                case "guard" -> Order.Stance.GUARD;
                default -> null;
            };
            return switch (bo.mode()) {
                case "hold" -> new Order(Order.Mode.HOLD, bo.place(), null, st != null ? st : Order.Stance.GUARD);
                case "follow" -> new Order(Order.Mode.FOLLOW, null, bo.player(), st != null ? st : Order.Stance.GUARD);
                default -> new Order(Order.Mode.FRONT, null, null, st != null ? st : Order.Stance.PUSH);
            };
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ---- 塗りつぶし ----

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /**
     * needle をすべて空白で塗りつぶす。英数字で始まる・終わる needle は、前後が英数字でないときだけ一致とみなす
     * （「a1」が「a12」に一致しないように）。
     *
     * @return 1 つ以上塗りつぶしたら true
     */
    private static boolean maskAll(StringBuilder sb, String needle) {
        if (needle.isEmpty()) {
            return false;
        }
        boolean found = false;
        int from = 0;
        while (true) {
            int i = sb.indexOf(needle, from);
            if (i < 0) {
                return found;
            }
            int end = i + needle.length();
            boolean okStart = !isAsciiAlnum(needle.charAt(0)) || i == 0 || !isAsciiAlnum(sb.charAt(i - 1));
            boolean okEnd = !isAsciiAlnum(needle.charAt(needle.length() - 1)) || end == sb.length()
                    || !isAsciiAlnum(sb.charAt(end));
            if (okStart && okEnd) {
                mask(sb, i, end);
                found = true;
            }
            from = i + 1;
        }
    }

    private static boolean maskPattern(StringBuilder sb, Pattern p) {
        Matcher m = p.matcher(sb);
        List<int[]> spans = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
        }
        for (int[] sp : spans) {
            mask(sb, sp[0], sp[1]);
        }
        return !spans.isEmpty();
    }

    private static void mask(StringBuilder sb, int start, int end) {
        for (int i = start; i < end; i++) {
            sb.setCharAt(i, ' ');
        }
    }
}
