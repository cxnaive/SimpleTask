package dev.user.simpletask.anticheat;

import dev.user.simpletask.SimpleTaskPlugin;
import org.bukkit.Location;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 防刷任务管理器
 * 用于检测和防止玩家通过放置-破坏方块来刷BREAK任务进度
 */
public class AntiCheatManager {

    private final SimpleTaskPlugin plugin;

    // 内存缓存：位置 -> 放置时间戳
    // 使用ConcurrentHashMap保证线程安全
    private final Map<String, Long> playerPlacedBlocks = new ConcurrentHashMap<>();

    public AntiCheatManager(SimpleTaskPlugin plugin) {
        this.plugin = plugin;
        startCleanupTask();
    }

    /**
     * 记录玩家放置方块
     * @param location 方块位置
     */
    public void recordBlockPlace(Location location) {
        if (!plugin.getConfigManager().isAntiCheatEnabled()) {
            return;
        }

        String key = locationToKey(location);
        playerPlacedBlocks.put(key, System.currentTimeMillis());
    }

    /**
     * 清除方块记录（当方块被破坏时）
     * @param location 方块位置
     */
    public void removeBlockRecord(Location location) {
        if (!plugin.getConfigManager().isAntiCheatEnabled()) {
            return;
        }

        String key = locationToKey(location);
        playerPlacedBlocks.remove(key);
    }

    /**
     * 检查方块是否是玩家最近放置的
     * @param location 方块位置
     * @return true如果是玩家放置且在时间窗口内
     */
    public boolean isPlayerPlacedBlock(Location location) {
        if (!plugin.getConfigManager().isAntiCheatEnabled()) {
            return false;
        }

        String key = locationToKey(location);
        Long placeTime = playerPlacedBlocks.get(key);

        if (placeTime == null) {
            return false;
        }

        // 检查是否在时间窗口内
        int timeWindowSeconds = plugin.getConfigManager().getAntiCheatTimeWindow();
        if (timeWindowSeconds <= 0) {
            return false; // 时间窗口为0表示不限制
        }

        long timeWindowMillis = timeWindowSeconds * 1000L;
        long currentTime = System.currentTimeMillis();

        if (currentTime - placeTime > timeWindowMillis) {
            // 已过期，移除记录
            playerPlacedBlocks.remove(key);
            return false;
        }

        return true;
    }

    /**
     * 将位置转换为字符串key
     * 格式: world_uuid:x:y:z
     */
    private String locationToKey(Location location) {
        return location.getWorld().getUID().toString() + ":" +
               location.getBlockX() + ":" +
               location.getBlockY() + ":" +
               location.getBlockZ();
    }

    /**
     * 启动定期清理任务，移除过期的记录
     */
    private void startCleanupTask() {
        // 每5分钟清理一次过期数据
        long cleanupInterval = 20L * 60 * 5; // 5分钟

        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            cleanupExpiredEntries();
        }, cleanupInterval, cleanupInterval);
    }

    /**
     * 清理过期的条目
     */
    private void cleanupExpiredEntries() {
        if (!plugin.getConfigManager().isAntiCheatEnabled()) {
            playerPlacedBlocks.clear();
            return;
        }

        int timeWindowSeconds = plugin.getConfigManager().getAntiCheatTimeWindow();
        if (timeWindowSeconds <= 0) {
            return;
        }

        long timeWindowMillis = timeWindowSeconds * 1000L;
        long currentTime = System.currentTimeMillis();

        // 使用 removeIf 原子清理过期条目，避免并发修改问题
        int beforeSize = playerPlacedBlocks.size();
        playerPlacedBlocks.entrySet().removeIf(entry -> currentTime - entry.getValue() > timeWindowMillis);
        int removedCount = beforeSize - playerPlacedBlocks.size();

        if (removedCount > 0) {
            plugin.getLogger().fine("[AntiCheat] Cleaned up " + removedCount + " expired block records");
        }
    }

    /**
     * 获取当前缓存的记录数量（用于调试）
     */
    public int getCacheSize() {
        return playerPlacedBlocks.size();
    }

    /**
     * 清空所有缓存（用于重载配置时）
     */
    public void clearCache() {
        playerPlacedBlocks.clear();
    }
}
