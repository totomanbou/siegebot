package siegebot;

import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * CombatLog プラグイン（https://github.com/Frwostella/CombatLogPlugin）の「戦闘中」を調べる。
 *
 * <p>CombatLog は API の jar を配っていないので、リフレクションで
 * {@code CombatLogPlugin#getCombatManager()} → {@code CombatManager#isInCombat(Player)} と
 * {@code CombatManager#getTimeLeft(Player)} を呼ぶ。CombatLog が入っていない・形が違うときは「戦闘中でない」とする
 * （形が違うときは 1 回だけ警告を出す）。
 */
final class CombatCheck {

    private final Logger log;
    private Plugin cachedPlugin;
    private Object manager;
    private Method isInCombat;
    private Method getTimeLeft;
    private boolean warned;

    CombatCheck(Logger log) {
        this.log = log;
    }

    /** 戦闘中なら残りの秒数（1 以上）、戦闘中でない・CombatLog がなければ 0。 */
    int secondsLeft(Player p) {
        Plugin cl = Bukkit.getPluginManager().getPlugin("CombatLog");
        if (cl == null || !cl.isEnabled()) {
            return 0;
        }
        try {
            if (cl != cachedPlugin) {
                manager = cl.getClass().getMethod("getCombatManager").invoke(cl);
                isInCombat = manager.getClass().getMethod("isInCombat", Player.class);
                getTimeLeft = manager.getClass().getMethod("getTimeLeft", Player.class);
                cachedPlugin = cl;
            }
            if (!(Boolean) isInCombat.invoke(manager, p)) {
                return 0;
            }
            return Math.max(1, ((Number) getTimeLeft.invoke(manager, p)).intValue());
        } catch (ReflectiveOperationException | RuntimeException e) {
            cachedPlugin = null;
            if (!warned) {
                warned = true;
                log.warning("CombatLog の戦闘中の判定を読めませんでした（戦闘中でも呼び寄せられます）: " + e);
            }
            return 0;
        }
    }
}
