package siegebot;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 兵科選択のアイテム（紙）。呼べる回数（{@link BotManager#picks}）が残っている間だけ持たせ、使い切ったら消す。
 * 右クリックで兵科選択の画面（/sbc units）を開く。
 *
 * <p>ItemJoin では配らない（ItemJoin は参加・リスポーンのたびに回数と関係なく配り、配る・消すコマンドは
 * プレイヤーに英語の知らせを出すため）。死んでも落とさず、捨てられない。リスポーン・参加のときに回数に合わせ直す。
 */
final class UnitSelectItem implements Listener {

    /** 置きたいホットバーの位置（0 から数えて 7 = 左から 8 番目。空いていなければ空いている所）。 */
    private static final int SLOT = 7;

    private final SiegeBotPlugin plugin;
    private final NamespacedKey key;

    UnitSelectItem(SiegeBotPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "unit_select");
    }

    /** 兵科選択のアイテムか（印で見分ける。名前を変えられても見分けられる）。 */
    boolean is(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private ItemStack create() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("兵科選択", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("右クリック: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text("兵科を選んで兵を呼ぶ", NamedTextColor.WHITE)),
                Component.text("回数を使い切ると消える", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    /** 回数に合わせる: 残っていれば 1 つ持たせ（なければ足す）、0 なら全部消す。 */
    void sync(Player p) {
        if (plugin.bots().byEntity(p) != null) {
            return; // ボット（プレイヤーの姿の NPC）には持たせない
        }
        PlayerInventory inv = p.getInventory();
        int have = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            if (is(inv.getItem(i))) {
                have++;
            }
        }
        if (is(p.getItemOnCursor())) {
            have++;
        }
        boolean want = plugin.bots().picks(p.getUniqueId()) > 0;
        if (want && have == 0) {
            if (inv.getItem(SLOT) == null || inv.getItem(SLOT).getType() == Material.AIR) {
                inv.setItem(SLOT, create());
            } else {
                inv.addItem(create()); // 持ち物が満杯なら渡せない（次のリスポーン・回数の変更で渡す）
            }
        } else if (!want && have > 0) {
            for (int i = 0; i < inv.getSize(); i++) {
                if (is(inv.getItem(i))) {
                    inv.setItem(i, null);
                }
            }
            if (is(p.getItemOnCursor())) {
                p.setItemOnCursor(null);
            }
        }
    }

    /** 右クリックで兵科選択の画面を開く。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !is(event.getItem())) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().performCommand("sbc units");
    }

    /** 死んでも落とさない（リスポーンのときに回数に合わせて持たせ直す）。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::is);
    }

    /** 捨てられない。 */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (is(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) {
                sync(p);
            }
        }, 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) {
                sync(p);
            }
        }, 5L);
    }
}
