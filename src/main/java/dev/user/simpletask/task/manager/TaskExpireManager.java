package dev.user.simpletask.task.manager;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.ExpirePolicy;
import dev.user.simpletask.task.PlayerTask;
import dev.user.simpletask.task.TaskTemplate;
import dev.user.simpletask.task.TemplateSyncManager;
import dev.user.simpletask.task.category.TaskCategory;
import dev.user.simpletask.util.ExpireUtil;
import dev.user.simpletask.util.MessageUtil;
import dev.user.simpletask.util.TimeUtil;
import dev.user.simpletask.util.TimeZoneConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 任务过期管理器
 * 负责任务的过期检测、删除和刷新
 */
public class TaskExpireManager {

    private final SimpleTaskPlugin plugin;
    private final TaskCacheManager cacheManager;
    private final TaskGenerator taskGenerator;
    private final TemplateSyncManager templateSyncManager;

    public TaskExpireManager(SimpleTaskPlugin plugin, TaskCacheManager cacheManager, TaskGenerator taskGenerator, TemplateSyncManager templateSyncManager) {
        this.plugin = plugin;
        this.cacheManager = cacheManager;
        this.taskGenerator = taskGenerator;
        this.templateSyncManager = templateSyncManager;
    }

    /**
     * 从数据库删除过期任务
     * 使用 assigned_at 作为精确匹配条件
     */
    public static void deleteExpiredTasks(Connection conn, UUID uuid, List<PlayerTask> expiredTasks) throws SQLException {
        // 优先使用 assigned_at 删除，更精确
        String sql = "DELETE FROM player_daily_tasks WHERE player_uuid = ? AND task_key = ? AND assigned_at = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (PlayerTask task : expiredTasks) {
                ps.setString(1, uuid.toString());
                ps.setString(2, task.getTaskKey());
                ps.setTimestamp(3, Timestamp.valueOf(task.getAssignedAt()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * 分类任务刷新结果
     */
    public record CategoryRefreshResult(
        List<PlayerTask> tasks,
        boolean hasRefreshed,
        int expiredCount,
        int newGeneratedCount
    ) {}

    /**
     * 检查并刷新指定分类的任务（同步版本）
     * @return 刷新结果，包含任务列表和刷新状态
     */
    public CategoryRefreshResult checkAndRefreshCategoryTasks(Connection conn, Player player, TaskCategory category) throws SQLException {
        UUID uuid = player.getUniqueId();
        String categoryId = category.getId();

        // 1. 加载该分类的任务
        List<PlayerTask> tasks = loadTasksByCategory(conn, uuid, categoryId);

        // 2. 检测过期任务
        List<PlayerTask> expiredTasks = tasks.stream()
            .filter(task -> task.isExpired(category))
            .toList();

        // 3. 删除过期任务
        int expiredCount = 0;
        if (!expiredTasks.isEmpty()) {
            deleteExpiredTasks(conn, uuid, expiredTasks);
            tasks.removeAll(expiredTasks);
            expiredCount = expiredTasks.size();
            plugin.getLogger().fine("Deleted " + expiredCount + " expired tasks for " + player.getName() + " in category " + categoryId);
        }

        // 4. 补充新任务
        int currentCount = tasks.size();
        int maxCount = category.getMaxConcurrent();
        int newGeneratedCount = 0;

        if (currentCount < maxCount) {
            // FIXED 策略：只有在有效期内才生成新任务
            boolean canGenerate = true;
            if (category.getExpirePolicy() == ExpirePolicy.FIXED) {
                canGenerate = ExpireUtil.isInFixedPeriod(category.getExpirePolicyConfig());
            }

            if (canGenerate) {
                int needToGenerate = maxCount - currentCount;
                List<PlayerTask> newTasks = taskGenerator.generateTasksForCategory(conn, player, category, needToGenerate, tasks);
                tasks.addAll(newTasks);
                newGeneratedCount = newTasks.size();
            }
        }

        boolean hasRefreshed = expiredCount > 0 || newGeneratedCount > 0;
        return new CategoryRefreshResult(tasks, hasRefreshed, expiredCount, newGeneratedCount);
    }

    /**
     * 加载并检查玩家所有类别的任务（登录时使用）
     */
    public void loadAndCheckPlayerTasks(Connection conn, Player player) throws SQLException {
        UUID uuid = player.getUniqueId();
        Map<String, TaskCategory> categories = plugin.getConfigManager().getTaskCategories();

        // 初始化各分类的缓存
        Map<String, CopyOnWriteArrayList<PlayerTask>> tasksByCategory = new ConcurrentHashMap<>();

        // 记录哪些分类有任务被刷新（使用 Component 支持嵌套样式）
        List<Component> refreshedCategories = new ArrayList<>();

        // 加载每个分类的任务
        for (String categoryId : categories.keySet()) {
            TaskCategory category = categories.get(categoryId);
            if (!category.isEnabled()) continue;

            // 使用公共方法检查并刷新该分类
            CategoryRefreshResult result = checkAndRefreshCategoryTasks(conn, player, category);
            tasksByCategory.put(categoryId, new CopyOnWriteArrayList<>(result.tasks()));

            // 如果该分类有任务被刷新，记录分类显示名称（解析为 Component）
            if (result.hasRefreshed()) {
                refreshedCategories.add(MessageUtil.parse(category.getDisplayName()));
            }
        }

        // 更新缓存
        cacheManager.updatePlayerTaskCache(uuid, tasksByCategory);

        // 回主线程通知玩家
        final List<Component> finalRefreshedCategories = refreshedCategories;
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            // 检查玩家是否仍然在线
            if (!player.isOnline()) {
                return;
            }

            int totalTasks = tasksByCategory.values().stream().mapToInt(List::size).sum();
            plugin.getLogger().fine("Loaded " + totalTasks + " tasks for " + player.getName());

            // 如果有任务被刷新，发送通知
            sendRefreshNotification(player, finalRefreshedCategories);
        });
    }

    /**
     * 检查并刷新指定玩家的所有任务（定时检查、GUI调用使用）
     */
    public void checkAndRefreshPlayerTasks(Player player) {
        UUID uuid = player.getUniqueId();
        Map<String, TaskCategory> categories = plugin.getConfigManager().getTaskCategories();

        plugin.getDatabaseQueue().submit("checkAndRefreshPlayerTasks", (conn) -> {
            Map<String, CopyOnWriteArrayList<PlayerTask>> tasksByCategory = cacheManager.getOrCreatePlayerTaskCache(uuid);
            boolean hasChanges = false;
            int totalExpiredCount = 0;

            // 记录哪些分类有任务被刷新
            List<Component> refreshedCategories = new ArrayList<>();

            for (Map.Entry<String, TaskCategory> entry : categories.entrySet()) {
                String categoryId = entry.getKey();
                TaskCategory category = entry.getValue();
                if (!category.isEnabled()) continue;

                // 使用公共方法检查并刷新该分类
                CategoryRefreshResult result = checkAndRefreshCategoryTasks(conn, player, category);
                List<PlayerTask> refreshedTasks = result.tasks();
                totalExpiredCount += result.expiredCount();

                // 检查是否有变化
                List<PlayerTask> currentTasks = cacheManager.getPlayerTasksByCategory(uuid, categoryId);
                if (hasChanges(refreshedTasks, currentTasks)) {
                    hasChanges = true;
                }

                // 如果该分类有任务被刷新，记录分类显示名称
                if (result.hasRefreshed()) {
                    refreshedCategories.add(MessageUtil.parse(category.getDisplayName()));
                }

                tasksByCategory.put(categoryId, new CopyOnWriteArrayList<>(refreshedTasks));
            }

            // 如果有变化，更新缓存并发送通知
            if (hasChanges) {
                final Map<String, CopyOnWriteArrayList<PlayerTask>> finalTasksByCategory = tasksByCategory;
                final boolean hasExpiredTasks = totalExpiredCount > 0;
                final List<Component> finalRefreshedCategories = refreshedCategories;
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                    // 检查玩家是否仍然在线
                    if (!player.isOnline()) {
                        // 玩家已离线，清除该玩家的缓存避免内存泄漏
                        cacheManager.clearPlayerCache(uuid);
                        return;
                    }
                    // 如果有任务过期，关闭玩家正在打开的GUI
                    if (hasExpiredTasks) {
                        dev.user.simpletask.gui.GUIManager.closePlayerGUI(uuid);
                    }
                    cacheManager.updatePlayerTaskCache(uuid, finalTasksByCategory);

                    // 如果有任务被刷新，发送通知
                    sendRefreshNotification(player, finalRefreshedCategories);
                });
            }

            return null;
        }, null, e -> plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to check and refresh tasks for player: " + player.getName(), e));
    }

