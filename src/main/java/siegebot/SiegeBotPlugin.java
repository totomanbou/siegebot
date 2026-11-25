package siegebot;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** 攻城戦の兵士ボットのプラグイン本体。 */
public final class SiegeBotPlugin extends JavaPlugin {

    private MapProfiles profiles;
    private SiegeData data;
    private BotManager bots;
    private Teams teams;
    private Formation formation;
    private UnitPicks picks;
    private Delegations delegations;
    private CombatDebug combatDebug;
    private BukkitTask botTask;
    private Separation separation;
    private BukkitTask separationTask;
    private BukkitTask shieldTask;
    private Shields shields;
    private Soldiers soldiers;
    private Archers archers;
    private Spearmen spearmen;
    private final Map<UnitType, UnitCombat> combats = new EnumMap<>(UnitType.class);
    private BukkitTask archerTask;
    private UnitSelectItem unitItem;
    private BukkitTask spearTask;
    private Aim aim;
    private BukkitTask aimTask;
    private BukkitTask chaseTask;
    private CombatCheck combat;
    private PlayerCommand playerCommand;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        data = new SiegeData(new File(getDataFolder(), "data.yml"), getLogger());
        data.load();
        profiles = new MapProfiles(new File(getDataFolder(), "maps"));
        profiles.ensureMapsDir();
        profiles.load(getConfig(), data.mapName());
        profiles.warnings().forEach(getLogger()::warning);
        getLogger().info("適用中のマップ: " + profiles.describeActive());
        delegations = new Delegations();
        bots = new BotManager(this);
        teams = new Teams(this);
        formation = new Formation(this);
        separation = new Separation(this);
        picks = new UnitPicks(this);
        combatDebug = new CombatDebug(this);
        shields = new Shields(this);
        soldiers = new Soldiers();
        archers = new Archers(this);
        spearmen = new Spearmen(this);
        combats.put(UnitType.SOLDIER, soldiers);
        combats.put(UnitType.ARCHER, archers);
        combats.put(UnitType.SPEARMAN, spearmen);
        aim = new Aim(this);
        combat = new CombatCheck(getLogger());

        Bukkit.getPluginManager().registerEvents(new GameListener(this), this);
        unitItem = new UnitSelectItem(this);
        Bukkit.getPluginManager().registerEvents(unitItem, this);
        PluginCommand cmd = getCommand("siegebot");
        if (cmd != null) {
            SiegeBotCommand executor = new SiegeBotCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
        PluginCommand sbc = getCommand("sbc");
        if (sbc != null) {
            PlayerCommand pc = new PlayerCommand(this);
            playerCommand = pc;
            sbc.setExecutor(pc);
            sbc.setTabCompleter(pc);
            Bukkit.getPluginManager().registerEvents(pc.menu(), this);
        }

        startTasks();
        // ワールドと Citizens の準備が終わってから、保存してあるボットを出す
        Bukkit.getScheduler().runTaskLater(this, this::spawnSavedBots, 40L);
    }

    @Override
    public void onDisable() {
        if (bots != null) {
            bots.removeAll();
        }
    }

    /**
     * config.yml・maps/・data.yml を読み直す。ボットは出したまま、設定だけ入れ直す（装備と盾の耐久は触らない）。
     * 読み込みの警告（アクティブなマップが読めず default に落ちた、など）を返す。
     */
    public List<String> reloadAll() {
        reloadConfig();
        data.load();
        profiles.ensureMapsDir();
        profiles.load(getConfig(), data.mapName());
        List<String> warnings = profiles.warnings();
        warnings.forEach(getLogger()::warning);
        separation.clearState();
        bots.navigationWatch().clearStats();
        archers.lowerAll();
        shields.lowerAll(); // 構えたままの遅い歩きを、新しい設定の速さに戻してから入れ直す
        startTasks();
        bots.reapplySettings(false);
        bots.syncPersonalBots();
        spawnSavedBots();
        return warnings;
    }

