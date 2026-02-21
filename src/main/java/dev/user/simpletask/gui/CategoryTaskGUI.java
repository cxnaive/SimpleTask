package dev.user.simpletask.gui;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.PlayerTask;
import dev.user.simpletask.task.Reward;
import dev.user.simpletask.task.TaskTemplate;
import dev.user.simpletask.task.TaskType;
import dev.user.simpletask.task.category.TaskCategory;
import dev.user.simpletask.util.ExpireUtil;
import dev.user.simpletask.util.GUIComponentBuilder;
import dev.user.simpletask.util.ItemUtil;
import dev.user.simpletask.util.MessageUtil;
import dev.user.simpletask.util.TimeUtil;
import dev.user.simpletask.util.TimeZoneConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.time.Instant;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 分类任务列表界面 - 显示特定分类的任务
 */
public class CategoryTaskGUI extends AbstractGUI {

    private final TaskCategory category;
    private List<PlayerTask> tasks;
    private boolean detailedMode = false;
    private final int usedRerollCount; // 已使用的 reroll 次数

    // 分页相关
    private int currentPage = 0;
    private static final int TASKS_PER_PAGE = 21; // 3行 * 7列
    private static final int[] TASK_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34
    };

    public CategoryTaskGUI(SimpleTaskPlugin plugin, Player player, TaskCategory category, List<PlayerTask> tasks, int usedRerollCount) {
        super(plugin, player, category.getDisplayName(), 54);
        this.category = category;
        this.tasks = tasks;
        this.usedRerollCount = usedRerollCount;
    }

    /**
     * 异步打开分类任务GUI（从分类列表点击进入，需要重新加载）
     * 加载任务时检测过期并自动补充
     */
    public static void open(SimpleTaskPlugin plugin, Player player, TaskCategory category) {
        open(plugin, player, category, null, -1);
    }

    /**
     * 打开分类任务GUI（带预加载数据，从TaskCategoryGUI点击进入）
     * 如果 tasks 为 null，会重新从数据库加载
     *
     * @param plugin 插件实例
     * @param player 玩家
     * @param category 任务分类
     * @param preloadedTasks 预加载的任务数据（可为null）
     * @param preloadedRerollCount 预加载的刷新次数（为-1时会重新加载）
     */
    public static void open(SimpleTaskPlugin plugin, Player player, TaskCategory category,
                            List<PlayerTask> preloadedTasks, int preloadedRerollCount) {
        // 如果有预加载数据，直接打开GUI
        if (preloadedTasks != null) {
            int finalRerollCount = preloadedRerollCount >= 0 ? preloadedRerollCount : 0;
            player.getScheduler().execute(plugin, () -> {
                CategoryTaskGUI gui = new CategoryTaskGUI(plugin, player, category, preloadedTasks, finalRerollCount);
                gui.open();
            }, () -> {}, 0L);
            return;
        }

        // 没有预加载数据，需要异步加载
        MessageUtil.send(plugin, player, "<gray>正在加载任务数据...");

        // 使用 TaskManager 统一检查并刷新该分类的任务
        plugin.getTaskManager().checkAndRefreshCategoryTasks(player, category.getId(), (tasks, usedRerolls) -> {
            // 在玩家调度器打开GUI
            player.getScheduler().execute(plugin, () -> {
                CategoryTaskGUI gui = new CategoryTaskGUI(plugin, player, category, tasks, usedRerolls);
                gui.open();
            }, () -> {}, 0L);
        }, e -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to load tasks for category: " + category.getId(), e);
            MessageUtil.send(plugin, player, "<red>加载任务数据失败，请重试");
        });
    }

    @Override
    public void initialize() {
        inventory.clear();
        actions.clear();

        // 顶部信息按钮 (slot 4)
        setInfoButton();

        // 填充边框
        ItemStack borderItem = ItemUtil.createDecoration(
            plugin.getConfigManager().getGuiDecoration("border"),
            " "
        );
        fillBorderExceptTopCenter(borderItem);

        // 显示任务
        displayTasks();

        // 底部按钮
        setBottomButtons();
    }

    private void displayTasks() {
        int totalTasks = tasks.size();
        int totalPages = (int) Math.ceil((double) totalTasks / TASKS_PER_PAGE);

        if (totalPages == 0) {
            // 显示空状态
            setItem(22, ItemUtil.createDecoration("minecraft:barrier", "<gray>暂无任务"));
            return;
        }

        // 确保当前页在有效范围内
        currentPage = Math.max(0, Math.min(currentPage, totalPages - 1));

        int startIndex = currentPage * TASKS_PER_PAGE;
        int endIndex = Math.min(startIndex + TASKS_PER_PAGE, totalTasks);

        // 显示当前页的任务
        for (int i = startIndex; i < endIndex; i++) {
            PlayerTask task = tasks.get(i);
            int slotIndex = i - startIndex;
            int slot = TASK_SLOTS[slotIndex];

            ItemStack taskItem = createTaskItem(task);
            setItem(slot, taskItem, (p, e) -> onTaskClick(p, task));
        }

        // 显示翻页按钮
        setupPagination(totalPages);
    }

    private void setupPagination(int totalPages) {
        // 上一页按钮 (slot 45)
        if (currentPage > 0) {
            setItem(45, createNavigationItem(Material.ARROW, "<yellow>上一页"), (p, e) -> {
                currentPage--;
                refreshPage();
            });
        } else {
            setItem(45, ItemUtil.createDecoration("minecraft:black_stained_glass_pane", " "));
        }

        // 页码显示 (slot 46)
        setItem(46, createPageInfoItem(currentPage + 1, totalPages));

        // 下一页按钮 (slot 47)
        if (currentPage < totalPages - 1) {
            setItem(47, createNavigationItem(Material.ARROW, "<yellow>下一页"), (p, e) -> {
                currentPage++;
                refreshPage();
            });
        } else {
            setItem(47, ItemUtil.createDecoration("minecraft:black_stained_glass_pane", " "));
        }
    }

    private ItemStack createNavigationItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtil.guiName(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createPageInfoItem(int currentPage, int totalPages) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtil.guiName("<gray>页码: <yellow>{current} <gray>/ <yellow>{total}",
                MessageUtil.textPlaceholders("current", String.valueOf(currentPage), "total", String.valueOf(totalPages))));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void refreshPage() {
        inventory.clear();
        actions.clear();

        // 重新绘制
        setInfoButton();
        ItemStack borderItem = ItemUtil.createDecoration(
            plugin.getConfigManager().getGuiDecoration("border"),
            " "
        );
        fillBorderExceptTopCenter(borderItem);

        displayTasks();
        setBottomButtons();
    }

    private ItemStack createTaskItem(PlayerTask task) {
        // 解析任务名称和描述
        Component taskNameComponent = MessageUtil.parse(task.getTemplate().getName());
        Component taskDescriptionComponent = MessageUtil.parse(task.getTemplate().getDescription());

        // 构建任务名称（根据状态添加颜色前缀和状态标签）
        Component name = buildTaskName(task, taskNameComponent);

        // 使用GUIComponentBuilder构建lore
        GUIComponentBuilder loreBuilder = new GUIComponentBuilder()
            .empty()
            .add(taskDescriptionComponent)
            .empty();

        // 进度信息
        loreBuilder.add(plugin.getConfigManager().getGuiMessage("progress"),
            MessageUtil.textPlaceholders(
                "current", String.valueOf(task.getCurrentProgress()),
                "target", String.valueOf(task.getTargetProgress())));
        loreBuilder.add(task.getProgressBar(20));

        // 奖励信息
        loreBuilder.empty();
        Reward reward = task.getTemplate().getReward();
        if (reward.hasMoney()) {
            loreBuilder.add(plugin.getConfigManager().getGuiMessage("reward-money"),
                MessageUtil.textPlaceholders(
                    "money", plugin.getConfigManager().formatCurrency(reward.getMoney())));
        }
        if (reward.hasItems()) {
            loreBuilder.add(plugin.getConfigManager().getGuiMessage("reward-items"),
                MessageUtil.textPlaceholders("count", String.valueOf(reward.getItems().size())));
        }

        // 详细模式
        if (detailedMode) {
            addDetailedLore(loreBuilder, task, reward);
        }

        // 对于limited分类，显示过期时间
        if ("limited".equals(category.getId())) {
            loreBuilder.empty();
            java.time.Instant expireTime = task.getExpireTime(category);
            if (expireTime != null) {
                java.time.Instant now = TimeZoneConfig.toInstant(TimeZoneConfig.now());
                if (expireTime.isBefore(now)) {
                    loreBuilder.add("<red>⚠ 已过期");
                } else {
                    java.time.Duration remaining = java.time.Duration.between(now, expireTime);
                    String timeLeft = TimeUtil.formatDuration(remaining);
                    loreBuilder.add("<yellow>⏳ 剩余时间: " + timeLeft);
                }
            }
        }

        // 操作提示
        loreBuilder.empty();
        if (task.isClaimed()) {
            loreBuilder.add(plugin.getConfigManager().getGuiMessage("status-completed"));
        } else if (task.isCompleted()) {
            loreBuilder.add(plugin.getConfigManager().getGuiMessage("status-claimable"));
        }

        // 创建物品
        String iconKey = task.getTemplate().getIcon();
        ItemStack item = ItemUtil.createItem(plugin, iconKey, 1);
        if (item == null || item.getType().isAir()) {
            item = new ItemStack(Material.PAPER);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(loreBuilder.build());
            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * 构建任务名称Component
     */
    private Component buildTaskName(PlayerTask task, Component taskNameComponent) {
        // 判断任务状态
        String statusKey;
        if (task.isClaimed()) {
            statusKey = "completed";
        } else if (task.isCompleted()) {
            statusKey = "claimable";
        } else if (task.getCurrentProgress() > 0) {
            statusKey = "in-progress";
        } else {
            statusKey = "not-started";
        }
        Component statusComponent = MessageUtil.parseStatus(plugin, statusKey);

        // 根据状态设置颜色
        NamedTextColor nameColor = task.isClaimed() ? NamedTextColor.GREEN
            : (task.isCompleted() || task.getCurrentProgress() > 0 ? NamedTextColor.YELLOW : NamedTextColor.GRAY);

        return Component.empty().color(nameColor)
            .append(taskNameComponent)
            .append(Component.text(" "))
            .append(statusComponent);
    }

    /**
     * 添加详细模式下的lore
     */
    private void addDetailedLore(GUIComponentBuilder builder, PlayerTask task, Reward reward) {
        builder.empty().add("<dark_gray>--- 详细信息 ---");

        TaskType taskType = task.getTemplate().getType();
        List<String> targetItems = task.getTemplate().getTargetItems();

        if (!targetItems.isEmpty()) {
            // 目标标签
            String targetLabel = switch (taskType) {
                case KILL, BREED -> "<gray>目标:";
                case CHAT -> "<gray>关键词:";
                default -> "<gray>目标物品:";
            };
            builder.add(targetLabel);

            // 目标列表
            for (String targetItem : targetItems) {
                Component itemComponent = buildTargetItemComponent(taskType, targetItem);
                builder.add(Component.text("  ").append(Component.text("• ").color(NamedTextColor.DARK_GRAY)).append(itemComponent));
            }
        }

        // 奖励物品详情
        if (reward.hasItems()) {
            builder.empty().add("<gray>奖励物品:");
            for (Reward.RewardItem rewardItem : reward.getItems()) {
                Component itemName = buildRewardItemComponent(rewardItem);
                builder.add(Component.text("  ").append(Component.text("• ").color(NamedTextColor.DARK_GRAY)).append(itemName));
            }
        }
    }

    /**
     * 构建目标物品/实体Component
     */
    private Component buildTargetItemComponent(TaskType taskType, String targetItem) {
        return switch (taskType) {
            case KILL, BREED -> {
                String entityId = targetItem.contains(":") ?
                    targetItem.substring(targetItem.indexOf(":") + 1) : targetItem;
                yield MessageUtil.parse("<white><lang:entity.minecraft." + entityId.toLowerCase() + ">");
            }
            case CHAT -> MessageUtil.parse("<yellow>" + targetItem);
            default -> {
                ItemStack dummyItem = ItemUtil.createItem(plugin, targetItem, 1);
                yield dummyItem != null
                    ? ItemUtil.getDisplayNameComponent(dummyItem)
                    : Component.text(targetItem);
            }
        };
    }

    /**
     * 构建奖励物品Component（名称 + 数量）
     */
    private Component buildRewardItemComponent(Reward.RewardItem rewardItem) {
        ItemStack dummyItem = ItemUtil.createItem(plugin, rewardItem.getItemKey(), 1);
        Component itemName = dummyItem != null
            ? ItemUtil.getDisplayNameComponent(dummyItem)
            : Component.text(rewardItem.getItemKey());
        Component amount = MessageUtil.parse("<gray> x" + rewardItem.getAmount());
        return itemName.append(amount);
    }

    private void onTaskClick(Player player, PlayerTask task) {
        if (task.getTemplate().getType() == TaskType.SUBMIT) {
            handleSubmitTask(player, task);
            return;
        }

        if (task.isCompleted() && !task.isClaimed()) {
            plugin.getTaskManager().claimRewardAsync(player, task, success -> {
                // 检查玩家是否仍然在线
                if (!player.isOnline()) {
                    return;
                }
                if (success) {
                    refreshTasks();
                }
            });
        }
    }

    private void handleSubmitTask(Player player, PlayerTask task) {
        if (task.isCompleted()) {
            if (!task.isClaimed()) {
                plugin.getTaskManager().claimRewardAsync(player, task, success -> {
                    if (success) {
                        refreshTasks();
                    }
                });
            }
            return;
        }

        List<String> requiredItems = task.getTemplate().getTargetItems();
        if (requiredItems == null || requiredItems.isEmpty()) {
            MessageUtil.send(plugin, player, "<red>该任务没有配置需要提交的物品");
            return;
        }

        List<String> nbtConditions = task.getTemplate().getNbtMatchConditions();
        Map<String, Integer> inventoryCounts = countItemsInInventory(player, requiredItems, nbtConditions);
        int targetAmount = task.getTemplate().getTargetAmount();
        int currentProgress = task.getCurrentProgress();
        int remainingNeeded = targetAmount - currentProgress;

        int canSubmit = calculateMaxSubmitAmount(requiredItems, inventoryCounts, remainingNeeded);

        if (canSubmit <= 0) {
            MessageUtil.send(plugin, player, "<red>背包中没有足够的物品提交");
            return;
        }

        final int finalCanSubmit = canSubmit;
        final List<String> finalNbtConditions = nbtConditions;
        player.getScheduler().execute(plugin, () -> {
            int actuallyRemoved = removeItemsFromInventory(player, requiredItems, finalCanSubmit, finalNbtConditions);

            if (actuallyRemoved <= 0) {
                MessageUtil.send(plugin, player, "<red>扣除物品失败");
                return;
            }

            final int newProgress = Math.min(currentProgress + actuallyRemoved, targetAmount);
            final boolean nowCompleted = newProgress >= targetAmount;

            // 使用 TaskProgressManager 提交任务进度
            plugin.getTaskManager().getProgressManager().submitTaskProgress(player, task, newProgress, (success, completed) -> {
                if (success) {
                    if (completed) {
                        MessageUtil.send(plugin, player, "<green>任务完成！点击领取奖励");
                    } else {
                        MessageUtil.send(plugin, player, "<yellow>已提交物品，进度: {current}/{target}",
                            MessageUtil.textPlaceholders("current", String.valueOf(newProgress), "target", String.valueOf(targetAmount)));
                    }
                    initialize();
                } else {
                    MessageUtil.send(plugin, player, "<red>提交失败，进度未保存");
                }
            });
        }, () -> {}, 0L);
    }

    private Map<String, Integer> countItemsInInventory(Player player, List<String> requiredItems, List<String> nbtConditions) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;
            String itemKey = ItemUtil.getItemKey(item);

            boolean matchesKey = false;
            for (String requiredItem : requiredItems) {
                if (itemKey.equalsIgnoreCase(requiredItem)) {
                    matchesKey = true;
                    break;
                }
            }
            if (!matchesKey) continue;

            if (nbtConditions != null && !nbtConditions.isEmpty()) {
                boolean matchesNbt = ItemUtil.matchesTarget(item, itemKey, nbtConditions);
                if (!matchesNbt) continue;
            }

            counts.merge(itemKey, item.getAmount(), Integer::sum);
        }
        return counts;
    }

    private int calculateMaxSubmitAmount(List<String> requiredItems, Map<String, Integer> inventoryCounts, int maxNeeded) {
        int totalAvailable = 0;
        for (String requiredItem : requiredItems) {
            totalAvailable += inventoryCounts.getOrDefault(requiredItem, 0);
        }
        return Math.min(maxNeeded, totalAvailable);
    }

    private int removeItemsFromInventory(Player player, List<String> requiredItems, int amount, List<String> nbtConditions) {
        if (amount <= 0) return 0;

        int remainingToRemove = amount;
        for (int i = 0; i < player.getInventory().getSize() && remainingToRemove > 0; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType().isAir()) continue;

            String itemKey = ItemUtil.getItemKey(item);
            boolean matchesKey = false;
            for (String requiredItem : requiredItems) {
                if (itemKey.equalsIgnoreCase(requiredItem)) {
                    matchesKey = true;
                    break;
                }
            }
            if (!matchesKey) continue;

            if (nbtConditions != null && !nbtConditions.isEmpty()) {
                boolean matchesNbt = ItemUtil.matchesTarget(item, itemKey, nbtConditions);
                if (!matchesNbt) continue;
            }

            int removeAmount = Math.min(item.getAmount(), remainingToRemove);
            if (removeAmount >= item.getAmount()) {
                player.getInventory().setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - removeAmount);
                player.getInventory().setItem(i, item);
            }
            remainingToRemove -= removeAmount;
        }
        return amount - remainingToRemove;
    }

    private void setInfoButton() {
        long totalCount = tasks.size();
        long completedCount = tasks.stream().filter(PlayerTask::isCompleted).count();
        long claimedCount = tasks.stream().filter(PlayerTask::isClaimed).count();
        int progressPercent = totalCount > 0 ? (int) ((completedCount * 100) / totalCount) : 0;

        Material material = detailedMode ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME;
        String title = detailedMode ?
            "<gold><bold>" + category.getDisplayName() + " (详细模式)" :
            "<gold><bold>" + category.getDisplayName();

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtil.guiName(title));

            String modeStatus = detailedMode ? "<green>已开启" : "<gray>已关闭";
            GUIComponentBuilder loreBuilder = new GUIComponentBuilder()
                .empty()
                .add("<gray>任务进度:")
                .add("  <yellow>总任务: <white>{total}",
                    MessageUtil.textPlaceholders("total", String.valueOf(totalCount)))
                .add("  <green>已完成: <white>{completed}",
                    MessageUtil.textPlaceholders("completed", String.valueOf(completedCount)))
                .add("  <aqua>已领取: <white>{claimed}",
                    MessageUtil.textPlaceholders("claimed", String.valueOf(claimedCount)))
                .empty()
                .add("<gray>进度: <yellow>{percent}%",
                    MessageUtil.textPlaceholders("percent", String.valueOf(progressPercent)))
                .add(buildProgressBar(progressPercent, 20))
                .empty()
                .add("<dark_gray>--- 操作帮助 ---")
                .add("  <dark_gray>• <white>左键点击任务: <gray>领取奖励/提交物品")
                .add("  <dark_gray>• <white>点击此按钮: <gray>切换详细模式")
                .empty()
                .add("<yellow>详细模式: " + modeStatus);

            meta.lore(loreBuilder.build());
            item.setItemMeta(meta);
        }

        setItem(4, item, (p, e) -> {
            detailedMode = !detailedMode;
            initialize();
        });
    }

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

    private void setBottomButtons() {
        // 分页按钮已在 setupPagination() 中设置 (slot 45, 46, 47)

        // 返回按钮 (slot 48)
        setItem(48, ItemUtil.createDecoration("minecraft:arrow", "<yellow>返回分类"), (p, e) -> {
            TaskCategoryGUI.open(plugin, p);
        });

        // 关闭按钮 (slot 49)
        setItem(49, ItemUtil.createDecoration("minecraft:barrier", "<red>关闭"), (p, e) -> p.closeInventory());

        // 刷新/Reroll 按钮 (slot 50) - 基于类别配置
        long completedCount = tasks.stream().filter(PlayerTask::isClaimed).count();
        long totalCount = tasks.size();
        setRerollButton(completedCount, totalCount);

        // slot 51 留空（刷新功能已移除，reroll功能在slot 50）
    }

    private void setRerollButton(long completedCount, long totalCount) {
        // 获取类别 reroll 配置
        boolean rerollEnabled = category.isRerollEnabled();
        int maxRerolls = category.getRerollMaxCount();
        double rerollCost = category.getRerollCost();

        // 计算剩余次数
        int remainingRerolls = Math.max(0, maxRerolls - usedRerollCount);

        // 计算未完成任务数
        long uncompletedCount = tasks.stream().filter(t -> !t.isCompleted()).count();

        // 构建reroll按钮
        ItemStack rerollItem = buildRerollItem(rerollEnabled, maxRerolls, rerollCost,
            remainingRerolls, uncompletedCount, completedCount, totalCount);

        final int finalRemainingRerolls = remainingRerolls;
        setItem(50, rerollItem, (p, e) -> {
            if (!rerollEnabled || maxRerolls <= 0) {
                MessageUtil.send(plugin, p, category.getDisplayName() + " <red>刷新功能已禁用");
                return;
            }
            if (finalRemainingRerolls <= 0) {
                MessageUtil.send(plugin, p, "<red>当前周期刷新次数已用完");
                return;
            }
            // 检查是否所有任务都已完成
            if (uncompletedCount == 0 && totalCount > 0) {
                MessageUtil.send(plugin, p, "<red>当前所有任务都已完成，无需刷新");
                return;
            }

            p.closeInventory();
            plugin.getTaskManager().playerRerollCategoryTasks(p, category.getId(), (success, message) -> {
                p.sendMessage(message);
                if (success) {
                    // 刷新后重新打开GUI
                    CategoryTaskGUI.open(plugin, p, category);
                }
            });
        });
    }

    /**
     * 构建reroll按钮物品
     */
    private ItemStack buildRerollItem(boolean rerollEnabled, int maxRerolls, double rerollCost,
                                       int remainingRerolls, long uncompletedCount,
                                       long completedCount, long totalCount) {
        String rerollName;
        List<String> rerollLore = new ArrayList<>();

        if (!rerollEnabled || maxRerolls <= 0) {
            rerollName = "<gray>刷新任务 (已禁用)";
            rerollLore.add("<red>该类任务刷新功能已禁用");
        } else if (remainingRerolls <= 0) {
            rerollName = "<gray>刷新任务 (次数已用完)";
            rerollLore.add("<red>当前周期刷新次数已用完");
            rerollLore.add("<gray>上限: " + maxRerolls + " 次/周期");
            // 显示下次重置时间
            String nextReset = calculateNextRerollReset();
            if (nextReset != null) {
                rerollLore.add("<gray>下次重置: <yellow>" + nextReset);
            }
        } else {
            rerollName = "<yellow>刷新任务 <green>(" + remainingRerolls + "/" + maxRerolls + ")";
            rerollLore.add("<gray>当前周期剩余: " + remainingRerolls + " 次");
            rerollLore.add("<gray>上限: " + maxRerolls + " 次/周期");
            if (rerollCost > 0) {
                rerollLore.add("<gold>花费: " + rerollCost + " 金币");
            } else {
                rerollLore.add("<green>免费");
            }
            rerollLore.add("");
            // 显示刷新详情
            if (uncompletedCount > 0) {
                rerollLore.add("<gray>当前: <green>" + completedCount + "<gray>/<yellow>" + totalCount + " <gray>已完成");
                rerollLore.add("<gray>将刷新: <yellow>" + uncompletedCount + " <gray>个未完成任务");
                rerollLore.add("<gray>保留: <green>" + completedCount + " <gray>个已完成任务");
            } else if (totalCount > 0) {
                rerollLore.add("<green>当前所有任务已完成");
            }
            rerollLore.add("");
            rerollLore.add("<yellow>点击刷新 " + category.getDisplayName() + " 任务");
        }

        ItemStack rerollItem = ItemUtil.createDecoration("minecraft:clock", rerollName);
        if (!rerollLore.isEmpty()) {
            ItemMeta meta = rerollItem.getItemMeta();
            if (meta != null) {
                meta.lore(MessageUtil.guiLore(rerollLore));
                rerollItem.setItemMeta(meta);
            }
        }

        return rerollItem;
    }

    private String calculateNextRerollReset() {
        // 刷新次数重置使用独立的 rerollResetConfig 配置
        var policy = category.getRerollResetPolicy();
        Instant now = TimeZoneConfig.toInstant(TimeZoneConfig.now());

        return switch (policy) {
            case DAILY -> {
                Instant next = ExpireUtil.getNextDailyReset(category.getRerollResetTime());
                yield formatTimeUntil(now, next);
            }
            case WEEKLY -> {
                DayOfWeek dayOfWeek = category.getRerollResetDayOfWeek();
                if (dayOfWeek == null) dayOfWeek = DayOfWeek.MONDAY;
                Instant next = ExpireUtil.getNextWeeklyReset(dayOfWeek, category.getRerollResetTime());
                yield formatTimeUntil(now, next);
            }
            case MONTHLY -> {
                int dayOfMonth = category.getRerollResetDayOfMonth();
                if (dayOfMonth <= 0) dayOfMonth = 1;
                Instant next = ExpireUtil.getNextMonthlyReset(dayOfMonth, category.getRerollResetTime());
                yield formatTimeUntil(now, next);
            }
            case RELATIVE, FIXED, PERMANENT -> null;
        };
    }

    private String formatTimeUntil(Instant now, Instant future) {
        long hours = Duration.between(now, future).toHours();
        if (hours < 1) {
            long minutes = Duration.between(now, future).toMinutes();
            return minutes + " 分钟后";
        } else if (hours < 24) {
            return hours + " 小时后";
        } else {
            long days = hours / 24;
            long remainingHours = hours % 24;
            if (remainingHours == 0) {
                return days + " 天后";
            } else {
                return days + " 天 " + remainingHours + " 小时后";
            }
        }
    }

    private void refreshTasks() {
        MessageUtil.send(plugin, player, "<gray>正在刷新...");
        open(plugin, player, category);
    }

    private void fillBorderExceptTopCenter(ItemStack item) {
        int[] borderSlots = {
            0, 1, 2, 3, /* skip 4, */ 5, 6, 7, 8,
            9, 17, 18, 26, 27, 35, 36, 44,
            45, 46, 47, 48, 49, 50, 51, 52, 53
        };
        for (int slot : borderSlots) {
            setItem(slot, item);
        }
    }
}
