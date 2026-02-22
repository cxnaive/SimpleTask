package dev.user.simpletask.config;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.ExpirePolicy;
import dev.user.simpletask.task.Reward;
import dev.user.simpletask.task.TaskTemplate;
import dev.user.simpletask.task.TaskType;
import dev.user.simpletask.task.category.TaskCategory;
import dev.user.simpletask.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;

public class ConfigManager {

    private final SimpleTaskPlugin plugin;
    private FileConfiguration config;
    private FileConfiguration tasksConfig;
    private File tasksFile;

    // Template sync settings
    private int templateSyncInterval;

    // Data retention settings
    private int dataRetentionDays;
    private int taskCheckIntervalMinutes;

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
    private String guiTitleAdmin;

    // Messages
    private String messagePrefix;
    private Map<String, String> messages;

    // Message cache (Component level)
    private final Map<String, Component> messageCache = new HashMap<>();

    // Task categories
    private final Map<String, TaskCategory> taskCategories = new HashMap<>();

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
        this.templateSyncInterval = config.getInt("template.sync-interval", 0); // 0 = disabled
        this.dataRetentionDays = config.getInt("data.retention-days", 7); // 默认保留7天
        this.taskCheckIntervalMinutes = config.getInt("task-check.interval-minutes", 5); // 默认5分钟

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
        this.guiTitleAdmin = config.getString("gui.titles.admin", "<dark_gray>任务管理");

        // Messages
        this.messagePrefix = config.getString("messages.prefix", "<gold>[任务系统] <reset>");
        this.messages = new HashMap<>();
        loadMessages();

        // Clear message cache on reload
        messageCache.clear();

