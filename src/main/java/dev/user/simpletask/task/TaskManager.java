package dev.user.simpletask.task;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.category.TaskCategory;
import dev.user.simpletask.util.MessageUtil;
import dev.user.simpletask.task.manager.*;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 任务管理器 - 入口类
 * 组合所有任务管理功能：缓存、生成、过期检查、reroll、调度
 */
public class TaskManager {

    private final SimpleTaskPlugin plugin;
    private final TaskCacheManager cacheManager;
    private final TaskGenerator taskGenerator;
    private final TaskExpireManager expireManager;
    private final RerollManager rerollManager;
    private final TaskScheduler taskScheduler;
    private final TaskProgressManager progressManager;
    private final TemplateSyncManager templateSyncManager;

    public TaskManager(SimpleTaskPlugin plugin) {
        this.plugin = plugin;

        // 首先初始化模板同步管理器（其他管理器依赖它）
        this.templateSyncManager = new TemplateSyncManager(plugin);

        // 初始化各个管理器
        this.cacheManager = new TaskCacheManager(plugin);
        this.taskGenerator = new TaskGenerator(plugin, templateSyncManager);
        this.expireManager = new TaskExpireManager(plugin, cacheManager, taskGenerator, templateSyncManager);
        this.rerollManager = new RerollManager(plugin, cacheManager, taskGenerator, templateSyncManager);
        this.taskScheduler = new TaskScheduler(plugin);
        this.progressManager = new TaskProgressManager(plugin, cacheManager);

        // 启动时从数据库加载模板
        templateSyncManager.loadFromDatabase();
        templateSyncManager.startPeriodicSync();

        // 启动定时调度器
        taskScheduler.startAll();
    }

    // ==================== 代理方法：缓存管理 ====================

    public Map<String, List<PlayerTask>> getPlayerTasksGroupedByCategory(UUID uuid) {
        return cacheManager.getPlayerTasksGroupedByCategory(uuid);
    }

    public List<PlayerTask> getPlayerTasksByCategory(UUID uuid, String categoryId) {
        return cacheManager.getPlayerTasksByCategory(uuid, categoryId);
    }

    public List<PlayerTask> getPlayerTasks(UUID uuid) {
        return cacheManager.getPlayerTasks(uuid);
    }

    public void updateCategoryTaskCache(UUID uuid, String categoryId, List<PlayerTask> tasks) {
        cacheManager.updateCategoryTaskCache(uuid, categoryId, tasks);
    }

    public void clearPlayerCache(UUID uuid) {
        cacheManager.clearPlayerCache(uuid);
    }

    // ==================== 代理方法：模板管理 ====================

    public TemplateSyncManager getTemplateSyncManager() {
        return templateSyncManager;
    }

    public TaskProgressManager getProgressManager() {
        return progressManager;
    }

    public TaskTemplate getTemplateByKey(String taskKey) {
        return templateSyncManager.getTemplate(taskKey);
    }

    public Collection<TaskTemplate> getAllTemplates() {
        return templateSyncManager.getAllTemplates();
    }

    @Deprecated
    public void loadTemplates() {
        // 模板现在由 TemplateSyncManager 管理
    }

    // ==================== 代理方法：玩家任务加载 ====================

