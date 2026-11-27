package siegebot;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

/** 兵士ボット 1 体の状態。動きの計算は {@link BotManager} が行う。 */
public final class SoldierBot {

    final String name;
    final Side side;
    final NPC npc;
    /** 持ち主のプレイヤー（専用のボット）。管理者が出したボットは null。 */
    final UUID owner;
    /**
     * 出したときの、持ち主が倒れた回数（{@link BotManager#ownerLife}）。今の値と違えば、持ち主の死を越えて生き残った兵
     * （指揮官が倒れて道連れになっても回数を戻さない）。
     */
    int ownerLife;
    /**
     * 持ち主が譲渡中に倒れたとき、この兵を預かり続ける人（譲渡先）。null なら持ち主の譲渡（{@link Delegations}）に従う。
     * 持ち主の譲渡は倒れた時点で解除するので、次の命の回数は預かり手に渡らない（兵だけが残る）。
     */
    UUID custodian;
    /** 兵科選択の回数を使って呼んだ兵か（/sb revive で出た兵は false）。回数に戻してよいのはこの兵だけ。 */
    boolean paidWithPick;
    /** {@link #custodian} の名前（追従の相手を戻すのに使う）。 */
    String custodianName;
    /** 兵種（出したときに決まり、変わらない）。 */
    final UnitType type;

    /** 今の方針。出すときに {@link BotManager} が決める（持ち主への追従か、出した地点を守る）。 */
    Order order;
    LivingEntity target;
    /** 最後に狙いを選び直した時刻（サーバーの tick）。 */
    long lastRetargetTick;
    /** 動けなくなったかの見張り: 最後に 1 ブロック以上動いたと確かめた位置と時刻。 */
    Location stuckPos;
    long stuckTick;
    /** 詰まりの見張り（{@link NavigationWatch}）: 直前の歩きの種類。 */
    NavigationWatch.Kind stuckKind;
    /** 見張り: 敵を追っているときの追跡対象。 */
    UUID stuckTargetId;
    /** 見張り: 敵を追っていないときの目標の位置。 */
    Location stuckGoalPos;
    /** 見張り: 目標までの最短の水平距離。 */
    double stuckBestGoalDist = Double.POSITIVE_INFINITY;
    /** 見張り: 同じ追跡対象で進まないまま続けて詰まった回数。 */
    int consecutiveChaseStucks;
    /** 見張り: 到達できないとみなした敵（UUID → 狙いから外す期限の tick）。 */
    final Map<UUID, Long> unreachableTargets = new HashMap<>();
    /** 見張り: 敵ごとの続けて到達不能になった回数（外す時間を 40 → 80 → 160 tick と延ばす）。 */
    final Map<UUID, Integer> unreachableStrikes = new HashMap<>();
    /** 見張り: 横隊の直線寄せで詰まったあと、ふつうの経路探索で歩く期限（tick）。 */
    long fineBypassUntilTick;
    /** 後退を始めた時刻（tick）。0 なら始めていない。 */
    long backoffStartTick;
    /** 後退を打ち切ったあと、この時刻（tick）まで再び後退を始めない。 */
    long backoffCooldownUntilTick;
    /** 弓兵: 引き切ったあと味方が射線にいる状態が始まった時刻（tick）。-1 なら起きていない。 */
    long allyBlockedSinceTick = -1;
    /** 弓兵: 味方が射線に被るので一時的に狙いから外す敵（UUID → 期限の tick）。 */
    final Map<UUID, Long> lineBlockedTargets = new HashMap<>();
    /** 槍兵: 実際に足を止めて脅威の方を向いている間は true。 */
    boolean shieldStopping;
    /** 槍兵: 最後に飛んでくる矢を見つけた時刻（tick）。 */
    long lastProjectileThreatTick = Long.MIN_VALUE / 2;
    /** 槍兵: 未発射の弓で止まり始めた時刻（tick）。-1 なら始めていない。 */
    long aimStopStartTick = -1;
    /** 槍兵: 未発射の弓で止まる上限に達したあと、この時刻（tick）まで未発射の弓では止まらない。 */
    long shieldAimStopCooldownUntilTick;
    /** 最後にダメージを受けた時刻（サーバーの tick）。自動の回復はこの後 regen-delay-seconds 待つ。 */
    long lastDamagedTick = Long.MIN_VALUE / 2;
    /** 最後に盾で攻撃を防いだ時刻（サーバーの tick）。同じ tick のノックバックを消すのに使う。 */
    long lastBlockedTick = Long.MIN_VALUE / 2;
    /** 盾を構えている（遅い歩きと向きの指定を入れている）間は true。{@link Shields} が決める。 */
    boolean shieldUp;
    /** 構えている間に向く地点（脅威の位置）。構えていなければ null。 */
    Location blockLook;
    /** 最後に脅威を見つけた時刻（サーバーの tick）。盾はこの後 lower-after-seconds 待って下ろす。 */
    long lastThreatTick = Long.MIN_VALUE / 2;

