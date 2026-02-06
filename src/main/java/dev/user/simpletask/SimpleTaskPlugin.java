package dev.user.simpletask;

import dev.user.simpletask.anticheat.AntiCheatManager;
import dev.user.simpletask.api.TaskAPI;
import dev.user.simpletask.command.AdminCommand;
import dev.user.simpletask.command.SimpleTaskCommand;
import dev.user.simpletask.config.ConfigManager;
import dev.user.simpletask.database.DatabaseManager;
import dev.user.simpletask.database.DatabaseQueue;
import dev.user.simpletask.economy.EconomyManager;
import dev.user.simpletask.gui.GUIManager;
import dev.user.simpletask.listener.GUIListener;
import dev.user.simpletask.listener.TaskListener;
import dev.user.simpletask.task.TaskManager;
import dev.user.simpletask.util.ItemUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class SimpleTaskPlugin extends JavaPlugin {

    private static SimpleTaskPlugin instance;

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private DatabaseQueue databaseQueue;
    private EconomyManager economyManager;
    private TaskManager taskManager;
    private GUIManager guiManager;
    private AntiCheatManager antiCheatManager;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config
        saveDefaultConfig();

        // Initialize configuration
        this.configManager = new ConfigManager(this);

        // Initialize ItemUtil
        ItemUtil.init(this);

        // Initialize database
        try {
            this.databaseManager = new DatabaseManager(this);
            if (!this.databaseManager.init()) {
                getLogger().severe("Database initialization failed!");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            this.databaseQueue = new DatabaseQueue(this);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize database", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize economy manager
        this.economyManager = new EconomyManager(this);

        // Initialize task manager
        this.taskManager = new TaskManager(this);

        // Load tasks for all online players (handles plugin reloads)
        loadOnlinePlayerTasks();

        // Initialize GUI manager
        this.guiManager = new GUIManager();

        // Initialize anti-cheat manager
        this.antiCheatManager = new AntiCheatManager(this);

        // Register listeners
        getServer().getPluginManager().registerEvents(new GUIListener(guiManager), this);
        getServer().getPluginManager().registerEvents(new TaskListener(this), this);

        // Register commands
        getCommand("task").setExecutor(new SimpleTaskCommand(this));
        AdminCommand adminCommand = new AdminCommand(this);
        getCommand("taskadmin").setExecutor(adminCommand);
        getCommand("taskadmin").setTabCompleter(adminCommand);

        // Initialize API
        TaskAPI.initialize(this);

        getLogger().info("SimpleTask has been enabled!");
    }

    @Override
    public void onDisable() {
        if (databaseQueue != null) {
            databaseQueue.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("SimpleTask has been disabled!");
    }

    public static SimpleTaskPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public DatabaseQueue getDatabaseQueue() {
        return databaseQueue;
    }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public TaskManager getTaskManager() {
        return taskManager;
    }

    public GUIManager getGuiManager() {
        return guiManager;
    }

    public AntiCheatManager getAntiCheatManager() {
        return antiCheatManager;
    }

    /**
     * 插件启动时加载所有在线玩家的任务（处理插件重载）
     */
    private void loadOnlinePlayerTasks() {
        if (getServer().getOnlinePlayers().isEmpty()) {
            return;
        }

        getLogger().info("Loading tasks for " + getServer().getOnlinePlayers().size() + " online players...");

        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            taskManager.loadPlayerTasks(player);
        }
    }
}
