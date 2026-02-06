package dev.user.simpletask.gui;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.PlayerTask;
import dev.user.simpletask.task.Reward;
import dev.user.simpletask.task.TaskTemplate;
import dev.user.simpletask.task.TaskType;
import dev.user.simpletask.util.ItemUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DailyTaskGUI extends AbstractGUI {

    private List<PlayerTask> tasks;
    private boolean detailedMode = false;

    public DailyTaskGUI(SimpleTaskPlugin plugin, Player player, List<PlayerTask> tasks) {
        super(plugin, player, plugin.getConfigManager().getGuiTitleDailyTasks(), 54);
        this.tasks = tasks;
    }

    public DailyTaskGUI(SimpleTaskPlugin plugin, Player player, List<PlayerTask> tasks, boolean detailedMode) {
        super(plugin, player, plugin.getConfigManager().getGuiTitleDailyTasks(), 54);
        this.tasks = tasks;
        this.detailedMode = detailedMode;
    }

    /**
     * 异步打开每日任务GUI - 先查询数据库，再打开界面
     */
    public static void open(SimpleTaskPlugin plugin, Player player) {
        UUID uuid = player.getUniqueId();
        LocalDate today = LocalDate.now();

        // 先显示加载中提示
        player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize("<gray>正在加载任务数据..."));

        // 使用新的 DatabaseQueue API - 队列自动管理连接
        plugin.getDatabaseQueue().submit("DailyTaskGUI.loadTasks", (Connection conn) -> {
            List<PlayerTask> tasks = loadTasksFromDatabase(conn, uuid, today, plugin);

            // 如果今天没有任务，检查是否需要生成
            if (tasks.isEmpty()) {
                boolean needGenerate = checkNeedGenerate(conn, uuid, today);

                if (needGenerate) {
                    // 调用 TaskManager 生成任务
                    plugin.getTaskManager().rerollPlayerTasks(player, true, success -> {
                        if (success) {
                            // 重新加载
                            open(plugin, player);
                        } else {
                            player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                    .deserialize("<red>生成任务失败，请重试"));
                        }
                    });
                    return null; // 异步生成任务，不继续打开GUI
                }
            }

            // 更新本地缓存
            plugin.getTaskManager().updatePlayerTaskCache(uuid, tasks);

            // 在主线程打开GUI
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                DailyTaskGUI gui = new DailyTaskGUI(plugin, player, tasks);
                gui.open();
            });
            return null;
        }, null, e -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to load player tasks for GUI", e);
            player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize("<red>加载任务数据失败，请重试"));
        });
    }

    /**
     * 从数据库加载任务
     */
    private static List<PlayerTask> loadTasksFromDatabase(Connection conn, UUID uuid, LocalDate today, SimpleTaskPlugin plugin) throws SQLException {
        List<PlayerTask> tasks = new ArrayList<>();
        String sql = """
            SELECT * FROM player_daily_tasks
            WHERE player_uuid = ? AND task_date = ?
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setDate(2, java.sql.Date.valueOf(today));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        PlayerTask task = parsePlayerTaskFromResultSet(uuid, rs, plugin);
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
     * 检查是否需要生成新任务
     */
    private static boolean checkNeedGenerate(Connection conn, UUID uuid, LocalDate today) throws SQLException {
        String resetSql = "SELECT last_reset_date FROM player_task_reset WHERE player_uuid = ?";
        try (PreparedStatement ps = conn.prepareStatement(resetSql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return true; // 首次登录
                } else {
                    java.sql.Date resetDate = rs.getDate("last_reset_date");
                    return !resetDate.toLocalDate().equals(today); // 日期不同
                }
            }
        }
    }

    /**
     * 获取玩家今日已使用刷新次数
     */
    private int getTodayRerollCount(Player player) {
        UUID uuid = player.getUniqueId();
        LocalDate today = LocalDate.now();

        // 尝试从数据库同步查询
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            String sql = "SELECT reroll_count, last_reset_date FROM player_task_reset WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        java.sql.Date resetDate = rs.getDate("last_reset_date");
                        if (resetDate.toLocalDate().equals(today)) {
                            return rs.getInt("reroll_count");
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get reroll count: " + e.getMessage());
        }
        return 0;
    }

    private static PlayerTask parsePlayerTaskFromResultSet(UUID playerUuid, ResultSet rs, SimpleTaskPlugin plugin) throws Exception {
        String taskKey = rs.getString("task_key");
        int progress = rs.getInt("current_progress");
        boolean completed = rs.getBoolean("completed");
        boolean claimed = rs.getBoolean("claimed");
        LocalDate taskDate = ((java.sql.Date) rs.getDate("task_date")).toLocalDate();

        // 查找本地模板
        TaskTemplate localTemplate = plugin.getTaskManager().getTemplateByKey(taskKey);
        if (localTemplate == null) {
            plugin.getLogger().warning("Task template not found locally: " + taskKey);
            return null;
        }

        return new PlayerTask(playerUuid, taskKey, localTemplate, progress, completed, claimed, taskDate);
    }

    @Override
    public void initialize() {
        inventory.clear();
        actions.clear();

        // 顶部信息/详细模式切换按钮 (slot 4 - 第一行中心)
        setInfoButton();

        // Fill border (保留顶部中心 slot 4 给信息按钮)
        ItemStack borderItem = createDecoration(
            Material.valueOf(plugin.getConfigManager().getGuiDecoration("border").replace("minecraft:", "").toUpperCase()),
            " "
        );
        // 手动填充边框，跳过 slot 4
        fillBorderExceptTopCenter(borderItem);

        // 自动计算任务slot位置，使其在GUI中居中显示
        // 可用区域：中间4行(第2-5行)，每行7格，共28格
        int maxTasksPerRow = 7;
        int maxRows = 4;
        int totalAvailableSlots = maxTasksPerRow * maxRows; // 28

        int taskCount = Math.min(tasks.size(), totalAvailableSlots);
        if (taskCount > 0) {
            // 计算需要多少行
            int rowsNeeded = (taskCount + maxTasksPerRow - 1) / maxTasksPerRow;
            // 垂直居中：在4行可用区域中居中
            int startRow = 1 + (maxRows - rowsNeeded) / 2; // 基础行(1) + 偏移

            for (int i = 0; i < taskCount; i++) {
                PlayerTask task = tasks.get(i);

                // 计算当前任务在哪一行、哪一列
                int rowInTaskArea = i / maxTasksPerRow;
                int colInRow = i % maxTasksPerRow;

                // 计算该行的实际任务数（最后一行可能不满）
                int tasksInThisRow = Math.min(maxTasksPerRow, taskCount - rowInTaskArea * maxTasksPerRow);
                // 水平居中：在该行内居中
                int startCol = 1 + (maxTasksPerRow - tasksInThisRow) / 2; // 基础列(1) + 偏移

                // 计算实际slot (行*9 + 列)
                int actualRow = startRow + rowInTaskArea;
                int actualCol = startCol + colInRow;
                int slot = actualRow * 9 + actualCol;

                ItemStack taskItem = createTaskItem(task, detailedMode);
                setItem(slot, taskItem, (p, e) -> onTaskClick(p, task));
            }
        }

        // Bottom row buttons (row 6: slots 45-53) - centered at bottom
        // Progress summary in slot 46
        long completedCount = tasks.stream().filter(PlayerTask::isClaimed).count();
        long totalCount = tasks.size();
        String progressText = "<yellow>进度: <green>" + completedCount + "<gray>/<yellow>" + totalCount;
        ItemStack progressItem = createDecoration(Material.PAPER, progressText);
        setItem(46, progressItem);

        // Close button centered at slot 49
        ItemStack closeButton = createDecoration(Material.BARRIER, "<red>关闭");
        setItem(49, closeButton, (p, e) -> p.closeInventory());

        // Refresh button at slot 51 - 刷新每日任务
        int maxRerolls = plugin.getConfigManager().getDailyRerollMax();
        double rerollCost = plugin.getConfigManager().getDailyRerollCost();

        // 获取今日已使用刷新次数
        int usedRerolls = getTodayRerollCount(player);
        int remainingRerolls = maxRerolls - usedRerolls;

        String rerollName;
        List<String> rerollLore = new ArrayList<>();

        if (maxRerolls <= 0) {
            rerollName = "<gray>刷新任务 (已禁用)";
        } else if (remainingRerolls <= 0) {
            rerollName = "<gray>刷新任务 (次数已用完)";
            rerollLore.add("<red>今日刷新次数已用完");
            rerollLore.add("<gray>上限: " + maxRerolls + " 次/天");
        } else {
            rerollName = "<yellow>刷新任务 <green>(" + remainingRerolls + "/" + maxRerolls + ")";
            rerollLore.add("<gray>今日剩余: " + remainingRerolls + " 次");
            rerollLore.add("<gray>上限: " + maxRerolls + " 次/天");
            if (rerollCost > 0) {
                rerollLore.add("<gold>花费: " + rerollCost + " 金币");
            } else {
                rerollLore.add("<green>免费");
            }
            rerollLore.add("");
            rerollLore.add("<yellow>点击刷新每日任务");
        }

        ItemStack refreshItem = createDecoration(Material.CLOCK, rerollName);
        if (!rerollLore.isEmpty()) {
            ItemMeta meta = refreshItem.getItemMeta();
            if (meta != null) {
                List<Component> loreComponents = new ArrayList<>();
                for (String line : rerollLore) {
                    loreComponents.add(MiniMessage.miniMessage().deserialize(line)
                        .decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(loreComponents);
                refreshItem.setItemMeta(meta);
            }
        }

        setItem(51, refreshItem, (p, e) -> {
            if (maxRerolls <= 0) {
                p.sendMessage(MiniMessage.miniMessage().deserialize("<red>每日任务刷新功能已禁用"));
                return;
            }
            if (remainingRerolls <= 0) {
                p.sendMessage(MiniMessage.miniMessage().deserialize("<red>今日刷新次数已用完"));
                return;
            }

            p.closeInventory();
            plugin.getTaskManager().playerRerollDailyTasks(p, (success, message) -> {
                p.sendMessage(MiniMessage.miniMessage().deserialize(message));
                if (success) {
                    // 刷新后重新打开GUI
                    open(plugin, p);
                }
            });
        });
    }

    private ItemStack createTaskItem(PlayerTask task, boolean detailedMode) {
        String name;
        List<Component> lore = new ArrayList<>();

        // 使用任务的 name 字段作为显示名称
        String taskName = task.getTemplate().getName();

        // Determine name based on status
        if (task.isClaimed()) {
            String status = plugin.getConfigManager().getStatusMessage("completed");
            name = "<green>" + taskName + " " + status;
        } else if (task.isCompleted()) {
            String status = plugin.getConfigManager().getStatusMessage("claimable");
            name = "<yellow>" + taskName + " " + status;
        } else {
            String status = plugin.getConfigManager().getStatusMessage("not-started");
            name = "<gray>" + taskName + " " + status;
        }

        // Description - 不设置斜体
        lore.add(Component.empty());
        lore.add(MiniMessage.miniMessage().deserialize(task.getTemplate().getDescription())
            .decoration(TextDecoration.ITALIC, false));

        // Progress
        lore.add(Component.empty());
        Map<String, String> progressPlaceholders = new HashMap<>();
        progressPlaceholders.put("current", String.valueOf(task.getCurrentProgress()));
        progressPlaceholders.put("target", String.valueOf(task.getTargetProgress()));
        String progressText = plugin.getConfigManager().getGuiMessage("progress", progressPlaceholders);
        lore.add(MiniMessage.miniMessage().deserialize(progressText)
            .decoration(TextDecoration.ITALIC, false));

        // Progress bar
        String progressBar = task.getProgressBar(20);
        lore.add(MiniMessage.miniMessage().deserialize(progressBar)
            .decoration(TextDecoration.ITALIC, false));

        // Reward info
        lore.add(Component.empty());
        Reward reward = task.getTemplate().getReward();
        if (reward.hasMoney()) {
            Map<String, String> moneyPlaceholders = new HashMap<>();
            moneyPlaceholders.put("money", plugin.getConfigManager().formatCurrency(reward.getMoney()));
            String moneyText = plugin.getConfigManager().getGuiMessage("reward-money", moneyPlaceholders);
            lore.add(MiniMessage.miniMessage().deserialize(moneyText)
                .decoration(TextDecoration.ITALIC, false));
        }
        if (reward.hasItems()) {
            Map<String, String> itemsPlaceholders = new HashMap<>();
            itemsPlaceholders.put("count", String.valueOf(reward.getItems().size()));
            String itemsText = plugin.getConfigManager().getGuiMessage("reward-items", itemsPlaceholders);
            lore.add(MiniMessage.miniMessage().deserialize(itemsText)
                .decoration(TextDecoration.ITALIC, false));
        }

        // Detailed info (when detailedMode is enabled)
        if (detailedMode) {
            lore.add(Component.empty());
            lore.add(MiniMessage.miniMessage().deserialize("<dark_gray>--- 详细信息 ---")
                .decoration(TextDecoration.ITALIC, false));

            // Task targets
            List<String> targetItems = task.getTemplate().getTargetItems();
            TaskType taskType = task.getTemplate().getType();
            if (!targetItems.isEmpty()) {
                String targetLabel;
                if (taskType == TaskType.KILL || taskType == TaskType.BREED) {
                    targetLabel = "<gray>目标:";
                } else if (taskType == TaskType.CHAT) {
                    targetLabel = "<gray>关键词:";
                } else {
                    targetLabel = "<gray>目标物品:";
                }
                lore.add(MiniMessage.miniMessage().deserialize(targetLabel)
                    .decoration(TextDecoration.ITALIC, false));
                for (String targetItem : targetItems) {
                    String displayName;
                    if (taskType == TaskType.KILL || taskType == TaskType.BREED) {
                        // 实体类型使用实体翻译键
                        displayName = getEntityDisplayName(targetItem);
                    } else if (taskType == TaskType.CHAT) {
                        // CHAT类型直接显示关键词
                        displayName = "<yellow>" + targetItem;
                    } else {
                        // 其他类型使用物品显示名
                        ItemStack dummyItem = ItemUtil.createItem(plugin, targetItem, 1);
                        displayName = dummyItem != null ? ItemUtil.getDisplayName(dummyItem) : targetItem;
                    }
                    lore.add(MiniMessage.miniMessage().deserialize("  <dark_gray>• <white>" + displayName)
                        .decoration(TextDecoration.ITALIC, false));
                }
            }

            // Reward items detail
            if (reward.hasItems()) {
                lore.add(Component.empty());
                lore.add(MiniMessage.miniMessage().deserialize("<gray>奖励物品:")
                    .decoration(TextDecoration.ITALIC, false));
                for (Reward.RewardItem rewardItem : reward.getItems()) {
                    ItemStack dummyItem = ItemUtil.createItem(plugin, rewardItem.getItemKey(), 1);
                    String itemName = dummyItem != null ? ItemUtil.getDisplayName(dummyItem) : rewardItem.getItemKey();
                    lore.add(MiniMessage.miniMessage().deserialize("  <dark_gray>• <white>" + itemName + " <gray>x" + rewardItem.getAmount())
                        .decoration(TextDecoration.ITALIC, false));
                }
            }
        }

        // Status hint
        lore.add(Component.empty());
        if (task.isClaimed()) {
            String completedText = plugin.getConfigManager().getGuiMessage("status-completed");
            lore.add(MiniMessage.miniMessage().deserialize(completedText)
                .decoration(TextDecoration.ITALIC, false));
        } else if (task.isCompleted()) {
            String claimableText = plugin.getConfigManager().getGuiMessage("status-claimable");
            lore.add(MiniMessage.miniMessage().deserialize(claimableText)
                .decoration(TextDecoration.ITALIC, false));
        }

        // 使用配置的图标（支持CE物品）
        String iconKey = task.getTemplate().getIcon();
        ItemStack item = ItemUtil.createItem(plugin, iconKey, 1);
        if (item == null || item.getType().isAir()) {
            item = new ItemStack(Material.PAPER);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize(name)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }

        return item;
    }

    private void onTaskClick(Player player, PlayerTask task) {
        // SUBMIT 类型任务：扫描背包并提交物品
        if (task.getTemplate().getType() == TaskType.SUBMIT) {
            handleSubmitTask(player, task);
            return;
        }

        if (task.isCompleted() && !task.isClaimed()) {
            // 异步领取奖励，成功后刷新GUI
            plugin.getTaskManager().claimRewardAsync(player, task, success -> {
                if (success) {
                    player.closeInventory();
                    open(plugin, player);
                }
            });
        }
    }

    /**
     * 处理提交类型任务
     * 扫描背包，扣除物品，更新任务进度
     */
    private void handleSubmitTask(Player player, PlayerTask task) {
        if (task.isCompleted()) {
            // 已完成但未领取奖励
            if (!task.isClaimed()) {
                plugin.getTaskManager().claimRewardAsync(player, task, success -> {
                    if (success) {
                        player.closeInventory();
                        open(plugin, player);
                    }
                });
            }
            return;
        }

        // 获取需要提交的物品列表
        List<String> requiredItems = task.getTemplate().getTargetItems();
        if (requiredItems == null || requiredItems.isEmpty()) {
            player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<red>该任务没有配置需要提交的物品"));
            return;
        }

        // 检查背包中是否有足够物品
        Map<String, Integer> inventoryCounts = countItemsInInventory(player);
        int targetAmount = task.getTemplate().getTargetAmount();
        int currentProgress = task.getCurrentProgress();
        int remainingNeeded = targetAmount - currentProgress;

        // 计算本次可以提交的数量
        int canSubmit = calculateMaxSubmitAmount(requiredItems, inventoryCounts, remainingNeeded);

        if (canSubmit <= 0) {
            player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<red>背包中没有足够的物品提交"));
            return;
        }

        // 先在玩家实体调度器中扣除物品，防止玩家卡bug转移物品
        final int finalCanSubmit = canSubmit;
        player.getScheduler().execute(plugin, () -> {
            int actuallyRemoved = removeItemsFromInventory(player, requiredItems, finalCanSubmit);

            if (actuallyRemoved <= 0) {
                player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<red>扣除物品失败"));
                return;
            }

            // 扣除成功后更新数据库
            final int newProgress = Math.min(currentProgress + actuallyRemoved, targetAmount);
            final boolean nowCompleted = newProgress >= targetAmount;

            plugin.getDatabaseQueue().submit("submitTask", (Connection conn) -> {
                String updateSql = "UPDATE player_daily_tasks SET current_progress = ?, completed = ? WHERE player_uuid = ? AND task_key = ? AND task_date = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setInt(1, newProgress);
                    ps.setBoolean(2, nowCompleted);
                    ps.setString(3, player.getUniqueId().toString());
                    ps.setString(4, task.getTaskKey());
                    ps.setDate(5, java.sql.Date.valueOf(task.getTaskDate()));
                    ps.executeUpdate();
                }
                return null;
            }, result -> {
                // 更新本地缓存
                task.setCurrentProgress(newProgress);
                if (nowCompleted) {
                    task.setCompleted(true);
                    player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize("<green>任务完成！点击领取奖励"));
                } else {
                    player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize("<yellow>已提交物品，进度: " + newProgress + "/" + targetAmount));
                }
                // 刷新GUI
                initialize();
            }, e -> {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to update submit task progress", e);
                player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<red>提交失败，进度未保存"));
            });
        }, () -> {
            // 玩家离线时的取消回调
            plugin.getLogger().info("Player " + player.getName() + " went offline before submit task");
        }, 0L);
    }

    /**
     * 统计背包中物品数量（支持CE物品）
     */
    private Map<String, Integer> countItemsInInventory(Player player) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;
            String itemKey = ItemUtil.getItemKey(item);
            counts.merge(itemKey, item.getAmount(), Integer::sum);
        }
        return counts;
    }

    /**
     * 计算最大可提交数量
     * 对于多物品配置，计算所有匹配物品的总和
     */
    private int calculateMaxSubmitAmount(List<String> requiredItems, Map<String, Integer> inventoryCounts, int maxNeeded) {
        int totalAvailable = 0;
        for (String requiredItem : requiredItems) {
            totalAvailable += inventoryCounts.getOrDefault(requiredItem, 0);
        }
        return Math.min(maxNeeded, totalAvailable);
    }

    /**
     * 从背包中扣除物品（支持CE物品）
     * 返回实际扣除的数量
     */
    private int removeItemsFromInventory(Player player, List<String> requiredItems, int amount) {
        if (amount <= 0) return 0;

        int remainingToRemove = amount;
        // 遍历背包，扣除任意匹配的物品
        for (int i = 0; i < player.getInventory().getSize() && remainingToRemove > 0; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType().isAir()) continue;

            String itemKey = ItemUtil.getItemKey(item);
            // 检查是否匹配任一目标物品
            boolean matches = false;
            for (String requiredItem : requiredItems) {
                if (itemKey.equalsIgnoreCase(requiredItem)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) continue;

            int removeAmount = Math.min(item.getAmount(), remainingToRemove);
            if (removeAmount >= item.getAmount()) {
                player.getInventory().setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - removeAmount);
                player.getInventory().setItem(i, item);
            }
            remainingToRemove -= removeAmount;
        }
        return amount - remainingToRemove; // 返回实际扣除的数量
    }

    private ItemStack createDecoration(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize(name)
                .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 创建顶部信息/详细模式切换按钮
     * 放在 slot 4（第一行正中间）
     */
    private void setInfoButton() {
        // 计算任务统计
        long totalCount = tasks.size();
        long completedCount = tasks.stream().filter(PlayerTask::isCompleted).count();
        long claimedCount = tasks.stream().filter(PlayerTask::isClaimed).count();
        int progressPercent = totalCount > 0 ? (int) ((completedCount * 100) / totalCount) : 0;

        // 根据模式选择材质和标题
        Material material = detailedMode ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME;
        String title = detailedMode ? "<gold><bold>每日任务系统 (详细模式)" : "<gold><bold>每日任务系统";

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize(title)
                .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();

            // 任务进度信息
            lore.add(Component.empty());
            lore.add(MiniMessage.miniMessage().deserialize("<gray>今日任务进度:")
                .decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize("  <yellow>总任务: <white>" + totalCount)
                .decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize("  <green>已完成: <white>" + completedCount)
                .decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize("  <aqua>已领取: <white>" + claimedCount)
                .decoration(TextDecoration.ITALIC, false));

            // 进度条
            lore.add(Component.empty());
            String progressBar = buildProgressBar(progressPercent, 20);
            lore.add(MiniMessage.miniMessage().deserialize("<gray>总体进度: <yellow>" + progressPercent + "%")
                .decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize(progressBar)
                .decoration(TextDecoration.ITALIC, false));

            // 帮助信息
            lore.add(Component.empty());
            lore.add(MiniMessage.miniMessage().deserialize("<dark_gray>--- 操作帮助 ---")
                .decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize("  <dark_gray>• <white>左键点击任务: <gray>领取奖励/提交物品")
                .decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize("  <dark_gray>• <white>点击此按钮: <gray>切换详细模式")
                .decoration(TextDecoration.ITALIC, false));

            // 详细模式状态
            lore.add(Component.empty());
            String modeStatus = detailedMode ? "<green>已开启" : "<gray>已关闭";
            lore.add(MiniMessage.miniMessage().deserialize("<yellow>详细模式: " + modeStatus)
                .decoration(TextDecoration.ITALIC, false));
            if (detailedMode) {
                lore.add(MiniMessage.miniMessage().deserialize("<dark_gray>显示: 目标物品/实体, 奖励详情")
                    .decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(MiniMessage.miniMessage().deserialize("<dark_gray>点击显示更多任务信息")
                    .decoration(TextDecoration.ITALIC, false));
            }

            meta.lore(lore);
            item.setItemMeta(meta);
        }

        setItem(4, item, (p, e) -> {
            detailedMode = !detailedMode;
            initialize(); // 重新渲染GUI
        });
    }

    /**
     * 构建进度条字符串
     */
    private String buildProgressBar(int percent, int length) {
        int filled = (int) Math.round(percent / 100.0 * length);
        StringBuilder bar = new StringBuilder();
        bar.append("<yellow>");
        for (int i = 0; i < length; i++) {
            if (i < filled) {
                bar.append("■");
            } else {
                bar.append("□");
            }
        }
        bar.append("<reset>");
        return bar.toString();
    }

    /**
     * 填充边框，但保留顶部中心 slot 4
     */
    private void fillBorderExceptTopCenter(ItemStack item) {
        int[] borderSlots = {
            // 第一行 (0-8)，但跳过 4
            0, 1, 2, 3, /* skip 4, */ 5, 6, 7, 8,
            // 中间行的左右边框
            9, 17, 18, 26, 27, 35, 36, 44,
            // 最后一行 (45-53)
            45, 46, 47, 48, 49, 50, 51, 52, 53
        };
        for (int slot : borderSlots) {
            setItem(slot, item);
        }
    }

    /**
     * 获取实体的显示名称（使用实体翻译键）
     * @param entityKey 实体key (格式: minecraft:zombie)
     * @return MiniMessage lang标签格式
     */
    private String getEntityDisplayName(String entityKey) {
        if (entityKey == null || entityKey.isEmpty()) {
            return "<lang:entity.minecraft.pig>";
        }
        // 提取实体ID（移除 minecraft: 前缀）
        String entityId = entityKey;
        if (entityKey.contains(":")) {
            entityId = entityKey.substring(entityKey.indexOf(":") + 1);
        }
        return "<lang:entity.minecraft." + entityId.toLowerCase() + ">";
    }
}
