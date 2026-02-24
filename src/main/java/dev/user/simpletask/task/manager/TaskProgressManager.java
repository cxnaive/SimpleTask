package dev.user.simpletask.task.manager;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.PlayerTask;
import dev.user.simpletask.task.TaskTemplate;
import dev.user.simpletask.task.TaskType;
import dev.user.simpletask.task.category.TaskCategory;
import dev.user.simpletask.util.MessageUtil;
import dev.user.simpletask.util.TimeZoneConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务进度管理器
 * 负责更新任务进度和检查完成状态
 */
public class TaskProgressManager {

    private final SimpleTaskPlugin plugin;
    private final TaskCacheManager cacheManager;

    public TaskProgressManager(SimpleTaskPlugin plugin, TaskCacheManager cacheManager) {
        this.plugin = plugin;
        this.cacheManager = cacheManager;
    }

    /**
     * 更新任务进度（基础版本）
     */
    public void updateProgress(Player player, TaskType type, String target, int amount) {
        updateProgress(player, type, target, null, amount);
    }

    /**
     * 更新任务进度（带物品信息版本）
     */
    public void updateProgress(Player player, TaskType type, String target, ItemStack item, int amount) {
        if (amount <= 0) return;

        UUID uuid = player.getUniqueId();
        Map<String, List<PlayerTask>> tasksByCategory = cacheManager.getPlayerTasksGroupedByCategory(uuid);

        if (tasksByCategory.isEmpty()) return;

        // 收集所有需要更新的任务
        Map<PlayerTask, Integer> tasksToUpdate = new HashMap<>();

        for (Map.Entry<String, List<PlayerTask>> entry : tasksByCategory.entrySet()) {
            String categoryId = entry.getKey();
            List<PlayerTask> tasks = entry.getValue();

            if (tasks == null || tasks.isEmpty()) continue;

            TaskCategory category = plugin.getConfigManager().getTaskCategory(categoryId);
            if (category == null || !category.isEnabled()) continue;

            for (PlayerTask task : tasks) {
                // 检查任务是否匹配
                if (!isTaskMatching(task, type, target, item)) continue;

                // 检查任务是否已完成
                if (task.isCompleted()) continue;

                // 检查任务是否过期
                if (task.isExpired(category)) continue;

                // 计算新进度
                int currentProgress = task.getCurrentProgress();
                int targetProgress = task.getTargetProgress();
                int newProgress = Math.min(currentProgress + amount, targetProgress);

                if (newProgress > currentProgress) {
                    tasksToUpdate.put(task, newProgress);
                }
            }
        }

        // 批量更新
        if (!tasksToUpdate.isEmpty()) {
            updateTaskProgressBatch(player, uuid, tasksToUpdate);
        }
    }

    /**
     * 检查任务是否匹配给定的类型和目标
     * 使用 TaskTemplate.matchesTarget 统一处理所有匹配逻辑
     */
    private boolean isTaskMatching(PlayerTask task, TaskType type, String target, ItemStack item) {
        TaskTemplate template = task.getTemplate();
        if (template.getType() != type) return false;

        // 使用 TaskTemplate 的统一匹配逻辑
        // 包括：基础匹配、HARVEST/BREAK ID标准化、CHAT包含匹配、COMMAND前缀匹配、NBT匹配
        return template.matchesTarget(target, item);
    }