        // Load task categories
        loadTaskCategories();
    }

    /**
     * 加载任务类别配置
     */
    private void loadTaskCategories() {
        taskCategories.clear();

        ConfigurationSection section = config.getConfigurationSection("task-categories");
        if (section == null) {
            // 如果没有配置，加载默认类别
            loadDefaultCategories();
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection catSection = section.getConfigurationSection(key);
            if (catSection == null) continue;

            TaskCategory category = parseCategory(key, catSection);
            taskCategories.put(key, category);
            plugin.getLogger().info("[Config] Loaded task category: " + key);
        }

        // 确保至少有 daily 类别
        if (!taskCategories.containsKey("daily")) {
            taskCategories.put("daily", createDefaultDailyCategory());
        }
    }

    /**
     * 解析类别配置
     */
    private TaskCategory parseCategory(String id, ConfigurationSection section) {
        TaskCategory category = new TaskCategory(id);
        category.setEnabled(section.getBoolean("enabled", true));

        // 显示配置
        ConfigurationSection displaySection = section.getConfigurationSection("display");
        if (displaySection != null) {
            category.setDisplayName(displaySection.getString("name", "<yellow>" + id));
            category.setLore(displaySection.getStringList("lore"));
            category.setItem(displaySection.getString("item", "minecraft:paper"));
            category.setSlot(displaySection.getInt("slot", -1));
        }

        // 分配配置
        ConfigurationSection assignSection = section.getConfigurationSection("assignment");
        if (assignSection != null) {
            category.setMaxConcurrent(assignSection.getInt("max-concurrent", 3));
            category.setAutoAssign(assignSection.getBoolean("auto-assign", true));
            category.setExpirePolicy(ExpirePolicy.fromString(assignSection.getString("expire-policy", "daily")));
            category.setAutoClaim(assignSection.getBoolean("auto-claim", false));

            String resetAfter = assignSection.getString("reset-after-complete");
            if (resetAfter != null) {
                category.setResetAfterComplete(parseDuration(resetAfter));
            }
        }

        // 刷新配置
        ConfigurationSection resetSection = section.getConfigurationSection("reset");
        if (resetSection != null) {
            String timeStr = resetSection.getString("time", "04:00");
            try {
                category.setResetTime(LocalTime.parse(timeStr));
            } catch (DateTimeParseException e) {
                category.setResetTime(LocalTime.of(4, 0));
            }

            String dayOfWeek = resetSection.getString("day-of-week", "MONDAY");
            try {
                category.setResetDayOfWeek(DayOfWeek.valueOf(dayOfWeek.toUpperCase()));
            } catch (IllegalArgumentException e) {
                category.setResetDayOfWeek(DayOfWeek.MONDAY);
            }

            category.setResetDayOfMonth(resetSection.getInt("day-of-month", 1));
        }

        // 相对时间配置
        ConfigurationSection relativeSection = section.getConfigurationSection("relative");
        if (relativeSection != null) {
            String duration = relativeSection.getString("default-duration", "7d");
            category.setDefaultDuration(parseDuration(duration));

            String warning = relativeSection.getString("warning-before", "24h");
            category.setWarningBefore(parseDuration(warning));
        }

        // 固定时间配置
        ConfigurationSection fixedSection = section.getConfigurationSection("fixed");
        if (fixedSection != null) {
            category.setFixedStart(fixedSection.getString("start"));
            category.setFixedEnd(fixedSection.getString("end"));
        }

        // Reroll 配置
        ConfigurationSection rerollSection = section.getConfigurationSection("reroll");
        if (rerollSection != null) {
            category.setRerollEnabled(rerollSection.getBoolean("enabled", true));
            category.setRerollCost(rerollSection.getDouble("cost", 0.0));
            category.setRerollMaxCount(rerollSection.getInt("max-count", 3));
            category.setRerollKeepCompleted(rerollSection.getBoolean("keep-completed", true));

            // 重置策略（与任务过期策略相同）
            String resetPolicy = rerollSection.getString("reset-policy", "daily");
            category.setRerollResetPolicy(ExpirePolicy.fromString(resetPolicy));

            // 重置时间
            String resetTimeStr = rerollSection.getString("reset-time", "04:00");
            try {
                category.setRerollResetTime(LocalTime.parse(resetTimeStr));
            } catch (DateTimeParseException e) {
                category.setRerollResetTime(LocalTime.of(4, 0));
            }

            // 周常重置星期几
            String resetDayOfWeek = rerollSection.getString("reset-day-of-week", "MONDAY");
            try {
                category.setRerollResetDayOfWeek(DayOfWeek.valueOf(resetDayOfWeek.toUpperCase()));
            } catch (IllegalArgumentException e) {
                category.setRerollResetDayOfWeek(DayOfWeek.MONDAY);
            }

            // 月常重置日期
            category.setRerollResetDayOfMonth(rerollSection.getInt("reset-day-of-month", 1));

            // 相对时间重置的持续时间
            String resetDuration = rerollSection.getString("reset-duration", "1d");
            category.setRerollResetDuration(parseDuration(resetDuration));
        }

        return category;
    }

    /**
     * 解析持续时间字符串 (如 "7d", "24h", "30m")
     */
    private Duration parseDuration(String input) {
        if (input == null || input.isEmpty()) {
            return Duration.ofDays(7);
        }

        input = input.trim().toLowerCase();
        try {
            if (input.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(input.substring(0, input.length() - 1)));
            } else if (input.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(input.substring(0, input.length() - 1)));
            } else if (input.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(input.substring(0, input.length() - 1)));
            } else if (input.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(input.substring(0, input.length() - 1)));
            } else {
                // 纯数字，默认为天
                return Duration.ofDays(Long.parseLong(input));
            }
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("Invalid duration format: " + input + ", using default 7d");
            return Duration.ofDays(7);
        }
    }

    /**
     * 加载默认类别（当没有配置时）
     */
    private void loadDefaultCategories() {
        taskCategories.put("daily", createDefaultDailyCategory());
        plugin.getLogger().info("[Config] Loaded default daily category");
    }

    /**
     * 创建默认 daily 类别
     */
    private TaskCategory createDefaultDailyCategory() {
        TaskCategory category = new TaskCategory("daily");
        category.setDisplayName("<gold><bold>每日任务");
        category.setLore(Arrays.asList(
            "<gray>每天更新的任务",
            "<gray>每日 <yellow>4:00 <gray>刷新"
        ));
        category.setItem("minecraft:clock");
        category.setSlot(0);
        category.setMaxConcurrent(5);
        category.setAutoAssign(true);
        category.setExpirePolicy(ExpirePolicy.DAILY);
        category.setResetTime(LocalTime.of(4, 0));
        return category;
    }

    /**
     * 获取任务类别
     */
    public TaskCategory getTaskCategory(String id) {
        return taskCategories.get(id);
    }

    /**
     * 获取所有启用的类别
     */
    public Collection<TaskCategory> getEnabledCategories() {
        return taskCategories.values().stream()
            .filter(TaskCategory::isEnabled)
            .toList();
    }

    /**
     * 获取所有类别
     */
    public Collection<TaskCategory> getAllCategories() {
        return taskCategories.values();
    }

    /**
     * 获取所有类别（返回 Map）
     */
    public Map<String, TaskCategory> getTaskCategories() {
        return new HashMap<>(taskCategories);
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

    public int getTemplateSyncInterval() {
        return templateSyncInterval;
    }

    public int getDataRetentionDays() {
        return dataRetentionDays;
    }

    public int getTaskCheckIntervalMinutes() {
        return taskCheckIntervalMinutes;
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
    public String getGuiTitleAdmin() {
        return guiTitleAdmin;
    }

    public String getGuiTitleTaskCategories() {
        return config.getString("gui.title.task-categories", "<gold><bold>任务系统");
    }

    public String getGuiDecoration(String key) {
        return config.getString("gui.decoration." + key + ".material", "minecraft:black_stained_glass_pane");
    }

    // Messages
    /**
     * 获取消息（自动添加prefix）
     * 注意：返回的字符串包含prefix，但不包含任何placeholder替换
     */
    public String getMessage(String key) {
        String msg = messages.getOrDefault(key, key);
        return messagePrefix + msg;
    }

    /**
     * 获取原始消息（不包含prefix，用于进一步处理）
     */
    public String getRawMessage(String key) {
        return messages.getOrDefault(key, key);
    }

    /**
     * 获取缓存的Component消息（用于频繁访问的GUI消息）
     * 性能优于每次重新解析MiniMessage字符串
     */
    public Component getCachedMessage(String key) {
        return messageCache.computeIfAbsent(key, k ->
            MessageUtil.parse(messages.getOrDefault(k, k))
        );
    }

    /**
     * 获取缓存的Component（带文本placeholder替换）
     */
    public Component getCachedMessage(String key, Map<String, String> placeholders) {
        Component base = getCachedMessage(key);
        if (placeholders == null || placeholders.isEmpty()) {
            return base;
        }
        return MessageUtil.parse(messages.getOrDefault(key, key), placeholders);
    }

    /**
     * 清除消息缓存（在配置重载时自动调用）
     */
    public void clearMessageCache() {
        messageCache.clear();
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

    /**
     * 获取管理员消息（原始消息，不包含placeholder替换）
     */
    public String getAdminMessage(String key) {
        return config.getString("admin." + key, key);
    }

    public String getTaskTypeName(TaskType type) {
        return config.getString("task-types." + type.name(), type.getDisplayName());
    }

    public String getStatusMessage(String key) {
        return config.getString("status." + key, key);
    }

    /**
     * 获取GUI消息（原始消息，不包含placeholder替换）
     */
    public String getGuiMessage(String key) {
        return config.getString("gui.task-item." + key, key);
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
        String category = section.getString("category", "daily");

        // Parse reward
        Reward reward = parseReward(section.getConfigurationSection("reward"));

        TaskTemplate template = new TaskTemplate(key, name, type, targetItems, targetAmount, description, icon, weight, reward);
        template.setCategory(category);

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
