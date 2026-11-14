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
 * 1 段目: 指示する相手（全員・ボット 1 体ずつ）          … 押すと選び直して、画面はそのまま
 * 2 段目: ついてこい・前線へ・ここを守れ・下がれ        … 押すと指示して画面を閉じる
 * 3 段目: 登録した場所を守らせる（最大 9 か所）        … 押すと指示して画面を閉じる
 * 4 段目: 構え（積極的・持ち場優先）・閉じる          … 構えは押すと変えて、画面はそのまま
 * </pre>
 */
public final class CommandMenu implements Listener {

    private static final int SIZE = 36;

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
        put(h, 10, button(Material.LEAD, "ついてこい", NamedTextColor.GREEN, false,
                List.of(who + "を自分についてこさせる")), () -> act(p, () -> commands.follow(p, side)));
        put(h, 12, button(Material.IRON_SWORD, "前線へ", NamedTextColor.RED, false,
                List.of(who + "を今の前線で戦わせる", "門が開くと次の前線へ移る")), () -> act(p, () -> commands.front(p, side)));
        put(h, 14, button(Material.WHITE_BANNER, "ここを守れ", NamedTextColor.YELLOW, false,
                List.of(who + "に今立っている場所を守らせる")), () -> act(p, () -> commands.here(p, side)));
        put(h, 16, button(Material.RED_BED, "下がれ", NamedTextColor.LIGHT_PURPLE, false,
                List.of(who + "をリスポーン地点まで下がらせる")), () -> act(p, () -> commands.retreat(p, side)));

        // ---- 3 段目: 登録した場所 ----
        int slot = 18;
        for (SiegeData.Place pl : plugin.data().places().values()) {
            if (slot > 26) {
                break;
            }
            String name = pl.name();
            List<String> lore = new ArrayList<>();
            lore.add(who + "に「" + name + "」を守らせる");
            if (!pl.aliases().isEmpty()) {
                lore.add("別名: " + String.join("、", pl.aliases()));
            }
            put(h, slot++, button(Material.OAK_SIGN, "「" + name + "」を守れ", NamedTextColor.WHITE, false, lore),
                    () -> act(p, () -> commands.holdPlace(p, side, name)));
        }

        // ---- 4 段目: 構え・閉じる ----
        Order.Stance common = commonStance(commands.targets(p, side));
        put(h, 29, button(Material.BLAZE_POWDER, "構え: 積極的", NamedTextColor.GOLD, common == Order.Stance.PUSH,
                List.of("広い範囲の敵を追いかける", "動き（前線・場所・追従）は変えない")), () -> {
                    commands.setStance(p, side, Order.Stance.PUSH);
                    open(p, side);
                });
        put(h, 33, button(Material.SHIELD, "構え: 持ち場優先", NamedTextColor.BLUE, common == Order.Stance.GUARD,
                List.of("持ち場の近くの敵とだけ戦う", "動き（前線・場所・追従）は変えない")), () -> {
                    commands.setStance(p, side, Order.Stance.GUARD);
                    open(p, side);
                });
        put(h, 31, button(Material.BARRIER, "閉じる", NamedTextColor.GRAY, false, List.of()), p::closeInventory);

        p.openInventory(inv);
    }

    private void act(Player p, Runnable action) {
        p.closeInventory();
        action.run();
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