    /**
     * 批量更新任务进度
     */
    private void updateTaskProgressBatch(Player player, UUID uuid, Map<PlayerTask, Integer> tasksToUpdate) {
        // 记录更新前的进度（用于里程碑计算）
        Map<PlayerTask, Integer> prevProgressMap = new HashMap<>();
        for (PlayerTask task : tasksToUpdate.keySet()) {
            prevProgressMap.put(task, task.getCurrentProgress());
        }

        plugin.getDatabaseQueue().submit("updateTaskProgressBatch", (Connection conn) -> {
            String updateSql = "UPDATE player_daily_tasks SET current_progress = ?, completed = ? " +
                "WHERE player_uuid = ? AND task_key = ? AND assigned_at = ?";

            // 禁用自动提交，确保事务完整性
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                for (Map.Entry<PlayerTask, Integer> entry : tasksToUpdate.entrySet()) {
                    PlayerTask task = entry.getKey();
                    int newProgress = entry.getValue();
                    boolean isCompleted = newProgress >= task.getTargetProgress();

                    ps.setInt(1, newProgress);
                    ps.setBoolean(2, isCompleted);
                    ps.setString(3, uuid.toString());
                    ps.setString(4, task.getTaskKey());
                    // 时区安全：LocalDateTime -> Instant -> Timestamp (使用 UTC Calendar)
                    Instant instant = TimeZoneConfig.toInstant(task.getAssignedAt());
                    ps.setTimestamp(5, Timestamp.from(instant), TimeZoneConfig.UTC_CALENDAR);
                    ps.addBatch();
                }

                int[] results = ps.executeBatch();

                // 检查每条语句的更新结果，只更新成功的任务到内存
                List<PlayerTask> successfulUpdates = new ArrayList<>();
                int index = 0;
                for (Map.Entry<PlayerTask, Integer> entry : tasksToUpdate.entrySet()) {
                    PlayerTask task = entry.getKey();
                    int affectedRows = results[index++];
                    if (affectedRows > 0) {
                        successfulUpdates.add(task);
                    }
                }

                // 显式提交事务
                conn.commit();

                // 只更新数据库更新成功的任务的内存
                for (PlayerTask task : successfulUpdates) {
                    Integer newProgress = tasksToUpdate.get(task);
                    if (newProgress != null) {
                        task.setCurrentProgress(newProgress);
                    }
                }
            } catch (SQLException e) {
                // 发生异常时回滚事务
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to rollback transaction", rollbackEx);
                }
                throw e;
            } finally {
                // 恢复原来的 autoCommit 状态
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException autoCommitEx) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to restore autoCommit state", autoCommitEx);
                }
            }
            return null;
        }, result -> {
            // 在数据库操作成功后，在主线程检查完成状态和里程碑
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                for (Map.Entry<PlayerTask, Integer> entry : tasksToUpdate.entrySet()) {
                    PlayerTask task = entry.getKey();
                    int newProgress = entry.getValue();
                    int prevProgress = prevProgressMap.get(task);

                    if (newProgress >= task.getTargetProgress()) {
                        onTaskComplete(player, task);
                    } else {
                        // 进度里程碑提示（传入更新前后的进度）
                        sendProgressUpdate(player, task, newProgress, prevProgress);
                    }
                }
            });
        }, e -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to update task progress", e));
    }

    /**
     * 任务完成处理
     */
    private void onTaskComplete(Player player, PlayerTask task) {
        task.setCompleted(true);

        // 获取类别配置，检查是否自动领取
        TaskCategory category = plugin.getConfigManager().getTaskCategory(task.getCategory());
        if (category != null && category.isAutoClaim()) {
            // 自动领取奖励
            autoClaimReward(player, task);
        } else {
            // 发送完成消息（使用 Component 支持嵌套样式）
            MessageUtil.sendConfigWithComponents(plugin, player, "task-completed",
                MessageUtil.componentPlaceholders(
                    "task_name", MessageUtil.parse(task.getTemplate().getDisplayName())
                ));
        }

        // 检查类别是否全部完成
        checkCategoryCompletion(player, task.getCategory());
    }

    /**
     * 自动领取奖励
     */
    private void autoClaimReward(Player player, PlayerTask task) {
        // 标记已领取
        task.setClaimed(true);

        // 发放奖励
        task.getTemplate().getReward().grant(player, plugin);

        // 发送完成+领取消息
        MessageUtil.sendConfigWithComponents(plugin, player, "task-completed-auto",
            MessageUtil.componentPlaceholders(
                "task_name", MessageUtil.parse(task.getTemplate().getDisplayName()),
                "reward", task.getTemplate().getReward().getDisplayComponent(plugin)
            ));

        // 更新数据库 claimed 字段
        plugin.getDatabaseQueue().submit("autoClaimReward", (Connection conn) -> {
            String sql = "UPDATE player_daily_tasks SET completed = TRUE, claimed = TRUE " +
                "WHERE player_uuid = ? AND task_key = ? AND assigned_at = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.getUniqueId().toString());
                ps.setString(2, task.getTaskKey());
                Instant instant = TimeZoneConfig.toInstant(task.getAssignedAt());
                ps.setTimestamp(3, Timestamp.from(instant), TimeZoneConfig.UTC_CALENDAR);
                return ps.executeUpdate();
            }
        }, null, e -> plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to auto claim reward", e));
    }

    /**
     * 检查类别是否全部完成
     */
    private void checkCategoryCompletion(Player player, String categoryId) {
        UUID uuid = player.getUniqueId();
        List<PlayerTask> tasks = cacheManager.getPlayerTasksByCategory(uuid, categoryId);

        if (tasks.isEmpty()) return;

        boolean allCompleted = tasks.stream().allMatch(PlayerTask::isCompleted);

        if (allCompleted && !cacheManager.isCategoryCompletedNotified(uuid, categoryId)) {
            cacheManager.markCategoryCompletedNotified(uuid, categoryId);

            TaskCategory category = plugin.getConfigManager().getTaskCategory(categoryId);
            Component categoryName = category != null
                ? MessageUtil.parse(category.getDisplayName())
                : Component.text(categoryId);

            MessageUtil.sendConfigWithComponents(plugin, player, "all-tasks-completed",
                MessageUtil.componentPlaceholders("category", categoryName));
        }
    }

    /**
     * 发送进度更新提示
     * @param player 玩家
     * @param task 任务
     * @param currentProgress 当前进度
     * @param prevProgress 之前的进度（用于判断是否跨越里程碑）
     */
    private void sendProgressUpdate(Player player, PlayerTask task, int currentProgress, int prevProgress) {
        // 只在特定进度点发送提示（25%, 50%, 75%）
        int target = task.getTargetProgress();
        int percent = (currentProgress * 100) / target;

        // 检查是否跨越了里程碑
        int[] milestones = {25, 50, 75};
        int prevPercent = (prevProgress * 100) / target;

        for (int milestone : milestones) {
            if (prevPercent < milestone && percent >= milestone) {
                MessageUtil.sendConfigWithComponents(plugin, player, "task-progress-milestone",
                    MessageUtil.componentPlaceholders(
                        "task_name", MessageUtil.parse(task.getTemplate().getDisplayName()),
                        "progress", Component.text(currentProgress),
                        "target", Component.text(target),
                        "percent", Component.text(percent)
                    ));
                break;
            }
        }
    }

    /**
     * 提交任务物品并更新进度（用于 SUBMIT 类型任务）
     * @param player 玩家
     * @param task 任务
     * @param newProgress 新进度
     * @param callback 回调（success, isCompleted）
     */
    public void submitTaskProgress(Player player, PlayerTask task, int newProgress,
                                    java.util.function.BiConsumer<Boolean, Boolean> callback) {
        if (task == null) {
            callback.accept(false, false);
            return;
        }

        UUID uuid = player.getUniqueId();
        boolean nowCompleted = newProgress >= task.getTargetProgress();

        plugin.getDatabaseQueue().submit("submitTask", (Connection conn) -> {
            String updateSql = """
                UPDATE player_daily_tasks
                SET current_progress = ?, completed = ?
                WHERE player_uuid = ? AND task_key = ? AND assigned_at = ?
                """;
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setInt(1, newProgress);
                ps.setBoolean(2, nowCompleted);
                ps.setString(3, uuid.toString());
                ps.setString(4, task.getTaskKey());
                Instant instant = TimeZoneConfig.toInstant(task.getAssignedAt());
                ps.setTimestamp(5, Timestamp.from(instant), TimeZoneConfig.UTC_CALENDAR);
                int affected = ps.executeUpdate();
                return affected > 0;
            }
        }, success -> {
            if (success) {
                // 更新内存缓存
                task.setCurrentProgress(newProgress);
                if (nowCompleted) {
                    task.setCompleted(true);
                }
            }
            callback.accept(success, nowCompleted);
        }, e -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to update submit task progress", e);
            callback.accept(false, false);
        });
    }

    /**
     * 领取任务奖励
     * @param player 玩家
     * @param task 任务
     * @param callback 回调函数
     */
    public void claimRewardAsync(Player player, PlayerTask task, java.util.function.Consumer<Boolean> callback) {
        if (!task.isCompleted() || task.isClaimed()) {
            callback.accept(false);
            return;
        }

        UUID uuid = player.getUniqueId();

        plugin.getDatabaseQueue().submit("claimReward", (Connection conn) -> {
            String sql = """
                UPDATE player_daily_tasks
                SET claimed = TRUE
                WHERE player_uuid = ? AND task_key = ? AND assigned_at = ? AND claimed = FALSE
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, task.getTaskKey());
                Instant instant = TimeZoneConfig.toInstant(task.getAssignedAt());
                ps.setTimestamp(3, Timestamp.from(instant), TimeZoneConfig.UTC_CALENDAR);
                return ps.executeUpdate();
            }
        }, affectedRows -> {
            if (affectedRows != null && affectedRows == 1) {
                // 发放奖励
                task.getTemplate().getReward().grant(player, plugin);
                task.setClaimed(true);

                // 发送消息
                MessageUtil.sendConfig(plugin, player, "reward-claimed",
                    MessageUtil.textPlaceholders("reward",
                        task.getTemplate().getReward().getDisplayString(plugin)));

                callback.accept(true);
            } else {
                // 可能已经被其他服务器领取
                task.setClaimed(true);
                callback.accept(false);
            }
        }, e -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to claim reward", e);
            callback.accept(false);
        });
    }

}
