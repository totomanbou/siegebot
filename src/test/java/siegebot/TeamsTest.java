package siegebot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.Test;

class TeamsTest {

    /** hasColor() と color() だけを持つ Team の最小プロキシ。色が未設定なら color() は例外を投げる。 */
    private static Team team(boolean hasColor, TextColor color) {
        return (Team) Proxy.newProxyInstance(Team.class.getClassLoader(), new Class<?>[] {Team.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasColor" -> hasColor;
                    case "color" -> {
                        if (!hasColor) {
                            throw new IllegalStateException("Team does not have a color");
                        }
                        yield color;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "TeamProxy";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void nullTeamIsWhite() {
        assertEquals(NamedTextColor.WHITE, Teams.colorOf((Team) null));
    }

    @Test
    void teamWithoutColorIsWhiteAndColorIsNotCalled() {
        assertEquals(NamedTextColor.WHITE, Teams.colorOf(team(false, null)));
    }

    @Test
    void teamColorIsUsed() {
        assertEquals(NamedTextColor.RED, Teams.colorOf(team(true, NamedTextColor.RED)));
        assertEquals(NamedTextColor.BLUE, Teams.colorOf(team(true, NamedTextColor.BLUE)));
    }
}
