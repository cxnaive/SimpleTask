package dev.user.simpletask.task.manager;

import dev.user.simpletask.SimpleTaskPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 任务调度器
 * 负责定时任务（在线玩家任务检查）
 */
public class TaskScheduler {

    private final SimpleTaskPlugin plugin;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask onlinePlayerTask;

    public TaskScheduler(SimpleTaskPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 启动所有调度器
     */
    public void startAll() {
        startOnlinePlayerTaskChecker();
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
     * 关闭调度器，取消所有定时任务
     */
    public void shutdown() {
        plugin.getLogger().fine("Shutting down task scheduler...");

        if (onlinePlayerTask != null) {
            onlinePlayerTask.cancel();
            onlinePlayerTask = null;
        }
    }
}
