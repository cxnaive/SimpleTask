package dev.user.simpletask.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.user.simpletask.SimpleTaskPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private final SimpleTaskPlugin plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(SimpleTaskPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean init() {
        close();

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(plugin.getClass().getClassLoader());

            String type = plugin.getConfigManager().getDatabaseType();

            if (type.equalsIgnoreCase("mysql")) {
                initMySQL();
            } else {
                initH2();
            }

            createTables();

            // 执行数据库迁移
            DatabaseMigration migration = new DatabaseMigration(plugin, this);
            migration.migrate();

            plugin.getLogger().info("Database connected! Type: " + type);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Database initialization failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private void initMySQL() throws Exception {
        HikariConfig config = new HikariConfig();
        String host = plugin.getConfigManager().getMysqlHost();
        int port = plugin.getConfigManager().getMysqlPort();
        String database = plugin.getConfigManager().getMysqlDatabase();
        String username = plugin.getConfigManager().getMysqlUsername();
        String password = plugin.getConfigManager().getMysqlPassword();
        int poolSize = plugin.getConfigManager().getMysqlPoolSize();

        // 注册重定位后的驱动
        try {
            Driver mysqlDriver = (Driver) Class.forName("dev.user.simpletask.libs.com.mysql.cj.jdbc.Driver", true, plugin.getClass().getClassLoader())
                    .getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(new DriverShim(mysqlDriver));
        } catch (Exception e) {
            plugin.getLogger().warning("MySQL driver registration failed (may already be registered): " + e.getMessage());
        }

        config.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                host, port, database));
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setDriverClassName("dev.user.simpletask.libs.com.mysql.cj.jdbc.Driver");

        // 添加连接泄漏检测（5分钟）
        config.setLeakDetectionThreshold(300000);

        dataSource = new HikariDataSource(config);
    }

    private void initH2() throws Exception {
        HikariConfig config = new HikariConfig();
        String filename = plugin.getConfigManager().getH2Filename();
        File dataFolder = new File(plugin.getDataFolder(), "data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        // 注册重定位后的驱动
        try {
            Driver h2Driver = (Driver) Class.forName("dev.user.simpletask.libs.org.h2.Driver", true, plugin.getClass().getClassLoader())
                    .getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(new DriverShim(h2Driver));
        } catch (Exception e) {
            plugin.getLogger().warning("H2 driver registration failed (may already be registered): " + e.getMessage());
        }

        config.setJdbcUrl("jdbc:h2:" + new File(dataFolder, filename).getAbsolutePath() +
                ";AUTO_RECONNECT=TRUE;DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setDriverClassName("dev.user.simpletask.libs.org.h2.Driver");
        config.setConnectionTestQuery("SELECT 1");

        // 添加连接泄漏检测（5分钟）
        config.setLeakDetectionThreshold(300000);

        dataSource = new HikariDataSource(config);
    }

    private static class DriverShim implements Driver {
        private final Driver driver;

        DriverShim(Driver driver) {
            this.driver = driver;
        }

        @Override
        public Connection connect(String url, java.util.Properties info) throws SQLException {
            return driver.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return driver.acceptsURL(url);
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) throws SQLException {
            return driver.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return driver.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return driver.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return driver.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger(DriverShim.class.getName());
        }
    }

    private void createTables() throws SQLException {
        boolean isMySQL = isMySQL();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // 任务模板表 - 简化结构，使用 task_data JSON 存储完整数据
            String templatesTable = "CREATE TABLE IF NOT EXISTS task_templates (" +
                    "    id " + (isMySQL ? "INT AUTO_INCREMENT PRIMARY KEY" : "INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY") + "," +
                    "    task_key VARCHAR(64) UNIQUE NOT NULL," +
                    "    version INT NOT NULL DEFAULT 1," +
                    "    task_data TEXT NOT NULL," +
                    "    enabled BOOLEAN DEFAULT TRUE" +
                    ")";
            stmt.execute(templatesTable);

            // 玩家任务表 - 支持多种过期策略
            // 使用 assigned_at (TIMESTAMP) 替代 task_date (DATE) 作为主键的一部分
            // 过期策略从 category 配置获取，不在表中存储
            String playerTasksTable = "CREATE TABLE IF NOT EXISTS player_daily_tasks (" +
                    "    player_uuid VARCHAR(36) NOT NULL," +
                    "    task_key VARCHAR(64) NOT NULL," +
                    "    task_version INT NOT NULL DEFAULT 1," +
                    "    category VARCHAR(32) DEFAULT 'daily'," +
                    "    current_progress INT DEFAULT 0," +
                    "    completed BOOLEAN DEFAULT FALSE," +
                    "    claimed BOOLEAN DEFAULT FALSE," +
                    "    assigned_at TIMESTAMP NOT NULL," +
                    "    task_data TEXT NOT NULL," +
                    "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "    PRIMARY KEY (player_uuid, task_key, assigned_at)" +
                    ")";
            stmt.execute(playerTasksTable);

            // 玩家分类任务重置记录表（支持多类别）
            String categoryResetTable = "CREATE TABLE IF NOT EXISTS player_category_reset (" +
                    "    player_uuid VARCHAR(36) NOT NULL," +
                    "    category_id VARCHAR(32) NOT NULL," +
                    "    last_reset_date DATE NOT NULL," +
                    "    PRIMARY KEY (player_uuid, category_id)" +
                    ")";
            stmt.execute(categoryResetTable);

            // 玩家分类 reroll 次数记录表
            // 使用 last_reset_time (TIMESTAMP) 替代 last_reset_date (DATE)
            // 刷新次数重置使用与任务过期相同的逻辑，需要精确时间
            String categoryRerollTable = "CREATE TABLE IF NOT EXISTS player_category_reroll (" +
                    "    player_uuid VARCHAR(36) NOT NULL," +
                    "    category_id VARCHAR(32) NOT NULL," +
                    "    reroll_count INT DEFAULT 0," +
                    "    last_reset_time TIMESTAMP NOT NULL," +
                    "    PRIMARY KEY (player_uuid, category_id)" +
                    ")";
            stmt.execute(categoryRerollTable);

            // 创建索引
            createIndexes(stmt);

            // 升级表结构（向后兼容）
            upgradeTables(stmt);
        }
    }

    private void createIndexes(Statement stmt) throws SQLException {
        boolean isMySQL = isMySQL();
        if (isMySQL) {
            // MySQL 不支持 CREATE INDEX IF NOT EXISTS，先检查再创建
            createMySQLIndexIfNotExists(stmt, "player_daily_tasks", "idx_player_tasks_assigned", "player_uuid, assigned_at");
            createMySQLIndexIfNotExists(stmt, "player_daily_tasks", "idx_category", "player_uuid, category");
            createMySQLIndexIfNotExists(stmt, "player_daily_tasks", "idx_assigned_at", "assigned_at");
            createMySQLIndexIfNotExists(stmt, "task_templates", "idx_task_templates_key", "task_key");
            createMySQLIndexIfNotExists(stmt, "player_category_reset", "idx_category_reset", "player_uuid, category_id");
            createMySQLIndexIfNotExists(stmt, "player_category_reroll", "idx_category_reroll", "player_uuid, category_id");
        } else {
            // H2 支持 IF NOT EXISTS
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_tasks_assigned ON player_daily_tasks (player_uuid, assigned_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_category ON player_daily_tasks (player_uuid, category)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_assigned_at ON player_daily_tasks (assigned_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_task_templates_key ON task_templates (task_key)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_category_reset ON player_category_reset (player_uuid, category_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_category_reroll ON player_category_reroll (player_uuid, category_id)");
        }
    }

    private void upgradeTables(Statement stmt) throws SQLException {
        boolean isMySQL = isMySQL();

        // 升级 player_daily_tasks 表 - 添加 category 字段
        if (isMySQL) {
            addMySQLColumnIfNotExists(stmt, "player_daily_tasks", "category", "VARCHAR(32) DEFAULT 'daily'");
        } else {
            addH2ColumnIfNotExists(stmt, "player_daily_tasks", "category", "VARCHAR(32) DEFAULT 'daily'");
        }
        // 注意：expire_policy 字段已从设计中移除，过期策略从 category 配置获取
    }

    private void addMySQLColumnIfNotExists(Statement stmt, String table, String column, String definition) throws SQLException {
        // 验证标识符安全（只允许字母、数字、下划线）
        if (!isValidIdentifier(table) || !isValidIdentifier(column)) {
            throw new SQLException("Invalid table or column name: " + table + "." + column);
        }
        // 使用 PreparedStatement 参数化 SCHEMA，但表名/列名使用反引号引用
        String checkSql = "SELECT COUNT(*) FROM information_schema.COLUMNS " +
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = stmt.getConnection().prepareStatement(checkSql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    // 使用反引号引用标识符防止 SQL 注入
                    stmt.execute(String.format("ALTER TABLE `%s` ADD COLUMN `%s` %s", table, column, definition));
                    plugin.getLogger().info("Added column " + column + " to table " + table);
                }
            }
        }
    }

    private void addH2ColumnIfNotExists(Statement stmt, String table, String column, String definition) throws SQLException {
        // H2: 尝试添加，如果已存在会报错，忽略错误
        try {
            stmt.execute(String.format("ALTER TABLE %s ADD COLUMN IF NOT EXISTS %s %s", table, column, definition));
        } catch (SQLException e) {
            // 如果报错说列已存在，忽略；否则抛出
            if (!e.getMessage().contains("already exists")) {
                throw e;
            }
        }
    }

    private void createMySQLIndexIfNotExists(Statement stmt, String table, String indexName, String columns) throws SQLException {
        // 验证标识符安全
        if (!isValidIdentifier(table) || !isValidIdentifier(indexName)) {
            throw new SQLException("Invalid table or index name: " + table + "." + indexName);
        }
        // 使用 PreparedStatement 参数化查询
        String checkSql = "SELECT COUNT(*) FROM information_schema.STATISTICS " +
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?";
        try (PreparedStatement ps = stmt.getConnection().prepareStatement(checkSql)) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    // 索引不存在，创建（使用反引号引用标识符）
                    stmt.execute(String.format("ALTER TABLE `%s` ADD INDEX `%s` (%s)", table, indexName, columns));
                }
            }
        }
    }

    /**
     * 验证 SQL 标识符是否只包含合法字符（字母、数字、下划线）
     */
    private boolean isValidIdentifier(String identifier) {
        return identifier != null && !identifier.isEmpty() && identifier.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            // 关闭连接池 - HikariCP 会优雅地关闭所有连接
            dataSource.close();

            // 注销驱动，避免类加载器泄漏（插件热重载时重要）
            // 先收集需要注销的驱动，再逐个注销，避免遍历中修改枚举
            ClassLoader pluginClassLoader = plugin.getClass().getClassLoader();
            java.util.List<Driver> driversToDeregister = new java.util.ArrayList<>();

            try {
                java.util.Enumeration<Driver> drivers = DriverManager.getDrivers();
                while (drivers.hasMoreElements()) {
                    Driver driver = drivers.nextElement();
                    if (driver.getClass().getClassLoader() == pluginClassLoader) {
                        driversToDeregister.add(driver);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to enumerate JDBC drivers: " + e.getMessage());
            }

            // 逐个注销，每个独立 try-catch 确保部分失败不影响其他
            for (Driver driver : driversToDeregister) {
                try {
                    DriverManager.deregisterDriver(driver);
                } catch (Exception e) {
                    plugin.getLogger().fine("Failed to deregister driver " + driver.getClass().getName() + ": " + e.getMessage());
                }
            }
        }
    }

    public boolean isMySQL() {
        String dbType = plugin.getConfigManager().getDatabaseType().toLowerCase();
        return dbType.equals("mysql") || dbType.equals("mariadb");
    }
}