    /** /sb map の切り替えの結果。 */
    public static final class SwitchResult {
        /** 切り替えた（または既に適用中で何もしなかった）。false なら error に理由。 */
        public final boolean ok;
        /** すでにそのマップが適用中で、何もしなかった。 */
        public final boolean alreadyActive;
        public final String error;
        /** 適用後のマップの表示（default・castle_a・snow_keep (extends: wide_field)）。 */
        public final String label;
        public final List<String> warnings;

        private SwitchResult(boolean ok, boolean alreadyActive, String error, String label, List<String> warnings) {
            this.ok = ok;
            this.alreadyActive = alreadyActive;
            this.error = error;
            this.label = label;
            this.warnings = warnings;
        }

        static SwitchResult error(String message) {
            return new SwitchResult(false, false, message, null, List.of());
        }

        static SwitchResult already(String label) {
            return new SwitchResult(true, true, null, label, List.of());
        }

        static SwitchResult switched(String label, List<String> warnings) {
            return new SwitchResult(true, false, null, label, warnings);
        }
    }

    /**
     * マップを切り替える（/sb map &lt;名前|default|reset&gt;）。すでに適用中なら何もしない（再読み込みは /sb reload）。
     * 新しい設定を作れなければ、今の設定のまま理由を返す。切り替えたら生存ボットの装備と盾の耐久も入れ直す。
     */
    public SwitchResult switchMap(String requested) {
        boolean toDefault = requested == null || requested.isBlank() || MapProfiles.isDefaultWord(requested.trim());
        if (toDefault) {
            if (profiles.activeMap() == null && data.mapName() == null) {
                return SwitchResult.already(profiles.describeActive());
            }
        } else {
            String err = profiles.checkName(requested);
            if (err != null) {
                return SwitchResult.error(err);
            }
            String canonical = profiles.canonicalName(requested);
            if (canonical != null && canonical.equalsIgnoreCase(profiles.activeMap())) {
                return SwitchResult.already(profiles.describeActive());
            }
        }
        MapProfiles.Result r = profiles.tryBuild(getConfig(), toDefault ? null : requested);
        if (!r.isOk()) {
            return SwitchResult.error(r.error());
        }
        MapProfiles.Snapshot snap = r.snapshot();
        profiles.apply(snap);
        data.setMapName(snap.activeMap());
        boolean saved = data.save();
        archers.lowerAll();
        shields.lowerAll();
        separation.clearState();
        bots.navigationWatch().clearStats();
        bots.reapplySettings(true);
        bots.syncPersonalBots();
        spawnSavedBots();
        List<String> warnings = new ArrayList<>(snap.warnings());
        warnings.forEach(getLogger()::warning);
        if (!saved) {
            warnings.add("data.yml の保存に失敗しました（再起動すると元のマップに戻る可能性があります）。");
        }
        return SwitchResult.switched(profiles.describeActive(), warnings);
    }

    private void startTasks() {
        if (botTask != null) {
            botTask.cancel();
        }
        botTask = Bukkit.getScheduler().runTaskTimer(this, () -> bots.tick(), 40L, BotManager.TICK_PERIOD);
        if (separationTask != null) {
            separationTask.cancel();
        }
        separationTask = Bukkit.getScheduler().runTaskTimer(this, () -> separation.tick(), 40L, Separation.TICK_PERIOD);
        if (shieldTask != null) {
            shieldTask.cancel();
        }
        shieldTask = Bukkit.getScheduler().runTaskTimer(this, () -> shields.tick(), 40L, Shields.TICK_PERIOD);
        if (archerTask != null) {
            archerTask.cancel();
        }
        archerTask = Bukkit.getScheduler().runTaskTimer(this, () -> archers.tick(), 40L, Archers.TICK_PERIOD);
        if (spearTask != null) {
            spearTask.cancel();
        }
        spearTask = Bukkit.getScheduler().runTaskTimer(this, () -> spearmen.tick(), 40L, Spearmen.TICK_PERIOD);
        if (aimTask != null) {
            aimTask.cancel();
        }
        aimTask = Bukkit.getScheduler().runTaskTimer(this, () -> aim.tick(), 40L, Aim.TICK_PERIOD);
        if (chaseTask != null) {
            chaseTask.cancel();
        }
        // 槍兵が蜘蛛の巣のある道で敵を追い始めたら、すぐ止める（行動の判断の 10 tick を待つと巣まで歩いてしまう）
        chaseTask = Bukkit.getScheduler().runTaskTimer(this, () -> spearmen.checkChasePaths(), 40L, Spearmen.CHASE_CHECK_PERIOD);
    }

