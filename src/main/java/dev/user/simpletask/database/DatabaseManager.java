package dev.user.simpletask.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.user.simpletask.SimpleTaskPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
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
                    "    enabled BOOLEAN DEFAULT TRUE," +
                    "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                    ")";
            stmt.execute(templatesTable);

            // 玩家每日任务表 - 简化结构，使用 task_data JSON 存储完整数据
            String playerTasksTable = "CREATE TABLE IF NOT EXISTS player_daily_tasks (" +
                    "    player_uuid VARCHAR(36) NOT NULL," +
                    "    task_key VARCHAR(64) NOT NULL," +
                    "    task_version INT NOT NULL DEFAULT 1," +
                    "    current_progress INT DEFAULT 0," +
                    "    completed BOOLEAN DEFAULT FALSE," +
                    "    claimed BOOLEAN DEFAULT FALSE," +
                    "    task_date DATE NOT NULL," +
                    "    task_data TEXT NOT NULL," +
                    "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "    PRIMARY KEY (player_uuid, task_key, task_date)" +
                    ")";
            stmt.execute(playerTasksTable);

            // 玩家任务重置记录表
            String resetTable = "CREATE TABLE IF NOT EXISTS player_task_reset (" +
                    "    player_uuid VARCHAR(36) PRIMARY KEY," +
                    "    last_reset_date DATE NOT NULL," +
                    "    reset_server VARCHAR(64)," +
                    "    reroll_count INT DEFAULT 0" +
                    ")";
            stmt.execute(resetTable);

            // 创建索引
            createIndexes(stmt);
        }
    }

    private void createIndexes(Statement stmt) throws SQLException {
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_tasks_date ON player_daily_tasks (player_uuid, task_date)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_task_templates_key ON task_templates (task_key)");
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();

            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {}

            System.gc();

            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {}

            try {
                ClassLoader pluginClassLoader = plugin.getClass().getClassLoader();
                java.util.Enumeration<Driver> drivers = DriverManager.getDrivers();
                while (drivers.hasMoreElements()) {
                    Driver driver = drivers.nextElement();
                    if (driver.getClass().getClassLoader() == pluginClassLoader) {
                        DriverManager.deregisterDriver(driver);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    public boolean isMySQL() {
        String dbType = plugin.getConfigManager().getDatabaseType().toLowerCase();
        return dbType.equals("mysql") || dbType.equals("mariadb");
    }
}
