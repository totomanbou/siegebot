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
 * 1 段目: 指示する相手の選択（全員・1 体ずつ）。自分のボットが 1 体だけなら、その状態の表示
 * 2 段目: ついてこい・呼び寄せる・視点の先を守れ・退却 … 押すと指示して画面を閉じる
 * 3 段目: 構え（積極的・持ち場優先）・閉じる          … 構えは押すと変えて、画面はそのまま
 * </pre>
 * 今の動き・構えのボタンは光る。
 */
public final class CommandMenu implements Listener {

    private static final int SIZE = 27;

    /** この画面の印。押されたマス → 処理（シフト+左クリックのときは shiftActions があればそちら）。 */
    private static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, Runnable> actions = new HashMap<>();
        private final Map<Integer, Runnable> shiftActions = new HashMap<>();

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

        // ---- 1 段目: 指示する相手（専用のボットが有効なら、自分のボットの中から選ぶ） ----
        String who;
        List<SoldierBot> choices = commands.candidates(p, side);
        if (commands.personal() && choices.size() <= 1) {
            // 自分のボットが 1 体なら、選ぶ段は出さずに状態だけを表示する
            SoldierBot own = choices.isEmpty() ? null : choices.get(0);
            who = own == null ? "自分のボット" : own.name();
            List<String> lore = own == null ? List.of("まだ出ていない")
                    : List.of(own.type().label() + "・今: " + own.order().describe());
            put(h, 4, button(icon(own), who, NamedTextColor.AQUA, false, lore), () -> open(p, side));
        } else {
            List<SoldierBot> mine = choices;
            java.util.Set<String> sel = commands.selectedNames(p, side);
            put(h, 0, button(Material.NETHER_STAR, "全員に指示", NamedTextColor.GOLD, sel.isEmpty(),
                    List.of(commands.personal() ? "自分のボット全員に同じ指示を出す" : "ボット全員に同じ指示を出す",
                            "ボットの絵をシフト+左クリックすると、何体でも選べる")), () -> {
                        commands.select(p, null);
                        open(p, side);
                    });
            for (int i = 0; i < mine.size() && i < 8; i++) {
                SoldierBot b = mine.get(i);
                List<String> lore = List.of(b.type().label() + "・今: " + b.order().describe(),
                        "左クリック: このボットだけに指示する",
                        "シフト+左クリック: 選んでいるボットに足す（もう一度で外す）");
                put(h, 1 + i, button(icon(b), b.name() + " に指示", NamedTextColor.AQUA,
                        sel.contains(b.name()), lore), () -> {
                            commands.select(p, b.name());
                            open(p, side);
                        });
                h.shiftActions.put(1 + i, () -> {
                    commands.toggle(p, b.name());
                    open(p, side);
                });
            }
            who = sel.isEmpty() ? "全員" : sel.size() == 1 ? sel.iterator().next() : "選んだ " + sel.size() + " 体";
        }

        // ---- 2 段目: 動き ----
        List<SoldierBot> targets = commands.targets(p, side);
        Order.Mode mode = commonMode(targets);
        put(h, 10, button(Material.LEAD, "ついてこい", NamedTextColor.GREEN, mode == Order.Mode.FOLLOW,
                List.of(who + "を自分についてこさせる")), () -> act(p, () -> commands.follow(p, side)));
        put(h, 12, button(Material.ENDER_PEARL, "呼び寄せる", NamedTextColor.LIGHT_PURPLE, false,
                List.of(who + "を自分の所へ瞬間移動させる", "動き（追従・守る地点）は変えない")),
                () -> act(p, () -> commands.callToMe(p, side)));
        put(h, 14, button(Material.SPYGLASS, "視点の先を守れ", NamedTextColor.YELLOW, mode == Order.Mode.HOLD,
                List.of(who + "に、メニューを開いたときに", "見ていたブロックの所を守らせる")),
                () -> act(p, () -> commands.holdLookPoint(p, side)));
        put(h, 16, button(Material.WHITE_BANNER, "退却", NamedTextColor.RED, allRetreating(targets),
                List.of(who + "に戦闘をやめさせ、", "持ち場（ついてこいなら自分）へ戻らせる", "着いたら元の動きに戻る")),
                () -> act(p, () -> commands.retreat(p, side)));

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

