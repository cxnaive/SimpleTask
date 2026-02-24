package dev.user.simpletask.task.manager;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.PlayerTask;
import dev.user.simpletask.task.TaskTemplate;
import dev.user.simpletask.task.TemplateSyncManager;
import dev.user.simpletask.task.category.TaskCategory;
import dev.user.simpletask.util.ExpireUtil;
import dev.user.simpletask.util.MessageUtil;
import dev.user.simpletask.util.TimeUtil;
import dev.user.simpletask.util.TimeZoneConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reroll 管理器
 * 负责刷新次数的管理和任务刷新功能（付费/免费）
 */
public class RerollManager {

    private final SimpleTaskPlugin plugin;
    private final TaskCacheManager cacheManager;
    private final TaskGenerator taskGenerator;
    private final TemplateSyncManager templateSyncManager;
    private final DatabaseUtils databaseUtils;

    public RerollManager(SimpleTaskPlugin plugin, TaskCacheManager cacheManager,
                         TaskGenerator taskGenerator, TemplateSyncManager templateSyncManager) {
        this.plugin = plugin;
        this.cacheManager = cacheManager;
        this.taskGenerator = taskGenerator;
        this.templateSyncManager = templateSyncManager;
        this.databaseUtils = new DatabaseUtils(plugin);
    }

    // ==================== 公共验证方法 ====================

    /**
     * 验证类别是否可用
     */
    private TaskCategory validateCategory(String categoryId, BiCallback<Boolean, Component> callback) {
        TaskCategory category = plugin.getConfigManager().getTaskCategory(categoryId);
        if (category == null) {
            callback.call(false, MessageUtil.parse("<red>类别不存在: {category}",
                MessageUtil.textPlaceholders("category", categoryId)));
            return null;
        }
        if (!category.isEnabled()) {
            Component categoryName = MessageUtil.parse(category.getDisplayName());
            callback.call(false, categoryName.append(MessageUtil.parse(" <red>类别已禁用")));
            return null;
        }
        return category;
    }

    /**
     * 验证是否可以刷新
     */
    private boolean canReroll(TaskCategory category, RerollOptions options, BiCallback<Boolean, Component> callback) {
        // 管理员命令跳过 enabled 和 max-count 检查
        if (!options.isSkipEnabledCheck()) {
            if (!category.isRerollEnabled() || category.getRerollMaxCount() <= 0) {
                Component categoryName = MessageUtil.parse(category.getDisplayName());
                callback.call(false, categoryName.append(MessageUtil.parse(" <red>刷新功能已禁用")));
                return false;
            }
        }
        if (templateSyncManager.getAllTemplates().isEmpty()) {
            callback.call(false, MessageUtil.parse("<red>系统错误：没有可用的任务模板，请联系管理员"));
            return false;
        }
        return true;
    }

    // ==================== 统一的刷新方法 ====================

    /**
     * 刷新指定类别的任务（支持付费/免费模式）
     *
     * @param player     玩家
     * @param categoryId 类别ID
     * @param options    刷新选项（是否付费、是否检查次数等）
     * @param callback   结果回调
     */
    public void rerollCategoryTasks(Player player, String categoryId, RerollOptions options,
                                    java.util.function.BiConsumer<Boolean, Component> callback) {
        // 验证类别
        TaskCategory category = validateCategory(categoryId, (success, msg) -> callback.accept(success, msg));
        if (category == null) return;

        // 验证刷新功能是否启用
        if (!canReroll(category, options, (success, msg) -> callback.accept(success, msg))) return;

        // 付费模式额外检查
        if (options.isPaid()) {
            if (!checkBalance(player, category.getRerollCost(), (success, msg) -> callback.accept(success, msg))) return;
        }

        // 执行刷新
        doReroll(player, category, options, callback);
    }

