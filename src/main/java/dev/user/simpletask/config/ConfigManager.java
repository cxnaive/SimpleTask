package dev.user.simpletask.config;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.Reward;
import dev.user.simpletask.task.TaskTemplate;
import dev.user.simpletask.task.TaskType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

public class ConfigManager {

    private final SimpleTaskPlugin plugin;
    private FileConfiguration config;
    private FileConfiguration tasksConfig;
    private File tasksFile;

    // Daily task settings
    private int dailyTaskCount;
    private boolean autoClaim;
    private int dailyRerollMax;
    private double dailyRerollCost;

    // Template sync settings
    private int templateSyncInterval;

    // Data retention settings
    private int dataRetentionDays;

    // Anti-cheat settings
    private boolean antiCheatEnabled;
    private int antiCheatTimeWindow;

    // Database settings
    private String databaseType;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;
    private int mysqlPoolSize;
    private String h2Filename;

    // GUI settings
    private String guiTitleDailyTasks;
    private String guiTitleAdmin;

    // Messages
    private String messagePrefix;
    private Map<String, String> messages;

    public ConfigManager(SimpleTaskPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        loadTasksConfig();
        loadConfig();
    }

    private void loadTasksConfig() {
        tasksFile = new File(plugin.getDataFolder(), "tasks.yml");
        if (!tasksFile.exists()) {
            plugin.saveResource("tasks.yml", false);
        }
        tasksConfig = YamlConfiguration.loadConfiguration(tasksFile);
    }

    public void loadConfig() {
        // Daily tasks
        this.dailyTaskCount = config.getInt("daily-tasks.daily-count", 5);
        this.autoClaim = config.getBoolean("daily-tasks.auto-claim", false);
        this.dailyRerollMax = config.getInt("daily-tasks.reroll.max-per-day", 3);
        this.dailyRerollCost = config.getDouble("daily-tasks.reroll.cost", 100.0);
        this.templateSyncInterval = config.getInt("template.sync-interval", 0); // 0 = disabled
        this.dataRetentionDays = config.getInt("data.retention-days", 7); // 默认保留7天

        // Anti-cheat settings
        this.antiCheatEnabled = config.getBoolean("anti-cheat.enabled", true);
        this.antiCheatTimeWindow = config.getInt("anti-cheat.time-window", 3600); // 默认60分钟

        // Database
        this.databaseType = config.getString("database.type", "h2").toLowerCase();
        this.mysqlHost = config.getString("database.mysql.host", "localhost");
        this.mysqlPort = config.getInt("database.mysql.port", 3306);
        this.mysqlDatabase = config.getString("database.mysql.database", "simpletask");
        this.mysqlUsername = config.getString("database.mysql.username", "root");
        this.mysqlPassword = config.getString("database.mysql.password", "password");
        this.mysqlPoolSize = config.getInt("database.mysql.pool-size", 10);
        this.h2Filename = config.getString("database.h2.filename", "simpletask");

        // GUI titles
        this.guiTitleDailyTasks = config.getString("gui.titles.daily-tasks", "<dark_gray>每日任务");
        this.guiTitleAdmin = config.getString("gui.titles.admin", "<dark_gray>任务管理");

        // Messages
        this.messagePrefix = config.getString("messages.prefix", "<gold>[每日任务] <reset>");
        this.messages = new HashMap<>();
        loadMessages();
    }

