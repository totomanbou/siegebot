package siegebot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * ボットの頭の上の名前を、同じ陣営のプレイヤーにだけ見せる。
 *
 * <p>Citizens の名前の表示（ネームプレート）はプレイヤーごとには出し分けられないので、team モードでは
 * Citizens の表示を消し、代わりにボットに乗せた {@link TextDisplay} を、同じ陣営のプレイヤーにだけ見せる。
 * メインスレッドからだけ呼ぶ。
 */
final class NameTags {

    /** 名前を頭の少し上に置くための持ち上げ量（ブロック）。 */
    private static final float LIFT = 0.35f;

    /** ボット 1 体分の表示と、今その表示を見せているプレイヤー。 */
    private static final class Tag {
        final TextDisplay display;
        final Set<UUID> viewers = new HashSet<>();

        Tag(TextDisplay display) {
            this.display = display;
        }
    }

    private final SiegeBotPlugin plugin;
    /** ボット名（小文字）→ 表示。 */
    private final Map<String, Tag> tags = new HashMap<>();

    NameTags(SiegeBotPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * ボットの名前の表示を、設定とボットの状態に合わせる。{@link BotManager#TICK_PERIOD} tick ごとに呼ぶ。
     * 設定が all なら Citizens の表示に任せ、team なら Citizens の表示を消して自前の表示を出す。
     */
    void update(SoldierBot bot) {
        NPC npc = bot.npc;
        if (!plugin.settings().nameTagTeamOnly) {
            setNameplate(npc, true);
            remove(bot);
            return;
        }
        setNameplate(npc, false);

        Entity entity = npc.isSpawned() ? npc.getEntity() : null;
        if (entity == null || !entity.isValid() || (entity instanceof LivingEntity le && le.isDead())) {
            remove(bot);
            return;
        }

        String key = key(bot);
        Tag tag = tags.get(key);
        if (tag == null || !tag.display.isValid() || tag.display.getWorld() != entity.getWorld()) {
            remove(bot);
            tag = new Tag(spawnDisplay(bot, entity));
            tags.put(key, tag);
        }
        if (!entity.getPassengers().contains(tag.display)) {
            entity.addPassenger(tag.display);
        }

        // 同じ陣営のプレイヤーにだけ見せる（チームなし・敵の陣営・ボット自身には見せない）
        Set<UUID> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasMetadata("NPC")) {
                continue;
            }
            UUID id = p.getUniqueId();
            online.add(id);
            boolean show = plugin.bots().sideOf(p) == bot.side();
            if (show && tag.viewers.add(id)) {
                p.showEntity(plugin, tag.display);
            } else if (!show && tag.viewers.remove(id)) {
                p.hideEntity(plugin, tag.display);
            }
        }
        tag.viewers.retainAll(online);
    }

    /** そのボットの表示を消す（なければ何もしない）。 */
    void remove(SoldierBot bot) {
        Tag tag = tags.remove(key(bot));
        if (tag != null && tag.display.isValid()) {
            tag.display.remove();
        }
    }

    /** 全ボットの表示を消す。 */
    void removeAll() {
        for (Tag tag : tags.values()) {
            if (tag.display.isValid()) {
                tag.display.remove();
            }
        }
        tags.clear();
    }

    private TextDisplay spawnDisplay(SoldierBot bot, Entity entity) {
        TextDisplay display = entity.getWorld().spawn(entity.getLocation(), TextDisplay.class, d -> {
            d.text(Component.text(bot.name(), NamedTextColor.WHITE));
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.setSeeThrough(false);
            d.setShadowed(true);
            d.setTransformation(new Transformation(new Vector3f(0f, LIFT, 0f), new AxisAngle4f(),
                    new Vector3f(1f, 1f, 1f), new AxisAngle4f()));
        });
        entity.addPassenger(display);
        return display;
    }

    /** Citizens の名前の表示を出す／消す。値が違うときだけ書き換える（Citizens が毎 tick 読んで反映する）。 */
    private static void setNameplate(NPC npc, boolean visible) {
        Object cur = npc.data().get(NPC.Metadata.NAMEPLATE_VISIBLE, true);
        if (!Boolean.valueOf(visible).equals(cur)) {
            npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, visible);
        }
    }

    private static String key(SoldierBot bot) {
        return bot.name().toLowerCase(Locale.ROOT);
    }
}
