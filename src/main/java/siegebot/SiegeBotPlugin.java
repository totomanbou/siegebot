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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig());
        data = new SiegeData(new File(getDataFolder(), "data.yml"), getLogger());
        data.load();
        bots = new BotManager(this);

        Bukkit.getPluginManager().registerEvents(new GameListener(this), this);
        PluginCommand cmd = getCommand("siegebot");
        if (cmd != null) {
            SiegeBotCommand executor = new SiegeBotCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
        PluginCommand sbc = getCommand("sbc");
        if (sbc != null) {
            PlayerCommand pc = new PlayerCommand(this);
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
        data.load();
        startTasks();
        bots.reapplySettings();
        spawnSavedBots();
    }

    private void startTasks() {
        if (botTask != null) {
            botTask.cancel();
        }
        botTask = Bukkit.getScheduler().runTaskTimer(this, () -> bots.tick(), 40L, BotManager.TICK_PERIOD);
    }

    /** /sb spawn で出したボット（持ち主なし）を、陣営のリスポーン地点に出す。 */
    private void spawnSavedBots() {
        for (Map.Entry<String, Side> e : data.bots().entrySet()) {
            if (bots.get(e.getKey()) == null && bots.spawn(e.getKey(), e.getValue(), null) == null) {
                getLogger().warning("ボット " + e.getKey() + " を出せません（/sb spawnpoint "
                        + e.getValue().key() + " でリスポーン地点を設定してください）");
            }
        }
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
}