    /**
     * /sb spawn で出したボット（持ち主なし。試験用で、test-bots.enabled のときだけ）のうち、今いないものを陣営のリスポーン地点に出す。
     * 起動時と、/sb revive bots・all（倒れて消えたものを出し直す）で呼ぶ。出した数を返す。
     */
    public int spawnSavedBots() {
        int n = 0;
        for (Map.Entry<String, Side> e : data.bots().entrySet()) {
            if (!settings(e.getValue()).testBots) {
                continue; // 持ち主のいないボットは試験用（test-bots.enabled）。data.yml の一覧は消さずに残す
            }
            if (bots.get(e.getKey()) != null) {
                continue;
            }
            if (bots.spawn(e.getKey(), e.getValue(), null, data.botTypes().get(e.getKey())) != null) {
                n++;
            } else {
                getLogger().warning("ボット " + e.getKey() + " を出せません（/sb spawnpoint "
                        + e.getValue().key() + " でリスポーン地点を設定してください）");
            }
        }
        return n;
    }

    /** 共通の設定（陣営を持たない場面。陣営がわかるなら {@link #settings(Side)}）。 */
    public Settings settings() {
        return profiles.base();
    }

    /** 陣営ごとの設定（マップ・sides.attack / sides.defend の上書きを含む）。 */
    public Settings settings(Side side) {
        return profiles.of(side);
    }

    /** マップ別・陣営別の設定。 */
    public MapProfiles profiles() {
        return profiles;
    }

    public SiegeData data() {
        return data;
    }

    public BotManager bots() {
        return bots;
    }

    /** 陣営の判定と、敵・味方の一覧。 */
    public Teams teams() {
        return teams;
    }

    /** 持ち場と横隊の位置の計算。 */
    public Formation formation() {
        return formation;
    }

    /** ボットどうしが重ならないようにする処理（毎 tick の押し分けと、空き地探し）。 */
    Separation separation() {
        return separation;
    }

    /** 兵科選択で兵を呼べる残りの回数。 */
    public UnitPicks picks() {
        return picks;
    }

    /** 指揮権の譲渡の一覧（兵の指揮者・呼べる回数の持ち主はここから導く）。 */
    public Delegations delegations() {
        return delegations;
    }

    /** 戦闘の調査用ログ（/sb debug kb）。 */
    CombatDebug combatDebug() {
        return combatDebug;
    }

    /** 弓兵の射撃。 */
    Archers archers() {
        return archers;
    }

    /** 兵種ごとの戦闘中の動き。 */
    UnitCombat combatOf(UnitType type) {
        return combats.get(type);
    }

    /** 盾の構え方。 */
    Shields shields() {
        return shields;
    }

    /** 兵科選択のアイテム（起動の途中は null のことがある）。 */
    UnitSelectItem unitItem() {
        return unitItem;
    }

    /** /sbc と指揮メニュー（sbc が plugin.yml にないときは null）。 */
    PlayerCommand playerCommand() {
        return playerCommand;
    }

    /** CombatLog の「戦闘中」の判定。 */
    CombatCheck combat() {
        return combat;
    }
}
