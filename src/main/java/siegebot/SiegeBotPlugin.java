package siegebot;

import java.io.File;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** 攻城戦の兵士ボットのプラグイン本体。 */
public final class SiegeBotPlugin extends JavaPlugin {

    private Settings settings;
    private SiegeData data;
    private BotManager bots;
    private BukkitTask botTask;
    private BukkitTask shieldTask;
    private Shields shields;
    private Archers archers;
    private BukkitTask archerTask;
    private Spearmen spearmen;
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
        settings = new Settings(getConfig());
        settings.warnings.forEach(getLogger()::warning);
        data = new SiegeData(new File(getDataFolder(), "data.yml"), getLogger());
        data.load();
        bots = new BotManager(this);
        shields = new Shields(this);
        archers = new Archers(this);
        spearmen = new Spearmen(this);
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

    /** config.yml と data.yml を読み直す。ボットは出したまま、設定だけ入れ直す。 */
    public void reloadAll() {
        reloadConfig();
        settings = new Settings(getConfig());
        settings.warnings.forEach(getLogger()::warning);
        data.load();
        archers.lowerAll();
        shields.lowerAll(); // 構えたままの遅い歩きを、新しい設定の速さに戻してから入れ直す
        startTasks();
        bots.reapplySettings();
        spawnSavedBots();
    }

    private void startTasks() {
        if (botTask != null) {
            botTask.cancel();
        }
        botTask = Bukkit.getScheduler().runTaskTimer(this, () -> bots.tick(), 40L, BotManager.TICK_PERIOD);
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
        chaseTask = Bukkit.getScheduler().runTaskTimer(this, () -> bots.checkChasePaths(), 40L, BotManager.CHASE_CHECK_PERIOD);
    }

    /**
     * /sb spawn で出したボット（持ち主なし。試験用で、test-bots.enabled のときだけ）のうち、今いないものを陣営のリスポーン地点に出す。
     * 起動時と、/sb revive bots・all（倒れて消えたものを出し直す）で呼ぶ。出した数を返す。
     */
    public int spawnSavedBots() {
        if (!settings.testBots) {
            return 0; // 持ち主のいないボットは試験用（test-bots.enabled）。data.yml の一覧は消さずに残す
        }
        int n = 0;
        for (Map.Entry<String, Side> e : data.bots().entrySet()) {
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

    public Settings settings() {
        return settings;
    }

    public SiegeData data() {
        return data;
    }

    public BotManager bots() {
        return bots;
    }

    /** 盾の構え方。 */
    Archers archers() {
        return archers;
    }

    Shields shields() {
        return shields;
    }

    /** /sbc と指揮メニュー（sbc が plugin.yml にないときは null）。 */
    /** 兵科選択のアイテム（起動の途中は null のことがある）。 */
    UnitSelectItem unitItem() {
        return unitItem;
    }

    PlayerCommand playerCommand() {
        return playerCommand;
    }

    /** CombatLog の「戦闘中」の判定。 */
    CombatCheck combat() {
        return combat;
    }
}