    /** 相手が 1 体以上いて、全員が今退却中か。 */
    private static boolean allRetreating(List<SoldierBot> bots) {
        long now = Bukkit.getCurrentTick();
        for (SoldierBot b : bots) {
            if (!b.retreating(now)) {
                return false;
            }
        }
        return !bots.isEmpty();
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

    /** ボットの絵: 兵種の武器（剣兵は剣、槍兵は斧、弓兵は弓）。まだ出ていなければ頭。 */
    private static Material icon(SoldierBot b) {
        if (b == null) {
            return Material.PLAYER_HEAD;
        }
        return switch (b.type()) {
            case ARCHER -> Material.BOW;
            case SPEARMAN -> Material.IRON_AXE;
            default -> Material.IRON_SWORD;
        };
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

    // ---- 兵科選択の画面（/sbc units） ----

    /**
     * 兵科選択の画面を開く（開いていれば描き直す）。兵種を押すと、呼べる回数を 1 使ってその兵種を 1 体呼ぶ
     * （{@link BotManager#summon}）。画面はそのまま（残りの回数を描き直す）。
     */
    public void openUnits(Player p, Side side) {
        Holder h = new Holder();
        h.inventory = Bukkit.createInventory(h, 9, Component.text("兵科選択（" + side.label() + "）", NamedTextColor.DARK_GRAY));
        BotManager bots = plugin.bots();
        int left = bots.picks(p.getUniqueId());
        List<SoldierBot> mine = commands.ownBots(p, side);
        int max = plugin.settings().maxBotsPerPlayer;
        int[] slots = {1, 3, 5};
        UnitType[] types = {UnitType.SOLDIER, UnitType.SPEARMAN, UnitType.ARCHER};
        for (int i = 0; i < types.length; i++) {
            UnitType t = types[i];
            int have = 0;
            for (SoldierBot b : mine) {
                if (b.type() == t) {
                    have++;
                }
            }
            List<String> lore = new ArrayList<>();
            lore.add("現在: " + have + " 体");
            if (left > 0 && mine.size() >= max) {
                lore.add("上限（" + max + " 体）に届いているので呼べない");
            }
            ItemStack icon = unitIcon(t);
            put(h, slots[i], decorate(icon, t.label(), NamedTextColor.AQUA, lore), () -> {
                BotManager.SummonResult r = plugin.bots().summon(p, t);
                // 呼べたときは何も言わない（呼べなかったときだけ理由を知らせる）
                String text = switch (r) {
                    case SPAWNED -> null;
                    case NO_PICKS -> "呼べる回数が残っていません。";
                    case FULL -> "ボットの上限（" + max + " 体）に届いているので呼べません。";
                    case NO_TEAM -> "攻め・守りのどちらのチームにも入っていません。";
                    case DISABLED -> "専用のボットが無効なので、兵科選択は使えません。";
                    case NO_PLACE -> "出す場所がありません。";
                };
                if (text != null) {
                    p.sendMessage(Component.text("[SiegeBot] ", NamedTextColor.GOLD)
                            .append(Component.text(text, NamedTextColor.RED)));
                }
                Side now = plugin.bots().sideOf(p);
                if (now != null && plugin.bots().picks(p.getUniqueId()) > 0) {
                    openUnits(p, now);
                } else {
                    p.closeInventory();
                }
            });
        }
        put(h, 7, button(Material.PAPER, "残り " + left + " 回", left > 0 ? NamedTextColor.GREEN : NamedTextColor.RED, false,
                List.of()), () -> { });
        p.openInventory(h.inventory);
    }

    /** 兵種の絵: その兵種の装備の右手（槍兵なら本サーバーで槍に見える斧）。なければ兵種の武器。 */
    private ItemStack unitIcon(UnitType t) {
        ItemStack hand = plugin.settings().unit(t).equipment.get("hand");
        if (hand != null) {
            return hand.clone();
        }
        return new ItemStack(switch (t) {
            case ARCHER -> Material.BOW;
            case SPEARMAN -> Material.IRON_AXE;
            default -> Material.IRON_SWORD;
        });
    }

    /** 渡したアイテムに名前と説明を付ける（部品の見た目はそのまま）。 */
    private static ItemStack decorate(ItemStack it, String title, NamedTextColor color, List<String> lore) {
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(title, color).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) {
            l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(l);
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
        Runnable action = event.isShiftClick() && event.isLeftClick() && h.shiftActions.containsKey(event.getSlot())
                ? h.shiftActions.get(event.getSlot()) : h.actions.get(event.getSlot());
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
