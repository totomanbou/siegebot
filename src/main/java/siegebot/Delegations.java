package siegebot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 指揮権の譲渡（持ち主 → 譲渡先）の一覧。持ち主ごとに直接の 1 ホップだけを覚える（A → B → C のように連鎖しない）。
 * 兵の指揮者も呼べる回数の持ち主も、ここから導く（別の表に写さない）。Bukkit に依存しない。メインスレッドからだけ呼ぶ。
 */
public final class Delegations {

    /** 譲渡先と、そのときの名前（追従の相手の名前を戻すのに使う）。 */
    public record Entry(UUID commander, String commanderName) {}

    /** 持ち主 → 譲渡先（登録順）。 */
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    /**
     * owner の指揮権を commander に譲る（前の譲渡先があれば置き換える）。
     * null や自分自身への譲渡は解除として扱う。
     *
     * @return 直前の譲渡先（なければ null）
     */
    public Entry set(UUID owner, UUID commander, String commanderName) {
        if (owner == null || commander == null || owner.equals(commander)) {
            return clear(owner);
        }
        return entries.put(owner, new Entry(commander, commanderName));
    }

    /** owner の譲渡を解除する。 @return 解除前の譲渡先（なければ null） */
    public Entry clear(UUID owner) {
        return owner == null ? null : entries.remove(owner);
    }

    /** commander に譲られている分をすべて解除する。 @return 解除した持ち主 → 譲渡先（登録順） */
    public Map<UUID, Entry> clearByCommander(UUID commander) {
        Map<UUID, Entry> removed = new LinkedHashMap<>();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (e.getValue().commander().equals(commander)) {
                removed.put(e.getKey(), e.getValue());
            }
        }
        for (UUID owner : removed.keySet()) {
            entries.remove(owner);
        }
        return removed;
    }

    /** owner の譲渡先（譲渡していなければ null）。 */
    public Entry entryOf(UUID owner) {
        return entries.get(owner);
    }

    /** owner が誰かに譲渡中か。 */
    public boolean isDelegated(UUID owner) {
        return entries.containsKey(owner);
    }

    /**
     * owner の兵を今指揮する人。譲渡先が validCommander を満たすときはその人、なければ（譲渡していない・
     * 譲渡先が無効）owner 自身。
     */
    public UUID commanderOf(UUID owner, Predicate<UUID> validCommander) {
        Entry e = entries.get(owner);
        return e != null && validCommander.test(e.commander()) ? e.commander() : owner;
    }

    /** {@link #commanderOf(UUID, Predicate)} で譲渡先を常に有効とみなしたもの。 */
    public UUID commanderOf(UUID owner) {
        return commanderOf(owner, id -> true);
    }

    /** commander に直接譲っている持ち主（登録順）。譲り先の先の連鎖はたどらない。 */
    public List<UUID> ownersOf(UUID commander) {
        List<UUID> r = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (e.getValue().commander().equals(commander)) {
                r.add(e.getKey());
            }
        }
        return r;
    }

    /** 今の譲渡の一覧の写し（登録順）。回しながら変えてよい。 */
    public Map<UUID, Entry> snapshot() {
        return new LinkedHashMap<>(entries);
    }
}
