package siegebot;

import net.citizensnpcs.api.ai.Navigator;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

/** 兵種ごとの戦闘中の動き。{@link BotManager} が 10 tick ごとの判断で呼ぶ。メインスレッドからだけ呼ぶ。 */
interface UnitCombat {
    /** 戦闘中の移動を決める。true なら移動判断済み、false なら持ち場へ戻る動きに任せる。 */
    boolean tickCombatMove(SoldierBot bot, Navigator nav, LivingEntity self, LivingEntity target,
                           Location anchor, Settings s, long now);

    /** 追う道がふさがれている等の理由で、今は狙い（追跡）から外すべき敵か。 */
    default boolean isChaseBlocked(SoldierBot bot, LivingEntity self, LivingEntity enemy, long now, Settings s) {
        return false;
    }
}