    /**
     * 玩家登录时加载其任务
     * 使用延迟执行确保不阻塞登录流程
     */
    public void loadPlayerTasks(Player player) {

        // 注意：不在这里清除缓存，让 loadAndCheckPlayerTasks 内部直接覆盖
        // 避免在加载完成前出现空缓存的窗口期

        // 延迟2tick执行，确保玩家已完全登录，不阻塞登录流程
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
            // 检查玩家是否还在线
            if (!player.isOnline()) {
                return;
            }

            // 提交到数据库队列异步执行
            plugin.getDatabaseQueue().submit("loadPlayerTasks", (Connection conn) -> {
                // 1. 先检查并重置所有 reroll 次数
                rerollManager.checkAndResetAllRerollCounts(conn, player);

                // 2. 再加载和检查任务
                expireManager.loadAndCheckPlayerTasks(conn, player);
                return null;
            }, null, e -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to load player tasks", e));
        }, 2);
    }

    /**
     * 检查并刷新玩家任务（带reroll重置检查）
     * 由定时任务调用
     */
    public void checkAndRefreshPlayerTasksWithReroll(Player player) {
        plugin.getDatabaseQueue().submit("checkAndRefreshWithReroll", (Connection conn) -> {
            // 1. 先检查并重置所有 reroll 次数
            rerollManager.checkAndResetAllRerollCounts(conn, player);

            // 2. 再检查任务过期
            expireManager.checkAndRefreshPlayerTasks(player);
            return null;
        }, null, e -> plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to check and refresh tasks for player: " + player.getName(), e));
    }

    /**
     * 检查并刷新指定分类的任务（GUI使用）
     */
    public void checkAndRefreshCategoryTasks(Player player, String categoryId,
                                              BiConsumer<List<PlayerTask>, Integer> callback,
                                              Consumer<Exception> errorCallback) {
        TaskCategory category = plugin.getConfigManager().getTaskCategory(categoryId);
        if (category == null || !category.isEnabled()) {
            errorCallback.accept(new IllegalArgumentException("Category not found or disabled: " + categoryId));
            return;
        }

        plugin.getDatabaseQueue().submit("checkAndRefreshCategory", (Connection conn) -> {
            // 检查并刷新该分类的任务
            TaskExpireManager.CategoryRefreshResult result = expireManager.checkAndRefreshCategoryTasks(conn, player, category);

            // 获取刷新次数
            int usedRerolls = rerollManager.getCategoryRerollCount(conn, player.getUniqueId(), category);

            return new CategoryRefreshData(result.tasks(), usedRerolls);
        }, result -> {
            // 更新缓存
            cacheManager.updateCategoryTaskCache(player.getUniqueId(), categoryId, result.tasks());
            callback.accept(result.tasks(), result.usedRerolls());
        }, e -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to check and refresh category tasks", e);
            errorCallback.accept(e);
        });
    }

    private record CategoryRefreshData(List<PlayerTask> tasks, int usedRerolls) {}

    // ==================== 代理方法：刷新任务（Admin使用） ====================

    public void rerollPlayerCategoryTasks(Player player, String categoryId, boolean notify,
                                          Consumer<Boolean> callback) {
        // Admin 免费刷新（不保留已完成，不检查次数）
        rerollManager.rerollCategoryTasks(player, categoryId, RerollManager.RerollOptions.admin(), (success, message) -> {
            if (notify && success) {
                TaskCategory category = plugin.getConfigManager().getTaskCategory(categoryId);
                Component categoryName = MessageUtil.parse(category != null ? category.getDisplayName() : categoryId);
                MessageUtil.sendConfigWithComponents(plugin, player, "tasks-refreshed",
                        MessageUtil.componentPlaceholders("categories", categoryName));
            }
            callback.accept(success);
        });
    }

    public void rerollAllPlayerCategoryTasks(String categoryId, boolean notify, Consumer<Boolean> callback) {
        dev.user.simpletask.task.manager.DatabaseUtils dbUtils = new dev.user.simpletask.task.manager.DatabaseUtils(plugin);
        dbUtils.executeBatch(
            new ArrayList<>(plugin.getServer().getOnlinePlayers()),
            (player, cb) -> rerollPlayerCategoryTasks(player, categoryId, notify, cb),
            callback
        );
    }

    public void forceRerollPlayerCategoryTasks(Player player, String categoryId, boolean notify,
                                                Consumer<Boolean> callback) {
        // 使用强制刷新选项（删除所有任务并重新生成）
        rerollManager.rerollCategoryTasks(player, categoryId, RerollManager.RerollOptions.force(), (success, message) -> {
            if (notify && success) {
                TaskCategory category = plugin.getConfigManager().getTaskCategory(categoryId);
                Component categoryName = MessageUtil.parse(category != null ? category.getDisplayName() : categoryId);
                MessageUtil.sendConfigWithComponents(plugin, player, "tasks-refreshed",
                        MessageUtil.componentPlaceholders("categories", categoryName));
            }
            callback.accept(success);
        });
    }

    public void forceRerollAllPlayerCategoryTasks(String categoryId, boolean notify, Consumer<Boolean> callback) {
        dev.user.simpletask.task.manager.DatabaseUtils dbUtils = new dev.user.simpletask.task.manager.DatabaseUtils(plugin);
        dbUtils.executeBatch(
            new ArrayList<>(plugin.getServer().getOnlinePlayers()),
            (player, cb) -> forceRerollPlayerCategoryTasks(player, categoryId, notify, cb),
            callback
        );
    }

    // ==================== 代理方法：玩家刷新（付费） ====================

    public void playerRerollCategoryTasks(Player player, String categoryId, java.util.function.BiConsumer<Boolean, net.kyori.adventure.text.Component> callback) {
        TaskCategory category = plugin.getConfigManager().getTaskCategory(categoryId);
        if (category == null) {
            callback.accept(false, net.kyori.adventure.text.Component.text("类别不存在: " + categoryId).color(net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }

        rerollManager.rerollCategoryTasks(player, categoryId, RerollManager.RerollOptions.paid(), callback);
    }

    public void resetPlayerCategoryRerollCount(Player player, String categoryId, Consumer<Boolean> callback) {
        rerollManager.resetPlayerCategoryRerollCount(player, categoryId, callback);
    }

    public void resetAllPlayerCategoryRerollCount(String categoryId, Consumer<Boolean> callback) {
        rerollManager.resetAllPlayerCategoryRerollCount(categoryId, callback);
    }

    // ==================== 代理方法：进度管理 ====================

    public void updateProgress(Player player, TaskType type, String targetItem, int amount) {
        progressManager.updateProgress(player, type, targetItem, amount);
    }

    public void updateProgress(Player player, TaskType type, String targetItem, org.bukkit.inventory.ItemStack itemStack, int amount) {
        progressManager.updateProgress(player, type, targetItem, itemStack, amount);
    }

    public void submitTaskProgress(Player player, PlayerTask task, int newProgress, BiConsumer<Boolean, Boolean> callback) {
        progressManager.submitTaskProgress(player, task, newProgress, callback);
    }

    public void claimRewardAsync(Player player, PlayerTask task, Consumer<Boolean> callback) {
        progressManager.claimRewardAsync(player, task, callback);
    }

    // ==================== 代理方法：模板导入/删除 ====================

    public void importTemplates(List<TaskTemplate> templates) {
        templateSyncManager.importTemplates(templates);
    }

    public void deleteTemplate(String taskKey, Consumer<Boolean> callback) {
        templateSyncManager.deleteTemplate(taskKey, callback);
    }

    /**
     * 给指定玩家分配任务到指定分类
     */
    public void assignTaskToCategory(Player player, String categoryId, TaskTemplate template, Consumer<Boolean> callback) {
        TaskCategory category = plugin.getConfigManager().getTaskCategory(categoryId);
        if (category == null) {
            callback.accept(false);
            return;
        }

        plugin.getDatabaseQueue().submit("assignTask", (Connection conn) -> {
            // 检查玩家是否已有该任务
            List<PlayerTask> existingTasks = cacheManager.getPlayerTasksByCategory(player.getUniqueId(), categoryId);
            boolean hasTask = existingTasks.stream()
                .anyMatch(t -> t.getTaskKey().equals(template.getTaskKey()));
            if (hasTask) {
                return false;
            }

            // 生成单个任务
            List<PlayerTask> newTasks = taskGenerator.generateTasksForCategory(
                conn, player, category, 1, existingTasks,
                Collections.singletonList(template)
            );
            return !newTasks.isEmpty();
        }, success -> {
            if (success) {
                // 更新缓存
                cacheManager.clearPlayerCache(player.getUniqueId());
                // 通知玩家
                if (player.isOnline()) {
                    MessageUtil.sendConfigWithComponents(plugin, player, "task-assigned",
                        MessageUtil.componentPlaceholders("task_name",
                            MessageUtil.parse(template.getDisplayName())));
                }
            }
            callback.accept(success);
        }, e -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to assign task to player: " + player.getName(), e);
            callback.accept(false);
        });
    }

    /**
     * 删除玩家的指定任务
     */
    public void removePlayerTask(UUID uuid, String category, String taskKey, Consumer<Boolean> callback) {
        plugin.getDatabaseQueue().submit("removePlayerTask", (Connection conn) -> {
            String sql = "DELETE FROM player_daily_tasks WHERE player_uuid = ? AND category = ? AND task_key = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, category);
                ps.setString(3, taskKey);
                return ps.executeUpdate();
            }
        }, deletedCount -> {
            if (deletedCount > 0) {
                // 从缓存中移除
                cacheManager.removePlayerTask(uuid, category, taskKey);
                callback.accept(true);
            } else {
                callback.accept(false);
            }
        }, e -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to remove player task", e);
            callback.accept(false);
        });
    }

    // ==================== 代理方法：系统控制 ====================

    public void shutdown() {
        plugin.getLogger().info("Shutting down task manager...");

        // 停止调度器
        taskScheduler.shutdown();

        // 停止模板同步
        templateSyncManager.stopPeriodicSync();

        plugin.getLogger().info("Task manager shutdown complete");
    }

}
