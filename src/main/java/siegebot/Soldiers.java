package siegebot;

import net.citizensnpcs.api.ai.EntityTarget;
import net.citizensnpcs.api.ai.Navigator;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

/** 剣兵の動き: 狙いを追って殴りに行く（殴るのは BotManager.meleeAttack。全兵種共通の Citizens の attackStrategy）。 */
final class Soldiers implements UnitCombat {
    @Override
    public boolean tickCombatMove(SoldierBot bot, Navigator nav, LivingEntity self, LivingEntity target,
                                  Location anchor, Settings s, long now) {
        EntityTarget cur = nav.getEntityTarget();
        if (cur == null || cur.getTarget() != target || !nav.isNavigating()) {
            nav.setTarget(target, true);
        }
        return true;
    }
}
