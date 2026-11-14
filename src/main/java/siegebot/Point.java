package siegebot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** ワールドの中の 1 点（守る地点・リスポーン地点）。 */
public record Point(String world, double x, double y, double z) {

    public Point {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world が必要");
        }
    }

    public static Point of(Location loc) {
        return new Point(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
    }

    /** ワールドが読み込まれていなければ null。 */
    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z);
    }

    /** 「(12, 64, -30)」の形（整数に丸める）。 */
    public String coords() {
        return "(" + Math.round(Math.floor(x)) + ", " + Math.round(Math.floor(y)) + ", " + Math.round(Math.floor(z)) + ")";
    }
}
