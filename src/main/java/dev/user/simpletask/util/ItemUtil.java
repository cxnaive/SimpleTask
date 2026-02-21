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
import de.tr7zw.nbtapi.iface.ReadWriteNBT;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
     * 获取物品的显示名称（返回Component，使用现代API）
     * 优先级：1. 自定义名称（如果有） 2. CE物品的translationKey 3. 原版翻译键
     */
    public static Component getDisplayNameComponent(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return Component.translatable("block.minecraft.air");
        }

        ItemMeta meta = item.getItemMeta();
        // 优先检查是否有CustomName
        if (meta != null && meta.hasCustomName()) {
            Component customName = meta.customName();
            if (customName != null) {
                return customName;
            }
        }

        // 其次检查是否有ItemName名称（CE物品通常有）
        if (meta != null && meta.hasItemName()) {
            Component itemName = meta.itemName();
            if (itemName != null) {
                return itemName;
            }
        }

        // 其次检查是否是CE物品，使用CE的translationKey
        if (ceAvailable) {
            String ceTranslationKey = getCEItemTranslationKey(item);
            if (ceTranslationKey != null) {
                return Component.translatable(ceTranslationKey);
            }
        }

        // 最后返回原版翻译键
        String matName = item.getType().name().toLowerCase();
        String translationKey = item.getType().isBlock()
            ? "block.minecraft." + matName
            : "item.minecraft." + matName;

        return Component.translatable(translationKey);
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
     * 创建GUI装饰物品（支持原版 + CE物品，使用MiniMessage格式名称）
     *
     * @param itemKey 物品key:
     *                - "minecraft:stone" 或 "stone" → 原版石头
     *                - "myce:custom_glass" → CE物品
     * @param miniMessageName MiniMessage格式的名称
     */
    public static ItemStack createDecoration(String itemKey, String miniMessageName) {
        ItemStack item = createItem(plugin, itemKey, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtil.guiName(miniMessageName));
            item.setItemMeta(meta);
        }
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
     * 检查物品是否匹配目标key和NBT条件
     * @param item 物品
     * @param targetKey 目标物品key
     * @param nbtConditions NBT条件列表，格式: "path+value" 或 "path>=value" 等
     * @return 是否匹配
     */
    public static boolean matchesTarget(ItemStack item, String targetKey, List<String> nbtConditions) {
        // 先匹配key
        if (!matchesTarget(item, targetKey)) {
            return false;
        }

        // 没有NBT条件，直接返回true
        if (nbtConditions == null || nbtConditions.isEmpty()) {
            return true;
        }

        // 检查所有NBT条件（AND关系）
        for (String condition : nbtConditions) {
            if (!matchesNbtCondition(item, condition)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 检查单个NBT条件是否匹配
     * 支持的操作符: + (精确匹配), >=, <=, >, < (数值比较), exists (存在性检查)
     * @param item 物品
     * @param condition 条件字符串，如 "minecraft:custom_name+\"§6传说之剑\"" 或 "minecraft:enchantments.levels.minecraft:sharpness>=3"
     * @return 是否匹配
     */
    public static boolean matchesNbtCondition(ItemStack item, String condition) {
        if (item == null || condition == null || condition.isEmpty()) {
            return false;
        }

        // 解析操作符
        String[] operators = {">=", "<=", ">", "<", "+"};
        String foundOperator = null;
        int operatorIndex = -1;

        for (String op : operators) {
            operatorIndex = condition.indexOf(op);
            if (operatorIndex != -1) {
                foundOperator = op;
                break;
            }
        }

        // 处理 exists 特殊语法
        if (condition.endsWith("+exists")) {
            String path = condition.substring(0, condition.length() - 7);
            return checkNbtExists(item, path);
        }

        if (foundOperator == null) {
            return false;
        }

        String path = condition.substring(0, operatorIndex).trim();
        String expectedValue = condition.substring(operatorIndex + foundOperator.length()).trim();

        // 获取实际NBT值
        Object actualValue = getNbtValue(item, path);

        // 根据操作符比较
        return switch (foundOperator) {
            case "+" -> matchExact(actualValue, expectedValue);
            case ">=" -> compareNumeric(actualValue, expectedValue) >= 0;
            case "<=" -> compareNumeric(actualValue, expectedValue) <= 0;
            case ">" -> compareNumeric(actualValue, expectedValue) > 0;
            case "<" -> compareNumeric(actualValue, expectedValue) < 0;
            default -> false;
        };
    }

    /**
     * 获取物品NBT路径的值
     */
    private static Object getNbtValue(ItemStack item, String path) {
        if (item == null || path == null || path.isEmpty()) {
            return null;
        }

        try {
            return de.tr7zw.nbtapi.NBT.getComponents(item, nbt -> {
                return NBTPathUtils.navigatePath(nbt, path);
            });
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 检查NBT路径是否存在
     */
    private static boolean checkNbtExists(ItemStack item, String path) {
        if (item == null || path == null || path.isEmpty()) {
            return false;
        }

        try {
            return de.tr7zw.nbtapi.NBT.getComponents(item, nbt -> {
                Object value = NBTPathUtils.navigatePath(nbt, path);
                return value != null;
            });
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 精确匹配NBT值
     */
    private static boolean matchExact(Object actual, String expected) {
        if (actual == null) {
            return false;
        }

        // 解析期望值
        Object parsedExpected = NBTPathUtils.parseValue(expected);

        // 如果实际值是ReadableNBT，使用字符串比较
        if (actual instanceof de.tr7zw.nbtapi.iface.ReadableNBT) {
            return actual.toString().equals(parsedExpected.toString());
        }

        // 数值类型比较
        if (actual instanceof Number && parsedExpected instanceof Number) {
            return ((Number) actual).doubleValue() == ((Number) parsedExpected).doubleValue();
        }

        // 字符串比较
        return actual.toString().equals(parsedExpected.toString());
    }

    /**
     * 数值比较，返回 -1 (actual < expected), 0 (相等), 1 (actual > expected)
     */
    private static int compareNumeric(Object actual, String expected) {
        if (!(actual instanceof Number)) {
            return -1; // 非数值视为更小
        }

        double actualNum = ((Number) actual).doubleValue();

        // 解析期望值
        Object parsedExpected = NBTPathUtils.parseValue(expected);
        if (!(parsedExpected instanceof Number)) {
            return -1;
        }

        double expectedNum = ((Number) parsedExpected).doubleValue();

        return Double.compare(actualNum, expectedNum);
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

    // ==================== NBT 组件方法 (从 folia_shop 拷贝) ====================

    /**
     * 应用 NBT 组件到物品
     * @param item 目标物品（会被直接修改）
     * @param components NBT 组件配置 (path -> value)
     * @return 应用后的物品（同一个实例）
     */
    public static ItemStack applyComponents(ItemStack item, Map<String, String> components) {
        if (item == null || components == null || components.isEmpty()) {
            return item;
        }

        de.tr7zw.nbtapi.NBT.modifyComponents(item, (ReadWriteNBT nbt) -> {
            for (Map.Entry<String, String> entry : components.entrySet()) {
                String path = entry.getKey();
                String valueStr = entry.getValue();

                try {
                    // 解析值
                    Object value = NBTPathUtils.parseValue(valueStr);

                    // 应用设置
                    applySetNbt(nbt, path, value);
                } catch (Exception e) {
                    // 忽略单个组件的错误，继续处理其他组件
                }
            }
        });
        return item;
    }

    /**
     * 从配置解析组件配置（字符串列表格式）
     * 格式: "path+value" 或 "path+{nbt}"
     * 示例:
     *   - "minecraft:enchantments+{levels:{'minecraft:sharpness':5}}"
     *   - "minecraft:custom_name+'\"传说之剑\"'"
     * @param obj 配置对象（必须是字符串列表）
     * @return 组件映射，如果没有则返回空 map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> parseComponents(Object obj) {
        Map<String, String> components = new java.util.HashMap<>();
        if (obj == null) {
            return components;
        }

        // 只支持字符串列表格式: components: ["path+value", "path+value"]
        if (obj instanceof java.util.List) {
            java.util.List<String> list = (java.util.List<String>) obj;
            for (String entry : list) {
                parseComponentEntry(entry, components);
            }
        }

        return components;
    }

    /**
     * 解析单个组件条目
     * 格式: "path+value"
     */
    private static void parseComponentEntry(String entry, Map<String, String> components) {
        if (entry == null || entry.isEmpty()) {
            return;
        }

        // 找到第一个 + 分隔符
        int firstPlus = entry.indexOf('+');
        if (firstPlus == -1) {
            // 没有 +，视为只有路径，值为空
            return;
        }

        String path = entry.substring(0, firstPlus).trim();
        String value = entry.substring(firstPlus + 1).trim();

        if (!path.isEmpty()) {
            components.put(path, value);
        }
    }

    /**
     * 移除指定路径的 NBT
     */
    public static void applyRemoveNbt(de.tr7zw.nbtapi.iface.ReadWriteNBT nbt, String path) {
        NBTPathUtils.PathNavigationResult result = NBTPathUtils.navigateToParent(nbt, path);
        if (!result.isSuccess()) {
            return;
        }

        de.tr7zw.nbtapi.iface.ReadWriteNBT parent = result.getParent();
        String key = result.getLastKey();
        List<NBTPathUtils.PathSegment> segments = NBTPathUtils.parsePath(path);
        NBTPathUtils.PathSegment lastSegment = segments.get(segments.size() - 1);

        if (lastSegment.hasIndex()) {
            NBTPathUtils.removeListElement(parent, key, lastSegment.getIndex());
        } else if (lastSegment.hasFilter()) {
            Integer index = NBTPathUtils.resolveListFilterIndex(parent, key, lastSegment.getFilter());
            if (index != null) {
                NBTPathUtils.removeListElement(parent, key, index);
            }
        } else {
            parent.removeKey(key);
        }
    }

    /**
     * 设置指定路径的 NBT 值
     */
    public static void applySetNbt(de.tr7zw.nbtapi.iface.ReadWriteNBT nbt, String path, Object value) {
        NBTPathUtils.PathNavigationResult result = NBTPathUtils.navigateToParent(nbt, path);
        if (!result.isSuccess()) {
            return;
        }

        de.tr7zw.nbtapi.iface.ReadWriteNBT parent = result.getParent();
        String key = result.getLastKey();
        List<NBTPathUtils.PathSegment> segments = NBTPathUtils.parsePath(path);
        NBTPathUtils.PathSegment lastSegment = segments.get(segments.size() - 1);

        if (lastSegment.hasIndex()) {
            if (value instanceof de.tr7zw.nbtapi.iface.ReadableNBT) {
                NBTPathUtils.replaceInCompoundList(parent, key, lastSegment.getIndex(), (de.tr7zw.nbtapi.iface.ReadableNBT) value);
            }
        } else if (lastSegment.hasFilter()) {
            Integer index = NBTPathUtils.resolveListFilterIndex(parent, key, lastSegment.getFilter());
            if (index != null && value instanceof de.tr7zw.nbtapi.iface.ReadableNBT) {
                NBTPathUtils.replaceInCompoundList(parent, key, index, (de.tr7zw.nbtapi.iface.ReadableNBT) value);
            }
        } else {
            NBTPathUtils.setTypedValue(parent, key, value);
        }
    }

}
