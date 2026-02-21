package dev.user.simpletask.gui;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.ExpirePolicy;
import dev.user.simpletask.task.PlayerTask;
import dev.user.simpletask.task.category.TaskCategory;
import dev.user.simpletask.util.ExpireUtil;
import dev.user.simpletask.util.ItemUtil;
import dev.user.simpletask.util.MessageUtil;
import dev.user.simpletask.util.TimeZoneConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务分类主界面 - 对称美观布局
 */
public class TaskCategoryGUI extends AbstractGUI {

    private final Map<String, List<PlayerTask>> playerTasksByCategory;

    // 对称布局槽位（中间3行，每行最多7个）
    private static final int[] CATEGORY_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34
    };

    private TaskCategoryGUI(SimpleTaskPlugin plugin, Player player,
            Map<String, List<PlayerTask>> playerTasksByCategory) {
        super(plugin, player, plugin.getConfigManager().getGuiTitleTaskCategories(), 54);
        this.playerTasksByCategory = playerTasksByCategory;
    }

    public static void open(SimpleTaskPlugin plugin, Player player) {
        MessageUtil.send(plugin, player, "<gray>正在检测并刷新任务数据...");

        plugin.getServer().getAsyncScheduler().runNow(plugin, (task) -> {
            Map<String, TaskCategory> categories = plugin.getConfigManager().getTaskCategories();
            List<TaskCategory> enabledCategories = categories.values().stream()
                .filter(TaskCategory::isEnabled)
                .toList();

            if (enabledCategories.isEmpty()) {
                player.getScheduler().execute(plugin, () -> {
                    TaskCategoryGUI gui = new TaskCategoryGUI(plugin, player, new HashMap<>());
                    gui.open();
                }, () -> {}, 0L);
                return;
            }

            // 并发刷新所有分类
            Map<String, List<PlayerTask>> resultTasks = new ConcurrentHashMap<>();
            AtomicInteger completedCount = new AtomicInteger(0);
            int totalCategories = enabledCategories.size();

            for (TaskCategory category : enabledCategories) {
                plugin.getTaskManager().checkAndRefreshCategoryTasks(player, category.getId(),
                    (tasks, usedRerolls) -> {
                        resultTasks.put(category.getId(), tasks);

                        // 检查是否所有分类都完成了
                        if (completedCount.incrementAndGet() == totalCategories) {
                            player.getScheduler().execute(plugin, () -> {
                                TaskCategoryGUI gui = new TaskCategoryGUI(plugin, player, resultTasks);
                                gui.open();
                            }, () -> {}, 0L);
                        }
                    },
                    e -> {
                        plugin.getLogger().warning("Failed to refresh category " + category.getId() + ": " + e.getMessage());
                        // 即使失败也继续，使用空列表
                        resultTasks.put(category.getId(), new ArrayList<>());

                        if (completedCount.incrementAndGet() == totalCategories) {
                            player.getScheduler().execute(plugin, () -> {
                                TaskCategoryGUI gui = new TaskCategoryGUI(plugin, player, resultTasks);
                                gui.open();
                            }, () -> {}, 0L);
                        }
                    }
                );
            }
        });
    }

    @Override
    public void initialize() {
        inventory.clear();
        actions.clear();

        // 填充装饰边框（统一使用青色玻璃）
        fillDecorativeBorder();

        // 获取启用的分类
        Map<String, TaskCategory> categories = plugin.getConfigManager().getTaskCategories();
        List<TaskCategory> enabledCategories = categories.values().stream()
            .filter(TaskCategory::isEnabled)
            .sorted((a, b) -> Integer.compare(a.getSlot(), b.getSlot()))
            .toList();

        // 居中显示分类
        int startIndex = calculateStartIndex(enabledCategories.size());
        for (int i = 0; i < enabledCategories.size() && i < CATEGORY_SLOTS.length; i++) {
            TaskCategory category = enabledCategories.get(i);
            int slot = CATEGORY_SLOTS[startIndex + i];

            List<PlayerTask> categoryTasks = playerTasksByCategory.getOrDefault(
                category.getId(), new ArrayList<>());

            // 计算该分类的已使用刷新次数
            int usedRerolls = calculateUsedRerolls(categoryTasks, category);

            ItemStack categoryItem = createCategoryItem(category, categoryTasks);

            // 传递预加载的数据，避免重复加载
            final TaskCategory finalCategory = category;
            final List<PlayerTask> finalCategoryTasks = categoryTasks;
            final int finalUsedRerolls = usedRerolls;

            setItem(slot, categoryItem, (p, e) -> {
                CategoryTaskGUI.open(plugin, p, finalCategory, finalCategoryTasks, finalUsedRerolls);
            });
        }

        // 底部对称按钮
        setBottomButtons();
    }

    /**
     * 根据分类数量计算起始索引，实现居中
     */
    private int calculateStartIndex(int categoryCount) {
        if (categoryCount >= 14) return 0; // 填满3行
        if (categoryCount <= 7) {
            // 1行：居中在第2行
            return 7 + (7 - categoryCount) / 2;
        }
        // 2行：从第2行开始
        int remainder = categoryCount - 7;
        return (7 - remainder) / 2;
    }

    /**
     * 填充装饰边框（统一使用青色玻璃）
     */
    private void fillDecorativeBorder() {
        // 统一使用青色玻璃作为边框
        ItemStack border = ItemUtil.createDecoration("minecraft:cyan_stained_glass_pane", " ");
        fillBorder(border);

        // 内边框也使用相同的青色玻璃
        int[] innerBorderSlots = {1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44};
        for (int slot : innerBorderSlots) {
            if (slot >= 0 && slot < 54) {
                setItem(slot, border);
            }
        }
    }

    private ItemStack createCategoryItem(TaskCategory category, List<PlayerTask> tasks) {
        int total = tasks.size();
        long completed = tasks.stream().filter(PlayerTask::isCompleted).count();
        long claimed = tasks.stream().filter(PlayerTask::isClaimed).count();
        int progressPercent = total > 0 ? (int) ((completed * 100) / total) : 0;

        // 根据完成度选择材料颜色
        Material material = getMaterialByProgress(category, progressPercent);

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // 名称带装饰
            String name = category.getDisplayName();
            if (!name.startsWith("<")) {
                name = "<gold>「 " + name + " <gold>」";
            }
            meta.displayName(MessageUtil.parse(name)
                .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();

            // 描述（如果有）
            if (!category.getLore().isEmpty()) {
                for (String line : category.getLore().subList(0, Math.min(2, category.getLore().size()))) {
                    lore.add(MessageUtil.parse("  <dark_gray>" + line)
                        .decoration(TextDecoration.ITALIC, false));
                }
            }

            // 简洁进度条（10字符）
            lore.add(Component.empty());
            lore.add(MessageUtil.parse(buildCompactProgressBar(progressPercent))
                .decoration(TextDecoration.ITALIC, false));

            // 统计数字
            String stats = String.format("  <gray>进度 <green>%d<gray>/<yellow>%d <gray>| <aqua>已领 <green>%d",
                completed, total, claimed);
            lore.add(MessageUtil.parse(stats)
                .decoration(TextDecoration.ITALIC, false));

            // 刷新信息 + 剩余时间
            lore.add(Component.empty());
            String policyIcon = getPolicyIcon(category.getExpirePolicy());
            String timeUntilReset = calculateTimeUntilReset(category);
            lore.add(MessageUtil.parse("  " + policyIcon + " <gray>" + getPolicyDisplay(category.getExpirePolicy()))
                .decoration(TextDecoration.ITALIC, false));
            lore.add(MessageUtil.parse("  <gray>剩余: <yellow>" + timeUntilReset)
                .decoration(TextDecoration.ITALIC, false));

            // 点击提示
            lore.add(Component.empty());
            lore.add(MessageUtil.parse("  <yellow>✦ 点击查看详情 ✦")
                .decoration(TextDecoration.ITALIC, false));

            meta.lore(lore);
            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * 计算距离下次刷新的剩余时间
     */
    private String calculateTimeUntilReset(TaskCategory category) {
        ExpirePolicy policy = category.getExpirePolicy();
        Instant now = TimeZoneConfig.toInstant(TimeZoneConfig.now());
        Instant nextReset;

        switch (policy) {
            case DAILY:
                nextReset = ExpireUtil.getNextDailyReset(category.getResetTime());
                break;
            case WEEKLY:
                nextReset = ExpireUtil.getNextWeeklyReset(category.getResetDayOfWeek(), category.getResetTime());
                break;
            case MONTHLY:
                nextReset = ExpireUtil.getNextMonthlyReset(category.getResetDayOfMonth(), category.getResetTime());
                break;
            case RELATIVE:
            case FIXED:
            case PERMANENT:
            default:
                return "--";
        }

        Duration duration = Duration.between(now, nextReset);
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();

        if (days > 0) {
            return String.format("%d天%d小时", days, hours);
        } else if (hours > 0) {
            return String.format("%d小时%d分", hours, minutes);
        } else {
            return String.format("%d分钟", minutes);
        }
    }

    private Material getMaterialByProgress(TaskCategory category, int progressPercent) {
        String itemKey = category.getItem();
        try {
            return Material.valueOf(itemKey.replace("minecraft:", "").toUpperCase());
        } catch (Exception e) {
            // 根据完成度返回不同材料
            if (progressPercent >= 100) return Material.LIME_STAINED_GLASS_PANE;
            if (progressPercent >= 50) return Material.YELLOW_STAINED_GLASS_PANE;
            return Material.WHITE_STAINED_GLASS_PANE;
        }
    }

    private String getPolicyIcon(ExpirePolicy policy) {
        return switch (policy) {
            case DAILY -> "<yellow>☀";
            case WEEKLY -> "<green>📅";
            case MONTHLY -> "<blue>📆";
            case RELATIVE -> "<red>⏳";
            case PERMANENT -> "<light_purple>♾";
            case FIXED -> "<gold>⚡";
        };
    }

    private String getPolicyDisplay(ExpirePolicy policy) {
        return switch (policy) {
            case DAILY -> "每日刷新";
            case WEEKLY -> "每周刷新";
            case MONTHLY -> "每月刷新";
            case RELATIVE -> "限时任务";
            case PERMANENT -> "永久成就";
            case FIXED -> "限时活动";
        };
    }

    private String buildCompactProgressBar(int percent) {
        int length = 10;
        int filled = (int) Math.round(percent / 100.0 * length);
        StringBuilder bar = new StringBuilder();
        bar.append("<gray>[");
        for (int i = 0; i < length; i++) {
            if (i < filled) {
                bar.append("<green>▬");
            } else {
                bar.append("<dark_gray>▬");
            }
        }
        bar.append("<gray>] <yellow>").append(percent).append("%");
        return bar.toString();
    }

    /**
     * 计算已使用的刷新次数
     * 注意：reroll 次数会由 CategoryTaskGUI 自行从数据库获取，这里返回 0 作为默认值
     */
    private int calculateUsedRerolls(List<PlayerTask> tasks, TaskCategory category) {
        // 从预加载的数据无法直接获取 reroll 次数
        // 传入 -1 让 CategoryTaskGUI 自行查询
        return -1;
    }

    private void setBottomButtons() {
        Map<String, List<PlayerTask>> allTasks = playerTasksByCategory;
        int totalTasks = allTasks.values().stream().mapToInt(List::size).sum();
        long totalCompleted = allTasks.values().stream()
            .flatMap(List::stream)
            .filter(PlayerTask::isCompleted)
            .count();

        // 左侧：总进度统计 (slot 46)
        double completionRate = totalTasks > 0 ? (totalCompleted * 100.0 / totalTasks) : 0;
        ItemStack progressItem = ItemUtil.createDecoration("minecraft:paper",
            "<gold><bold>📊 总进度");
        ItemMeta meta = progressItem.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            lore.add(MessageUtil.parse(
                "  <gray>已完成: <green>" + (int)completionRate + "<gray>%")
                .decoration(TextDecoration.ITALIC, false));
            lore.add(MessageUtil.parse(
                "  <gray>任务数: <yellow>" + totalCompleted + "<gray>/<yellow>" + totalTasks)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            progressItem.setItemMeta(meta);
        }
        setItem(46, progressItem);

        // 中间：关闭按钮 (slot 49)
        ItemStack closeItem = ItemUtil.createDecoration("minecraft:barrier", "<red><bold>✕ 关闭");
        ItemMeta closeMeta = closeItem.getItemMeta();
        if (closeMeta != null) {
            closeMeta.lore(List.of(
                MessageUtil.parse("  <gray>点击关闭界面")
                    .decoration(TextDecoration.ITALIC, false)
            ));
            closeItem.setItemMeta(closeMeta);
        }
        setItem(49, closeItem, (p, e) -> p.closeInventory());

        // 右侧：帮助信息 (slot 52)
        ItemStack helpItem = ItemUtil.createDecoration("minecraft:book",
            "<aqua><bold>? 帮助");
        ItemMeta helpMeta = helpItem.getItemMeta();
        if (helpMeta != null) {
            List<Component> lore = new ArrayList<>();
            lore.add(MessageUtil.parse("  <gray>点击分类查看任务")
                .decoration(TextDecoration.ITALIC, false));
            lore.add(MessageUtil.parse("  <gray>完成任务领取奖励")
                .decoration(TextDecoration.ITALIC, false));
            lore.add(MessageUtil.parse("  <gray>限时任务请及时完成")
                .decoration(TextDecoration.ITALIC, false));
            helpMeta.lore(lore);
            helpItem.setItemMeta(helpMeta);
        }
        setItem(52, helpItem);
    }
}
