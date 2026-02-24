package dev.user.simpletask.task.manager;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.PlayerTask;
import dev.user.simpletask.task.TaskTemplate;
import dev.user.simpletask.task.TemplateSyncManager;
import dev.user.simpletask.task.category.TaskCategory;
import dev.user.simpletask.util.TimeUtil;
import dev.user.simpletask.util.TimeZoneConfig;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 任务生成器
 * 负责任务的随机选择和生成
 */
public class TaskGenerator {

    private final SimpleTaskPlugin plugin;
    private final TemplateSyncManager templateSyncManager;

    public TaskGenerator(SimpleTaskPlugin plugin, TemplateSyncManager templateSyncManager) {
        this.plugin = plugin;
        this.templateSyncManager = templateSyncManager;
    }

    /**
     * 从指定列表中随机选择指定数量的任务模板
     */
    public static List<TaskTemplate> selectRandomTasksFromList(List<TaskTemplate> available, int count) {
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

    /**
     * 为指定分类生成新任务
     */
    public List<PlayerTask> generateTasksForCategory(Connection conn, Player player, TaskCategory category,
                                                      int count, List<PlayerTask> existingTasks) throws SQLException {
        List<TaskTemplate> templates = templateSyncManager.getTemplatesByCategory(category.getId());
        return generateTasksForCategory(conn, player, category, count, existingTasks, templates);
    }

    /**
     * 为指定分类生成新任务（指定模板列表）
     */
    public List<PlayerTask> generateTasksForCategory(Connection conn, Player player, TaskCategory category,
                                                      int count, List<PlayerTask> existingTasks,
                                                      List<TaskTemplate> availableTemplates) throws SQLException {
        UUID uuid = player.getUniqueId();
        LocalDateTime assignedAt = TimeUtil.nowTruncated(); // 截断到秒级确保跨数据库一致性

        // 创建可修改的模板列表副本（传入的可能是不可修改集合）
        List<TaskTemplate> templatesToUse = new ArrayList<>(availableTemplates);

        // 如果没有特定分类的模板，不生成任务
        if (templatesToUse.isEmpty()) {
            plugin.getLogger().warning("[SimpleTask] Category '" + category.getId() + "' has no task templates configured");
            return Collections.emptyList();
        }

        // 排除已存在的任务
        Set<String> existingKeys = existingTasks.stream()
            .map(PlayerTask::getTaskKey)
            .collect(Collectors.toSet());
        templatesToUse.removeIf(t -> existingKeys.contains(t.getTaskKey()));

        if (templatesToUse.isEmpty()) {
            return Collections.emptyList();
        }

        // 警告：模板数量小于最大并行数量（排除已存在的任务后）
        int maxConcurrent = category.getMaxConcurrent();
        if (templatesToUse.size() < maxConcurrent) {
            plugin.getLogger().warning("[SimpleTask] Category '" + category.getId() + "' has only " +
                templatesToUse.size() + " available templates but max_concurrent is " + maxConcurrent);
        }

        // 随机选择任务
        List<TaskTemplate> selected = selectRandomTasksFromList(templatesToUse, count);
        List<PlayerTask> newTasks = new ArrayList<>();

        // 插入数据库 - 使用 assigned_at (TIMESTAMP)
        // 过期策略从 category 配置获取，不存储在表中
        String insertSql = """
            INSERT INTO player_daily_tasks
            (player_uuid, task_key, task_version, category, current_progress, completed, claimed, assigned_at, task_data)
            VALUES (?, ?, ?, ?, 0, FALSE, FALSE, ?, ?)
            """;

        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (TaskTemplate template : selected) {
                // 保存原始 category，设置为分配目标的 category 以保持一致性
                String originalCategory = template.getCategory();
                template.setCategory(category.getId());

                ps.setString(1, uuid.toString());
                ps.setString(2, template.getTaskKey());
                ps.setInt(3, template.getVersion());
                ps.setString(4, category.getId());
                // 时区安全：先将 LocalDateTime 转为 Instant，再存为 Timestamp
                java.time.Instant instant = TimeZoneConfig.toInstant(assignedAt);
                ps.setTimestamp(5, java.sql.Timestamp.from(instant), TimeZoneConfig.UTC_CALENDAR);
                ps.setString(6, template.toJson());  // 现在包含更新后的 category
                ps.addBatch();

                // 恢复原始 category（保持内存模板缓存不变）
                template.setCategory(originalCategory);

                PlayerTask task = new PlayerTask(uuid, template.getTaskKey(), template, 0, false, false, assignedAt, category.getId());
                newTasks.add(task);
            }
            ps.executeBatch(); // 批量执行
        }

        return newTasks;
    }

    /**
     * 随机选择任务（旧方法，兼容已有代码）
     */
    public List<TaskTemplate> selectRandomTasks(int count) {
        return selectRandomTasksFromList(
            new ArrayList<>(templateSyncManager.getAllTemplates()),
            count
        );
    }
}
