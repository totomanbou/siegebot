package siegebot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.bukkit.inventory.meta.SkullMeta;

/**
 * 指揮メニュー（チェストの画面）。/sbc menu で開く。ItemJoin の指揮棒の右クリックから呼ぶ想定。
 *
 * <pre>
 * 1 段目: 指示する相手の選択（全員・1 体ずつ。8 体ずつのページ）。指揮するボットが 1 体だけなら、その状態の表示
 * 2 段目: ついてこい・呼び寄せる・視点の先を守れ・退却 … 押すと指示して画面を閉じる
 *         両端（左端・右端）はページめくり（ページ番号は出さない）
 * 3 段目: 構え（積極的・持ち場優先）・閉じる          … 構えは押すと変えて、画面はそのまま
 * </pre>
 * 今の動き・構えのボタンは光る。指揮するボットには、他のプレイヤーから指揮権を譲られて預かっているボットも入る
 * （その絵には持ち主の名前を添える）。
 */
public final class CommandMenu implements Listener {

    private static final int SIZE = 27;
    /** 指揮メニューの 1 ページに出すボットの数（1 段目の 1〜8 番目）。 */
    static final int BOT_PAGE_SIZE = 8;
    /** 譲渡先の選択画面の 1 ページに出すプレイヤーの数（1〜2 段目）。 */
    static final int DELEGATE_PAGE_SIZE = 18;

    /** 全部で total 件を 1 ページ pageSize 件に分けたときのページ数（0 件でも 1 ページ）。 */
    static int pageCount(int total, int pageSize) {
        return total <= 0 || pageSize <= 0 ? 1 : (total + pageSize - 1) / pageSize;
    }

    /** page（0 始まり）を、有効なページの範囲に収める。 */
    static int clampPage(int page, int total, int pageSize) {
        return Math.max(0, Math.min(page, pageCount(total, pageSize) - 1));
    }

    /** そのページの最初の件の番号（0 始まり）。 */
    static int pageStart(int page, int total, int pageSize) {
        return clampPage(page, total, pageSize) * pageSize;
    }

    /** そのページの最後の件の次の番号（総数を超えない）。 */
    static int pageEnd(int page, int total, int pageSize) {
        return Math.min(total, pageStart(page, total, pageSize) + pageSize);
    }

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
    /** プレイヤー → 指揮メニューで最後に開いていたページ（0 始まり）。 */
    private final Map<UUID, Integer> menuPages = new HashMap<>();

    /** 指揮メニューのページを最初に戻す（倒れた・退出したとき）。 */
    void resetPage(UUID player) {
        menuPages.remove(player);
    }

    public CommandMenu(SiegeBotPlugin plugin, PlayerCommand commands) {
        this.plugin = plugin;
        this.commands = commands;
    }

    /** 画面を開く（開いていれば中身を描き直す）。最後に開いていたページを開く。 */
    public void open(Player p, Side side) {
        open(p, side, menuPages.getOrDefault(p.getUniqueId(), 0));
    }

    /** 画面を page（0 始まり）のページで開く。指揮するボットが 1 体もいなければ閉じる。 */
    public void open(Player p, Side side, int page) {
        List<SoldierBot> choices = commands.candidates(p, side);
        if (choices.isEmpty()) {
            p.closeInventory(); // もう誰も指揮できない（倒れた・譲渡した・消えた）
            return;
        }
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, SIZE,
                Component.text("指揮メニュー（" + side.label() + "）", NamedTextColor.DARK_GRAY));
        h.inventory = inv;