    /**
     * 发送任务刷新通知
     * @param player 玩家
     * @param refreshedCategories 被刷新的分类显示名称列表
     */
    private void sendRefreshNotification(Player player, List<Component> refreshedCategories) {
        if (refreshedCategories == null || refreshedCategories.isEmpty()) {
            return;
        }

        Component categoriesComponent = Component.empty();
        for (int i = 0; i < refreshedCategories.size(); i++) {
            if (i > 0) {
                categoriesComponent = categoriesComponent.append(Component.text(", "));
            }
            categoriesComponent = categoriesComponent.append(refreshedCategories.get(i));
        }

        MessageUtil.sendConfigWithComponents(plugin, player, "tasks-refreshed",
            MessageUtil.componentPlaceholders("categories", categoriesComponent));
    }

    /**
     * 检查两个任务列表是否有变化
     */
    private boolean hasChanges(List<PlayerTask> refreshedTasks, List<PlayerTask> currentTasks) {
        if (refreshedTasks.size() != currentTasks.size()) {
            return true;
        }

        Set<String> refreshedKeys = refreshedTasks.stream()
            .map(PlayerTask::getTaskKey)
            .collect(Collectors.toSet());
        Set<String> currentKeys = currentTasks.stream()
            .map(PlayerTask::getTaskKey)
            .collect(Collectors.toSet());

        return !refreshedKeys.equals(currentKeys);
    }

