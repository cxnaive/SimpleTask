package dev.user.simpletask.database;

import dev.user.simpletask.SimpleTaskPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import dev.user.simpletask.util.TimeZoneConfig;
import java.util.Set;
import java.util.logging.Level;

/**
 * 数据库迁移管理器
 * 处理表结构升级和数据迁移
 */
public class DatabaseMigration {

    private final SimpleTaskPlugin plugin;
    private final DatabaseManager databaseManager;

    // 允许的表名和列名白名单（防止SQL注入）
    private static final Set<String> ALLOWED_TABLES = Set.of(
        "player_daily_tasks", "task_templates", "player_category_reroll", "player_category_reset"
    );
    private static final Set<String> ALLOWED_COLUMNS = Set.of(
        "task_date", "assigned_at", "last_reset_date", "last_reset_time",
        "player_uuid", "task_key", "category", "category_id", "updated_at"
    );

    public DatabaseMigration(SimpleTaskPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    /**
     * 验证表名是否合法
     */
    private boolean isValidTableName(String table) {
        return ALLOWED_TABLES.contains(table);
    }

    /**
     * 验证列名是否合法
     */
    private boolean isValidColumnName(String column) {
        return ALLOWED_COLUMNS.contains(column);
    }

    /**
     * 执行数据库迁移
     * 确保 player_daily_tasks 表结构正确（使用 assigned_at 作为主键）
     */
    public void migrate() {
        plugin.getLogger().info("Checking database schema...");

        try (Connection conn = databaseManager.getConnection()) {
            // 检查表结构是否需要迁移
            if (!needsMigration(conn)) {
                plugin.getLogger().info("Database schema is up to date");
                return;
            }

            // 执行迁移
            doMigration(conn);
            plugin.getLogger().info("Database migration completed!");

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Database migration failed!", e);
            throw new RuntimeException("Database migration failed", e);
        }
    }

    /**
     * 检查是否需要迁移
     */
    private boolean needsMigration(Connection conn) throws SQLException {
        // 检查 player_daily_tasks 表是否存在
        boolean tableExists = tableExists(conn, "player_daily_tasks");
        if (tableExists) {
            // 检查是否需要迁移 player_daily_tasks 表
            if (columnExists(conn, "player_daily_tasks", "task_date")) {
                return true;
            }
            if (!columnExists(conn, "player_daily_tasks", "assigned_at")) {
                return true;
            }
        }

        // 检查 player_category_reroll 表是否需要升级（last_reset_date -> last_reset_time）
        if (tableExists(conn, "player_category_reroll")) {
            if (columnExists(conn, "player_category_reroll", "last_reset_date")) {
                return true;
            }
        }

        // 检查是否需要删除 task_templates 的 updated_at 字段
        if (tableExists(conn, "task_templates")) {
            if (columnExists(conn, "task_templates", "updated_at")) {
                return true;
            }
        }

        // 检查 player_category_reroll.last_reset_time 是否需要修复（MySQL）
        if (databaseManager.isMySQL() && tableExists(conn, "player_category_reroll")) {
            if (needsRerollLastResetTimeFix(conn)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 执行统一迁移：
     * 1. player_daily_tasks: task_date -> assigned_at
     * 2. player_category_reroll: last_reset_date -> last_reset_time
     */
    private void doMigration(Connection conn) throws SQLException {
        plugin.getLogger().info("Migrating database schema...");
        boolean isMySQL = databaseManager.isMySQL();

        // ====== 迁移 player_daily_tasks 表 ======
        if (tableExists(conn, "player_daily_tasks")) {
            // 1. 添加 assigned_at 字段（如果不存在）
            if (!columnExists(conn, "player_daily_tasks", "assigned_at")) {
                String addColumnSql = isMySQL ?
                        "ALTER TABLE player_daily_tasks ADD COLUMN assigned_at TIMESTAMP NULL" :
                        "ALTER TABLE player_daily_tasks ADD COLUMN assigned_at TIMESTAMP";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(addColumnSql);
                    plugin.getLogger().info("Added assigned_at column");
                }
            }

            // 2. 迁移 task_date 数据到 assigned_at
            if (columnExists(conn, "player_daily_tasks", "task_date")) {
                migrateTaskDateData(conn);
            }

            // 3. 创建唯一约束（如果不存在）
            try {
                String uniqueConstraintSql = isMySQL ?
                        "ALTER TABLE player_daily_tasks ADD CONSTRAINT uk_player_task_cycle " +
                                "UNIQUE (player_uuid, task_key, assigned_at)" :
                        "ALTER TABLE player_daily_tasks ADD CONSTRAINT IF NOT EXISTS uk_player_task_cycle " +
                                "UNIQUE (player_uuid, task_key, assigned_at)";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(uniqueConstraintSql);
                }
            } catch (SQLException e) {
                // 约束可能已存在，忽略
            }

            // 4. 删除旧的 task_date 字段
            if (columnExists(conn, "player_daily_tasks", "task_date")) {
                String dropColumnSql = isMySQL ?
                        "ALTER TABLE player_daily_tasks DROP COLUMN task_date" :
                        "ALTER TABLE player_daily_tasks DROP COLUMN IF EXISTS task_date";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(dropColumnSql);
                    plugin.getLogger().info("Removed task_date column");
                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to drop task_date column: " + e.getMessage());
                }
            }
        }

        // ====== 迁移 player_category_reroll 表：last_reset_date -> last_reset_time ======
        if (tableExists(conn, "player_category_reroll")) {
            if (columnExists(conn, "player_category_reroll", "last_reset_date")) {
                plugin.getLogger().info("Upgrading player_category_reroll table...");

                // 添加新的 last_reset_time 字段
                String addTimeColumnSql = isMySQL ?
                        "ALTER TABLE player_category_reroll ADD COLUMN last_reset_time TIMESTAMP NULL" :
                        "ALTER TABLE player_category_reroll ADD COLUMN last_reset_time TIMESTAMP";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(addTimeColumnSql);
                }

                // 迁移数据：DATE -> TIMESTAMP (设置为当天 04:00:00)
                String migrateDataSql = isMySQL ?
                        "UPDATE player_category_reroll SET last_reset_time = TIMESTAMP(CONCAT(last_reset_date, ' 04:00:00'))" :
                        "UPDATE player_category_reroll SET last_reset_time = CAST(last_reset_date || ' 04:00:00' AS TIMESTAMP)";
                try (Statement stmt = conn.createStatement()) {
                    int updated = stmt.executeUpdate(migrateDataSql);
                    plugin.getLogger().info("Migrated " + updated + " reroll records to use timestamp");
                }

                // 删除旧的 last_reset_date 字段
                String dropDateColumnSql = isMySQL ?
                        "ALTER TABLE player_category_reroll DROP COLUMN last_reset_date" :
                        "ALTER TABLE player_category_reroll DROP COLUMN IF EXISTS last_reset_date";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(dropDateColumnSql);
                    plugin.getLogger().info("Removed last_reset_date column");
                }
            }
        }

