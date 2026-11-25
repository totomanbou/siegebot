package siegebot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;

/**
 * 陣営（攻め・守り）の判定と、敵・味方の一覧。スコアボードのチームと config.yml の teams を突き合わせる。
 * ボットの一覧（{@link BotManager}）は {@link SiegeBotPlugin#bots()} 経由で引くので、生成の順序は問わない。
 * メインスレッドからだけ呼ぶ。
 */
public final class Teams {

    private final SiegeBotPlugin plugin;

    public Teams(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    /** プレイヤーのスコアボードのチームから陣営を出す。どちらでもなければ null。 */
    public Side sideOf(Player p) {
        return sideOfTeam(Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(p.getName()));
    }

    /** エンティティ（プレイヤーかボット）の陣営。どちらでもなければ null。 */
    public Side sideOf(Entity e) {
        SoldierBot b = plugin.bots().byEntity(e);
        if (b != null) {
            return b.side;
        }
        if (e instanceof Player p) {
            return sideOf(p);
        }
        // モブはスコアボードのチームに UUID で入る（/team join <チーム> <セレクタ>）
        Team t = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(e.getUniqueId().toString());
        return sideOfTeam(t);
    }

    /**
     * スコアボードのチームが、どちらの陣営のチームか（config.yml の teams と、大文字・小文字を区別せずに比べる）。
     * 陣営の判定はすべてここを通す。
     */
    private Side sideOfTeam(Team t) {
        if (t == null) {
            return null;
        }
        for (Side s : Side.values()) {
            if (t.getName().equalsIgnoreCase(plugin.settings(s).teamName(s))) {
                return s;
            }
        }
        return null;
    }

    /** その陣営のスコアボードのチーム（名前は大文字・小文字を区別しない）。なければ null。 */
    private Team teamOf(Side side) {
        for (Team t : Bukkit.getScoreboardManager().getMainScoreboard().getTeams()) {
            if (sideOfTeam(t) == side) {
                return t;
            }
        }
        return null;
    }

    /** その陣営のスコアボードのチームの色。チームがない、または色が未設定（reset）なら白。 */
    public TextColor colorOf(Side side) {
        return colorOf(teamOf(side));
    }

    /** スコアボードのチームの色。null または色が未設定（reset）なら白（未設定で team.color() を呼ぶと例外が出る）。 */
    static TextColor colorOf(Team team) {
        return team != null && team.hasColor() ? team.color() : NamedTextColor.WHITE;
    }

    /** その陣営のチームに入っているモブ（プレイヤーとボットと防具立てを除く、生きている読み込み済みのもの）。 */
    private List<LivingEntity> mobs(Side side) {
        List<LivingEntity> r = new ArrayList<>();
        Team t = teamOf(side);
        if (t == null) {
            return r;
        }
        for (String entry : t.getEntries()) {
            UUID id;
            try {
                id = UUID.fromString(entry);
            } catch (IllegalArgumentException notUuid) {
                continue; // プレイヤー名の登録
            }
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity le && !(e instanceof Player) && !(e instanceof ArmorStand)
                    && plugin.bots().byEntity(e) == null && le.isValid() && !le.isDead()) {
                r.add(le);
            }
        }
        return r;
    }

    /** その陣営のオンラインのプレイヤー（ボットは含まない）。 */
    public List<Player> humans(Side side) {
        List<Player> r = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (sideOf(p) == side) {
                r.add(p);
            }
        }
        return r;
    }

    /** 敵: 敵の陣営のプレイヤー・ボットと、敵のチームに入っているモブ。 */
    List<LivingEntity> enemies(Side side) {
        List<LivingEntity> r = new ArrayList<>(humans(side.opposite()));
        for (SoldierBot b : plugin.bots().of(side.opposite())) {
            if (b.npc.isSpawned() && b.npc.getEntity() instanceof LivingEntity le) {
                r.add(le);
            }
        }
        r.addAll(mobs(side.opposite()));
        return r;
    }
}
