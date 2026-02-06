package dev.user.simpletask.task;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.database.DatabaseQueue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 模板同步管理器
 * 只负责从数据库同步模板到本地缓存（单向同步）
 * 修改数据库只能通过 /taskadmin import 或 Admin GUI
 */
public class TemplateSyncManager {

    private final SimpleTaskPlugin plugin;
    private final DatabaseQueue databaseQueue;

    // 本地模板缓存 (task_key -> TaskTemplate)
    private final Map<String, TaskTemplate> localTemplates = new ConcurrentHashMap<>();

    // 上次同步时间
    private volatile long lastSyncTime = 0;

    public TemplateSyncManager(SimpleTaskPlugin plugin) {
        this.plugin = plugin;
        this.databaseQueue = plugin.getDatabaseQueue();
    }

    /**
     * 启动时从数据库加载所有模板
     */
    public void loadFromDatabase() {
        plugin.getLogger().info("Loading templates from database...");

        databaseQueue.submit("loadTemplates", (Connection conn) -> {
            Map<String, TaskTemplate> templates = loadTemplatesFromDatabase(conn);
            localTemplates.clear();
            localTemplates.putAll(templates);
            lastSyncTime = System.currentTimeMillis();

            plugin.getLogger().info("Loaded " + templates.size() + " templates from database");
            return null;
        }, null, e -> plugin.getLogger().log(Level.SEVERE, "Failed to load templates", e));
    }

    /**
     * 强制从数据库重新加载（用于 import 命令后）
     */
    public void reloadFromDatabase(Runnable callback) {
        databaseQueue.submit("reloadTemplates", (Connection conn) -> {
            Map<String, TaskTemplate> templates = loadTemplatesFromDatabase(conn);
            localTemplates.clear();
            localTemplates.putAll(templates);
            lastSyncTime = System.currentTimeMillis();

            plugin.getLogger().info("Reloaded " + templates.size() + " templates from database");
            if (callback != null) {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, callback);
            }
            return null;
        }, null, e -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to reload templates", e);
            if (callback != null) {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, callback);
            }
        });
    }

    /**
     * 启动定时同步（如果配置了定时同步间隔）
     */
    public void startPeriodicSync() {
        int interval = plugin.getConfigManager().getTemplateSyncInterval();
        if (interval <= 0) {
            plugin.getLogger().info("Template periodic sync disabled");
            return;
        }

        long ticks = 20L * interval;
        plugin.getLogger().info("Starting template periodic sync (interval: " + interval + "s)");

        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            databaseQueue.submit("periodicSyncCheck", (Connection conn) -> {
                // 第一步：获取数据库中所有模板的版本信息（轻量级查询）
                Map<String, Integer> dbVersions = loadTemplateVersionsFromDatabase(conn);

                // 检查是否有变化
                Set<String> changedKeys = new HashSet<>();

                // 1. 检查新增或更新的模板
                for (Map.Entry<String, Integer> entry : dbVersions.entrySet()) {
                    String taskKey = entry.getKey();
                    int dbVersion = entry.getValue();
                    TaskTemplate local = localTemplates.get(taskKey);

                    if (local == null || local.getVersion() < dbVersion) {
                        changedKeys.add(taskKey);
                    }
                }

                // 2. 检查被删除的模板（本地有但数据库没有）
                for (String localKey : localTemplates.keySet()) {
                    if (!dbVersions.containsKey(localKey)) {
                        changedKeys.add(localKey);
                    }
                }

                if (changedKeys.isEmpty()) {
                    return null; // 没有变化，无需更新
                }

                plugin.getLogger().info("Detected " + changedKeys.size() + " template(s) changed in database");

                // 第二步：只获取有变化的模板的完整数据
                Map<String, TaskTemplate> updatedTemplates = new HashMap<>();

                // 复制本地缓存中未变化的模板
                for (Map.Entry<String, TaskTemplate> entry : localTemplates.entrySet()) {
                    if (!changedKeys.contains(entry.getKey())) {
                        updatedTemplates.put(entry.getKey(), entry.getValue());
                    }
                }

                // 从数据库加载变化的模板
                if (!changedKeys.isEmpty()) {
                    Map<String, TaskTemplate> changedTemplates = loadTemplatesByKeys(conn, changedKeys);
                    updatedTemplates.putAll(changedTemplates);
                }

                // 更新本地缓存
                localTemplates.clear();
                localTemplates.putAll(updatedTemplates);
                lastSyncTime = System.currentTimeMillis();

                plugin.getLogger().info("Templates updated from database: " + localTemplates.size() + " templates");
                return null;
            }, null, e -> plugin.getLogger().log(Level.WARNING, "Template sync failed", e));
        }, ticks, ticks);
    }

    /**
     * 从数据库加载所有模板的版本信息（轻量级查询）
     */
    private Map<String, Integer> loadTemplateVersionsFromDatabase(Connection conn) throws SQLException {
        Map<String, Integer> versions = new HashMap<>();

        String sql = "SELECT task_key, version FROM task_templates WHERE enabled = TRUE";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String taskKey = rs.getString("task_key");
                int version = rs.getInt("version");
                versions.put(taskKey, version);
            }
        }
        return versions;
    }

    /**
     * 从数据库加载指定 key 的模板
     */
    private Map<String, TaskTemplate> loadTemplatesByKeys(Connection conn, Set<String> keys) throws SQLException {
        Map<String, TaskTemplate> templates = new HashMap<>();
        if (keys.isEmpty()) {
            return templates;
        }

        // 构建 IN 子句
        String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
        String sql = "SELECT * FROM task_templates WHERE task_key IN (" + placeholders + ") AND enabled = TRUE";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            for (String key : keys) {
                ps.setString(index++, key);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TaskTemplate template = parseTemplateFromResultSet(rs);
                    if (template != null) {
                        templates.put(template.getTaskKey(), template);
                    }
                }
            }
        }
        return templates;
    }

    /**
     * 从数据库加载所有模板 - 队列自动管理连接
     */
    private Map<String, TaskTemplate> loadTemplatesFromDatabase(Connection conn) throws SQLException {
        Map<String, TaskTemplate> templates = new HashMap<>();

        String sql = "SELECT * FROM task_templates WHERE enabled = TRUE";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TaskTemplate template = parseTemplateFromResultSet(rs);
                if (template != null) {
                    templates.put(template.getTaskKey(), template);
                }
            }
        }
        return templates;
    }

    /**
     * 从 ResultSet 解析模板
     */
    private TaskTemplate parseTemplateFromResultSet(ResultSet rs) throws SQLException {
        try {
            String taskKey = rs.getString("task_key");
            int version = rs.getInt("version");
            String taskData = rs.getString("task_data");

            if (taskData == null || taskData.isEmpty()) {
                plugin.getLogger().warning("Missing task_data for template: " + taskKey);
                return null;
            }

            TaskTemplate template = TaskTemplate.fromJson(taskData);
            template.setVersion(version);
            template.setId(rs.getInt("id"));
            return template;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse template from database: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取本地模板
     */
    public TaskTemplate getTemplate(String taskKey) {
        return localTemplates.get(taskKey);
    }

    /**
     * 获取所有本地模板
     */
    public Collection<TaskTemplate> getAllTemplates() {
        return localTemplates.values();
    }

    /**
     * 获取模板数量
     */
    public int getTemplateCount() {
        return localTemplates.size();
    }

    /**
     * 获取上次同步时间
     */
    public long getLastSyncTime() {
        return lastSyncTime;
    }
}
