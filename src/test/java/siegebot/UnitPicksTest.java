package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** {@link UnitPicks}（呼べる回数と、指揮権の譲渡との関係）。Bukkit なしで確かめる。 */
class UnitPicksTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final Predicate<UUID> ALL = id -> true;
    private static final Predicate<UUID> NONE = id -> false;

    private final Delegations del = new Delegations();
    private final List<UUID> synced = new ArrayList<>();
    private final UnitPicks picks = new UnitPicks(del, synced::add);

    // ---- available ----

    @Test
    void availableIsOwnPicksWhenNotDelegatedPlusHeldPicks() {
        picks.setPicks(A, 3);
        picks.setPicks(B, 2);
        assertEquals(3, picks.available(A, ALL, ALL));
        del.set(B, A, "Alice"); // B が A に譲る
        assertEquals(5, picks.available(A, ALL, ALL)); // 自分 3 + 預かり 2
        assertEquals(0, picks.available(B, ALL, ALL)); // 譲渡中の B の自前分は 0
    }

    @Test
    void delegatedOwnersOwnPicksAreZeroUntilTheTargetBecomesInvalid() {
        picks.setPicks(A, 3);
        del.set(A, B, "Bob");
        assertEquals(0, picks.available(A, ALL, ALL));
        assertEquals(3, picks.available(B, ALL, ALL));
        // B が無効（オフライン・別チーム）になると、commanderOf と同じく A の分は A に戻る
        Predicate<UUID> bInvalid = id -> !id.equals(B);
        assertEquals(A, del.commanderOf(A, bInvalid));
        assertEquals(3, picks.available(A, bInvalid, bInvalid));
        assertEquals(picks.ownPicks(A), picks.available(A, bInvalid, bInvalid));
    }

    @Test
    void invalidOwnersHeldPicksAreNotCounted() {
        picks.setPicks(B, 4);
        del.set(B, A, "Alice");
        assertEquals(4, picks.available(A, ALL, ALL));
        assertEquals(0, picks.available(A, ALL, NONE));
        assertTrue(picks.delegatedBreakdown(A, NONE).isEmpty());
    }

    @Test
    void delegatedBreakdownListsOnlyOwnersWithPicksInRegistrationOrder() {
        picks.setPicks(B, 2);
        picks.setPicks(C, 0);
        del.set(B, A, "Alice");
        del.set(C, A, "Alice");
        Map<UUID, Integer> breakdown = picks.delegatedBreakdown(A, ALL);
        assertEquals(List.of(B), List.copyOf(breakdown.keySet()));
        assertEquals(2, breakdown.get(B));
    }

    // ---- peekOriginFor ----

    @Test
    void peekPrefersOwnPicksThenHeldPicksInOrder() {
        picks.setPicks(A, 1);
        picks.setPicks(B, 1);
        picks.setPicks(C, 1);
        del.set(B, A, "Alice");
        del.set(C, A, "Alice");
        UnitPicks.PickChoice c = picks.peekOriginFor(A, ALL, ALL, ALL);
        assertEquals(UnitPicks.PickChoice.Status.OK, c.status());
        assertEquals(A, c.originOwner());
        picks.consumeOne(A);
        c = picks.peekOriginFor(A, ALL, ALL, ALL);
        assertEquals(B, c.originOwner()); // 預かった順
        picks.consumeOne(B);
        assertEquals(C, picks.peekOriginFor(A, ALL, ALL, ALL).originOwner());
    }

    @Test
    void peekFallsBackToHeldPicksWhenOwnSlotsAreFull() {
        picks.setPicks(A, 2);
        picks.setPicks(B, 1);
        del.set(B, A, "Alice");
        UnitPicks.PickChoice c = picks.peekOriginFor(A, ALL, ALL, id -> !id.equals(A));
        assertEquals(UnitPicks.PickChoice.Status.OK, c.status());
        assertEquals(B, c.originOwner());
    }

    @Test
    void peekReportsFullWhenEverySlotIsFullAndNoPicksWhenNothingIsLeft() {
        picks.setPicks(A, 1);
        UnitPicks.PickChoice full = picks.peekOriginFor(A, ALL, ALL, NONE);
        assertEquals(UnitPicks.PickChoice.Status.FULL, full.status());
        assertNull(full.originOwner());

        picks.setPicks(A, 0);
        UnitPicks.PickChoice none = picks.peekOriginFor(A, ALL, ALL, ALL);
        assertEquals(UnitPicks.PickChoice.Status.NO_PICKS, none.status());
        assertNull(none.originOwner());
    }

    @Test
    void peekDoesNotChangeAnything() {
        picks.setPicks(A, 2);
        synced.clear();
        picks.peekOriginFor(A, ALL, ALL, ALL);
        assertEquals(2, picks.ownPicks(A));
        assertTrue(synced.isEmpty());
    }

    @Test
    void delegatedOwnerCannotUseOwnPicksEvenWithRoom() {
        picks.setPicks(A, 2);
        del.set(A, B, "Bob");
        assertEquals(UnitPicks.PickChoice.Status.NO_PICKS, picks.peekOriginFor(A, ALL, ALL, ALL).status());
        UnitPicks.PickChoice held = picks.peekOriginFor(B, ALL, ALL, ALL);
        assertEquals(UnitPicks.PickChoice.Status.OK, held.status());
        assertEquals(A, held.originOwner()); // B は A の分を預かって使う（A の枠に出る）
    }

    // ---- setPicks / consumeOne / syncHook ----

    @Test
    void setPicksClampsAtZeroAndSyncsTheOwnerAndTheCommander() {
        picks.setPicks(A, 3);
        assertEquals(3, picks.ownPicks(A));
        assertEquals(3, picks.picks(A));
        assertEquals(List.of(A), synced);

        del.set(A, B, "Bob");
        synced.clear();
        picks.setPicks(A, -5);
        assertEquals(0, picks.ownPicks(A));
        assertEquals(List.of(A, B), synced); // 本人と指揮者
    }

    @Test
    void consumeOneUsesTheOriginOwnersPicksAndSyncsBoth() {
        picks.setPicks(B, 2);
        del.set(B, A, "Alice");
        synced.clear();
        picks.consumeOne(B);
        assertEquals(1, picks.ownPicks(B));
        assertEquals(List.of(B, A), synced);
    }

    @Test
    void syncPlayersSkipsNullsAndDuplicates() {
        picks.syncPlayers(A, null, A, B, null, B);
        assertEquals(List.of(A, B), synced);
    }

    @Test
    void addPicksNeverGoesBelowZero() {
        picks.addPicks(A, 2);
        picks.addPicks(A, -5);
        assertEquals(0, picks.ownPicks(A));
        picks.addPicks(A, 4);
        assertEquals(4, picks.ownPicks(A));
    }

    // ---- 指揮官の死亡（BotManager.handleCommanderDeath） ----

    private static final UUID A2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    /** handleCommanderDeath の呼び出しを記録する道具立て。 */
    private final List<Runnable> deferred = new ArrayList<>();
    private final List<UUID> killCalls = new ArrayList<>();
    private final List<Map.Entry<UUID, Integer>> notices = new ArrayList<>();

    private void commanderDies(UUID commander, Map<UUID, Integer> killedByOwner, Predicate<UUID> eligible) {
        BotManager.handleCommanderDeath(del, picks, commander,
                owner -> {
                    killCalls.add(owner);
                    return killedByOwner.getOrDefault(owner, 0);
                },
                eligible, deferred::add, (owner, n) -> notices.add(Map.entry(owner, n)));
    }

    private void runDeferred() {
        for (Runnable r : List.copyOf(deferred)) {
            r.run();
        }
        deferred.clear();
    }

    @Test
    void commanderDeathKillsHeldBotsAndAddsBackTheKilledCountNextTick() {
        picks.setPicks(A, 2);
        picks.setPicks(B, 0);
        del.set(A, B, "Bob");
        synced.clear();
        commanderDies(B, Map.of(A, 3), ALL);
        // 1 tick 待つ前に、委任の解除と預かり分の紙の更新は済んでいる
        assertTrue(!del.isDelegated(A));
        assertTrue(synced.contains(A) && synced.contains(B));
        assertEquals(0, picks.available(B, ALL, ALL));
        assertEquals(2, picks.ownPicks(A)); // まだ加算されない
        runDeferred();
        assertEquals(5, picks.ownPicks(A));
        assertEquals(5, picks.available(A, ALL, ALL));
        assertEquals(List.of(Map.entry(A, 3)), notices);
    }

    @Test
    void commanderDeathWithNoAliveBotsKeepsPicksAndStillNotifies() {
        picks.setPicks(A, 2);
        del.set(A, B, "Bob");
        synced.clear();
        commanderDies(B, Map.of(), ALL);
        assertTrue(synced.contains(A) && synced.contains(B));
        runDeferred();
        assertEquals(2, picks.ownPicks(A));
        assertEquals(List.of(Map.entry(A, 0)), notices);
    }

    @Test
    void simultaneousDeathBThenAKillsBotsButAddsNothingAndNotifiesNobody() {
        picks.setPicks(A, 2);
        del.set(A, B, "Bob");
        commanderDies(B, Map.of(A, 3), NONE); // A も同じ tick に倒れている
        assertEquals(List.of(A), killCalls);
        runDeferred();
        assertEquals(2, picks.ownPicks(A));
        assertTrue(notices.isEmpty());
    }

    @Test
    void simultaneousDeathAThenBDoesNothingAtAll() {
        picks.setPicks(A, 2);
        del.set(A, B, "Bob");
        del.clear(A); // A の死亡処理が先に委任を解除している
        commanderDies(B, Map.of(A, 3), ALL);
        assertTrue(killCalls.isEmpty());
        assertTrue(deferred.isEmpty());
        assertTrue(notices.isEmpty());
        assertEquals(2, picks.ownPicks(A));
    }

    @Test
    void withSeveralOwnersOnlyTheEligibleOneGetsPicksAndNotice() {
        picks.setPicks(A, 1);
        picks.setPicks(A2, 1);
        del.set(A, B, "Bob");
        del.set(A2, B, "Bob");
        commanderDies(B, Map.of(A, 2, A2, 4), id -> id.equals(A)); // A2 は同時死亡
        assertEquals(List.of(A, A2), killCalls);
        runDeferred();
        assertEquals(3, picks.ownPicks(A));
        assertEquals(1, picks.ownPicks(A2));
        assertEquals(List.of(Map.entry(A, 2)), notices);
    }

    @Test
    void onlyActuallyKilledBotsAreAddedBack() {
        picks.setPicks(A, 0);
        del.set(A, B, "Bob");
        commanderDies(B, Map.of(A, 1), ALL); // 3 体のうち 1 体だけ倒れた（他はイベントのキャンセルなどで生存）
        runDeferred();
        assertEquals(1, picks.ownPicks(A));
        assertEquals(List.of(Map.entry(A, 1)), notices);
    }

    // ---- 保存則 ----

    /**
     * 委任・付け替え・解除・死亡リセット・消費・増減・設定をランダムに繰り返しても、どの持ち主の回数も
     * ちょうど 1 人の指揮者の available に入る（二重にも欠けにもならない。相互・多段の委任でも）。
     */
    @Test
    void everyOwnersPicksCountForExactlyOneCommanderUnderRandomOperations() {
        UUID[] ids = {A, B, C, UUID.fromString("00000000-0000-0000-0000-00000000000d"),
                UUID.fromString("00000000-0000-0000-0000-00000000000e")};
        for (long seed = 1; seed <= 40; seed++) {
            Random rnd = new Random(seed);
            Delegations d = new Delegations();
            UnitPicks p = new UnitPicks(d, id -> { });
            for (int step = 0; step < 400; step++) {
                UUID x = ids[rnd.nextInt(ids.length)];
                UUID y = ids[rnd.nextInt(ids.length)];
                switch (rnd.nextInt(7)) {
                    case 0 -> d.set(x, y, "n"); // 委任・付け替え（自分指定は解除）
                    case 1 -> d.clear(x); // 解除
                    case 2 -> { // 死亡リセット: 指揮者として戻し、持ち主としても解除（回数は消えない）
                        d.clearByCommander(x);
                        d.clear(x);
                    }
                    case 3 -> {
                        UnitPicks.PickChoice c = p.peekOriginFor(x, ALL, ALL, id -> rnd.nextBoolean());
                        if (c.status() == UnitPicks.PickChoice.Status.OK) {
                            assertTrue(p.ownPicks(c.originOwner()) > 0);
                            p.consumeOne(c.originOwner());
                        }
                    }
                    case 4 -> p.addPicks(x, rnd.nextInt(7) - 3);
                    case 5 -> p.setPicks(x, rnd.nextInt(6) - 1);
                    default -> p.addPicks(x, 1 + rnd.nextInt(3)); // 加算を多めにして消費の機会を作る
                }
                long total = 0;
                long sumAvailable = 0;
                for (UUID id : ids) {
                    total += p.ownPicks(id);
                    sumAvailable += p.available(id, ALL, ALL);
                }
                assertEquals(total, sumAvailable, "seed=" + seed + " step=" + step);
            }
        }
    }

    /** 加算・設定・消費の帳尻を、default 分も含めて別に数える版（期待値は操作の戻り値だけから作る）。 */
    @Test
    void picksTotalMatchesInitialPlusChangesMinusConsumed() {
        UUID[] ids = {A, B, C};
        for (long seed = 100; seed < 130; seed++) {
            Random rnd = new Random(seed);
            Delegations d = new Delegations();
            UnitPicks p = new UnitPicks(d, id -> { });
            long expected = 0;
            for (int step = 0; step < 300; step++) {
                UUID x = ids[rnd.nextInt(ids.length)];
                UUID y = ids[rnd.nextInt(ids.length)];
                switch (rnd.nextInt(7)) {
                    case 0 -> d.set(x, y, "n");
                    case 1 -> d.clear(x);
                    case 2 -> {
                        d.clearByCommander(x);
                        d.clear(x);
                    }
                    case 3 -> {
                        UnitPicks.PickChoice c = p.peekOriginFor(x, ALL, ALL, ALL);
                        if (c.status() == UnitPicks.PickChoice.Status.OK) {
                            p.consumeOne(c.originOwner());
                            expected--;
                        }
                    }
                    case 4 -> {
                        int before = p.ownPicks(x);
                        p.addPicks(x, rnd.nextInt(9) - 4);
                        expected += p.ownPicks(x) - before;
                    }
                    case 5 -> {
                        int before = p.ownPicks(x);
                        p.setPicks(x, rnd.nextInt(6) - 1);
                        expected += p.ownPicks(x) - before;
                    }
                    default -> {
                        int before = p.ownPicks(x);
                        p.addPicks(x, 2);
                        expected += p.ownPicks(x) - before;
                    }
                }
                long total = 0;
                for (UUID id : ids) {
                    total += p.ownPicks(id);
                }
                assertEquals(expected, total, "seed=" + seed + " step=" + step);
            }
        }
    }

    // ---- 持ち主が倒れても委任した兵は残る・その兵では回数を戻さない ----

    @Test
    void refundableOnlyWhenOwnerHasNotDiedSinceSpawn() {
        assertTrue(BotManager.refundable(true, 0, 0));
        assertTrue(BotManager.refundable(true, 3, 3));
        assertTrue(!BotManager.refundable(true, 0, 1));
        assertTrue(!BotManager.refundable(true, 2, 5));
        assertTrue(!BotManager.refundable(false, 0, 0)); // /sb revive で出た兵（回数を使っていない）は戻さない
    }

    /**
     * 不具合の再現: A が B に委任したまま倒れ、次の命で回数をもらう。持ち主が倒れたら回数の譲渡は終わる
     * （{@link BotManager#onOwnerDeath} が譲渡を解除する）ので、B は A の新しい回数を使えない。
     * 兵は B が預かり続け（custodian）、B が倒れても、その兵の分の回数は戻らない。
     */
    @Test
    void ownersNextLifePicksAreNotUsableByThePreviousDelegate() {
        int[] life = {0};
        List<Integer> custodial = new ArrayList<>(); // A が倒れた後も B が預かっている兵（出したときの life）
        List<Integer> delegated = new ArrayList<>(); // 今の譲渡で B が指揮している A の兵
        picks.setPicks(A, 3);
        for (int i = 0; i < 3; i++) {
            picks.consumeOne(A);
            delegated.add(life[0]);
        }
        del.set(A, B, "Bob");
        // A が倒れる: 譲渡は解除、兵は B が預かる、回数は 0
        life[0]++;
        assertEquals(B, BotManager.endDelegationOnDeath(del, A).commander());
        custodial.addAll(delegated);
        delegated.clear();
        picks.setPicks(A, 0);
        // 次の命で 2 回もらう: B は使えず、A は使える
        picks.setPicks(A, 2);
        assertEquals(0, picks.available(B, ALL, ALL));
        assertEquals(2, picks.available(A, ALL, ALL));
        assertEquals(UnitPicks.PickChoice.Status.NO_PICKS,
                picks.peekOriginFor(B, ALL, ALL, o -> true).status());
        // A がこの命でもう一度譲れば、そのときから B が使える（A の枠。上限 5 に 3 体の預かり兵も数える）
        del.set(A, B, "Bob");
        for (int i = 0; i < 2; i++) {
            UnitPicks.PickChoice c = picks.peekOriginFor(B, ALL, ALL,
                    o -> custodial.size() + delegated.size() < 5);
            assertEquals(UnitPicks.PickChoice.Status.OK, c.status());
            picks.consumeOne(c.originOwner());
            delegated.add(life[0]);
        }
        assertEquals(UnitPicks.PickChoice.Status.NO_PICKS,
                picks.peekOriginFor(B, ALL, ALL, o -> true).status());
        // B が倒れる: 預かり兵（前の命）は戻さず、この命で呼んだ 2 体の分だけ戻る
        custodial.clear();
        BotManager.handleCommanderDeath(del, picks, B, owner -> {
            int refund = 0;
            for (int l : delegated) {
                if (BotManager.refundable(true, l, life[0])) {
                    refund++;
                }
            }
            delegated.clear();
            return refund;
        }, ALL, deferred::add, (owner, n) -> notices.add(Map.entry(owner, n)));
        runDeferred();
        assertEquals(2, picks.ownPicks(A));
        assertEquals(List.of(Map.entry(A, 2)), notices);
    }

    /**
     * 「回数をもらう・呼ぶ・/sb revive で出す・譲渡する・持ち主が倒れる・指揮官が倒れる・取り戻す」を乱数で繰り返しても、
     * 兵は上限を超えず、回数は「今の命でもらった回数」を超えず、前の譲渡先は持ち主がこの命で譲るまで回数を使えない。
     * 持ち主が倒れたときの扱いは {@link BotManager#onOwnerDeath} と同じ（譲渡を解除し、譲渡中の兵は預かり兵にする）。
     */
    @Test
    void dyingWhileDelegatedNeverGrowsBotsOrPicks() {
        final int max = 30;
        for (long seed = 0; seed < 200; seed++) {
            Random rnd = new Random(seed);
            Delegations d = new Delegations();
            UnitPicks p = new UnitPicks(d, id -> { });
            int[] life = {0};
            List<Bot> own = new ArrayList<>();       // A が指揮している兵（譲渡中は B が指揮）
            List<Bot> custodial = new ArrayList<>(); // A が倒れた後も B が預かっている兵
            int grantedThisLife = 0;
            int maxGrantPerLife = 0; // これまでの命で、1 つの命にもらった回数の最大
            List<Runnable> later = new ArrayList<>();
            for (int step = 0; step < 300; step++) {
                switch (rnd.nextInt(7)) {
                    case 0 -> { // コマンドブロックで回数をもらう
                        int g = rnd.nextInt(4);
                        p.addPicks(A, g);
                        grantedThisLife += g;
                        maxGrantPerLife = Math.max(maxGrantPerLife, grantedThisLife);
                    }
                    case 1 -> { // 呼ぶ（譲渡中なら B が A の回数で呼ぶ。上限は A の兵の合計）
                        UUID caller = d.isDelegated(A) ? B : A;
                        int survivors = survivors(own, life[0]) + survivors(custodial, life[0]);
                        UnitPicks.PickChoice c = p.peekOriginFor(caller, ALL, ALL,
                                o -> own.size() + custodial.size() < max
                                        && BotManager.canUsePick(survivors, p.ownPicks(o)));
                        if (c.status() == UnitPicks.PickChoice.Status.OK) {
                            p.consumeOne(c.originOwner());
                            own.add(new Bot(life[0], true));
                        }
                    }
                    case 2 -> { // 譲る: 預かり兵もまとめて新しい譲渡に入る
                        d.set(A, B, "Bob");
                        own.addAll(custodial);
                        custodial.clear();
                    }
                    case 3 -> { // A が倒れる
                        life[0]++;
                        if (BotManager.endDelegationOnDeath(d, A) != null) {
                            custodial.addAll(own); // 譲渡中の兵は B が預かり続ける
                        }
                        own.clear(); // 譲渡していない兵は倒れる
                        p.setPicks(A, 0);
                        grantedThisLife = 0;
                    }
                    case 4 -> { // B が倒れる: 預かり兵は戻さずに倒れ、譲渡中の兵は戻してよい分だけ次の tick に戻る
                        custodial.clear();
                        BotManager.handleCommanderDeath(d, p, B, owner -> {
                            int refund = 0;
                            for (Bot bot : own) {
                                if (BotManager.refundable(bot.paid(), bot.life(), life[0])) {
                                    refund++;
                                }
                            }
                            own.clear();
                            return refund;
                        }, ALL, later::add, (o, n) -> { });
                        later.forEach(Runnable::run);
                        later.clear();
                    }
                    case 5 -> { // /sb revive: 回数を使わずに 1 体出す（上限まで。何度でも押せる）
                        if (own.size() + custodial.size() < max) {
                            own.add(new Bot(life[0], false));
                        }
                    }
                    default -> { // 自分に戻す
                        d.clear(A);
                        own.addAll(custodial);
                        custodial.clear();
                    }
                }
                String at = "seed=" + seed + " step=" + step;
                assertTrue(own.size() + custodial.size() <= max, at);
                int paidAlive = paid(own) + paid(custodial);
                assertTrue(paidAlive <= maxGrantPerLife,
                        at + " 回数で呼んだ兵=" + paidAlive + " 1 つの命の回数の最大=" + maxGrantPerLife);
                assertTrue(p.ownPicks(A) <= grantedThisLife,
                        at + " picks=" + p.ownPicks(A) + " granted=" + grantedThisLife);
                if (!d.isDelegated(A)) {
                    assertEquals(0, p.available(B, ALL, ALL), at + "（譲渡していないのに B が A の回数を使える）");
                }
            }
        }
    }

    /**
     * 報告された不具合: 「譲渡してから倒れる」を繰り返すと兵が増え続けた（上限 30、1 つの命でもらう回数 3）。
     * 前の命の生き残りが次の命の回数を先に使うので、持ち主の兵は 3 体より増えない。
     */
    @Test
    void delegateThenDieLoopDoesNotGrowTheArmy() {
        final int max = 30;
        Delegations d = new Delegations();
        UnitPicks p = new UnitPicks(d, id -> { });
        int life = 0;
        List<Bot> bots = new ArrayList<>(); // A の生きている兵（倒れない）
        for (int round = 0; round < 20; round++) {
            p.setPicks(A, 3); // 役職に入るたびに 3 回
            UUID caller = round % 2 == 0 ? A : B; // 自分で呼ぶ命と、先に譲って B に呼ばせる命を交互に
            if (caller == B) {
                d.set(A, B, "Bob");
            }
            while (true) {
                int survivors = survivors(bots, life);
                UnitPicks.PickChoice c = p.peekOriginFor(caller, ALL, ALL,
                        o -> bots.size() < max && BotManager.canUsePick(survivors, p.ownPicks(o)));
                if (c.status() != UnitPicks.PickChoice.Status.OK) {
                    break;
                }
                p.consumeOne(c.originOwner());
                bots.add(new Bot(life, true));
            }
            d.set(A, B, "Bob"); // 譲ってから
            life++;             // 倒れる（兵は B が預かって残る）
            BotManager.endDelegationOnDeath(d, A);
            p.setPicks(A, 0);
            assertTrue(bots.size() <= 3, "round=" + round + " bots=" + bots.size());
        }
        assertEquals(3, bots.size());
    }

    private static int survivors(List<Bot> list, int lifeNow) {
        int n = 0;
        for (Bot b : list) {
            if (b.life() < lifeNow) {
                n++;
            }
        }
        return n;
    }

    private static int paid(List<Bot> list) {
        int n = 0;
        for (Bot b : list) {
            if (b.paid()) {
                n++;
            }
        }
        return n;
    }

    /** シミュレーションの兵 1 体: 出したときの持ち主の life と、回数を使って呼んだか。 */
    private record Bot(int life, boolean paid) {
    }
}
