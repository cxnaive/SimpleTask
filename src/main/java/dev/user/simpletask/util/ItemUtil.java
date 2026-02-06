package dev.user.simpletask.util;

import dev.user.simpletask.SimpleTaskPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.item.CustomItem;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ItemUtil {

    private static SimpleTaskPlugin plugin;
    private static boolean ceAvailable = false;

    public static void init(SimpleTaskPlugin pluginInstance) {
        plugin = pluginInstance;
        // 检测 CraftEngine 是否可用
        ceAvailable = checkCEAvailable();
        if (ceAvailable) {
            plugin.getLogger().info("[ItemUtil] CraftEngine 已检测到，已启用 CE 物品支持");
        }
    }

    /**
     * 检查 CraftEngine 是否可用
     */
    private static boolean checkCEAvailable() {
        try {
            Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 判断 CraftEngine 是否可用
     */
    public static boolean isCEAvailable() {
        return ceAvailable;
    }

    /**
     * 从物品键创建物品（支持原版物品和CE物品）
     */
    public static ItemStack createItem(SimpleTaskPlugin plugin, String key, int amount) {
        if (key == null || key.isEmpty()) {
            return new ItemStack(Material.STONE);
        }

        // 解析命名空间和ID
        String namespace = "minecraft";
        String id = key;

        if (key.contains(":")) {
            String[] parts = key.split(":", 2);
            namespace = parts[0];
            id = parts[1];
        }

        // 如果不是原版物品，尝试获取CE物品
        if (!namespace.equals("minecraft")) {
            ItemStack ceItem = getCEItem(plugin, namespace, id);
            if (ceItem != null) {
                ceItem.setAmount(amount);
                return ceItem;
            }
        }

        // 尝试创建原版物品
        try {
            Material material = Material.valueOf(id.toUpperCase());
            return new ItemStack(material, amount);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("未知的物品: " + key);
            return new ItemStack(Material.BARRIER);
        }
    }

    /**
     * 获取CE物品
     */
    private static ItemStack getCEItem(SimpleTaskPlugin plugin, String namespace, String id) {
        try {
            Key itemKey = Key.of(namespace, id);
            CustomItem<ItemStack> customItem = CraftEngineItems.byId(itemKey);
            if (customItem != null) {
                return customItem.buildItemStack(ItemBuildContext.empty(), 1);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[CE] 获取CE物品失败 " + namespace + ":" + id + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * 获取物品的显示名称
     * 优先级：1. 自定义名称（如果有） 2. CE物品的translationKey 3. 原版翻译键
     */
    public static String getDisplayName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return "<lang:block.minecraft.air>空气</lang>";
        }

        ItemMeta meta = item.getItemMeta();
        // 优先检查是否有Itemname名称（CE物品通常有）
        if (meta != null && meta.hasItemName()) {
            Component itemName = meta.itemName();
            if (itemName != null) {
                return LegacyComponentSerializer.legacySection().serialize(itemName);
            }
        }

        // 其次检查是否是CE物品，使用CE的translationKey
        if (ceAvailable) {
            String ceTranslationKey = getCEItemTranslationKey(item);
            if (ceTranslationKey != null) {
                return "<lang:" + ceTranslationKey + ">";
            }
        }

        // 最后返回原版 <lang> 标签格式
        String matName = item.getType().name().toLowerCase();
        String translationKey;

        if (item.getType().isBlock()) {
            translationKey = "block.minecraft." + matName;
        } else {
            translationKey = "item.minecraft." + matName;
        }

        return "<lang:" + translationKey + ">";
    }

    /**
     * 设置物品的显示名称（支持颜色代码 §）
     */
    public static void setDisplayName(ItemStack item, String name) {
        if (item == null || item.getType() == Material.AIR) return;

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Component component = LegacyComponentSerializer.legacySection().deserialize(name)
                    .decoration(TextDecoration.ITALIC, false);
            meta.displayName(component);
            item.setItemMeta(meta);
        }
    }

    /**
     * 设置物品的Lore（支持颜色代码 §），保留原有ItemMeta
     */
    public static void setLore(ItemStack item, List<String> lore) {
        if (item == null || item.getType() == Material.AIR) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            // 如果没有ItemMeta，尝试创建
            meta = org.bukkit.Bukkit.getItemFactory().getItemMeta(item.getType());
            if (meta == null) return;
        }

        List<Component> components = lore.stream()
                .map(line -> LegacyComponentSerializer.legacySection().deserialize(line)
                        .decoration(TextDecoration.ITALIC, false))
                .collect(Collectors.toList());
        meta.lore(components);
        item.setItemMeta(meta);
    }

    /**
     * 在原有Lore基础上添加新的Lore行（用于CE物品）
     */
    public static void addLore(ItemStack item, List<String> additionalLore) {
        if (item == null || item.getType() == Material.AIR) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        // 获取原有lore
        List<Component> existingLore = meta.lore();
        if (existingLore == null) {
            existingLore = new ArrayList<>();
        }

        // 添加新lore
        for (String line : additionalLore) {
            existingLore.add(LegacyComponentSerializer.legacySection().deserialize(line)
                    .decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(existingLore);
        item.setItemMeta(meta);
    }

    /**
     * 创建一个简单的GUI装饰物品
     */
    public static ItemStack createDecoration(Material material, String name) {
        ItemStack item = new ItemStack(material);
        setDisplayName(item, name);
        return item;
    }

    /**
     * 克隆物品并设置数量
     */
    public static ItemStack cloneWithAmount(ItemStack item, int amount) {
        if (item == null) return null;
        ItemStack clone = item.clone();
        clone.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
        return clone;
    }

    /**
     * 获取物品的Key (格式: "minecraft:stone" 或 "namespace:id")
     * 支持原版物品和 CraftEngine 自定义物品
     */
    public static String getItemKey(ItemStack item) {
        if (item == null) return "minecraft:air";

        // 尝试从 CraftEngine 获取自定义物品ID
        String ceKey = getCEItemKey(item);
        if (ceKey != null) {
            return ceKey;
        }

        // 默认返回原版物品ID
        return "minecraft:" + item.getType().name().toLowerCase();
    }

    /**
     * 尝试获取 CraftEngine 物品的Key
     * @param item 物品
     * @return CE物品key，如果不是CE物品则返回null
     */
    public static String getCEItemKey(ItemStack item) {
        if (!ceAvailable) {
            return null;
        }
        Key customId = CraftEngineItems.getCustomItemId(item);
        return customId != null ? customId.toString() : null;
    }

    /**
     * 尝试获取 CraftEngine 物品的翻译键
     * @param item 物品
     * @return CE物品翻译键 (格式: item.namespace.value)，如果不是CE物品则返回null
     */
    private static String getCEItemTranslationKey(ItemStack item) {
        if (!ceAvailable) {
            return null;
        }
        try {
            Key customId = CraftEngineItems.getCustomItemId(item);
            if (customId != null) {
                // CE的translationKey格式: item.namespace.value
                return "item." + customId.namespace() + "." + customId.value();
            }
        } catch (Exception e) {
            // CE API 调用失败
        }
        return null;
    }

    /**
     * 检查物品是否匹配目标key
     */
    public static boolean matchesTarget(ItemStack item, String targetKey) {
        if (item == null || targetKey == null) {
            return false;
        }

        String itemKey = getItemKey(item);
        return itemKey.equalsIgnoreCase(targetKey);
    }

    /**
     * 将 § 格式颜色代码转换为 Adventure Component
     * @param message 包含 § 颜色代码的消息
     * @return Adventure Component 对象
     */
    public static net.kyori.adventure.text.Component deserializeLegacyMessage(String message) {
        if (message == null || message.isEmpty()) {
            return net.kyori.adventure.text.Component.empty();
        }
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(message);
    }

    // ========== 方块相关方法 ==========

    /**
     * 获取方块的 Key (格式: "minecraft:stone" 或 "namespace:id")
     * 支持原版方块和 CraftEngine 自定义方块
     *
     * @param block 方块
     * @return 方块的 key
     */
    public static String getBlockKey(Block block) {
        if (block == null) return "minecraft:air";

        // 尝试从 CraftEngine 获取自定义方块 ID
        String ceKey = getCEBlockKey(block);
        if (ceKey != null) {
            return ceKey;
        }

        // 默认返回原版方块 ID
        return "minecraft:" + block.getType().name().toLowerCase();
    }

    /**
     * 尝试获取 CraftEngine 方块的 Key
     * @param block 方块
     * @return CE方块 key，如果不是 CE 方块则返回 null
     */
    private static String getCEBlockKey(Block block) {
        if (!ceAvailable) {
            return null;
        }
        try {
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
            if (state != null && !state.isEmpty()) {
                return state.owner().value().id().toString();
            }
        } catch (Exception e) {
            // CE API 调用失败
        }
        return null;
    }

}
