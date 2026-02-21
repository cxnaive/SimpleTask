package dev.user.simpletask.task.manager;

import dev.user.simpletask.SimpleTaskPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.LocalDateTime;

import dev.user.simpletask.util.TimeZoneConfig;

/**
 * 任务调度器
 * 负责定时任务（在线玩家任务检查、数据清理）
 */
public class TaskScheduler {

    private final SimpleTaskPlugin plugin;
    private final TaskExpireManager expireManager;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask onlinePlayerTask;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask cleanupTask;

    public TaskScheduler(SimpleTaskPlugin plugin, TaskExpireManager expireManager) {
        this.plugin = plugin;
        this.expireManager = expireManager;
    }

    /**
     * 启动所有调度器
     */
    public void startAll() {
        startOnlinePlayerTaskChecker();
        startDataCleanupScheduler();
    }

    /**
     * 启动在线玩家任务检查器
     */
    private void startOnlinePlayerTaskChecker() {
        int intervalMinutes = plugin.getConfigManager().getTaskCheckIntervalMinutes();
        if (intervalMinutes <= 0) {
            plugin.getLogger().info("Online player task checker disabled (interval-minutes <= 0)");
            return;
        }

        plugin.getLogger().info("Starting online player task checker (interval: " + intervalMinutes + " minutes)");

        long intervalTicks = 20L * 60 * intervalMinutes;

        onlinePlayerTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            checkAndRefreshOnlinePlayerTasks();
        }, intervalTicks, intervalTicks);
    }

    /**
     * 检查并刷新所有在线玩家的任务
     */
    private void checkAndRefreshOnlinePlayerTasks() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // 通过 TaskManager 检查，确保包含 reroll 重置逻辑
            plugin.getTaskManager().checkAndRefreshPlayerTasksWithReroll(player);
        }
    }

    /**
     * 启动过期数据清理调度器
     */
    private void startDataCleanupScheduler() {
        int retentionDays = plugin.getConfigManager().getDataRetentionDays();
        if (retentionDays <= 0) {
            plugin.getLogger().info("Data cleanup disabled (retention-days <= 0)");
            return;
        }

        plugin.getLogger().info("Starting data cleanup scheduler (retention: " + retentionDays + " days)");

        cleanupTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            cleanupExpiredData(retentionDays);
        }, 20L * 60 * 60 * 6, 20L * 60 * 60 * 6);
    }

    /**
     * 清理过期数据
     */
    private void cleanupExpiredData(int retentionDays) {
        LocalDateTime cutoffDate = TimeZoneConfig.now().minusDays(retentionDays);

        plugin.getDatabaseQueue().submit("cleanupExpiredData", (conn) -> {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            int deletedTasks = 0;
            int deletedResets = 0;
            int deletedRerolls = 0;

            try {
                // 1. 清理过期任务
                String deleteTasksSql = "DELETE FROM player_daily_tasks WHERE assigned_at < ?";
                try (java.sql.PreparedStatement ps = conn.prepareStatement(deleteTasksSql)) {
                    ps.setTimestamp(1, java.sql.Timestamp.valueOf(cutoffDate));
                    deletedTasks = ps.executeUpdate();
                }

                // 2. 清理重置记录
                String deleteResetSql = "DELETE FROM player_category_reset WHERE last_reset_time < ?";
                try (java.sql.PreparedStatement ps = conn.prepareStatement(deleteResetSql)) {
                    ps.setTimestamp(1, java.sql.Timestamp.valueOf(cutoffDate));
                    deletedResets = ps.executeUpdate();
                }

                // 3. 清理刷新次数记录
                String deleteRerollSql = "DELETE FROM player_category_reroll WHERE last_reset_time < ?";
                try (java.sql.PreparedStatement ps = conn.prepareStatement(deleteRerollSql)) {
                    ps.setTimestamp(1, java.sql.Timestamp.valueOf(cutoffDate));
                    deletedRerolls = ps.executeUpdate();
                }

                conn.commit();

                if (deletedTasks > 0 || deletedResets > 0 || deletedRerolls > 0) {
                    plugin.getLogger().info("Cleaned up expired data: " + deletedTasks + " tasks, "
                        + deletedResets + " reset records, " + deletedRerolls + " reroll records (before " + cutoffDate + ")");
                }
                return null;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        }, null, e -> plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to cleanup expired data", e));
    }

    /**
     * 关闭调度器，取消所有定时任务
     */
    public void shutdown() {
        plugin.getLogger().fine("Shutting down task scheduler...");

        if (onlinePlayerTask != null) {
            onlinePlayerTask.cancel();
            onlinePlayerTask = null;
        }

        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }
}