    /**
     * 执行刷新操作
     * 采用"先数据库操作，成功后扣费"的策略，避免经济API不支持事务的问题
     */
    private void doReroll(Player player, TaskCategory category, RerollOptions options,
                          java.util.function.BiConsumer<Boolean, Component> callback) {
        UUID uuid = player.getUniqueId();
        LocalDateTime now = TimeUtil.nowTruncated();
        String categoryId = category.getId();
        double cost = options.isPaid() ? category.getRerollCost() : 0;

        // 先检查金币是否足够（不扣除）
        if (cost > 0 && plugin.getEconomyManager().isEnabled()) {
            double balance = plugin.getEconomyManager().getBalance(player);
            if (balance < cost) {
                callback.accept(false, MessageUtil.parse("<red>金币不足，需要 {cost} 金币",
                    MessageUtil.textPlaceholders("cost", String.format("%.0f", cost))));
                return;
            }
        }

        // 数据库操作（先确保数据库操作成功）
        plugin.getDatabaseQueue().submit("doReroll", (Connection conn) -> {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            try {
                int currentRerolls = 0;
                if (options.isCheckCount()) {
                    boolean canReroll = incrementRerollCountAtomic(conn, uuid, category, now);
                    if (!canReroll) {
                        return RerollResult.fail(MessageUtil.parse(
                            "<red>当前周期刷新次数已用完（上限：{max}次）",
                            MessageUtil.textPlaceholders("max", String.valueOf(category.getRerollMaxCount()))));
                    }
                    currentRerolls = getCategoryRerollCount(conn, uuid, category);
                }

                RerollResult result;
                if (options.isForce()) {
                    result = doForceReroll(conn, player, uuid, now, category);
                } else if (options.isKeepCompleted()) {
                    result = doPartialReroll(conn, player, uuid, now, category, currentRerolls);
                } else {
                    result = doFullReroll(conn, player, uuid, now, category);
                }

                conn.commit();
                return result;
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to rollback transaction", rollbackEx);
                }
                throw e;
            } finally {
                try {
                    if (!conn.isClosed()) {
                        conn.setAutoCommit(originalAutoCommit);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to restore auto-commit", e);
                    try {
                        conn.close();
                    } catch (SQLException closeEx) {
                        // ignore
                    }
                }
            }
        }, result -> {
            if (result.isSuccess()) {
                if (cost > 0 && plugin.getEconomyManager().isEnabled()) {
                    plugin.getEconomyManager().withdraw(player, cost);
                }
                cacheManager.getOrCreatePlayerTaskCache(uuid).remove(categoryId);
                callback.accept(true, result.getMessage());
            } else {
                callback.accept(false, result.getMessage());
            }
        }, e -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to reroll tasks for player: " + player.getName(), e);
            callback.accept(false, MessageUtil.parse("<red>刷新失败，请重试"));
        });
    }

    // ==================== 刷新策略实现 ====================

    /**
     * 部分刷新 - 只刷新未完成的任务，保留已完成的（用于付费刷新）
     * 注意：刷新次数已由原子操作更新，此方法不再处理次数更新
     */
    private RerollResult doPartialReroll(Connection conn, Player player, UUID uuid, LocalDateTime now,
                                          TaskCategory category, int currentRerolls) throws SQLException {
        String categoryId = category.getId();

        // 查询已领取的任务数量（这些应该保留）
        int completedCount = countTasksByStatus(conn, uuid, categoryId, true);

        if (completedCount >= category.getMaxConcurrent()) {
            Component categoryName = MessageUtil.parse(category.getDisplayName());
            return RerollResult.fail(categoryName.append(MessageUtil.parse(" <red>都已完成，无需刷新")));
        }

        // 查询已存在的任务key
        Set<String> existingTaskKeys = getExistingTaskKeys(conn, uuid, categoryId);

        // 删除未完成的任务
        int deletedCount = deleteUncompletedTasks(conn, uuid, categoryId);

        // 计算需要生成的新任务数量
        int needToGenerate = Math.min(
            category.getMaxConcurrent() - completedCount,
            templateSyncManager.getTemplatesByCategory(categoryId).size() - existingTaskKeys.size()
        );

        if (needToGenerate <= 0) {
            return RerollResult.fail(MessageUtil.parseConfig(plugin, "reroll-fail-no-templates"));
        }

        // 生成新任务
        List<TaskTemplate> availableTemplates = new ArrayList<>(templateSyncManager.getTemplatesByCategory(categoryId));
        availableTemplates.removeIf(t -> existingTaskKeys.contains(t.getTaskKey()));
        List<TaskTemplate> selectedTasks = TaskGenerator.selectRandomTasksFromList(availableTemplates, needToGenerate);

        // 插入新任务
        insertTasks(conn, uuid, now, category, selectedTasks);

        // 注意：刷新次数已由 incrementRerollCountAtomic 原子操作更新
        int newRerollCount = currentRerolls + 1;

        // 构建成功消息
        Component categoryName = MessageUtil.parse(category.getDisplayName());
        Component message = Component.empty()
            .append(MessageUtil.parse("<green>已刷新 "))
            .append(Component.text(deletedCount).color(NamedTextColor.YELLOW))
            .append(MessageUtil.parse(" <green>个未完成的"))
            .append(categoryName)
            .append(MessageUtil.parse(" <green>，保留了 "))
            .append(Component.text(completedCount).color(NamedTextColor.YELLOW))
            .append(MessageUtil.parse(" <green>个已完成任务！当前周期已使用 "))
            .append(Component.text(newRerollCount + "/" + category.getRerollMaxCount()).color(NamedTextColor.YELLOW))
            .append(MessageUtil.parse(" <green>次刷新"));
        return RerollResult.success(message);
    }

    /**
     * 强制刷新 - 删除所有任务并重新生成（无视完成状态）
     */
    private RerollResult doForceReroll(Connection conn, Player player, UUID uuid, LocalDateTime now,
                                        TaskCategory category) throws SQLException {
        String categoryId = category.getId();

        // 删除该玩家的所有任务
        deleteAllTasks(conn, uuid, categoryId);

        // 生成新任务
        List<PlayerTask> existingTasks = new ArrayList<>();
        List<PlayerTask> newTasks = taskGenerator.generateTasksForCategory(conn, player, category,
            category.getMaxConcurrent(), existingTasks);

        // 构建成功消息
        Component categoryName = MessageUtil.parse(category.getDisplayName());
        Component message = Component.empty()
            .append(MessageUtil.parse("<green>已强制刷新 "))
            .append(categoryName)
            .append(MessageUtil.parse(" <green>，生成了 "))
            .append(Component.text(newTasks.size()).color(NamedTextColor.YELLOW))
            .append(MessageUtil.parse(" <green>个新任务"));
        return RerollResult.success(message);
    }

    /**
     * 完全刷新 - 删除所有任务并重新生成（用于 Admin 免费刷新）
     */
    private RerollResult doFullReroll(Connection conn, Player player, UUID uuid, LocalDateTime now,
                                       TaskCategory category) throws SQLException {
        return doForceReroll(conn, player, uuid, now, category);
    }

    // ==================== 数据库操作封装 ====================

    private int countTasksByStatus(Connection conn, UUID uuid, String categoryId, boolean claimed) throws SQLException {
        String sql = "SELECT COUNT(*) as count FROM player_daily_tasks WHERE player_uuid = ? AND category = ? AND claimed = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, categoryId);
            ps.setBoolean(3, claimed);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("count") : 0;
            }
        }
    }

    private Set<String> getExistingTaskKeys(Connection conn, UUID uuid, String categoryId) throws SQLException {
        Set<String> keys = new HashSet<>();
        String sql = "SELECT task_key FROM player_daily_tasks WHERE player_uuid = ? AND category = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString("task_key"));
                }
            }
        }
        return keys;
    }

    private int deleteUncompletedTasks(Connection conn, UUID uuid, String categoryId) throws SQLException {
        String sql = "DELETE FROM player_daily_tasks WHERE player_uuid = ? AND category = ? AND completed = FALSE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, categoryId);
            return ps.executeUpdate();
        }
    }

    private void deleteAllTasks(Connection conn, UUID uuid, String categoryId) throws SQLException {
        String sql = "DELETE FROM player_daily_tasks WHERE player_uuid = ? AND category = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, categoryId);
            ps.executeUpdate();
        }
    }

    private void insertTasks(Connection conn, UUID uuid, LocalDateTime assignedAt, TaskCategory category,
                              List<TaskTemplate> templates) throws SQLException {
        if (templates.isEmpty()) return;

        // 过期策略从 category 配置获取，不存储在表中
        String insertSql = """
            INSERT INTO player_daily_tasks
            (player_uuid, task_key, task_version, category, current_progress, completed, claimed, assigned_at, task_data)
            VALUES (?, ?, ?, ?, 0, FALSE, FALSE, ?, ?)
            """;

        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (TaskTemplate template : templates) {
                ps.setString(1, uuid.toString());
                ps.setString(2, template.getTaskKey());
                ps.setInt(3, template.getVersion());
                ps.setString(4, category.getId());
                ps.setTimestamp(5, java.sql.Timestamp.from(TimeZoneConfig.toInstant(assignedAt)), TimeZoneConfig.UTC_CALENDAR);
                ps.setString(6, template.toJson());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ==================== 刷新次数管理 ====================

    /**
     * 检查并重置指定分类的刷新次数（如果需要）
     * 由外部在检查任务过期时调用
     *
     * @return true 如果执行了重置
     */
    public boolean checkAndResetRerollCount(Connection conn, UUID uuid, TaskCategory category) throws SQLException {
        String categoryId = category.getId();

        String selectSql = "SELECT reroll_count, last_reset_time FROM player_category_reroll WHERE player_uuid = ? AND category_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp lastResetTs = rs.getTimestamp("last_reset_time", TimeZoneConfig.UTC_CALENDAR);
                    LocalDateTime lastReset = null;
                    if (lastResetTs != null) {
                        java.time.Instant instant = lastResetTs.toInstant();
                        lastReset = TimeZoneConfig.toLocalDateTime(instant);
                    }

                    if (checkRerollNeedReset(category, lastReset)) {
                        String updateSql = "UPDATE player_category_reroll SET reroll_count = 0, last_reset_time = CURRENT_TIMESTAMP WHERE player_uuid = ? AND category_id = ?";
                        try (PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
                            updatePs.setString(1, uuid.toString());
                            updatePs.setString(2, categoryId);
                            updatePs.executeUpdate();
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 检查并重置玩家所有类别的刷新次数
     */
    public void checkAndResetAllRerollCounts(Connection conn, Player player) throws SQLException {
        for (TaskCategory category : plugin.getConfigManager().getTaskCategories().values()) {
            if (category.isEnabled() && category.isRerollEnabled()) {
                checkAndResetRerollCount(conn, player.getUniqueId(), category);
            }
        }
    }

    /**
     * 原子性增加刷新次数（纯原子操作，不处理重置）
     * 假设调用前已通过 checkAndResetRerollCount 处理了重置
     *
     * @return true 如果成功增加次数，false 如果已达上限
     */
    private boolean incrementRerollCountAtomic(Connection conn, UUID uuid, TaskCategory category,
                                                LocalDateTime now) throws SQLException {
        String categoryId = category.getId();
        int maxCount = category.getRerollMaxCount();

        // 确保记录存在
        ensureRerollRecordExists(conn, uuid, categoryId, now);

        // 条件更新：只有 count < maxCount 时才增加
        String updateSql = "UPDATE player_category_reroll SET reroll_count = reroll_count + 1 WHERE player_uuid = ? AND category_id = ? AND reroll_count < ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, categoryId);
            ps.setInt(3, maxCount);
            int affected = ps.executeUpdate();
            // affected > 0 表示成功更新（未超上限）
            return affected > 0;
        }
    }

    private void ensureRerollRecordExists(Connection conn, UUID uuid, String categoryId, LocalDateTime now) throws SQLException {
        boolean isMySQL = plugin.getDatabaseManager().isMySQL();

        if (isMySQL) {
            String insertIgnoreSql = "INSERT IGNORE INTO player_category_reroll (player_uuid, category_id, reroll_count, last_reset_time) VALUES (?, ?, 0, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertIgnoreSql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, categoryId);
                ps.setTimestamp(3, Timestamp.from(TimeZoneConfig.toInstant(now)), TimeZoneConfig.UTC_CALENDAR);
                ps.executeUpdate();
            }
        } else {
            // H2: 使用 MERGE INTO
            String mergeSql = "MERGE INTO player_category_reroll KEY(player_uuid, category_id) VALUES (?, ?, 0, ?)";
            try (PreparedStatement ps = conn.prepareStatement(mergeSql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, categoryId);
                ps.setTimestamp(3, Timestamp.from(TimeZoneConfig.toInstant(now)), TimeZoneConfig.UTC_CALENDAR);
                ps.executeUpdate();
            }
        }
    }

    /**
     * 获取玩家指定类别的刷新次数
     * 使用与任务过期相同的逻辑判断是否需要重置次数
     */
    public int getCategoryRerollCount(Connection conn, UUID uuid, TaskCategory category) throws SQLException {
        String selectSql = "SELECT reroll_count, last_reset_time FROM player_category_reroll WHERE player_uuid = ? AND category_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, category.getId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp lastResetTs = rs.getTimestamp("last_reset_time", TimeZoneConfig.UTC_CALENDAR);
                    LocalDateTime lastReset = null;
                    if (lastResetTs != null) {
                        java.time.Instant instant = lastResetTs.toInstant();
                        lastReset = TimeZoneConfig.toLocalDateTime(instant);
                    }

                    // 使用与任务过期相同的逻辑判断是否需要重置
                    if (checkRerollNeedReset(category, lastReset)) {
                        return 0;
                    }
                    return rs.getInt("reroll_count");
                }
            }
        }
        return 0;
    }

    /**
     * 更新刷新次数
     */
    private void updateRerollCount(Connection conn, UUID uuid, String categoryId, LocalDateTime resetTime, int newRerollCount) throws SQLException {
        String[] columns = {"player_uuid", "category_id", "reroll_count", "last_reset_time"};
        String[] keyColumns = {"player_uuid", "category_id"};
        Object[] values = {uuid.toString(), categoryId, newRerollCount, Timestamp.from(TimeZoneConfig.toInstant(resetTime))};

        databaseUtils.executeUpsert(conn, "player_category_reroll", columns, keyColumns, values);
    }

    /**
     * 检查刷新次数是否需要重置
     * 使用与任务过期相同的逻辑（ExpireUtil），但使用独立的 rerollResetConfig 配置
     *
     * @param category  任务类别配置
     * @param lastReset 上次重置时间
     * @return 是否需要重置
     */
    public boolean checkRerollNeedReset(TaskCategory category, LocalDateTime lastReset) {
        if (lastReset == null) return true;

        // 使用 ExpireUtil 进行判断，与任务过期逻辑保持一致
        // 但使用 rerollResetConfig 的独立配置（可以与任务过期策略不同）
        return ExpireUtil.isExpired(lastReset, category.getRerollResetConfig());
    }

    // ==================== 批量操作 ====================

    public void resetPlayerCategoryRerollCount(Player player, String categoryId,
                                                java.util.function.Consumer<Boolean> callback) {
        UUID uuid = player.getUniqueId();
        LocalDateTime now = TimeZoneConfig.now();

        plugin.getDatabaseQueue().submit("resetRerollCount", (Connection conn) -> {
            String[] columns = {"player_uuid", "category_id", "reroll_count", "last_reset_time"};
            String[] keyColumns = {"player_uuid", "category_id"};
            Object[] values = {uuid.toString(), categoryId, 0, Timestamp.from(TimeZoneConfig.toInstant(now))};

            databaseUtils.executeUpsert(conn, "player_category_reroll", columns, keyColumns, values);
            return true;
        }, callback, e -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                "Failed to reset reroll count for player: " + player.getName() + ", category: " + categoryId, e);
            callback.accept(false);
        });
    }

    public void resetAllPlayerCategoryRerollCount(String categoryId,
                                                   java.util.function.Consumer<Boolean> callback) {
        List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        databaseUtils.executeBatch(players,
            (player, cb) -> resetPlayerCategoryRerollCount(player, categoryId, cb),
            callback);
    }

    // ==================== 辅助方法 ====================

    private boolean checkBalance(Player player, double cost, BiCallback<Boolean, Component> callback) {
        if (cost <= 0) return true;
        if (!plugin.getEconomyManager().isEnabled()) return true;

        double balance = plugin.getEconomyManager().getBalance(player);
        if (balance < cost) {
            callback.call(false, MessageUtil.parse("<red>金币不足，需要 {cost} 金币",
                MessageUtil.textPlaceholders("cost", String.format("%.0f", cost))));
            return false;
        }
        return true;
    }

    // ==================== 回调接口 ====================

    @FunctionalInterface
    private interface BiCallback<T, U> {
        void call(T t, U u);
    }

    // 用于外部调用的回调接口（使用Component）
    @FunctionalInterface
    public interface RerollCallback {
        void onResult(boolean success, Component message);
    }

    // ==================== 结果记录 ====================

    private static class RerollResult {
        private final boolean success;
        private final Component message;

        private RerollResult(boolean success, Component message) {
            this.success = success;
            this.message = message;
        }

        static RerollResult success(Component message) {
            return new RerollResult(true, message);
        }

        static RerollResult fail(Component message) {
            return new RerollResult(false, message);
        }

        boolean isSuccess() {
            return success;
        }

        Component getMessage() {
            return message;
        }
    }

    // ==================== 刷新选项 ====================

    public static class RerollOptions {
        private boolean paid = false;          // 是否付费
        private boolean checkCount = false;    // 是否检查次数限制
        private boolean keepCompleted = false; // 是否保留已完成任务
        private boolean force = false;         // 是否强制刷新（删除所有）
        private boolean skipEnabledCheck = false; // 是否跳过 enabled 检查（管理员命令）

        private RerollOptions() {}

        public static RerollOptions free() {
            return new RerollOptions();
        }

        public static RerollOptions paid() {
            RerollOptions opts = new RerollOptions();
            opts.paid = true;
            opts.checkCount = true;
            opts.keepCompleted = true;
            return opts;
        }

        public static RerollOptions admin() {
            RerollOptions opts = new RerollOptions();
            opts.keepCompleted = true;  // Admin的reroll命令保留已完成任务（只刷新未完成的）
            opts.skipEnabledCheck = true; // 管理员跳过 enabled 检查
            return opts;
        }

        public static RerollOptions force() {
            RerollOptions opts = new RerollOptions();
            opts.force = true;
            opts.skipEnabledCheck = true; // 管理员跳过 enabled 检查
            return opts;
        }

        public RerollOptions withCountCheck(boolean check) {
            this.checkCount = check;
            return this;
        }

        public RerollOptions withKeepCompleted(boolean keep) {
            this.keepCompleted = keep;
            return this;
        }

        // Getters
        public boolean isPaid() { return paid; }
        public boolean isCheckCount() { return checkCount; }
        public boolean isKeepCompleted() { return keepCompleted; }
        public boolean isForce() { return force; }
        public boolean isSkipEnabledCheck() { return skipEnabledCheck; }
    }
}