        // ---- 1 段目: 指示する相手（指揮しているボットの中から選ぶ） ----
        String who;
        int curPage = 0;
        if (choices.size() == 1) {
            // ボットが 1 体なら、選ぶ段は出さずに状態だけを表示する
            menuPages.remove(p.getUniqueId());
            SoldierBot own = choices.get(0);
            who = own.name();
            List<String> lore = new ArrayList<>();
            if (borrowed(p, own)) {
                lore.add("持ち主: " + ownerName(own.owner()));
            }
            lore.add(own.type().label() + "・今: " + own.order().describe());
            put(h, 4, button(icon(own), who, NamedTextColor.AQUA, false, lore), () -> open(p, side));
        } else {
            int totalPages = pageCount(choices.size(), BOT_PAGE_SIZE);
            curPage = clampPage(page, choices.size(), BOT_PAGE_SIZE);
            menuPages.put(p.getUniqueId(), curPage);
            final int shown = curPage;
            java.util.Set<String> sel = commands.selectedNames(p, side);
            put(h, 0, button(Material.NETHER_STAR, "全員に指示", NamedTextColor.GOLD, sel.isEmpty(),
                    List.of("指揮しているボット全員（全ページ）に同じ指示を出す",
                            "ボットの絵をシフト+左クリックすると、何体でも選べる")), () -> {
                        commands.select(p, null);
                        open(p, side, shown);
                    });
            int start = pageStart(curPage, choices.size(), BOT_PAGE_SIZE);
            int end = pageEnd(curPage, choices.size(), BOT_PAGE_SIZE);
            for (int i = start; i < end; i++) {
                SoldierBot b = choices.get(i);
                List<String> lore = new ArrayList<>();
                if (borrowed(p, b)) {
                    lore.add("持ち主: " + ownerName(b.owner()));
                }
                lore.add(b.type().label() + "・今: " + b.order().describe());
                lore.add("左クリック: このボットだけに指示する");
                lore.add("シフト+左クリック: 選んでいるボットに足す（もう一度で外す）");
                int slot = 1 + (i - start);
                put(h, slot, button(icon(b), b.name() + " に指示", NamedTextColor.AQUA, sel.contains(b.name()), lore),
                        () -> {
                            if (commands.candidates(p, side).contains(b)) {
                                commands.select(p, b.name());
                            }
                            open(p, side, shown);
                        });
                h.shiftActions.put(slot, () -> {
                    if (commands.candidates(p, side).contains(b)) {
                        commands.toggle(p, b.name());
                    }
                    open(p, side, shown);
                });
            }
            // 2 段目の両端: ページめくり（今のページ・総ページ数は書かない）
            if (curPage > 0) {
                put(h, 9, button(Material.ARROW, "前のページ", NamedTextColor.YELLOW, false, List.of()),
                        () -> open(p, side, shown - 1));
            }
            if (curPage < totalPages - 1) {
                put(h, 17, button(Material.ARROW, "次のページ", NamedTextColor.YELLOW, false, List.of()),
                        () -> open(p, side, shown + 1));
            }
            who = sel.isEmpty() ? "全員" : sel.size() == 1 ? sel.iterator().next() : "選んだ " + sel.size() + " 体";
        }
        final int keepPage = curPage;

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
                    open(p, side, keepPage);
                });
        put(h, 24, button(Material.SHIELD, "構え: 持ち場優先", NamedTextColor.BLUE, common == Order.Stance.GUARD,
                List.of("持ち場の近くの敵とだけ戦う", "動き（追従・守る地点）は変えない")), () -> {
                    commands.setStance(p, side, Order.Stance.GUARD);
                    open(p, side, keepPage);
                });
        put(h, 22, button(Material.BARRIER, "閉じる", NamedTextColor.GRAY, false, List.of()), p::closeInventory);

        p.openInventory(inv);
    }

    /** 他のプレイヤーから指揮権を譲られて預かっているボットか。 */
    private static boolean borrowed(Player p, SoldierBot b) {
        return b.owner() != null && !p.getUniqueId().equals(b.owner());
    }

    private static String ownerName(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : "不明";
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
        return b.type().defaultIcon();
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
     * 預かっている回数も使える。右端の本は指揮権の譲渡先の選択（{@link #openDelegate}）。
     */
    public void openUnits(Player p, Side side) {
        Holder h = new Holder();
        h.inventory = Bukkit.createInventory(h, 9, Component.text("兵科選択（" + side.label() + "）", NamedTextColor.DARK_GRAY));
        int left = plugin.picks().available(p);
        List<SoldierBot> mine = commands.candidates(p, side);
        UnitPicks.PickChoice avail = plugin.bots().checkSummonAvailability(p, side);
        int max = plugin.settings(side).maxBotsPerPlayer;
        int[] slots = {0, 2, 4};
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
            if (left > 0 && avail.status() == UnitPicks.PickChoice.Status.FULL) {
                lore.add("上限（" + max + " 体）に届いているので呼べない");
            }
            ItemStack icon = unitIcon(side, t);
            put(h, slots[i], decorate(icon, t.label(), NamedTextColor.AQUA, lore), () -> {
                BotManager.SummonResult r = plugin.bots().summon(p, t);
                // 呼べたときは何も言わない（呼べなかったときだけ理由を知らせる）
                String text = switch (r) {
                    case SPAWNED -> null;
                    case NO_PICKS -> "呼べる回数が残っていません。";
                    case FULL -> "ボットの上限（" + max + " 体）に届いているので呼べません。";
                    case NO_TEAM -> "攻め・守りのどちらのチームにも入っていません。";
                    case NO_PLACE -> "出す場所がありません。";
                };
                if (text != null) {
                    tell(p, text, NamedTextColor.RED);
                }
                Side now = plugin.teams().sideOf(p);
                if (now != null && plugin.picks().available(p) > 0) {
                    openUnits(p, now);
                } else {
                    p.closeInventory();
                }
            });
        }

        UUID id = p.getUniqueId();
        Delegations.Entry del = plugin.delegations().entryOf(id);
        int own = plugin.picks().ownPicks(id);
        Map<UUID, Integer> breakdown = plugin.picks().delegatedBreakdown(p, side);
        List<String> countLore = new ArrayList<>();
        if (del != null) {
            countLore.add("自分の分（" + own + " 回）は " + del.commanderName() + " に譲渡中");
        } else if (!breakdown.isEmpty()) {
            countLore.add("自分: " + own + " 回");
        }
        for (Map.Entry<UUID, Integer> e : breakdown.entrySet()) {
            countLore.add("預かり（" + ownerName(e.getKey()) + "）: " + e.getValue() + " 回");
        }
        put(h, 6, button(Material.PAPER, "残り " + left + " 回", left > 0 ? NamedTextColor.GREEN : NamedTextColor.RED, false,
                countLore), () -> { });
        put(h, 8, button(Material.WRITABLE_BOOK,
                del != null ? "指揮権の譲渡先: " + del.commanderName() : "指揮権を譲渡", NamedTextColor.GOLD, del != null,
                List.of("自分の兵と自分の残り回数を同チームの他プレイヤーに譲渡する", "自分が倒れても渡した兵は残る（残り回数は 0 になる）")),
                () -> openDelegate(p, side, 0));
        p.openInventory(h.inventory);
    }

    /**
     * 指揮権の譲渡先を選ぶ画面（同じチームのオンラインのプレイヤーの頭。18 人ずつのページ）。
     * 押すと自分の兵と残り回数をその人に譲る（{@link BotManager#delegateCommand}）。3 段目: 前のページ・自分に戻す・
     * 兵科選択に戻る・次のページ（ページ番号は出さない）。
     */
    public void openDelegate(Player p, Side side, int page) {
        Holder h = new Holder();
        h.inventory = Bukkit.createInventory(h, SIZE,
                Component.text("指揮権を譲渡（" + side.label() + "）", NamedTextColor.DARK_GRAY));
        List<Player> others = new ArrayList<>();
        for (Player other : plugin.teams().humans(side)) {
            if (!other.getUniqueId().equals(p.getUniqueId()) && !other.isDead()) {
                others.add(other);
            }
        }
        others.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int totalPages = pageCount(others.size(), DELEGATE_PAGE_SIZE);
        int curPage = clampPage(page, others.size(), DELEGATE_PAGE_SIZE);
        Delegations.Entry currentDel = plugin.delegations().entryOf(p.getUniqueId());

        if (others.isEmpty()) {
            put(h, 4, button(Material.GRAY_DYE, "譲渡できるプレイヤーがいません", NamedTextColor.GRAY, false,
                    List.of("同じチームに他のプレイヤーがいません")), () -> { });
        } else {
            int start = pageStart(curPage, others.size(), DELEGATE_PAGE_SIZE);
            int end = pageEnd(curPage, others.size(), DELEGATE_PAGE_SIZE);
            for (int i = start; i < end; i++) {
                Player other = others.get(i);
                boolean selectedTarget = currentDel != null && currentDel.commander().equals(other.getUniqueId());
                List<String> lore = new ArrayList<>();
                lore.add("クリックして指揮権と残り回数を譲渡");
                Delegations.Entry theirDel = plugin.delegations().entryOf(other.getUniqueId());
                if (theirDel != null) {
                    lore.add("（現在 " + theirDel.commanderName() + " に譲渡中）");
                }
                put(h, i - start, headButton(other, selectedTarget, lore), () -> {
                    Side nowSide = plugin.teams().sideOf(p);
                    if (other.isOnline() && !other.isDead() && nowSide != null && plugin.teams().sideOf(other) == nowSide
                            && plugin.bots().delegateCommand(p, other)) {
                        if (plugin.picks().available(p) > 0) {
                            openUnits(p, nowSide);
                        } else {
                            p.closeInventory();
                        }
                    } else {
                        tell(p, "相手が同じチームにいないため譲渡できません。", NamedTextColor.RED);
                        if (nowSide != null) {
                            openDelegate(p, nowSide, curPage);
                        } else {
                            p.closeInventory();
                        }
                    }
                });
            }
        }

        if (curPage > 0) {
            put(h, 18, button(Material.ARROW, "前のページ", NamedTextColor.YELLOW, false, List.of()),
                    () -> openDelegate(p, side, curPage - 1));
        }
        put(h, 20, button(Material.NETHER_STAR, "自分に戻す", NamedTextColor.GOLD, currentDel != null,
                List.of("譲渡を解除して自分の指揮権・回数を戻す")), () -> {
                    plugin.bots().reclaimCommand(p);
                    backToUnits(p);
                });
        put(h, 22, button(Material.PAPER, "兵科選択に戻る", NamedTextColor.GRAY, false, List.of()), () -> backToUnits(p));
        if (curPage < totalPages - 1) {
            put(h, 26, button(Material.ARROW, "次のページ", NamedTextColor.YELLOW, false, List.of()),
                    () -> openDelegate(p, side, curPage + 1));
        }
        p.openInventory(h.inventory);
    }

    /** 兵科選択の画面へ戻る（チームに入っていなければ閉じる）。 */
    private void backToUnits(Player p) {
        Side now = plugin.teams().sideOf(p);
        if (now != null) {
            openUnits(p, now);
        } else {
            p.closeInventory();
        }
    }

    private static void tell(Player p, String text, NamedTextColor color) {
        p.sendMessage(Component.text("[SiegeBot] ", NamedTextColor.GOLD).append(Component.text(text, color)));
    }

    /** プレイヤーの頭のボタン（名前・説明つき。glow なら光って「選択中」を添える）。 */
    private static ItemStack headButton(Player other, boolean glow, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(other);
        meta.displayName(Component.text(other.getName(), NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
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

    /** 兵種の絵: その兵種の装備の右手（槍兵なら本サーバーで槍に見える斧）。なければ兵種の武器。 */
    private ItemStack unitIcon(Side side, UnitType t) {
        ItemStack hand = plugin.settings(side).unit(t).equipment.get("hand");
        if (hand != null) {
            return hand.clone();
        }
        return new ItemStack(t.defaultIcon());
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
