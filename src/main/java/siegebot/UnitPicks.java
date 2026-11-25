package siegebot;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 兵科選択で兵を呼べる残りの回数（持ち主ごと）。コマンドブロックの /sb picks で決める。サーバーを止めると消える。
 * メインスレッドからだけ呼ぶ。
 *
 * <p>回数は持ち主ごとに 1 つだけ持つ（{@link #picks}）。指揮権を譲っているときに誰がその回数を使えるかは
 * {@link Delegations} から導く（別の表に写さない）。
 */
public final class UnitPicks {

    /** {@link #peekOriginFor} の結果: どの持ち主の回数・枠で呼ぶか。 */
    public record PickChoice(Status status, UUID originOwner) {
        public enum Status {
            /** 呼べる（originOwner の回数を使い、originOwner の枠に出す） */
            OK,
            /** 使える回数がない */
            NO_PICKS,
            /** 回数はあるが、どの持ち主の枠も上限に届いている */
            FULL
        }
    }

    private final SiegeBotPlugin plugin;
    private final Delegations delegations;
    private final Consumer<UUID> syncHook;
    /** 持ち主 → 兵科選択で呼べる残りの回数。 */
    private final Map<UUID, Integer> picks = new HashMap<>();

    /** 本番用。回数が変わった人がオンラインなら、兵科選択のアイテムを回数に合わせる。 */
    public UnitPicks(SiegeBotPlugin plugin) {
        this(plugin, plugin.delegations(), id -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null && plugin.unitItem() != null) {
                plugin.unitItem().sync(p);
            }
        });
    }

    /** テスト用（Bukkit なし）。syncHook は回数が変わった人ごとに呼ばれる。 */
    UnitPicks(Delegations delegations, Consumer<UUID> syncHook) {
        this(null, delegations, syncHook);
    }

    private UnitPicks(SiegeBotPlugin plugin, Delegations delegations, Consumer<UUID> syncHook) {
        this.plugin = plugin;
        this.delegations = delegations;
        this.syncHook = syncHook;
    }

    /** その持ち主の自分の残りの回数（なければ 0）。譲渡中でも自分の分はここに残る。 */
    public int ownPicks(UUID player) {
        return picks.getOrDefault(player, 0);
    }

    /** {@link #ownPicks} と同じ。 */
    public int picks(UUID player) {
        return picks.getOrDefault(player, 0);
    }

    /**
     * その持ち主の残りの回数を n にする（0 以下は 0）。本人と、譲渡中ならその指揮者の兵科選択のアイテムを合わせ直す。
     */
    public void setPicks(UUID player, int n) {
        if (n <= 0) {
            picks.remove(player);
        } else {
            picks.put(player, n);
        }
        Delegations.Entry e = delegations.entryOf(player);
        syncPlayers(player, e != null ? e.commander() : null);
    }

    /** 残りの回数に delta を足す（0 未満にはならない）。 */
    public void addPicks(UUID player, int delta) {
        setPicks(player, Math.max(0, ownPicks(player) + delta));
    }

    /** 渡した人（null と重複を除く）の兵科選択のアイテムを合わせ直す。 */
    public void syncPlayers(UUID... ids) {
        Set<UUID> unique = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id != null) {
                unique.add(id);
            }
        }
        for (UUID id : unique) {
            syncHook.accept(id);
        }
    }

    /**
     * commander が今使える回数: 自分の分（譲渡中でなければ。譲渡先が無効なら自分の分に戻る）と、
     * 有効な持ち主から預かっている分の合計。
     *
     * @param validTarget 譲渡先として有効か（{@link Delegations#commanderOf} と同じ判定）
     * @param validOwner  預かっている持ち主として有効か
     */
    public int available(UUID commander, Predicate<UUID> validTarget, Predicate<UUID> validOwner) {
        int total = delegations.commanderOf(commander, validTarget).equals(commander) ? ownPicks(commander) : 0;
        for (UUID o : delegations.ownersOf(commander)) {
            if (validOwner.test(o)) {
                total += ownPicks(o);
            }
        }
        return total;
    }

    /** 有効な持ち主ごとの、預かっている回数（0 より大きいものだけ。登録順）。 */
    public Map<UUID, Integer> delegatedBreakdown(UUID commander, Predicate<UUID> validOwner) {
        Map<UUID, Integer> r = new LinkedHashMap<>();
        for (UUID o : delegations.ownersOf(commander)) {
            if (validOwner.test(o) && ownPicks(o) > 0) {
                r.put(o, ownPicks(o));
            }
        }
        return r;
    }

    /**
     * commander が 1 体呼ぶとき、どの持ち主の回数・枠を使うか決める（何も変えない）。自分の分を先に、
     * 次に預かっている分を預かった順に使う。hasRoom はその持ち主の枠（max-per-player）に空きがあるか。
     */
    public PickChoice peekOriginFor(UUID commander, Predicate<UUID> validTarget, Predicate<UUID> validOwner,
            Predicate<UUID> hasRoom) {
        if (available(commander, validTarget, validOwner) <= 0) {
            return new PickChoice(PickChoice.Status.NO_PICKS, null);
        }
        if (delegations.commanderOf(commander, validTarget).equals(commander) && ownPicks(commander) > 0
                && hasRoom.test(commander)) {
            return new PickChoice(PickChoice.Status.OK, commander);
        }
        for (UUID o : delegations.ownersOf(commander)) {
            if (validOwner.test(o) && ownPicks(o) > 0 && hasRoom.test(o)) {
                return new PickChoice(PickChoice.Status.OK, o);
            }
        }
        return new PickChoice(PickChoice.Status.FULL, null);
    }

    /** originOwner の回数を 1 使う。必ず兵を出せた後にだけ呼ぶ。 */
    public void consumeOne(UUID originOwner) {
        setPicks(originOwner, ownPicks(originOwner) - 1);
    }

    // ---- 本番用（Bukkit のプレイヤーとチームから有効性を決める） ----

    /** オンラインで、そのチームにいる人だけを有効とする。 */
    private Predicate<UUID> sameSideOnline(Side side) {
        return id -> {
            Player x = Bukkit.getPlayer(id);
            return x != null && x.isOnline() && plugin.teams().sideOf(x) == side;
        };
    }

    /** p が今使える回数。チームに入っていなければ自分の分だけ。 */
    public int available(Player p) {
        Side s = plugin.teams().sideOf(p);
        if (s == null) {
            return ownPicks(p.getUniqueId());
        }
        Predicate<UUID> valid = sameSideOnline(s);
        return available(p.getUniqueId(), valid, valid);
    }

    /** p が預かっている回数の持ち主ごとの内訳。 */
    public Map<UUID, Integer> delegatedBreakdown(Player p, Side side) {
        if (side == null) {
            return new LinkedHashMap<>();
        }
        return delegatedBreakdown(p.getUniqueId(), sameSideOnline(side));
    }

    /** p が 1 体呼ぶときに使う持ち主を決める（何も変えない）。 */
    public PickChoice peekOriginFor(Player p, Side side, Predicate<UUID> hasRoom) {
        if (side == null) {
            return new PickChoice(PickChoice.Status.NO_PICKS, null);
        }
        Predicate<UUID> valid = sameSideOnline(side);
        return peekOriginFor(p.getUniqueId(), valid, valid, hasRoom);
    }
}
