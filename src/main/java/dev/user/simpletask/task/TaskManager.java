package dev.user.simpletask.task;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.database.DatabaseQueue;
import dev.user.simpletask.util.ItemUtil;
import dev.user.simpletask.util.MessageUtil;
import dev.user.simpletask.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class TaskManager {

    private final SimpleTaskPlugin plugin;
    private final DatabaseQueue databaseQueue;
    private final TemplateSyncManager templateSyncManager;

    // 缓存玩家任务 - 使用 CopyOnWriteArrayList 保证线程安全
    private final Map<UUID, CopyOnWriteArrayList<PlayerTask>> playerTasks = new ConcurrentHashMap<>();
    private final Map<UUID, LocalDate> playerResetDates = new ConcurrentHashMap<>();

    public TaskManager(SimpleTaskPlugin plugin) {
        this.plugin = plugin;
        this.databaseQueue = plugin.getDatabaseQueue();
        this.templateSyncManager = new TemplateSyncManager(plugin);

        // Initialize ItemUtil
        ItemUtil.init(plugin);

        // 启动时从数据库加载模板
        templateSyncManager.loadFromDatabase();

        // 启动定时模板同步（如果配置了）
        templateSyncManager.startPeriodicSync();

        // Start daily reset checker
        startResetChecker();

        // 启动过期数据清理器
        startDataCleanupScheduler();
    }

    /**
     * 获取模板同步管理器
     */
    public TemplateSyncManager getTemplateSyncManager() {
        return templateSyncManager;
    }

    /**
     * @deprecated 使用 TemplateSyncManager.getAllTemplates()
     */
    @Deprecated
    public void loadTemplates() {
        // 模板现在由 TemplateSyncManager 管理
    }

    /**
     * 玩家登录时加载其每日任务
     */
    public void loadPlayerTasks(Player player) {
        UUID uuid = player.getUniqueId();
        LocalDate today = TimeUtil.getToday();

        // 先清除旧缓存
        playerTasks.remove(uuid);
        playerResetDates.remove(uuid);

        // 清理该玩家的过期任务数据
        cleanupPlayerExpiredTasks(player);

        // 提交到队列执行 - 队列自动管理连接
        databaseQueue.submit("loadPlayerTasks", (conn) -> {
            String sql = "SELECT last_reset_date FROM player_task_reset WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        java.sql.Date resetDate = rs.getDate("last_reset_date");
                        LocalDate lastReset = resetDate.toLocalDate();
                        playerResetDates.put(uuid, lastReset);

                        if (!lastReset.equals(today)) {
                            // 需要重置，回到主线程调用（避免嵌套队列）
                            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> generateNewDailyTasks(player));
                        } else {
                            loadTodayTasks(player, today);
                        }
                    } else {
                        // 首次登录
                        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> generateNewDailyTasks(player));
                    }
                }
            }
            return null;
        }, null, e -> plugin.getLogger().log(Level.SEVERE, "Failed to load player tasks", e));
    }

    /**
     * 加载玩家今日任务
     */
    private void loadTodayTasks(Player player, LocalDate today) {
        UUID uuid = player.getUniqueId();

        databaseQueue.submit("loadTodayTasks", (conn) -> {
            CopyOnWriteArrayList<PlayerTask> tasks = new CopyOnWriteArrayList<>();
            String sql = """
                SELECT * FROM player_daily_tasks
                WHERE player_uuid = ? AND task_date = ?
                """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setDate(2, java.sql.Date.valueOf(today));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PlayerTask task = parsePlayerTaskFromResultSet(uuid, rs);
                        if (task != null) {
                            tasks.add(task);
                        }
                    }
                }

                if (tasks.isEmpty()) {
                    plugin.getLogger().info("No tasks found for player " + player.getName() + " on " + today + ", generating new tasks...");
                    plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                        clearResetDate(uuid);
                        generateNewDailyTasks(player);
                    });
                } else {
                    playerTasks.put(uuid, tasks);
                }
            }
            return null;
        }, null, e -> plugin.getLogger().log(Level.SEVERE, "Failed to load today tasks", e));
    }

    private void clearResetDate(UUID uuid) {
        databaseQueue.submit("clearResetDate", (conn) -> {
            String sql = "DELETE FROM player_task_reset WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private PlayerTask parsePlayerTaskFromResultSet(UUID playerUuid, ResultSet rs) throws SQLException {
        String taskKey = rs.getString("task_key");
        int progress = rs.getInt("current_progress");
        boolean completed = rs.getBoolean("completed");
        boolean claimed = rs.getBoolean("claimed");
        LocalDate taskDate = ((java.sql.Date) rs.getDate("task_date")).toLocalDate();

        TaskTemplate effectiveTemplate = null;
        String taskData = rs.getString("task_data");
        if (taskData != null && !taskData.isEmpty()) {
            try {
                effectiveTemplate = TaskTemplate.fromJson(taskData);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to parse task data snapshot for " + taskKey);
            }
        }

        if (effectiveTemplate == null) {
            effectiveTemplate = templateSyncManager.getTemplate(taskKey);
        }

        if (effectiveTemplate == null) {
            plugin.getLogger().warning("Task template not found for: " + taskKey);
            return null;
        }

        return new PlayerTask(playerUuid, taskKey, effectiveTemplate, progress, completed, claimed, taskDate);
    }

    /**
     * 生成新的每日任务
     */
    public void generateNewDailyTasks(Player player) {
        generateNewDailyTasks(player, true, null);
    }

    public void generateNewDailyTasks(Player player, boolean notify, Runnable callback) {
        UUID uuid = player.getUniqueId();
        LocalDate today = TimeUtil.getToday();
        int taskCount = plugin.getConfigManager().getDailyTaskCount();

        // 先检查是否有可用模板
        if (templateSyncManager.getAllTemplates().isEmpty()) {
            plugin.getLogger().warning("Cannot generate tasks: no task templates available");
            player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<red>系统错误：没有可用的任务模板，请联系管理员"));
            return;
        }

        databaseQueue.submit("generateNewDailyTasks", (conn) -> {
            doGenerateTasks(conn, player, uuid, today, taskCount, notify);
            if (callback != null) {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, callback);
            }
            return null;
        }, null, e -> plugin.getLogger().log(Level.SEVERE, "Failed to generate daily tasks", e));
    }

    /**
     * 同步生成任务 - 必须在队列线程中调用
     */
    private void doGenerateTasks(Connection conn, Player player, UUID uuid, LocalDate today, int taskCount, boolean notify) throws SQLException {
        // 先选择任务模板
        List<TaskTemplate> selectedTasks = selectRandomTasks(taskCount);

        if (selectedTasks.isEmpty()) {
            plugin.getLogger().warning("No tasks selected for player " + player.getName() + ", check template availability");
            return;
        }

        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        List<PlayerTask> newTasks = new ArrayList<>();

        try {
            String deleteSql = "DELETE FROM player_daily_tasks WHERE player_uuid = ? AND task_date = ?";
            try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                ps.setString(1, uuid.toString());
                ps.setDate(2, java.sql.Date.valueOf(today));
                ps.executeUpdate();
            }

            String insertSql = """
                INSERT INTO player_daily_tasks
                (player_uuid, task_key, task_version, current_progress, completed, claimed, task_date, task_data)
                VALUES (?, ?, ?, 0, FALSE, FALSE, ?, ?)
                """;

            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                for (TaskTemplate template : selectedTasks) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, template.getTaskKey());
                    ps.setInt(3, template.getVersion());
                    ps.setDate(4, java.sql.Date.valueOf(today));
                    ps.setString(5, template.toJson());
                    ps.executeUpdate();

                    PlayerTask playerTask = new PlayerTask(uuid, template.getTaskKey(), template,
                        0, false, false, today);
                    newTasks.add(playerTask);
                }
            }

            // 更新重置记录 - 使用数据库特定的语法
            boolean isMySQL = plugin.getDatabaseManager().isMySQL();
            String updateResetSql;
            if (isMySQL) {
                updateResetSql = """
                    INSERT INTO player_task_reset (player_uuid, last_reset_date, reset_server, reroll_count)
                    VALUES (?, ?, ?, 0)
                    ON DUPLICATE KEY UPDATE
                    last_reset_date = VALUES(last_reset_date),
                    reset_server = VALUES(reset_server),
                    reroll_count = VALUES(reroll_count)
                    """;
            } else {
                updateResetSql = """
                    MERGE INTO player_task_reset (player_uuid, last_reset_date, reset_server, reroll_count)
                    KEY(player_uuid)
                    VALUES (?, ?, ?, 0)
                    """;
            }
            try (PreparedStatement ps = conn.prepareStatement(updateResetSql)) {
                ps.setString(1, uuid.toString());
                ps.setDate(2, java.sql.Date.valueOf(today));
                ps.setString(3, Bukkit.getServer().getName());
                ps.executeUpdate();
            }

            conn.commit();

            playerTasks.put(uuid, new CopyOnWriteArrayList<>(newTasks));
            playerResetDates.put(uuid, today);

            if (notify) {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                    if (player.isOnline()) {
                        MessageUtil.sendConfigMessage(plugin, player, "task-reset");
                    }
                });
            }

        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            try {
                conn.setAutoCommit(originalAutoCommit);
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to restore autoCommit: " + e.getMessage());
            }
        }
    }

    public void updatePlayerTaskCache(UUID uuid, List<PlayerTask> tasks) {
        if (tasks instanceof CopyOnWriteArrayList) {
            playerTasks.put(uuid, (CopyOnWriteArrayList<PlayerTask>) tasks);
        } else {
            playerTasks.put(uuid, new CopyOnWriteArrayList<>(tasks));
        }
    }

    public List<TaskTemplate> selectRandomTasks(int count) {
        List<TaskTemplate> available = new ArrayList<>(templateSyncManager.getAllTemplates());
        List<TaskTemplate> selected = new ArrayList<>();

        if (available.isEmpty()) {
            return selected;
        }

        int totalWeight = available.stream().mapToInt(TaskTemplate::getWeight).sum();
        if (totalWeight <= 0) {
            return selected;
        }

        Random random = new Random();

        for (int i = 0; i < count && !available.isEmpty(); i++) {
            int remainingWeight = available.stream().mapToInt(TaskTemplate::getWeight).sum();
            if (remainingWeight <= 0) break;

            int randomValue = random.nextInt(remainingWeight);
            int currentWeight = 0;

            for (Iterator<TaskTemplate> it = available.iterator(); it.hasNext(); ) {
                TaskTemplate template = it.next();
                currentWeight += template.getWeight();
                if (randomValue < currentWeight) {
                    selected.add(template);
                    it.remove();
                    break;
                }
            }
        }

        return selected;
    }

    public void updateProgress(Player player, TaskType type, String targetItem, int amount) {
        updateProgress(player, type, targetItem, null, amount);
    }

    /**
     * 更新任务进度（支持NBT匹配）
     * @param player 玩家
     * @param type 任务类型
     * @param targetItem 目标物品key
     * @param itemStack 实际物品（用于NBT匹配，可为null）
     * @param amount 增加的数量
     */
    public void updateProgress(Player player, TaskType type, String targetItem, org.bukkit.inventory.ItemStack itemStack, int amount) {
        UUID uuid = player.getUniqueId();
        CopyOnWriteArrayList<PlayerTask> tasks = playerTasks.get(uuid);

        if (tasks == null) {
            plugin.getLogger().fine("[TaskManager] No tasks loaded for player: " + player.getName());
            return;
        }

        plugin.getLogger().fine("[TaskManager] Updating progress for " + player.getName() +
            ", type=" + type + ", targetItem=" + targetItem + ", tasks=" + tasks.size());

        for (PlayerTask task : tasks) {
            TaskTemplate template = task.getTemplate();
            plugin.getLogger().fine("[TaskManager] Checking task: key=" + task.getTaskKey() +
                ", type=" + template.getType() + ", completed=" + task.isCompleted() + ", claimed=" + task.isClaimed());

            if (task.isCompleted() || task.isClaimed()) continue;

            if (template.getType() != type) {
                plugin.getLogger().fine("[TaskManager] Task type mismatch: expected=" + type + ", actual=" + template.getType());
                continue;
            }

            if (type.requiresTarget() && targetItem != null) {
                // 如果有NBT条件且提供了ItemStack，使用NBT匹配
                if (template.hasNbtMatchConditions() && itemStack != null) {
                    boolean matchesNbt = false;
                    for (String targetKey : template.getTargetItems()) {
                        if (ItemUtil.matchesTarget(itemStack, targetKey, template.getNbtMatchConditions())) {
                            matchesNbt = true;
                            break;
                        }
                    }
                    if (!matchesNbt) {
                        plugin.getLogger().fine("[TaskManager] NBT mismatch for task: " + task.getTaskKey());
                        continue;
                    }
                } else {
                    // 无NBT条件，使用key匹配
                    if (!template.matchesTarget(targetItem)) {
                        plugin.getLogger().fine("[TaskManager] Target mismatch: expected=" + template.getTargetItems() + ", actual=" + targetItem);
                        continue;
                    }
                }
            }

            plugin.getLogger().fine("[TaskManager] Task matched! key=" + task.getTaskKey() + ", updating progress");
            int oldProgress = task.getCurrentProgress();
            int newProgress = Math.min(oldProgress + amount, template.getTargetAmount());
            if (newProgress <= oldProgress) continue;

            boolean wasCompleted = task.isCompleted();
            boolean nowCompleted = newProgress >= template.getTargetAmount();
            boolean justCompleted = !wasCompleted && nowCompleted;

            final String taskKey = task.getTaskKey();
            final LocalDate taskDate = task.getTaskDate();

            databaseQueue.submit("updateProgress", (conn) -> {
                String updateSql = "UPDATE player_daily_tasks SET current_progress = ?, completed = ? WHERE player_uuid = ? AND task_key = ? AND task_date = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setInt(1, newProgress);
                    ps.setBoolean(2, nowCompleted);
                    ps.setString(3, uuid.toString());
                    ps.setString(4, taskKey);
                    ps.setDate(5, java.sql.Date.valueOf(taskDate));
                    ps.executeUpdate();
                }
                return null;
            }, result -> {
                task.setCurrentProgress(newProgress);
                if (nowCompleted) {
                    task.setCompleted(true);
                }

                if (justCompleted && player.isOnline()) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("task_name", template.getDisplayName());
                    MessageUtil.sendConfigMessage(plugin, player, "task-completed", placeholders);

                    if (plugin.getConfigManager().isAutoClaim()) {
                        claimRewardAsync(player, task, success -> {});
                    }

                    checkAllTasksCompleted(player);
                } else if (player.isOnline() && !justCompleted) {
                    // 进度更新提示（达到特定里程碑时）
                    int target = template.getTargetAmount();
                    int oldPercent = (oldProgress * 100) / target;
                    int newPercent = (newProgress * 100) / target;

                    // 达到 25%, 50%, 75% 时提示
                    int[] milestones = {25, 50, 75};
                    for (int milestone : milestones) {
                        if (oldPercent < milestone && newPercent >= milestone) {
                            Map<String, String> placeholders = new HashMap<>();
                            placeholders.put("task_name", template.getDisplayName());
                            placeholders.put("progress", String.valueOf(newProgress));
                            placeholders.put("target", String.valueOf(target));
                            placeholders.put("percent", String.valueOf(milestone));
                            MessageUtil.sendConfigMessage(plugin, player, "task-progress-milestone", placeholders);
                            break; // 只提示一次
                        }
                    }
                }
            }, e -> plugin.getLogger().log(Level.SEVERE, "Failed to update task progress", e));
        }
    }

    public void claimRewardAsync(Player player, PlayerTask task, java.util.function.Consumer<Boolean> callback) {
        if (!task.isCompleted() || task.isClaimed()) {
            callback.accept(false);
            return;
        }

        final String taskKey = task.getTaskKey();
        final LocalDate taskDate = task.getTaskDate();

        databaseQueue.submit("claimReward", (conn) -> {
            String sql = "UPDATE player_daily_tasks SET claimed = TRUE WHERE player_uuid = ? AND task_key = ? AND task_date = ? AND claimed = FALSE";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.getUniqueId().toString());
                ps.setString(2, taskKey);
                ps.setDate(3, java.sql.Date.valueOf(taskDate));
                int affectedRows = ps.executeUpdate();
                return affectedRows;
            }
        }, affectedRows -> {
            if (affectedRows != 1) {
                task.setClaimed(true);
                player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<red>该任务已在其他服务器领取"));
                callback.accept(false);
                return;
            }

            task.getTemplate().getReward().grant(player, plugin);
            task.setClaimed(true);

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("reward", task.getTemplate().getReward().getDisplayString(plugin));
            MessageUtil.sendConfigMessage(plugin, player, "reward-claimed", placeholders);

            callback.accept(true);
        }, e -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to claim task", e);
            callback.accept(false);
        });
    }

    private void checkAllTasksCompleted(Player player) {
        UUID uuid = player.getUniqueId();
        CopyOnWriteArrayList<PlayerTask> tasks = playerTasks.get(uuid);

        if (tasks == null) return;

        boolean allCompleted = tasks.stream().allMatch(PlayerTask::isCompleted);
        if (allCompleted) {
            MessageUtil.sendConfigMessage(plugin, player, "all-tasks-completed");
        }
    }

    public List<PlayerTask> getPlayerTasks(UUID uuid) {
        CopyOnWriteArrayList<PlayerTask> tasks = playerTasks.get(uuid);
        return tasks != null ? tasks : Collections.emptyList();
    }

    public Collection<TaskTemplate> getAllTemplates() {
        return templateSyncManager.getAllTemplates();
    }

    public TaskTemplate getTemplateByKey(String key) {
        return templateSyncManager.getTemplate(key);
    }

    @Deprecated
    public TaskTemplate getTemplateById(int id) {
        return null;
    }

    public void importTemplates(List<TaskTemplate> templates) {
        databaseQueue.submit("importTemplates", (conn) -> {
            boolean isMySQL = plugin.getDatabaseManager().isMySQL();
            String sql;

            if (isMySQL) {
                // MySQL 使用 ON DUPLICATE KEY UPDATE 语法
                sql = """
                    INSERT INTO task_templates
                    (task_key, version, task_data, enabled)
                    VALUES (?, ?, ?, TRUE)
                    ON DUPLICATE KEY UPDATE
                    version = VALUES(version),
                    task_data = VALUES(task_data),
                    enabled = TRUE
                    """;
            } else {
                // H2 使用 MERGE INTO 语法
                sql = """
                    MERGE INTO task_templates
                    (task_key, version, task_data, enabled)
                    KEY(task_key)
                    VALUES (?, ?, ?, TRUE)
                    """;
            }

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (TaskTemplate template : templates) {
                    ps.setString(1, template.getTaskKey());
                    ps.setInt(2, template.getVersion());
                    ps.setString(3, template.toJson());
                    ps.addBatch();
                }

                ps.executeBatch();
                plugin.getLogger().info("Imported " + templates.size() + " task templates");
                templateSyncManager.reloadFromDatabase(null);
            }
            return null;
        }, null, e -> plugin.getLogger().log(Level.SEVERE, "Failed to import templates", e));
    }

    public void deleteTemplate(String taskKey, java.util.function.Consumer<Boolean> callback) {
        databaseQueue.submit("deleteTemplate", (conn) -> {
            String sql = "UPDATE task_templates SET enabled = FALSE WHERE task_key = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, taskKey);
                int affected = ps.executeUpdate();
                plugin.getLogger().info("Disabled task template: " + taskKey);
                return affected > 0;
            }
        }, callback, e -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete template", e);
            callback.accept(false);
        });
    }

    public void rerollPlayerTasks(Player player, boolean notify, java.util.function.Consumer<Boolean> callback) {
        UUID uuid = player.getUniqueId();
        LocalDate today = TimeUtil.getToday();
        int taskCount = plugin.getConfigManager().getDailyTaskCount();

        // 先检查是否有可用模板
        if (templateSyncManager.getAllTemplates().isEmpty()) {
            plugin.getLogger().warning("Cannot generate tasks: no task templates available");
            player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<red>系统错误：没有可用的任务模板，请联系管理员"));
            callback.accept(false);
            return;
        }

        databaseQueue.submit("rerollPlayerTasks", (conn) -> {
            doGenerateTasks(conn, player, uuid, today, taskCount, notify);
            return true;
        }, callback, e -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to reroll tasks for player: " + player.getName(), e);
            callback.accept(false);
        });
    }

    public void rerollAllPlayerTasks(boolean notify, java.util.function.Consumer<Boolean> callback) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            callback.accept(true);
            return;
        }

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);
        int total = players.size();

        for (Player player : players) {
            rerollPlayerTasks(player, notify, success -> {
                if (success) {
                    successCount.incrementAndGet();
                }
                completedCount.incrementAndGet();

                if (completedCount.get() == total) {
                    callback.accept(successCount.get() == total);
                }
            });
        }
    }

    /**
     * 强制刷新玩家所有任务（无视完成状态，删除所有任务重新生成）
     * @param player 玩家
     * @param notify 是否通知玩家
     * @param callback 回调函数
     */
    public void forceRerollPlayerTasks(Player player, boolean notify, java.util.function.Consumer<Boolean> callback) {
        UUID uuid = player.getUniqueId();
        LocalDate today = TimeUtil.getToday();
        int taskCount = plugin.getConfigManager().getDailyTaskCount();

        // 先检查是否有可用模板
        if (templateSyncManager.getAllTemplates().isEmpty()) {
            plugin.getLogger().warning("Cannot generate tasks: no task templates available");
            callback.accept(false);
            return;
        }

        databaseQueue.submit("forceRerollPlayerTasks", (conn) -> {
            // 删除所有任务（不管是否完成）
            String deleteSql = "DELETE FROM player_daily_tasks WHERE player_uuid = ? AND task_date = ?";
            try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                ps.setString(1, uuid.toString());
                ps.setDate(2, java.sql.Date.valueOf(today));
                ps.executeUpdate();
            }

            // 生成新任务
            doGenerateTasks(conn, player, uuid, today, taskCount, notify);
            return true;
        }, callback, e -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to force reroll tasks for player: " + player.getName(), e);
            callback.accept(false);
        });
    }

    /**
     * 强制刷新所有在线玩家的任务（无视完成状态）
     * @param notify 是否通知玩家
     * @param callback 回调函数
     */
    public void forceRerollAllPlayerTasks(boolean notify, java.util.function.Consumer<Boolean> callback) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            callback.accept(true);
            return;
        }

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);
        int total = players.size();

        for (Player player : players) {
            forceRerollPlayerTasks(player, notify, success -> {
                if (success) {
                    successCount.incrementAndGet();
                }
                completedCount.incrementAndGet();

                if (completedCount.get() == total) {
                    callback.accept(successCount.get() == total);
                }
            });
        }
    }

    /**
     * 玩家付费刷新每日任务
     * @param player 玩家
     * @param callback 回调函数，返回刷新结果和消息
     */
    public void playerRerollDailyTasks(Player player, java.util.function.BiConsumer<Boolean, String> callback) {
        UUID uuid = player.getUniqueId();
        LocalDate today = TimeUtil.getToday();

        int maxRerolls = plugin.getConfigManager().getDailyRerollMax();
        double cost = plugin.getConfigManager().getDailyRerollCost();

        // 检查是否允许刷新
        if (maxRerolls <= 0) {
            callback.accept(false, "<red>每日任务刷新功能已禁用");
            return;
        }

        // 检查是否有可用模板
        if (templateSyncManager.getAllTemplates().isEmpty()) {
            callback.accept(false, "<red>系统错误：没有可用的任务模板，请联系管理员");
            return;
        }

        databaseQueue.submit("playerRerollCheck", (conn) -> {
            // 获取今日刷新次数
            String selectSql = "SELECT reroll_count, last_reset_date FROM player_task_reset WHERE player_uuid = ?";
            int currentRerolls = 0;

            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        LocalDate lastReset = rs.getDate("last_reset_date").toLocalDate();
                        if (lastReset.equals(today)) {
                            currentRerolls = rs.getInt("reroll_count");
                        }
                    }
                }
            }

            // 检查次数限制
            if (currentRerolls >= maxRerolls) {
                return new RerollCheckResult(false, "<red>今日刷新次数已用完（上限：" + maxRerolls + "次）", currentRerolls);
            }

            // 检查金币（如果配置了花费）
            if (cost > 0 && plugin.getEconomyManager().isEnabled()) {
                double balance = plugin.getEconomyManager().getBalance(player);
                if (balance < cost) {
                    return new RerollCheckResult(false, "<red>金币不足，需要 " + cost + " 金币", currentRerolls);
                }
            }

            return new RerollCheckResult(true, null, currentRerolls);
        }, result -> {
            if (!result.success) {
                callback.accept(false, result.message);
                return;
            }

            // 扣除金币
            if (cost > 0 && plugin.getEconomyManager().isEnabled()) {
                boolean deducted = plugin.getEconomyManager().withdraw(player, cost);
                if (!deducted) {
                    callback.accept(false, "<red>扣除金币失败");
                    return;
                }
            }

            // 执行刷新
            doPlayerReroll(player, today, result.currentRerolls + 1, callback);
        }, e -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to check reroll for player: " + player.getName(), e);
            callback.accept(false, "<red>刷新检查失败，请重试");
        });
    }

    private record RerollCheckResult(boolean success, String message, int currentRerolls) {}

    private void doPlayerReroll(Player player, LocalDate today, int newRerollCount,
                                java.util.function.BiConsumer<Boolean, String> callback) {
        UUID uuid = player.getUniqueId();
        int taskCount = plugin.getConfigManager().getDailyTaskCount();
        boolean keepCompleted = plugin.getConfigManager().isRerollKeepCompleted();

        databaseQueue.submit("doPlayerReroll", (conn) -> {
            if (keepCompleted) {
                // 新模式：只刷新未完成的任务
                return doPartialReroll(conn, player, uuid, today, taskCount, newRerollCount);
            } else {
                // 旧模式：刷新所有任务
                return doFullReroll(conn, player, uuid, today, taskCount, newRerollCount);
            }
        }, result -> {
            if (result.success) {
                // 清除本地缓存，下次加载时会重新查询数据库
                playerTasks.remove(uuid);
                callback.accept(true, result.message);
            } else {
                callback.accept(false, result.message);
            }
        }, e -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to reroll tasks for player: " + player.getName(), e);
            callback.accept(false, "<red>刷新失败，请重试");
        });
    }

    private record RerollResult(boolean success, String message) {}

    /**
     * 完全刷新模式 - 删除所有任务并重新生成
     */
    private RerollResult doFullReroll(Connection conn, Player player, UUID uuid, LocalDate today,
                                      int taskCount, int newRerollCount) throws SQLException {
        // 删除今日旧任务
        String deleteSql = "DELETE FROM player_daily_tasks WHERE player_uuid = ? AND task_date = ?";
        try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            ps.setString(1, uuid.toString());
            ps.setDate(2, java.sql.Date.valueOf(today));
            ps.executeUpdate();
        }

        // 生成新任务
        doGenerateTasks(conn, player, uuid, today, taskCount, false);

        // 更新刷新次数
        updateRerollCount(conn, uuid, today, newRerollCount);

        return new RerollResult(true, "<green>每日任务已刷新！今日已使用 " + newRerollCount + "/" +
                plugin.getConfigManager().getDailyRerollMax() + " 次刷新");
    }

    /**
     * 部分刷新模式 - 只刷新未完成的任务，保留已领取的任务
     */
    private RerollResult doPartialReroll(Connection conn, Player player, UUID uuid, LocalDate today,
                                         int taskCount, int newRerollCount) throws SQLException {
        // 1. 查询已完成的任务数量（claimed = TRUE）
        String countCompletedSql = "SELECT COUNT(*) as completed_count FROM player_daily_tasks " +
                "WHERE player_uuid = ? AND task_date = ? AND claimed = TRUE";
        int completedCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(countCompletedSql)) {
            ps.setString(1, uuid.toString());
            ps.setDate(2, java.sql.Date.valueOf(today));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    completedCount = rs.getInt("completed_count");
                }
            }
        }

        // 2. 检查是否所有任务都已完成
        if (completedCount >= taskCount) {
            return new RerollResult(false, "<red>今日所有任务都已完成，无需刷新");
        }

        // 3. 计算需要生成的新任务数量
        int needToGenerate = taskCount - completedCount;

        // 4. 查询已存在的任务key（包括已完成和未完成的）
        Set<String> existingTaskKeys = new HashSet<>();
        String selectExistingSql = "SELECT task_key FROM player_daily_tasks " +
                "WHERE player_uuid = ? AND task_date = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectExistingSql)) {
            ps.setString(1, uuid.toString());
            ps.setDate(2, java.sql.Date.valueOf(today));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    existingTaskKeys.add(rs.getString("task_key"));
                }
            }
        }

        // 5. 只删除未完成的任务（claimed = FALSE）
        String deleteUnclaimedSql = "DELETE FROM player_daily_tasks " +
                "WHERE player_uuid = ? AND task_date = ? AND claimed = FALSE";
        int deletedCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(deleteUnclaimedSql)) {
            ps.setString(1, uuid.toString());
            ps.setDate(2, java.sql.Date.valueOf(today));
            deletedCount = ps.executeUpdate();
        }

        // 6. 从可用模板中排除已存在的任务key，然后随机选择
        List<TaskTemplate> availableTemplates = new ArrayList<>(templateSyncManager.getAllTemplates());
        availableTemplates.removeIf(t -> existingTaskKeys.contains(t.getTaskKey()));

        if (availableTemplates.size() < needToGenerate) {
            plugin.getLogger().warning("Not enough available templates for player " + player.getName() +
                    ". Need " + needToGenerate + ", available " + availableTemplates.size());
            // 如果模板不够，使用所有可用模板
            needToGenerate = availableTemplates.size();
        }

        if (needToGenerate <= 0) {
            return new RerollResult(false, "<red>没有更多可用的任务模板可以刷新");
        }

        List<TaskTemplate> selectedTasks = selectRandomTasksFromList(availableTemplates, needToGenerate);

        // 7. 插入新任务
        String insertSql = """
            INSERT INTO player_daily_tasks
            (player_uuid, task_key, task_version, current_progress, completed, claimed, task_date, task_data)
            VALUES (?, ?, ?, 0, FALSE, FALSE, ?, ?)
            """;

        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (TaskTemplate template : selectedTasks) {
                ps.setString(1, uuid.toString());
                ps.setString(2, template.getTaskKey());
                ps.setInt(3, template.getVersion());
                ps.setDate(4, java.sql.Date.valueOf(today));
                ps.setString(5, template.toJson());
                ps.executeUpdate();
            }
        }

        // 8. 更新刷新次数
        updateRerollCount(conn, uuid, today, newRerollCount);

        // 9. 更新内存中的任务列表
        List<PlayerTask> currentTasks = new ArrayList<>();
        // 重新加载所有任务到内存
        String reloadSql = "SELECT * FROM player_daily_tasks WHERE player_uuid = ? AND task_date = ?";
        try (PreparedStatement ps = conn.prepareStatement(reloadSql)) {
            ps.setString(1, uuid.toString());
            ps.setDate(2, java.sql.Date.valueOf(today));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlayerTask task = parsePlayerTaskFromResultSet(uuid, rs);
                    if (task != null) {
                        currentTasks.add(task);
                    }
                }
            }
        }
        playerTasks.put(uuid, new CopyOnWriteArrayList<>(currentTasks));

        return new RerollResult(true, "<green>已刷新 " + deletedCount + " 个未完成任务，保留了 " +
                completedCount + " 个已完成任务！今日已使用 " + newRerollCount + "/" +
                plugin.getConfigManager().getDailyRerollMax() + " 次刷新");
    }

    /**
     * 更新玩家刷新次数
     */
    private void updateRerollCount(Connection conn, UUID uuid, LocalDate today, int newRerollCount) throws SQLException {
        String updateRerollSql;
        if (isH2Database()) {
            updateRerollSql = "MERGE INTO player_task_reset (player_uuid, last_reset_date, reroll_count) KEY(player_uuid) VALUES (?, ?, ?)";
        } else {
            updateRerollSql = "INSERT INTO player_task_reset (player_uuid, last_reset_date, reroll_count) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE reroll_count = ?";
        }

        try (PreparedStatement ps = conn.prepareStatement(updateRerollSql)) {
            ps.setString(1, uuid.toString());
            ps.setDate(2, java.sql.Date.valueOf(today));
            ps.setInt(3, newRerollCount);
            if (!isH2Database()) {
                ps.setInt(4, newRerollCount);
            }
            ps.executeUpdate();
        }
    }

    /**
     * 从指定列表中随机选择指定数量的任务模板
     */
    private List<TaskTemplate> selectRandomTasksFromList(List<TaskTemplate> available, int count) {
        List<TaskTemplate> selected = new ArrayList<>();
        if (available.isEmpty() || count <= 0) {
            return selected;
        }

        List<TaskTemplate> tempList = new ArrayList<>(available);
        Random random = new Random();

        for (int i = 0; i < count && !tempList.isEmpty(); i++) {
            int totalWeight = tempList.stream().mapToInt(TaskTemplate::getWeight).sum();
            if (totalWeight <= 0) break;

            int randomValue = random.nextInt(totalWeight);
            int currentWeight = 0;

            for (Iterator<TaskTemplate> it = tempList.iterator(); it.hasNext(); ) {
                TaskTemplate template = it.next();
                currentWeight += template.getWeight();
                if (randomValue < currentWeight) {
                    selected.add(template);
                    it.remove();
                    break;
                }
            }
        }

        return selected;
    }

    private boolean isH2Database() {
        return "h2".equalsIgnoreCase(plugin.getConfigManager().getDatabaseType());
    }

    /**
     * 给指定玩家添加单个任务
     * @param player 目标玩家
     * @param template 任务模板
     * @param callback 回调函数
     */
    public void assignTaskToPlayer(Player player, TaskTemplate template, java.util.function.Consumer<Boolean> callback) {
        UUID uuid = player.getUniqueId();
        LocalDate today = TimeUtil.getToday();

        // 检查玩家是否已有该任务
        CopyOnWriteArrayList<PlayerTask> currentTasks = playerTasks.get(uuid);
        if (currentTasks != null) {
            boolean hasTask = currentTasks.stream()
                .anyMatch(t -> t.getTaskKey().equals(template.getTaskKey()) && t.getTaskDate().equals(today));
            if (hasTask) {
                plugin.getLogger().warning("Player " + player.getName() + " already has task: " + template.getTaskKey());
                callback.accept(false);
                return;
            }
        }

        databaseQueue.submit("assignTask", (conn) -> {
            // 检查数据库中是否已有该任务
            String checkSql = "SELECT 1 FROM player_daily_tasks WHERE player_uuid = ? AND task_key = ? AND task_date = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, template.getTaskKey());
                ps.setDate(3, java.sql.Date.valueOf(today));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return false; // 已存在
                    }
                }
            }

            // 插入新任务
            String insertSql = """
                INSERT INTO player_daily_tasks
                (player_uuid, task_key, task_version, current_progress, completed, claimed, task_date, task_data)
                VALUES (?, ?, ?, 0, FALSE, FALSE, ?, ?)
                """;

            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, template.getTaskKey());
                ps.setInt(3, template.getVersion());
                ps.setDate(4, java.sql.Date.valueOf(today));
                ps.setString(5, template.toJson());
                ps.executeUpdate();
            }

            return true;
        }, success -> {
            if (success) {
                // 添加到内存缓存
                PlayerTask newTask = new PlayerTask(uuid, template.getTaskKey(), template, 0, false, false, today);
                playerTasks.computeIfAbsent(uuid, k -> new CopyOnWriteArrayList<>()).add(newTask);

                // 通知玩家
                if (player.isOnline()) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("task_name", template.getDisplayName());
                    MessageUtil.sendConfigMessage(plugin, player, "task-assigned", placeholders);
                }
            }
            callback.accept(success);
        }, e -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to assign task to player: " + player.getName(), e);
            callback.accept(false);
        });
    }

    public void clearPlayerCache(UUID uuid) {
        playerTasks.remove(uuid);
        playerResetDates.remove(uuid);
    }

    private void startResetChecker() {
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            LocalDate today = TimeUtil.getToday();

            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                LocalDate lastReset = playerResetDates.get(uuid);

                if (lastReset != null && !lastReset.equals(today)) {
                    generateNewDailyTasks(player);
                }
            }
        }, 20L * 60, 20L * 60);
    }

    private void startDataCleanupScheduler() {
        int retentionDays = plugin.getConfigManager().getDataRetentionDays();
        if (retentionDays <= 0) {
            plugin.getLogger().info("Data cleanup disabled (retention-days <= 0)");
            return;
        }

        plugin.getLogger().info("Starting data cleanup scheduler (retention: " + retentionDays + " days)");

        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            cleanupExpiredData(retentionDays);
        }, 20L * 60 * 60 * 6, 20L * 60 * 60 * 6);
    }

    public void cleanupExpiredData(int retentionDays) {
        LocalDate cutoffDate = TimeUtil.getToday().minusDays(retentionDays);

        databaseQueue.submit("cleanupExpiredData", (conn) -> {
            int deletedTasks = 0;
            int deletedResets = 0;

            String deleteTasksSql = "DELETE FROM player_daily_tasks WHERE task_date < ?";
            try (PreparedStatement ps = conn.prepareStatement(deleteTasksSql)) {
                ps.setDate(1, java.sql.Date.valueOf(cutoffDate));
                deletedTasks = ps.executeUpdate();
            }

            String deleteResetSql = "DELETE FROM player_task_reset WHERE last_reset_date < ?";
            try (PreparedStatement ps = conn.prepareStatement(deleteResetSql)) {
                ps.setDate(1, java.sql.Date.valueOf(cutoffDate.minusDays(retentionDays)));
                deletedResets = ps.executeUpdate();
            }

            if (deletedTasks > 0 || deletedResets > 0) {
                plugin.getLogger().info("Cleaned up expired data: " + deletedTasks + " tasks, " + deletedResets + " reset records (before " + cutoffDate + ")");
            }
            return null;
        }, null, e -> plugin.getLogger().log(Level.WARNING, "Failed to cleanup expired data", e));
    }

    public void cleanupPlayerExpiredTasks(Player player) {
        int retentionDays = plugin.getConfigManager().getDataRetentionDays();
        if (retentionDays <= 0) return;

        UUID uuid = player.getUniqueId();
        LocalDate cutoffDate = TimeUtil.getToday().minusDays(retentionDays);

        databaseQueue.submit("cleanupPlayerExpiredTasks", (conn) -> {
            String deleteSql = "DELETE FROM player_daily_tasks WHERE player_uuid = ? AND task_date < ?";
            try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                ps.setString(1, uuid.toString());
                ps.setDate(2, java.sql.Date.valueOf(cutoffDate));
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    plugin.getLogger().fine("Cleaned up " + deleted + " expired tasks for player " + player.getName());
                }
            }
            return null;
        }, null, e -> plugin.getLogger().log(Level.WARNING, "Failed to cleanup player expired tasks", e));
    }

    /**
     * 重置玩家今日刷新次数
     * @param player 目标玩家
     * @param callback 回调函数
     */
    public void resetPlayerRerollCount(Player player, java.util.function.Consumer<Boolean> callback) {
        UUID uuid = player.getUniqueId();
        LocalDate today = TimeUtil.getToday();

        databaseQueue.submit("resetPlayerRerollCount", (conn) -> {
            String updateSql;
            if (isH2Database()) {
                updateSql = "MERGE INTO player_task_reset (player_uuid, last_reset_date, reroll_count) KEY(player_uuid) VALUES (?, ?, 0)";
            } else {
                updateSql = "INSERT INTO player_task_reset (player_uuid, last_reset_date, reroll_count) VALUES (?, ?, 0) " +
                        "ON DUPLICATE KEY UPDATE reroll_count = 0";
            }

            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, uuid.toString());
                ps.setDate(2, java.sql.Date.valueOf(today));
                ps.executeUpdate();
            }
            return true;
        }, callback, e -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to reset reroll count for player: " + player.getName(), e);
            callback.accept(false);
        });
    }

    /**
     * 重置所有在线玩家的今日刷新次数
     * @param callback 回调函数
     */
    public void resetAllPlayerRerollCount(java.util.function.Consumer<Boolean> callback) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            callback.accept(true);
            return;
        }

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);
        int total = players.size();

        for (Player player : players) {
            resetPlayerRerollCount(player, success -> {
                if (success) {
                    successCount.incrementAndGet();
                }
                completedCount.incrementAndGet();

                if (completedCount.get() == total) {
                    callback.accept(successCount.get() == total);
                }
            });
        }
    }
}
