package dev.user.simpletask.gui;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.TaskTemplate;
import dev.user.simpletask.util.ItemUtil;
import dev.user.simpletask.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AdminTaskGUI extends AbstractGUI {

    private int page = 0;
    private static final int ITEMS_PER_PAGE = 28; // 4 rows * 7 slots (excluding borders)

    public AdminTaskGUI(SimpleTaskPlugin plugin, Player player) {
        super(plugin, player, plugin.getConfigManager().getGuiTitleAdmin(), 54);
    }

    @Override
    public void initialize() {
        inventory.clear();
        actions.clear();

        // Fill border
        ItemStack borderItem = ItemUtil.createDecoration(
            plugin.getConfigManager().getGuiDecoration("border"),
            " "
        );
        fillBorder(borderItem);

        // Get all templates
        List<TaskTemplate> templates = new ArrayList<>(plugin.getTaskManager().getAllTemplates());
        int totalPages = (int) Math.ceil((double) templates.size() / ITEMS_PER_PAGE);

        // Display templates
        int[] slots = getItemSlots();
        int startIndex = page * ITEMS_PER_PAGE;

        for (int i = 0; i < slots.length && startIndex + i < templates.size(); i++) {
            TaskTemplate template = templates.get(startIndex + i);
            ItemStack item = createTemplateItem(template);
            setItem(slots[i], item, (p, e) -> onTemplateClick(p, template, e.getClick()));
        }

        // Fill empty slots
        ItemStack emptyItem = ItemUtil.createDecoration("minecraft:gray_stained_glass_pane", " ");
        for (int i = 0; i < slots.length; i++) {
            if (startIndex + i >= templates.size()) {
                setItem(slots[i], emptyItem);
            }
        }

        // Navigation buttons
        if (page > 0) {
            ItemStack prevButton = ItemUtil.createDecoration("minecraft:arrow", "<yellow><< 上一页");
            setItem(45, prevButton, (p, e) -> {
                page--;
                initialize();
            });
        }

        if (page < totalPages - 1) {
            ItemStack nextButton = ItemUtil.createDecoration("minecraft:arrow", "<yellow>下一页 >>");
            setItem(53, nextButton, (p, e) -> {
                page++;
                initialize();
            });
        }

        // Page indicator
        ItemStack pageIndicator = ItemUtil.createDecoration("minecraft:paper",
            "<yellow>第 " + (page + 1) + "/" + Math.max(1, totalPages) + " 页");
        setItem(49, pageIndicator);

        // Close button
        ItemStack closeButton = ItemUtil.createDecoration("minecraft:barrier", "<red>关闭");
        setItem(50, closeButton, (p, e) -> p.closeInventory());
    }

    private int[] getItemSlots() {
        return new int[]{
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
        };
    }

    private ItemStack createTemplateItem(TaskTemplate template) {
        // 使用配置的图标（支持CE物品）
        String iconKey = template.getIcon();
        ItemStack item = ItemUtil.createItem(plugin, iconKey, 1);
        if (item == null || item.getType().isAir()) {
            item = new ItemStack(getMaterialForType(template.getType()));
        }

        String name = "<yellow>" + template.getTaskKey();
        List<Component> lore = new ArrayList<>();

        lore.add(MessageUtil.guiLore("<gray>类型: <yellow>" + template.getType().getDisplayName()));

        if (template.getTargetItem() != null) {
            lore.add(MessageUtil.guiLore("<gray>目标物品: <white>" + template.getTargetItem()));
        }

        lore.add(MessageUtil.guiLore("<gray>目标数量: <white>" + template.getTargetAmount()));
        lore.add(MessageUtil.guiLore("<gray>权重: <white>" + template.getWeight()));
        lore.add(Component.empty());
        // 添加描述（支持多行）
        for (String descLine : template.getDescription()) {
            lore.add(MessageUtil.guiLore(descLine));
        }
        lore.add(Component.empty());
        lore.add(MessageUtil.guiLore("<green>左键 - 编辑"));
        lore.add(MessageUtil.guiLore("<red>右键 - 删除"));

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtil.guiName(name));
            meta.lore(lore);
            item.setItemMeta(meta);
        }

        return item;
    }

    private Material getMaterialForType(dev.user.simpletask.task.TaskType type) {
        return switch (type) {
            case CHAT -> Material.WRITABLE_BOOK;
            case CRAFT -> Material.CRAFTING_TABLE;
            case FISH -> Material.FISHING_ROD;
            case CONSUME -> Material.APPLE;
            case BREAK -> Material.IRON_PICKAXE;
            case HARVEST -> Material.WHEAT;
            case SUBMIT -> Material.CHEST;
            case KILL -> Material.IRON_SWORD;
            case BREED -> Material.WHEAT;
            case COMMAND -> Material.COMMAND_BLOCK;
        };
    }

    private void onTemplateClick(Player player, TaskTemplate template, ClickType clickType) {
        if (clickType.isRightClick()) {
            // 右键 - 删除确认
            player.closeInventory();
            String taskKey = template.getTaskKey();

            // 异步执行删除，根据结果显示消息
            plugin.getTaskManager().deleteTemplate(taskKey, success -> {
                if (success) {
                    MessageUtil.sendAdmin(plugin, player, "delete-success",
                        dev.user.simpletask.util.MessageUtil.textPlaceholders("task_key", taskKey));
                } else {
                    dev.user.simpletask.util.MessageUtil.send(plugin, player, "<red>删除任务失败");
                }
            });
        } else if (clickType.isLeftClick()) {
            // 左键 - 编辑功能（暂时发送消息）
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("task_key", template.getTaskKey());
            dev.user.simpletask.util.MessageUtil.send(plugin, player, "<yellow>正在编辑任务: <gold>{task_key} <gray>(功能开发中)",
                    dev.user.simpletask.util.MessageUtil.textPlaceholders("task_key", template.getTaskKey()));
            player.closeInventory();
        }
    }
}
