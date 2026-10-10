package dev.user.simpletask.task.manager;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.PAPIExpression;
import dev.user.simpletask.task.PlayerTask;
import dev.user.simpletask.task.TaskTemplate;
import dev.user.simpletask.task.TaskType;
import dev.user.simpletask.task.category.TaskCategory;
import dev.user.simpletask.util.MessageUtil;
import dev.user.simpletask.util.TimeZoneConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PAPI 任务检查器
 * 周期性轮询在线玩家的 PAPI 类型任务，评估占位符表达式并更新进度/完成状态
 */
public class PAPIChecker {

    private final SimpleTaskPlugin plugin;
    private final TaskCacheManager cacheManager;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask checkTask;
    private int intervalSeconds;

    public PAPIChecker(SimpleTaskPlugin plugin, TaskCacheManager cacheManager) {
        this.plugin = plugin;
        this.cacheManager = cacheManager;
    }

    /**
     * 启动 PAPI 检查器
     * 仅在 PlaceholderAPI 已安装时调用
     */
    public void start() {
        if (!PAPIExpression.isPAPIAvailable()) {
            plugin.getLogger().info("PlaceholderAPI not found, PAPI task checker disabled");
            return;
        }

        this.intervalSeconds = plugin.getConfigManager().getPapiCheckIntervalSeconds();
        if (intervalSeconds <= 0) {
            plugin.getLogger().info("PAPI task checker disabled (papi-check.interval-seconds <= 0)");
            return;
        }

        long intervalTicks = 20L * intervalSeconds;
        plugin.getLogger().info("Starting PAPI task checker (interval: " + intervalSeconds + " seconds)");

        checkTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            checkAllOnlinePlayers();
        }, intervalTicks, intervalTicks);
    }

    /**
     * 关闭 PAPI 检查器
     */
    public void shutdown() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
    }

    /**
     * 检查所有在线玩家的 PAPI 任务
     */
    private void checkAllOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().execute(plugin, () -> {
                try {
                    checkPlayerTasks(player);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error checking PAPI tasks for player " + player.getName() + ": " + e.getMessage());
                }
            }, null, 1L);
        }
    }

    /**
     * 检查单个玩家的 PAPI 任务
     */
    private void checkPlayerTasks(Player player) {
        UUID uuid = player.getUniqueId();
        Map<String, List<PlayerTask>> tasksByCategory = cacheManager.getPlayerTasksGroupedByCategory(uuid);

        if (tasksByCategory.isEmpty()) return;

        for (Map.Entry<String, List<PlayerTask>> entry : tasksByCategory.entrySet()) {
            String categoryId = entry.getKey();
            List<PlayerTask> tasks = entry.getValue();

            TaskCategory category = plugin.getConfigManager().getTaskCategory(categoryId);
            if (category == null || !category.isEnabled()) continue;

            for (PlayerTask task : tasks) {
                // 跳过非 PAPI 类型
                if (task.getTemplate().getType() != TaskType.PAPI) continue;

                // 跳过已完成或已领取的任务
                if (task.isCompleted() || task.isClaimed()) continue;

                // 跳过已过期的任务
                if (task.isExpired(category)) continue;

                processPAPITask(player, task);
            }
        }
    }

    /**
     * 处理单个 PAPI 任务
     */
    private void processPAPITask(Player player, PlayerTask task) {
        TaskTemplate template = task.getTemplate();
        PAPIExpression expression = template.getPAPIExpression();
        if (expression == null) return;

        // 获取当前数值（用于进度显示）
        double numericValue = expression.getNumericValue(player);
        if (numericValue >= 0) {
            task.setPAPIProgress((int) numericValue);
        }

        // 评估表达式是否满足
        if (expression.evaluate(player)) {
            // 使用 CAS 原子标记完成，防止重复触发
            if (task.tryMarkCompleted()) {
                // 持久化完成状态到数据库
                persistCompletion(player, task);
                // 已在玩家所属线程，可直接处理完成通知。
                onPAPITaskComplete(player, task);
            }
        } else {
            // 未完成，持久化进度更新
            persistProgress(player, task);
        }
    }

    /**
     * 持久化完成状态到数据库
     */
    private void persistCompletion(Player player, PlayerTask task) {
        plugin.getDatabaseQueue().submit("papiComplete", (Connection conn) -> {
            String sql = "UPDATE player_daily_tasks SET current_progress = ?, completed = TRUE " +
                "WHERE player_uuid = ? AND task_key = ? AND assigned_at = ? AND completed = FALSE";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, task.getCurrentProgress());
                ps.setString(2, player.getUniqueId().toString());
                ps.setString(3, task.getTaskKey());
                Instant instant = TimeZoneConfig.toInstant(task.getAssignedAt());
                ps.setTimestamp(4, Timestamp.from(instant), TimeZoneConfig.UTC_CALENDAR);
                return ps.executeUpdate();
            }
        }, affectedRows -> {
            if (affectedRows == null || affectedRows == 0) {
                // 可能已被其他线程/服务器完成，回滚内存状态
                // 但不需要额外处理，因为 tryMarkCompleted 的 CAS 保证了只有首次标记才触发完成流程
                plugin.getLogger().fine("PAPI task " + task.getTaskKey() + " completion already recorded in DB");
            }
        }, e -> plugin.getLogger().severe("Failed to persist PAPI task completion: " + e.getMessage()));
    }

    /**
     * 持久化进度更新到数据库
     */
    private void persistProgress(Player player, PlayerTask task) {
        plugin.getDatabaseQueue().submit("papiProgress", (Connection conn) -> {
            String sql = "UPDATE player_daily_tasks SET current_progress = ? " +
                "WHERE player_uuid = ? AND task_key = ? AND assigned_at = ? AND completed = FALSE";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, task.getCurrentProgress());
                ps.setString(2, player.getUniqueId().toString());
                ps.setString(3, task.getTaskKey());
                Instant instant = TimeZoneConfig.toInstant(task.getAssignedAt());
                ps.setTimestamp(4, Timestamp.from(instant), TimeZoneConfig.UTC_CALENDAR);
                return ps.executeUpdate();
            }
        }, null, e -> plugin.getLogger().warning("Failed to persist PAPI task progress: " + e.getMessage()));
    }

    /**
     * PAPI 任务完成处理
     * 在主线程执行，处理通知和自动领取
     */
    private void onPAPITaskComplete(Player player, PlayerTask task) {
        // 获取类别配置，检查是否自动领取
        TaskCategory category = plugin.getConfigManager().getTaskCategory(task.getCategory());
        if (category != null && category.isAutoClaim()) {
            autoClaimReward(player, task);
        } else {
            // 发送完成消息
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
        plugin.getDatabaseQueue().submit("papiAutoClaim", (Connection conn) -> {
            String sql = "UPDATE player_daily_tasks SET claimed = TRUE " +
                "WHERE player_uuid = ? AND task_key = ? AND assigned_at = ? AND claimed = FALSE";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.getUniqueId().toString());
                ps.setString(2, task.getTaskKey());
                Instant instant = TimeZoneConfig.toInstant(task.getAssignedAt());
                ps.setTimestamp(3, Timestamp.from(instant), TimeZoneConfig.UTC_CALENDAR);
                return ps.executeUpdate();
            }
        }, affectedRows -> {
            if (affectedRows != null && affectedRows > 0) {
                task.setClaimed(true);
                task.getTemplate().getReward().grant(player, plugin);
                MessageUtil.sendConfigWithComponents(plugin, player, "task-completed-auto",
                    MessageUtil.componentPlaceholders(
                        "task_name", MessageUtil.parse(task.getTemplate().getDisplayName()),
                        "reward", task.getTemplate().getReward().getDisplayComponent(plugin)
                    ));
            }
        }, e -> plugin.getLogger().severe("Failed to auto-claim PAPI task reward: " + e.getMessage()));
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
}