        // ====== 删除 task_templates 表的 updated_at 字段（已不再使用）======
        if (tableExists(conn, "task_templates")) {
            if (columnExists(conn, "task_templates", "updated_at")) {
                plugin.getLogger().info("Removing unused updated_at column from task_templates...");
                String dropUpdatedAtSql = isMySQL ?
                        "ALTER TABLE task_templates DROP COLUMN updated_at" :
                        "ALTER TABLE task_templates DROP COLUMN IF EXISTS updated_at";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(dropUpdatedAtSql);
                    plugin.getLogger().info("Removed updated_at column from task_templates");
                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to drop updated_at column: " + e.getMessage());
                }
            }
        }

        // ====== 修复 player_category_reroll.last_reset_time（MySQL）======
        if (isMySQL && tableExists(conn, "player_category_reroll")) {
            if (needsRerollLastResetTimeFix(conn)) {
                fixRerollLastResetTimeColumn(conn);
            }
        }

        plugin.getLogger().info("Schema migration completed");
    }

    /**
     * 修复 player_category_reroll.last_reset_time 的 MySQL 隐式 ON UPDATE CURRENT_TIMESTAMP 行为
     *
     * MySQL 在 explicit_defaults_for_timestamp=OFF（默认）时，第一个没有显式 DEFAULT 的 TIMESTAMP 列
     * 会自动获得 ON UPDATE CURRENT_TIMESTAMP 属性。导致任何 UPDATE 语句都会隐式更新 last_reset_time，
     * 使得每日重置检查永远判定为"不需要重置"。
     *
     * 修复方法：MODIFY COLUMN 添加 DEFAULT CURRENT_TIMESTAMP，消除隐式 ON UPDATE
     */
    private void fixRerollLastResetTimeColumn(Connection conn) throws SQLException {
        plugin.getLogger().info("Fixing player_category_reroll.last_reset_time implicit ON UPDATE behavior...");

        // 修改列定义，显式添加 DEFAULT CURRENT_TIMESTAMP 消除隐式 ON UPDATE
        String alterSql = "ALTER TABLE player_category_reroll MODIFY COLUMN last_reset_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(alterSql);
            plugin.getLogger().info("Fixed last_reset_time column: removed implicit ON UPDATE CURRENT_TIMESTAMP");
        }
    }

    /**
     * 迁移 task_date 数据到 assigned_at
     */
    private void migrateTaskDateData(Connection conn) throws SQLException {
        plugin.getLogger().info("Migrating task_date to assigned_at...");

        // 查询需要迁移的数据
        String selectSql = "SELECT player_uuid, task_key, task_date, category " +
                "FROM player_daily_tasks WHERE assigned_at IS NULL";

        String updateSql = "UPDATE player_daily_tasks SET assigned_at = ? " +
                "WHERE player_uuid = ? AND task_key = ? AND task_date = ?";

        int migratedCount = 0;
        int batchSize = 0;
        final int BATCH_LIMIT = 1000;

        try (PreparedStatement selectPs = conn.prepareStatement(selectSql);
             ResultSet rs = selectPs.executeQuery();
             PreparedStatement updatePs = conn.prepareStatement(updateSql)) {

            while (rs.next()) {
                String playerUuid = rs.getString("player_uuid");
                String taskKey = rs.getString("task_key");
                LocalDate taskDate = rs.getDate("task_date").toLocalDate();

                // 默认使用凌晨4点作为时间
                LocalDateTime assignedAt = taskDate.atTime(4, 0);

                // 时区安全：LocalDateTime -> Instant -> Timestamp
                updatePs.setTimestamp(1, Timestamp.from(TimeZoneConfig.toInstant(assignedAt)), TimeZoneConfig.UTC_CALENDAR);
                updatePs.setString(2, playerUuid);
                updatePs.setString(3, taskKey);
                updatePs.setDate(4, java.sql.Date.valueOf(taskDate));
                updatePs.addBatch();

                batchSize++;
                migratedCount++;

                if (batchSize >= BATCH_LIMIT) {
                    updatePs.executeBatch();
                    batchSize = 0;
                    plugin.getLogger().info("Migrated " + migratedCount + " records...");
                }
            }

            if (batchSize > 0) {
                updatePs.executeBatch();
            }
        }

        plugin.getLogger().info("Total migrated records: " + migratedCount);
    }

    /**
     * 检查表是否存在
     */
    private boolean tableExists(Connection conn, String table) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    /**
     * 检查字段是否存在
     */
    private boolean columnExists(Connection conn, String table, String column) throws SQLException {
        // 白名单校验（防止SQL注入）
        if (!isValidTableName(table)) {
            plugin.getLogger().warning("Invalid table name: " + table);
            return false;
        }
        if (!isValidColumnName(column)) {
            plugin.getLogger().warning("Invalid column name: " + column);
            return false;
        }

        boolean isMySQL = databaseManager.isMySQL();

        if (isMySQL) {
            String sql = "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, table);
                ps.setString(2, column);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        } else {
            // H2: 尝试查询列（表名和列名已通过白名单校验）
            String sql = "SELECT " + column + " FROM " + table + " WHERE 1=0";
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
                return true;
            } catch (SQLException e) {
                return false;
            }
        }
    }

    /**
     * 获取表中的记录数
     */
    public long getTableRecordCount(String table) {
        // 白名单验证，防止SQL注入
        if (!isValidTableName(table)) {
            plugin.getLogger().warning("Invalid table name for record count: " + table);
            return -1;
        }

        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get record count", e);
        }
        return -1;
    }

    /**
     * 检查 player_category_reroll.last_reset_time 是否需要修复
     * 通过 SHOW CREATE TABLE 解析列定义，检测两种问题：
     * 1. 有隐式 ON UPDATE CURRENT_TIMESTAMP（explicit_defaults_for_timestamp=OFF）
     * 2. 缺少 DEFAULT CURRENT_TIMESTAMP（列定义不匹配 DDL 规范）
     */
    private boolean needsRerollLastResetTimeFix(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE player_category_reroll")) {
            if (rs.next()) {
                String createTable = rs.getString(2);
                // 查找 last_reset_time 列定义
                // 预期格式: `last_reset_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
                for (String line : createTable.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("`last_reset_time`")) {
                        String lower = trimmed.toLowerCase();
                        // 检查是否缺少 DEFAULT CURRENT_TIMESTAMP
                        if (!lower.contains("default current_timestamp")) {
                            return true;
                        }
                        // 检查是否有隐式 ON UPDATE CURRENT_TIMESTAMP
                        if (lower.contains("on update current_timestamp")) {
                            return true;
                        }
                        return false; // 列定义正确，不需要修复
                    }
                }
            }
        }
        return false;
    }

    /**
     * 检查 MySQL TIMESTAMP 列是否有隐式 ON UPDATE CURRENT_TIMESTAMP
     * 通过 information_schema.COLUMNS 的 EXTRA 字段检测
     */
    private boolean columnHasImplicitOnUpdate(Connection conn, String table, String column) throws SQLException {
        String sql = "SELECT EXTRA FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String extra = rs.getString("EXTRA");
                    return extra != null && extra.contains("on update CURRENT_TIMESTAMP");
                }
            }
        }
        return false;
    }
}
