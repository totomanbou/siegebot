package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KeywordParserTest {

    private static final List<String> BOTS = List.of("A1", "A2", "A3");
    private static final List<String> PLAYERS = List.of("Steve", "Alex");
    private static final String[][] PLACE_DEFS = {
        {"攻め陣地"}, {"外門前", "門の前"}, {"外門内"}, {"中庭", "なかにわ"}, {"本丸"}};

    // ---- ケースファイル（bench\cases*.json）----

    private record Result(int total, int correct, List<String> ngLines) {
    }

    /** 場所の正式名・別名 → 正式名。SiegeData.aliasMap と同じ作り方（名前が先、別名は putIfAbsent）。 */
    private static Map<String, String> placeAliases(JsonNode places) {
        Map<String, String> m = new LinkedHashMap<>();
        for (JsonNode p : places) {
            String name = p.get("name").asText();
            m.put(name.toLowerCase(Locale.ROOT), name);
        }
        for (JsonNode p : places) {
            String name = p.get("name").asText();
            for (JsonNode a : p.get("aliases")) {
                m.putIfAbsent(a.asText().toLowerCase(Locale.ROOT), name);
            }
        }
        return m;
    }

    private static List<String> strings(JsonNode array) {
        List<String> r = new ArrayList<>();
        for (JsonNode n : array) {
            r.add(n.asText());
        }
        return r;
    }

    private static boolean fieldMatches(JsonNode expected, String actual) {
        return expected.isNull() ? actual == null : expected.asText().equals(actual);
    }

    /** 期待の 1 候補（ボット名または ALL → [mode, place, player, stance]）と解決結果が完全に一致するか。 */
    private static boolean matches(Map<String, Order> resolved, JsonNode alternative, List<String> bots) {
        Map<String, JsonNode> want = new LinkedHashMap<>();
        if (alternative.has("ALL")) {
            for (String b : bots) {
                want.put(b, alternative.get("ALL"));
            }
        }
        Iterator<Map.Entry<String, JsonNode>> it = alternative.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getKey().equals("ALL")) {
                want.put(e.getKey(), e.getValue());
            }
        }
        if (!want.keySet().equals(resolved.keySet())) {
            return false;
        }
        for (Map.Entry<String, JsonNode> e : want.entrySet()) {
            Order o = resolved.get(e.getKey());
            JsonNode f = e.getValue();
            if (!f.get(0).asText().equals(o.mode().name().toLowerCase(Locale.ROOT))
                    || !fieldMatches(f.get(1), o.place())
                    || !fieldMatches(f.get(2), o.player())) {
                return false;
            }
            if (!f.get(3).asText().equals("*") && !fieldMatches(f.get(3), o.stance().name().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static Result evaluate(String file) throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(Path.of("bench", file)));
        List<String> bots = strings(root.get("bots"));
        Map<String, String> aliases = placeAliases(root.get("places"));
        List<String> teammates = strings(root.get("teammates"));
        String retreat = root.hasNonNull("retreat") ? root.get("retreat").asText() : null;
        Map<String, Order> current = new LinkedHashMap<>();
        for (String b : bots) {
            current.put(b, Order.front());
        }
        int total = 0;
        int correct = 0;
        List<String> ng = new ArrayList<>();
        for (JsonNode c : root.get("cases")) {
            total++;
            String text = c.get("text").asText();
            String speaker = c.get("speaker").asText();
            LlmDecision decision = KeywordParser.parse(text, bots, aliases, teammates, speaker, current, retreat);
            Map<String, Order> resolved = OrderResolver.resolve(decision, bots, aliases, teammates, speaker).orders();
            boolean ok = false;
            for (JsonNode alt : c.get("expect")) {
                if (matches(resolved, alt, bots)) {
                    ok = true;
                    break;
                }
            }
            String line = (ok ? "OK " : "NG ") + c.get("id").asText() + " 「" + text + "」 -> " + resolved;
            System.out.println(line);
            if (ok) {
                correct++;
            } else {
                ng.add(line + " / expect " + c.get("expect"));
            }
        }
        System.out.println(file + ": " + correct + " / " + total);
        return new Result(total, correct, ng);
    }

    @Test
    void casesJsonAllCorrect() throws IOException {
        Result r = evaluate("cases.json");
        assertEquals(24, r.total());
        assertEquals(24, r.correct(), "NG: " + r.ngLines());
    }

    @Test
    void holdoutCasesMostlyCorrect() throws IOException {
        Result r = evaluate("cases_holdout.json");
        assertEquals(22, r.total());
        assertTrue(r.correct() >= 18, r.correct() + " / " + r.total() + " NG: " + r.ngLines());
    }

    // ---- 端の入力 ----

    private static Map<String, String> places() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] def : PLACE_DEFS) {
            m.put(def[0].toLowerCase(Locale.ROOT), def[0]);
        }
        for (String[] def : PLACE_DEFS) {
            for (int i = 1; i < def.length; i++) {
                m.putIfAbsent(def[i].toLowerCase(Locale.ROOT), def[0]);
            }
        }
        return m;
    }

    private static Map<String, Order> allFront(List<String> bots) {
        Map<String, Order> current = new LinkedHashMap<>();
        for (String b : bots) {
            current.put(b, Order.front());
        }
        return current;
    }

    private static Map<String, Order> run(String text, List<String> bots, String speaker,
            Map<String, Order> current, String retreat) {
        LlmDecision d = KeywordParser.parse(text, bots, places(), PLAYERS, speaker, current, retreat);
        return OrderResolver.resolve(d, bots, places(), PLAYERS, speaker).orders();
    }

    private static Map<String, Order> run(String text) {
        return run(text, BOTS, "Steve", allFront(BOTS), null);
    }

    @Test
    void nullBlankAndEmptyBotsGiveNoOrders() {
        assertTrue(run(null).isEmpty());
        assertTrue(run("").isEmpty());
        assertTrue(run("   ").isEmpty());
        assertTrue(run("全員前線に出て", List.of(), "Steve", Map.of(), null).isEmpty());
    }

    @Test
    void botNameIsNotMatchedInsideLongerName() {
        List<String> bots = List.of("A1", "A12");
        Map<String, Order> r = run("A12は前線", bots, "Steve", allFront(bots), null);
        assertEquals(Set.of("A12"), r.keySet());
        assertEquals(Order.Mode.FRONT, r.get("A12").mode());
    }

    @Test
    void politeQuestionIsAnOrderButPlainQuestionIsNot() {
        Map<String, Order> r = run("中庭守ってくれる？");
        assertEquals(Set.of("A1", "A2", "A3"), r.keySet());
        for (Order o : r.values()) {
            assertEquals(Order.Mode.HOLD, o.mode());
            assertEquals("中庭", o.place());
        }
        assertTrue(run("前線どうなってる？").isEmpty());
    }

    @Test
    void chatterIsIgnored() {
        assertTrue(run("トイレ行ってくる").isEmpty());
        assertTrue(run("敵が出てきた").isEmpty());
    }

    @Test
    void stanceOnlyKeepsTheCurrentOrder() {
        Map<String, Order> current = allFront(BOTS);
        Order hold = new Order(Order.Mode.HOLD, "中庭", null, Order.Stance.GUARD);
        current.put("A1", hold);
        Map<String, Order> r = run("A1 深追いするな", BOTS, "Steve", current, null);
        assertEquals(Set.of("A1"), r.keySet());
        assertEquals(hold, r.get("A1"));
    }

    @Test
    void retreatGoesToTheRetreatPlaceOnlyWhenItIsSet() {
        assertTrue(run("下がれ", BOTS, "Steve", allFront(BOTS), null).isEmpty());
        Map<String, Order> r = run("下がれ", BOTS, "Steve", allFront(BOTS), "攻め陣地");
        assertEquals(Set.of("A1", "A2", "A3"), r.keySet());
        for (Order o : r.values()) {
            assertEquals(Order.Mode.HOLD, o.mode());
            assertEquals("攻め陣地", o.place());
        }
    }

    @Test
    void followWithoutSpeakerGivesNoOrders() {
        assertTrue(run("ついてきて", BOTS, null, allFront(BOTS), null).isEmpty());
    }

    @Test
    void secondClauseWorksOnWhatTheFirstClauseSet() {
        Map<String, Order> r = run("全員前線に出て、深追いするな");
        assertEquals(Set.of("A1", "A2", "A3"), r.keySet());
        for (Order o : r.values()) {
            assertEquals(Order.Mode.FRONT, o.mode());
            assertEquals(Order.Stance.GUARD, o.stance());
        }
    }

    @Test
    void fullWidthBotNameIsRecognised() {
        Map<String, Order> r = run("Ａ２は本丸を守れ");
        assertEquals(Set.of("A2"), r.keySet());
        assertEquals(Order.Mode.HOLD, r.get("A2").mode());
        assertEquals("本丸", r.get("A2").place());
    }

    @Test
    void wordInsideAPlaceNameDoesNotChangeTheStance() {
        Map<String, Order> r = run("A1は攻め陣地を守れ");
        assertEquals(Set.of("A1"), r.keySet());
        assertEquals(new Order(Order.Mode.HOLD, "攻め陣地", null, Order.Stance.GUARD), r.get("A1"));
    }
}
