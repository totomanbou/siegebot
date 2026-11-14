package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderResolverTest {

    private static final List<String> BOTS = List.of("Alpha", "Bravo");
    private static final Map<String, String> PLACES = Map.of("gate", "Gate", "east gate", "Gate", "keep", "Keep");
    private static final List<String> TEAM = List.of("Steve", "Alex");

    private static LlmDecision.BotOrder bo(String bot, String mode, String place, String player, String stance) {
        return new LlmDecision.BotOrder(bot, mode, place, player, stance);
    }

    private static OrderResolver.Result resolve(String reply, LlmDecision.BotOrder... orders) {
        return OrderResolver.resolve(new LlmDecision(Arrays.asList(orders), reply), BOTS, PLACES, TEAM, "Steve");
    }

    @Test
    void nullDecisionGivesEmptyResult() {
        OrderResolver.Result r = OrderResolver.resolve(null, BOTS, PLACES, TEAM, "Steve");
        assertTrue(r.orders().isEmpty());
        assertTrue(r.warnings().isEmpty());
        assertEquals("", r.reply());
    }

    @Test
    void nullOrdersListKeepsTrimmedReply() {
        OrderResolver.Result r = OrderResolver.resolve(new LlmDecision(null, "  了解  "), BOTS, PLACES, TEAM, "Steve");
        assertTrue(r.orders().isEmpty());
        assertEquals("了解", r.reply());
    }

    @Test
    void nullReplyBecomesEmptyString() {
        OrderResolver.Result r = resolve(null, bo("Alpha", "front", "", "", ""));
        assertEquals("", r.reply());
        assertEquals(1, r.orders().size());
    }

    @Test
    void wildcardTargetsEveryBot() {
        for (String star : List.of("*", "all", "ALL", "全員", " * ")) {
            OrderResolver.Result r = resolve("", bo(star, "front", "", "", ""));
            assertEquals(List.of("Alpha", "Bravo"), List.copyOf(r.orders().keySet()), star);
        }
    }

    @Test
    void botNameMatchIgnoresCaseAndReturnsCanonicalName() {
        OrderResolver.Result r = resolve("", bo("aLPHa", "front", "", "", ""));
        assertEquals(List.of("Alpha"), List.copyOf(r.orders().keySet()));
    }

    @Test
    void unknownBotWarnsAndGivesNoOrder() {
        OrderResolver.Result r = resolve("", bo("Zulu", "front", "", "", ""));
        assertTrue(r.orders().isEmpty());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("Zulu"));
    }

    @Test
    void holdResolvesAliasToCanonicalPlaceAndDefaultsToGuard() {
        OrderResolver.Result r = resolve("", bo("Alpha", "hold", "East Gate", "", ""));
        Order o = r.orders().get("Alpha");
        assertEquals(Order.Mode.HOLD, o.mode());
        assertEquals("Gate", o.place());
        assertEquals(Order.Stance.GUARD, o.stance());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void holdWithUnknownPlaceWarns() {
        OrderResolver.Result r = resolve("", bo("Alpha", "hold", "moon", "", ""));
        assertTrue(r.orders().isEmpty());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("moon"));
    }

    @Test
    void followWithEmptyPlayerUsesSpeaker() {
        Order o = resolve("", bo("Alpha", "follow", "", "", "")).orders().get("Alpha");
        assertEquals(Order.Mode.FOLLOW, o.mode());
        assertEquals("Steve", o.player());
        assertEquals(Order.Stance.GUARD, o.stance());
    }

    @Test
    void followWithMeUsesSpeaker() {
        Order o = resolve("", bo("Alpha", "follow", "", "me", "")).orders().get("Alpha");
        assertEquals("Steve", o.player());
        Order o2 = resolve("", bo("Alpha", "follow", "", "ME", "")).orders().get("Alpha");
        assertEquals("Steve", o2.player());
    }

    @Test
    void followWithTeammateInOtherCaseReturnsCanonicalName() {
        Order o = resolve("", bo("Alpha", "follow", "", "aLEX", "")).orders().get("Alpha");
        assertEquals("Alex", o.player());
    }

    @Test
    void followWithUnknownPlayerWarns() {
        OrderResolver.Result r = resolve("", bo("Alpha", "follow", "", "Herobrine", ""));
        assertTrue(r.orders().isEmpty());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("Herobrine"));
    }

    @Test
    void followWithEmptyPlayerAndNullSpeakerWarns() {
        OrderResolver.Result r = OrderResolver.resolve(
                new LlmDecision(List.of(bo("Alpha", "follow", "", "", "")), ""), BOTS, PLACES, TEAM, null);
        assertTrue(r.orders().isEmpty());
        assertEquals(1, r.warnings().size());
    }

    @Test
    void frontDefaultsToPush() {
        Order o = resolve("", bo("Alpha", "front", "", "", "")).orders().get("Alpha");
        assertEquals(Order.Mode.FRONT, o.mode());
        assertEquals(Order.Stance.PUSH, o.stance());
        assertNull(o.place());
        assertNull(o.player());
    }

    @Test
    void explicitStanceIsHonoured() {
        Order front = resolve("", bo("Alpha", "front", "", "", "guard")).orders().get("Alpha");
        assertEquals(Order.Stance.GUARD, front.stance());
        Order hold = resolve("", bo("Alpha", "hold", "keep", "", "push")).orders().get("Alpha");
        assertEquals(Order.Stance.PUSH, hold.stance());
        assertEquals("Keep", hold.place());
    }

    @Test
    void unknownStanceFallsBackToModeDefault() {
        assertEquals(Order.Stance.PUSH,
                resolve("", bo("Alpha", "front", "", "", "berserk")).orders().get("Alpha").stance());
        assertEquals(Order.Stance.GUARD,
                resolve("", bo("Alpha", "hold", "keep", "", "berserk")).orders().get("Alpha").stance());
    }

    @Test
    void unknownModeWarns() {
        OrderResolver.Result r = resolve("", bo("Alpha", "dance", "", "", ""));
        assertTrue(r.orders().isEmpty());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("dance"));
    }

    @Test
    void laterEntryForSameBotOverridesEarlier() {
        OrderResolver.Result r = resolve("",
                bo("Alpha", "front", "", "", ""),
                bo("Alpha", "hold", "keep", "", ""));
        assertEquals(1, r.orders().size());
        assertEquals(Order.Mode.HOLD, r.orders().get("Alpha").mode());
    }

    @Test
    void laterWildcardOverridesEarlierSpecificEntry() {
        OrderResolver.Result r = resolve("",
                bo("Alpha", "hold", "keep", "", ""),
                bo("*", "front", "", "", ""));
        assertEquals(Order.Mode.FRONT, r.orders().get("Alpha").mode());
        assertEquals(Order.Mode.FRONT, r.orders().get("Bravo").mode());
    }

    @Test
    void nullElementInOrdersListIsSkipped() {
        OrderResolver.Result r = OrderResolver.resolve(
                new LlmDecision(Arrays.asList(null, bo("Bravo", "front", "", "", "")), "ok"),
                BOTS, PLACES, TEAM, "Steve");
        assertEquals(List.of("Bravo"), List.copyOf(r.orders().keySet()));
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void nullFieldsInOrderDoNotThrow() {
        OrderResolver.Result r = resolve("", bo(null, null, null, null, null));
        assertTrue(r.orders().isEmpty());
        assertEquals(1, r.warnings().size());
    }
}