    private void loadMessages() {
        ConfigurationSection msgSection = config.getConfigurationSection("messages");
        if (msgSection != null) {
            for (String key : msgSection.getKeys(false)) {
                if (!key.equals("prefix")) {
                    messages.put(key, msgSection.getString(key, ""));
                }
            }
        }
    }

    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        loadTasksConfig();
        loadConfig();
    }

    /**
     * 获取 tasks.yml 中的所有任务 key
     */
    public Set<String> getTaskKeysFromConfig() {
        Set<String> keys = new HashSet<>();
        // tasks.yml 直接在根级别定义任务
        for (String key : tasksConfig.getKeys(false)) {
            if (tasksConfig.isConfigurationSection(key)) {
                keys.add(key);
            }
        }
        // 也检查主配置文件的 templates 部分（向后兼容）
        ConfigurationSection section = config.getConfigurationSection("templates");
        if (section != null) {
            keys.addAll(section.getKeys(false));
        }
        return keys;
    }

    /**
     * 从配置文件加载指定 key 的任务模板
     *
     * @param taskKey 任务 key，null 或 "all" 表示加载所有
     * @return 任务模板列表
     */
    public List<TaskTemplate> loadTemplatesFromConfig(String taskKey) {
        List<TaskTemplate> templates = new ArrayList<>();

        if (taskKey == null || taskKey.equalsIgnoreCase("all")) {
            // 加载所有任务
            loadAllTemplates(templates);
            loadAllTemplatesFromConfig(templates); // 向后兼容
        } else {
            // 加载指定 key 的任务
            TaskTemplate template = loadSingleTemplate(taskKey);
            if (template == null) {
                template = loadSingleTemplateFromConfig(taskKey); // 尝试主配置文件
            }
            if (template != null) {
                templates.add(template);
            } else {
                plugin.getLogger().warning("Task template not found in config: " + taskKey);
            }
        }

        return templates;
    }

    private void loadAllTemplates(List<TaskTemplate> templates) {
        // 从 tasks.yml 根级别加载
        for (String key : tasksConfig.getKeys(false)) {
            if (!tasksConfig.isConfigurationSection(key)) continue;
            ConfigurationSection templateSection = tasksConfig.getConfigurationSection(key);
            if (templateSection == null) continue;

            try {
                TaskTemplate template = parseTemplate(key, templateSection);
                if (template != null) {
                    templates.add(template);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load template: " + key + " - " + e.getMessage());
            }
        }
    }

    private void loadAllTemplatesFromConfig(List<TaskTemplate> templates) {
        // 从主配置文件的 templates 部分加载（向后兼容）
        ConfigurationSection section = config.getConfigurationSection("templates");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ConfigurationSection templateSection = section.getConfigurationSection(key);
            if (templateSection == null) continue;

            try {
                TaskTemplate template = parseTemplate(key, templateSection);
                if (template != null) {
                    templates.add(template);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load template: " + key + " - " + e.getMessage());
            }
        }
    }

    private TaskTemplate loadSingleTemplate(String taskKey) {
        ConfigurationSection section = tasksConfig.getConfigurationSection(taskKey);
        if (section == null) return null;

        try {
            return parseTemplate(taskKey, section);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load template: " + taskKey + " - " + e.getMessage());
            return null;
        }
    }

    private TaskTemplate loadSingleTemplateFromConfig(String taskKey) {
        ConfigurationSection section = config.getConfigurationSection("templates." + taskKey);
        if (section == null) return null;

        try {
            return parseTemplate(taskKey, section);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load template: " + taskKey + " - " + e.getMessage());
            return null;
        }
    }

    // Daily task settings
    public int getDailyTaskCount() {
        return dailyTaskCount;
    }

    public boolean isAutoClaim() {
        return autoClaim;
    }

    public int getDailyRerollMax() {
        return dailyRerollMax;
    }

    public double getDailyRerollCost() {
        return dailyRerollCost;
    }

    public int getTemplateSyncInterval() {
        return templateSyncInterval;
    }

    public int getDataRetentionDays() {
        return dataRetentionDays;
    }

    // Anti-cheat settings
    public boolean isAntiCheatEnabled() {
        return antiCheatEnabled;
    }

    public int getAntiCheatTimeWindow() {
        return antiCheatTimeWindow;
    }

    // Database settings
    public String getDatabaseType() {
        return databaseType;
    }

    public String getMysqlHost() {
        return mysqlHost;
    }

    public int getMysqlPort() {
        return mysqlPort;
    }

    public String getMysqlDatabase() {
        return mysqlDatabase;
    }

    public String getMysqlUsername() {
        return mysqlUsername;
    }

    public String getMysqlPassword() {
        return mysqlPassword;
    }

    public int getMysqlPoolSize() {
        return mysqlPoolSize;
    }

    public String getH2Filename() {
        return h2Filename;
    }

    // GUI settings
    public String getGuiTitleDailyTasks() {
        return guiTitleDailyTasks;
    }

    public String getGuiTitleAdmin() {
        return guiTitleAdmin;
    }

    public String getGuiDecoration(String key) {
        return config.getString("gui.decoration." + key + ".material", "minecraft:black_stained_glass_pane");
    }

    // Messages
    public String getMessage(String key) {
        String msg = messages.getOrDefault(key, key);
        return messagePrefix + msg;
    }

    public String getMessage(String key, Map<String, String> placeholders) {
        String msg = getMessage(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            msg = msg.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return msg;
    }

    public String getRawMessage(String key) {
        return messages.getOrDefault(key, key);
    }

    public String getPrefix() {
        return messagePrefix;
    }

    // 本地化消息获取方法
    public String getCommandMessage(String key) {
        return config.getString("commands." + key, key);
    }

    public List<String> getCommandHelpMessages(String key) {
        return config.getStringList("commands.help-" + key);
    }

    public String getAdminMessage(String key) {
        return config.getString("admin." + key, key);
    }

    public String getAdminMessage(String key, Map<String, String> placeholders) {
        String msg = getAdminMessage(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            msg = msg.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return msg;
    }

    public String getTaskTypeName(TaskType type) {
        return config.getString("task-types." + type.name(), type.getDisplayName());
    }

    public String getStatusMessage(String key) {
        return config.getString("status." + key, key);
    }

    public String getGuiMessage(String key) {
        return config.getString("gui.task-item." + key, key);
    }

    public String getGuiMessage(String key, Map<String, String> placeholders) {
        String msg = getGuiMessage(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            msg = msg.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return msg;
    }

    public String getEconomyMessage(String key) {
        return config.getString("economy." + key, key);
    }

    public String formatCurrency(double amount) {
        String format = getEconomyMessage("currency-format");
        String currency = getEconomyMessage("currency-name");
        return format.replace("{amount}", String.format("%.0f", amount))
                    .replace("{currency}", currency);
    }

    private TaskTemplate parseTemplate(String key, ConfigurationSection section) {
        String typeStr = section.getString("type");
        if (typeStr == null) {
            plugin.getLogger().warning("Template " + key + " missing type");
            return null;
        }

        TaskType type;
        try {
            type = TaskType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid task type for template " + key + ": " + typeStr);
            return null;
        }

        // 解析目标物品（支持字符串或列表）
        List<String> targetItems;
        if (section.isList("target-item")) {
            targetItems = section.getStringList("target-item");
        } else {
            String targetItem = section.getString("target-item", null);
            targetItems = targetItem != null ? Arrays.asList(targetItem) : new ArrayList<>();
        }

        int targetAmount = section.getInt("target-amount", type.requiresTarget() ? 1 : 1);
        String name = section.getString("name", key); // 任务名称，默认使用 taskKey
        String description = section.getString("description", "完成任务");
        String icon = section.getString("icon", getDefaultIconForType(type));
        int weight = section.getInt("weight", 10);

        // Parse reward
        Reward reward = parseReward(section.getConfigurationSection("reward"));

        TaskTemplate template = new TaskTemplate(key, name, type, targetItems, targetAmount, description, icon, weight, reward);

        // 解析版本号（可选，默认为1）
        int version = section.getInt("version", 1);
        template.setVersion(version);

        // 解析NBT匹配条件（可选）
        List<String> nbtMatchConditions = section.getStringList("nbt-match");

        // 为不支持NBT匹配的任务类型添加警告
        if (!nbtMatchConditions.isEmpty()) {
            switch (type) {
                case KILL, BREAK, HARVEST, BREED -> {
                    plugin.getLogger().warning("[Config] 任务 '" + key + "' (类型: " + type + ") 配置了NBT匹配条件，" +
                        "但当前版本不支持该类型任务的NBT匹配。配置将被保存但不生效。");
                }
                case CHAT -> {
                    plugin.getLogger().warning("[Config] 任务 '" + key + "' (类型: CHAT) 配置了NBT匹配条件，" +
                        "CHAT任务类型不支持NBT匹配（聊天消息无NBT数据）。配置将被忽略。");
                }
            }
        }
        template.setNbtMatchConditions(nbtMatchConditions);

        // 解析方块状态匹配条件（可选，用于BREAK/HARVEST任务）
        List<String> blockStateConditions = section.getStringList("block-state");
        template.setBlockStateConditions(blockStateConditions);

        return template;
    }

    private String getDefaultIconForType(TaskType type) {
        return switch (type) {
            case CHAT -> "minecraft:writable_book";
            case CRAFT -> "minecraft:crafting_table";
            case FISH -> "minecraft:fishing_rod";
            case CONSUME -> "minecraft:apple";
            case BREAK -> "minecraft:iron_pickaxe";
            case HARVEST -> "minecraft:wheat";
            case SUBMIT -> "minecraft:chest";
            case KILL -> "minecraft:iron_sword";
            case BREED -> "minecraft:wheat";
        };
    }

    private Reward parseReward(ConfigurationSection section) {
        if (section == null) {
            return new Reward(0, new ArrayList<>(), new ArrayList<>());
        }

        double money = section.getDouble("money", 0);
        List<Reward.RewardItem> items = new ArrayList<>();
        List<String> commands = section.getStringList("commands");

        // Parse items
        List<Map<?, ?>> itemList = section.getMapList("items");
        for (Map<?, ?> map : itemList) {
            String itemKey = (String) map.get("item");
            int amount = map.get("amount") instanceof Number ? ((Number) map.get("amount")).intValue() : 1;
            if (itemKey != null) {
                items.add(new Reward.RewardItem(itemKey, amount));
            }
        }

        return new Reward(money, items, commands);
    }
}
