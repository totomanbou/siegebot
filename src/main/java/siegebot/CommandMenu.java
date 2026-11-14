package siegebot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 指揮メニュー（チェストの画面）。/sbc menu で開く。ItemJoin の指揮棒の右クリックから呼ぶ想定。
 *
 * <pre>
 * 1 段目: 自分のボットの状態（専用のボットが無効なら、指示する相手の選択）
 * 2 段目: ついてこい・視点の先を守れ                  … 押すと指示して画面を閉じる
 * 3 段目: 構え（積極的・持ち場優先）・閉じる          … 構えは押すと変えて、画面はそのまま
 * </pre>
 * 今の動き・構えのボタンは光る。
 */
public final class CommandMenu implements Listener {

    private static final int SIZE = 27;

    /** この画面の印。押されたマス → 処理。 */
    private static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, Runnable> actions = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final SiegeBotPlugin plugin;
    private final PlayerCommand commands;

    public CommandMenu(SiegeBotPlugin plugin, PlayerCommand commands) {
        this.plugin = plugin;
        this.commands = commands;
    }

    /** 画面を開く（開いていれば中身を描き直す）。 */
    public void open(Player p, Side side) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, SIZE,
                Component.text("指揮メニュー（" + side.label() + "）", NamedTextColor.DARK_GRAY));
        h.inventory = inv;

        // ---- 1 段目: 指示する相手（専用のボットなら自分のボットの状態） ----
        String who;
        if (commands.personal()) {
            SoldierBot own = commands.ownBot(p, side);
            who = own == null ? "自分のボット" : own.name();
            List<String> lore = own == null ? List.of("まだ出ていない")
                    : List.of("今: " + own.order().describe(), own.alive() ? "生存" : "復活待ち");
            put(h, 4, button(Material.PLAYER_HEAD, who, NamedTextColor.AQUA, false, lore), () -> open(p, side));
        } else {
            List<SoldierBot> mine = plugin.bots().of(side);
            String sel = commands.selectedName(p, side);
            put(h, 0, button(Material.NETHER_STAR, "全員に指示", NamedTextColor.GOLD, sel == null,
                    List.of("ボット全員に同じ指示を出す")), () -> {
                        commands.select(p, null);
                        open(p, side);
                    });
            for (int i = 0; i < mine.size() && i < 8; i++) {
                SoldierBot b = mine.get(i);
                List<String> lore = List.of("今: " + b.order().describe(), b.alive() ? "生存" : "復活待ち",
                        "押すとこのボットだけに指示する");
                put(h, 1 + i, button(Material.PLAYER_HEAD, b.name() + " に指示", NamedTextColor.AQUA,
                        b.name().equals(sel), lore), () -> {
                            commands.select(p, b.name());
                            open(p, side);
                        });
            }
            who = sel == null ? "全員" : sel;
        }

        // ---- 2 段目: 動き ----
        List<SoldierBot> targets = commands.targets(p, side);
        Order.Mode mode = commonMode(targets);
        put(h, 11, button(Material.LEAD, "ついてこい", NamedTextColor.GREEN, mode == Order.Mode.FOLLOW,
                List.of(who + "を自分についてこさせる")), () -> act(p, () -> commands.follow(p, side)));
        put(h, 15, button(Material.SPYGLASS, "視点の先を守れ", NamedTextColor.YELLOW, mode == Order.Mode.HOLD,
                List.of(who + "に、メニューを開いたときに", "見ていたブロックの所を守らせる")),
                () -> act(p, () -> commands.holdLookPoint(p, side)));

        // ---- 3 段目: 構え・閉じる ----
        Order.Stance common = commonStance(targets);
        put(h, 20, button(Material.BLAZE_POWDER, "構え: 積極的", NamedTextColor.GOLD, common == Order.Stance.PUSH,
                List.of("広い範囲の敵を追いかける", "動き（追従・守る地点）は変えない")), () -> {
                    commands.setStance(p, side, Order.Stance.PUSH);
                    open(p, side);
                });
        put(h, 24, button(Material.SHIELD, "構え: 持ち場優先", NamedTextColor.BLUE, common == Order.Stance.GUARD,
                List.of("持ち場の近くの敵とだけ戦う", "動き（追従・守る地点）は変えない")), () -> {
                    commands.setStance(p, side, Order.Stance.GUARD);
                    open(p, side);
                });
        put(h, 22, button(Material.BARRIER, "閉じる", NamedTextColor.GRAY, false, List.of()), p::closeInventory);

        p.openInventory(inv);
    }

    private void act(Player p, Runnable action) {
        p.closeInventory();
        action.run();
    }

    /** 全員が同じ動きならその動き、ばらばらなら null。 */
    private static Order.Mode commonMode(List<SoldierBot> bots) {
        Order.Mode m = null;
        for (SoldierBot b : bots) {
            if (m == null) {
                m = b.order().mode();
            } else if (m != b.order().mode()) {
                return null;
            }
        }
        return m;
    }

    private static Order.Stance commonStance(List<SoldierBot> bots) {
        Order.Stance s = null;
        for (SoldierBot b : bots) {
            if (s == null) {
                s = b.order().stance();
            } else if (s != b.order().stance()) {
                return null;
            }
        }
        return s;
    }

    private static void put(Holder h, int slot, ItemStack item, Runnable action) {
        h.inventory.setItem(slot, item);
        h.actions.put(slot, action);
    }

    private static ItemStack button(Material m, String title, NamedTextColor color, boolean glow, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(title, color).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) {
            l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        if (glow) {
            l.add(Component.text("▶ 選択中", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(l);
        meta.setEnchantmentGlintOverride(glow);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    // ---- 画面の操作 ----

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder h)) {
            return;
        }
        event.setCancelled(true); // 画面のアイテムを取り出させない（自分の持ち物側のクリックも止める）
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (event.getClickedInventory() != h.inventory) {
            return;
        }
        Runnable action = h.actions.get(event.getSlot());
        if (action != null) {
            // 画面の開き直し・閉じるは、クリックの処理が終わってから行う
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
