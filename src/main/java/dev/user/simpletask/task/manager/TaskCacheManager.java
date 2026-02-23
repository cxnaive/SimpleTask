package dev.user.simpletask.task.manager;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.PlayerTask;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 任务缓存管理器
 * 管理玩家任务的内存缓存
 */
public class TaskCacheManager {

    private final SimpleTaskPlugin plugin;

    // 缓存玩家任务 - 按类别分组 Map<UUID, Map<category, CopyOnWriteArrayList<PlayerTask>>>
    private final Map<UUID, Map<String, CopyOnWriteArrayList<PlayerTask>>> playerTasks = new ConcurrentHashMap<>();
    // 追踪每个玩家每个类别是否已经发送过完成提示
    private final Map<UUID, Set<String>> playerCategoryCompletedNotified = new ConcurrentHashMap<>();

    public TaskCacheManager(SimpleTaskPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 获取玩家的所有任务（按类别分组）
     */
    public Map<String, List<PlayerTask>> getPlayerTasksGroupedByCategory(UUID uuid) {
        Map<String, CopyOnWriteArrayList<PlayerTask>> tasksByCategory = playerTasks.get(uuid);
        if (tasksByCategory == null) return new HashMap<>();

        Map<String, List<PlayerTask>> result = new HashMap<>();
        for (Map.Entry<String, CopyOnWriteArrayList<PlayerTask>> entry : tasksByCategory.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
    }

    /**
     * 获取玩家指定类别的任务
     */
    public List<PlayerTask> getPlayerTasksByCategory(UUID uuid, String categoryId) {
        Map<String, CopyOnWriteArrayList<PlayerTask>> tasksByCategory = playerTasks.get(uuid);
        if (tasksByCategory == null) return Collections.emptyList();

        CopyOnWriteArrayList<PlayerTask> tasks = tasksByCategory.get(categoryId);
        return tasks != null ? new ArrayList<>(tasks) : Collections.emptyList();
    }

    /**
     * 获取玩家的所有任务（所有类别）
     */
    public List<PlayerTask> getPlayerTasks(UUID uuid) {
        Map<String, CopyOnWriteArrayList<PlayerTask>> tasksByCategory = playerTasks.get(uuid);
        if (tasksByCategory == null) return Collections.emptyList();

        return tasksByCategory.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    /**
     * 更新指定类别的任务缓存
     */
    public void updateCategoryTaskCache(UUID uuid, String categoryId, List<PlayerTask> tasks) {
        playerTasks.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
            .put(categoryId, new CopyOnWriteArrayList<>(tasks));
    }

    /**
     * 更新整个玩家的任务缓存
     */
    public void updatePlayerTaskCache(UUID uuid, Map<String, CopyOnWriteArrayList<PlayerTask>> tasksByCategory) {
        playerTasks.put(uuid, tasksByCategory);
    }

    /**
     * 获取或创建玩家的任务缓存
     */
    public Map<String, CopyOnWriteArrayList<PlayerTask>> getOrCreatePlayerTaskCache(UUID uuid) {
        return playerTasks.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
    }

    /**
     * 清除玩家缓存
     */
    public void clearPlayerCache(UUID uuid) {
        playerTasks.remove(uuid);
        playerCategoryCompletedNotified.remove(uuid);
    }

    /**
     * 检查玩家是否已完成某类别的所有任务
     */
    public boolean isCategoryCompletedNotified(UUID uuid, String categoryId) {
        Set<String> notified = playerCategoryCompletedNotified.get(uuid);
        return notified != null && notified.contains(categoryId);
    }

    /**
     * 标记玩家已完成某类别的所有任务
     */
    public void markCategoryCompletedNotified(UUID uuid, String categoryId) {
        playerCategoryCompletedNotified.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet())
            .add(categoryId);
    }

    /**
     * 获取已通知完成的类别集合
     */
    public Set<String> getCompletedNotifiedCategories(UUID uuid) {
        return playerCategoryCompletedNotified.getOrDefault(uuid, Collections.emptySet());
    }

    /**
     * 获取所有已缓存的玩家UUID
     */
    public Set<UUID> getAllCachedUUIDs() {
        return new HashSet<>(playerTasks.keySet());
    }

    /**
     * 原子更新玩家任务缓存
     * 直接使用 put 替换，不先 clear，避免中间状态
     */
    public void atomicUpdatePlayerTaskCache(UUID uuid, Map<String, CopyOnWriteArrayList<PlayerTask>> tasksByCategory) {
        // 直接 put 替换，不使用 clear
        playerTasks.put(uuid, tasksByCategory);
        // 可选：清理已完成通知标记（如果需要）
        // playerCategoryCompletedNotified.remove(uuid);
    }

    /**
     * 从缓存中移除玩家的指定任务
     */
    public void removePlayerTask(UUID uuid, String categoryId, String taskKey) {
        Map<String, CopyOnWriteArrayList<PlayerTask>> tasksByCategory = playerTasks.get(uuid);
        if (tasksByCategory == null) return;

        CopyOnWriteArrayList<PlayerTask> tasks = tasksByCategory.get(categoryId);
        if (tasks == null) return;

        tasks.removeIf(task -> task.getTaskKey().equals(taskKey));
    }
}