    /**
     * 按分类加载任务
     */
    public List<PlayerTask> loadTasksByCategory(Connection conn, UUID uuid, String categoryId) throws SQLException {
        List<PlayerTask> tasks = new ArrayList<>();
        String sql = "SELECT * FROM player_daily_tasks WHERE player_uuid = ? AND category = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, categoryId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        PlayerTask task = parsePlayerTaskFromResultSet(uuid, rs);
                        if (task != null) {
                            tasks.add(task);
                        }
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Failed to parse player task: " + ex.getMessage());
                    }
                }
            }
        }
        return tasks;
    }

    /**
     * 从结果集解析玩家任务
     * 优先使用 assigned_at 字段，如果不存在则使用 task_date
     */
    private PlayerTask parsePlayerTaskFromResultSet(UUID playerUuid, ResultSet rs) throws Exception {
        String taskKey = rs.getString("task_key");
        int progress = rs.getInt("current_progress");
        boolean completed = rs.getBoolean("completed");
        boolean claimed = rs.getBoolean("claimed");

        String category = rs.getString("category");
        if (category == null || category.isEmpty()) {
            category = "daily";
        }

        // 优先使用 assigned_at（带时分秒）
        LocalDateTime assignedAt = null;
        try {
            Timestamp assignedAtTs = rs.getTimestamp("assigned_at");
            if (assignedAtTs != null) {
                // 时区安全：先转 Instant，再转配置时区的 LocalDateTime
                java.time.Instant instant = assignedAtTs.toInstant();
                assignedAt = TimeZoneConfig.toLocalDateTime(instant);
            }
        } catch (SQLException e) {
            // assigned_at 字段可能不存在，忽略
        }

        // 如果没有 assigned_at，使用 task_date + 默认时间（兼容旧数据）
        if (assignedAt == null) {
            try {
                LocalDate taskDate = ((java.sql.Date) rs.getDate("task_date")).toLocalDate();
                assignedAt = taskDate.atTime(4, 0); // 默认凌晨4点
            } catch (SQLException e) {
                // 如果 task_date 也不存在，使用当前时间（截断到秒级）
                assignedAt = TimeUtil.nowTruncated();
            }
        }

        TaskTemplate localTemplate = templateSyncManager.getTemplate(taskKey);
        if (localTemplate == null) {
            plugin.getLogger().warning("Task template not found locally: " + taskKey);
            return null;
        }

        return new PlayerTask(playerUuid, taskKey, localTemplate, progress, completed, claimed, assignedAt, category);
    }
}