    /**
     * 「退却」を続ける期限（サーバーの tick。この tick 未満の間だけ退却中）。0 なら退却していない。
     * サーバーの tick は 0 より大きいので、0 を「なし」の印にしてよい。
     */
    long retreatUntilTick;

    // ---- 弓兵（{@link Archers} が使う） ----
    /** 弓を引き始めた時刻（サーバーの tick）。引いていなければ -1。 */
    long drawStartTick = -1;
    /** 近すぎる敵から下がって歩いている途中なら true（弓兵は keep-distance、槍兵は届くぎりぎりまで下がり切る）。 */
    boolean backingOff;
    /** 横隊で自分の持ち場に着いたあとの状態（出入りの半径を変えて、持ち場の縁で行ったり来たりしない）。 */
    boolean holdingSlot;
    /** 「守れ」で横隊が向く向き（度。指示した人が見ていた向き）。null なら敵のリスポーン地点の方。 */
    Float holdYaw;
    /** 槍兵が最後に突いた時刻（サーバーの tick）。 */
    long lastSpearAttackTick = Long.MIN_VALUE / 2;
    /** 槍兵: 追う道に蜘蛛の巣があったので追わない敵（UUID → この tick まで）。届けばその場で突く。 */
    final Map<UUID, Long> webBlocked = new HashMap<>();
    /** この時刻（サーバーの tick）まで次の矢を引き始めない。 */
    long nextDrawTick;
    /** 狙いの動きの見積もり用: 最後に見た狙いの位置・その時刻・その相手。 */
    Location aimLastPos;
    long aimLastTick;
    LivingEntity aimLastTarget;
    /** 見積もった狙いの速さ（ブロック/tick）。 */
    Vector aimVelocity = new Vector();
    /** エイム（{@link Aim}）: 照準の向き（度）。aimSet が false なら次の tick に体の向きから始める。 */
    double aimYaw;
    double aimPitch;
    boolean aimSet;
    /** エイム: 直前の tick に照準を回した角度（度。揺れを大きくする量の元。/sb debug 用にも残す）。 */
    double aimMoved;
    /** エイム: 照準の今の揺れ（度）。 */
    double swayYaw;
    double swayPitch;
    /** 弓兵: 照準を向けたい弾道の向き（{@link Archers} が決める）。null なら狙っていない。 */
    Vector aimWant;
    /** 弓兵: {@link #aimWant} を計算したときの対象（相手切り替え直後に古い向きを履歴へ積まないため）。 */
    LivingEntity aimWantTarget;
    /** エイム: 反応の遅れ用の履歴（近接は相手の胴の座標、弓兵は弾道の向き）。先頭が最も古い。 */
    final ArrayDeque<Vector> aimHistory = new ArrayDeque<>();
    /** エイム: {@link #aimHistory} を記録している対象。相手が変わったら履歴を消す。 */
    LivingEntity aimHistoryTarget;
    /** エイム: 反応の遅れを適用したあとの狙う向き（狙いがなければ null）。 */
    Vector aimTargetDir;

    SoldierBot(String name, Side side, NPC npc, UUID owner, UnitType type) {
        this.name = name;
        this.side = side;
        this.npc = npc;
        this.owner = owner;
        this.type = type;
    }

    public UnitType type() {
        return type;
    }

    public UUID owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public Side side() {
        return side;
    }

    public Order order() {
        return order;
    }

    /** 今 tick の時点で退却中か（期限 {@link #retreatUntilTick} の前か）。 */
    public boolean retreating(long now) {
        return now < retreatUntilTick;
    }

    public boolean alive() {
        return npc.isSpawned();
    }
}
