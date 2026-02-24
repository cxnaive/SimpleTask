package dev.user.simpletask.task.manager;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.util.TimeZoneConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 数据库操作工具类
 * 提供通用的 SQL 操作和批量操作功能
 */
public class DatabaseUtils {

    private final SimpleTaskPlugin plugin;

    public DatabaseUtils(SimpleTaskPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 执行 UPSERT 操作（H2/MySQL 兼容）
     */
    public void executeUpsert(Connection conn, String table, String[] columns, String[] keyColumns,
                              Object[] values) throws SQLException {
        boolean isMySQL = plugin.getDatabaseManager().isMySQL();
        String sql = buildUpsertSql(table, columns, keyColumns, isMySQL);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            for (Object value : values) {
                setParameter(ps, index++, value);
            }
            // MySQL 需要额外设置 UPDATE 部分的值
            if (isMySQL) {
                for (Object value : values) {
                    setParameter(ps, index++, value);
                }
            }
            ps.executeUpdate();
        }
    }

    /**
     * 构建 UPSERT SQL 语句
     */
    private String buildUpsertSql(String table, String[] columns, String[] keyColumns, boolean isMySQL) {
        StringBuilder sql = new StringBuilder();

        if (isMySQL) {
            sql.append("INSERT INTO ").append(table).append(" (");
            sql.append(String.join(", ", columns));
            sql.append(") VALUES (");
            sql.append("?,".repeat(columns.length - 1)).append("?");
            sql.append(") ON DUPLICATE KEY UPDATE ");
            for (String column : columns) {
                sql.append(column).append(" = VALUES(").append(column).append("), ");
            }
            sql.setLength(sql.length() - 2); // 移除最后的 ", "
        } else {
            sql.append("MERGE INTO ").append(table).append(" (");
            sql.append(String.join(", ", columns));
            sql.append(") KEY(");
            sql.append(String.join(", ", keyColumns));
            sql.append(") VALUES (");
            sql.append("?,".repeat(columns.length - 1)).append("?");
            sql.append(")");
        }

        return sql.toString();
    }

    /**
     * 设置 PreparedStatement 参数
     */
    private void setParameter(PreparedStatement ps, int index, Object value) throws SQLException {
        if (value instanceof String) {
            ps.setString(index, (String) value);
        } else if (value instanceof Integer) {
            ps.setInt(index, (Integer) value);
        } else if (value instanceof java.sql.Date) {
            ps.setDate(index, (java.sql.Date) value);
        } else if (value instanceof Boolean) {
            ps.setBoolean(index, (Boolean) value);
        } else if (value instanceof Timestamp) {
            // 使用 UTC Calendar 确保时区一致性
            ps.setTimestamp(index, (Timestamp) value, TimeZoneConfig.UTC_CALENDAR);
        } else {
            ps.setObject(index, value);
        }
    }

    /**
     * 批量执行玩家操作
     *
     * @param players     玩家列表
     * @param operation   操作函数
     * @param onComplete  完成回调（参数表示是否全部成功）
     */
    public <T> void executeBatch(List<T> players,
                                  BiConsumer<T, Consumer<Boolean>> operation,
                                  Consumer<Boolean> onComplete) {
        if (players.isEmpty()) {
            onComplete.accept(true);
            return;
        }

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);
        int total = players.size();

        for (T player : players) {
            operation.accept(player, success -> {
                if (success) {
                    successCount.incrementAndGet();
                } else {
                    // 记录失败日志
                    plugin.getLogger().warning("Batch operation failed for player: " + player);
                }
                if (completedCount.incrementAndGet() == total) {
                    int failures = total - successCount.get();
                    if (failures > 0) {
                        plugin.getLogger().warning("Batch operation completed with " + failures + " failures out of " + total);
                    }
                    onComplete.accept(successCount.get() == total);
                }
            });
        }
    }
}
