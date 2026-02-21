package dev.user.simpletask.util;

import dev.user.simpletask.SimpleTaskPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一消息工具类
 * 所有消息发送都必须通过此类，禁止直接使用MiniMessage或Component
 *
 * 支持的placeholder格式: {variable}
 * - 文本替换: 使用 String 类型值，纯文本，特殊字符会被转义
 * - 组件替换: 使用 Component 类型值，支持嵌套MiniMessage样式
 */
public class MessageUtil {

    private static final MiniMessage miniMessage = MiniMessage.miniMessage();

    // ==================== 核心发送方法 ====================

    /**
     * 发送消息（无placeholder）
     * 消息会自动添加prefix
     */
    public static void send(SimpleTaskPlugin plugin, CommandSender sender, String message) {
        if (message == null || message.isEmpty()) return;

        Component component = miniMessage.deserialize(plugin.getConfigManager().getPrefix() + message);
        sendComponent(sender, component);
    }

    /**
     * 发送消息（带文本placeholder）
     * 使用 Component.replaceText 安全替换，placeholder值中的特殊字符会被当作纯文本
     */
    public static void send(SimpleTaskPlugin plugin, CommandSender sender, String message,
                           Map<String, String> placeholders) {
        if (message == null || message.isEmpty()) return;

        Component component = miniMessage.deserialize(plugin.getConfigManager().getPrefix() + message);
        component = replaceTextPlaceholders(component, placeholders);
        sendComponent(sender, component);
    }

    /**
     * 发送消息（带组件placeholder）
     * 支持嵌套MiniMessage样式，placeholder值可以是带样式的Component
     */
    public static void sendWithComponents(SimpleTaskPlugin plugin, CommandSender sender, String message,
                           Map<String, Component> placeholders) {
        if (message == null || message.isEmpty()) return;

        Component component = miniMessage.deserialize(plugin.getConfigManager().getPrefix() + message);
        component = replaceComponentPlaceholders(component, placeholders);
        sendComponent(sender, component);
    }

    /**
     * 发送配置消息（无placeholder）
     */
    public static void sendConfig(SimpleTaskPlugin plugin, CommandSender sender, String key) {
        String message = plugin.getConfigManager().getRawMessage(key);
        if (message == null || message.isEmpty()) return;
        send(plugin, sender, message);
    }

    /**
     * 发送配置消息（带文本placeholder）
     */
    public static void sendConfig(SimpleTaskPlugin plugin, CommandSender sender, String key,
                                  Map<String, String> placeholders) {
        String message = plugin.getConfigManager().getRawMessage(key);
        if (message == null || message.isEmpty()) return;
        send(plugin, sender, message, placeholders);
    }

    /**
     * 发送配置消息（带组件placeholder）
     */
    public static void sendConfigWithComponents(SimpleTaskPlugin plugin, CommandSender sender, String key,
                                  Map<String, Component> placeholders) {
        String message = plugin.getConfigManager().getRawMessage(key);
        if (message == null || message.isEmpty()) return;
        sendWithComponents(plugin, sender, message, placeholders);
    }

    // ==================== 专用发送方法 ====================

    /**
     * 发送命令消息（从 commands. 路径读取）
     */
    public static void sendCommand(SimpleTaskPlugin plugin, CommandSender sender, String key) {
        String message = plugin.getConfigManager().getCommandMessage(key);
        if (message == null || message.isEmpty() || message.equals(key)) return;
        send(plugin, sender, message);
    }

    public static void sendCommand(SimpleTaskPlugin plugin, CommandSender sender, String key,
                                   Map<String, String> placeholders) {
        String message = plugin.getConfigManager().getCommandMessage(key);
        if (message == null || message.isEmpty() || message.equals(key)) return;
        send(plugin, sender, message, placeholders);
    }

    /**
     * 发送管理员消息（从 admin. 路径读取）
     */
    public static void sendAdmin(SimpleTaskPlugin plugin, CommandSender sender, String key) {
        String message = plugin.getConfigManager().getAdminMessage(key);
        if (message == null || message.isEmpty() || message.equals(key)) return;
        send(plugin, sender, message);
    }

    public static void sendAdmin(SimpleTaskPlugin plugin, CommandSender sender, String key,
                                 Map<String, String> placeholders) {
        String message = plugin.getConfigManager().getAdminMessage(key);
        if (message == null || message.isEmpty() || message.equals(key)) return;
        send(plugin, sender, message, placeholders);
    }

    /**
     * 发送GUI消息（从 gui. 路径读取）
     */
    public static void sendGui(SimpleTaskPlugin plugin, CommandSender sender, String key) {
        String message = plugin.getConfigManager().getGuiMessage(key);
        if (message == null || message.isEmpty() || message.equals(key)) return;
        send(plugin, sender, message);
    }

    public static void sendGui(SimpleTaskPlugin plugin, CommandSender sender, String key,
                               Map<String, String> placeholders) {
        String message = plugin.getConfigManager().getGuiMessage(key);
        if (message == null || message.isEmpty() || message.equals(key)) return;
        send(plugin, sender, message, placeholders);
    }

    /**
     * 发送经济相关消息
     */
    public static void sendEconomy(SimpleTaskPlugin plugin, CommandSender sender, String key,
                                   double amount) {
        String message = plugin.getConfigManager().getEconomyMessage(key);
        if (message == null || message.isEmpty()) return;

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("amount", String.format("%.0f", amount));
        placeholders.put("currency", plugin.getConfigManager().getEconomyMessage("currency-name"));
        send(plugin, sender, message, placeholders);
    }

    // ==================== GUI专用方法（返回Component） ====================

    /**
     * 解析消息为Component（用于GUI显示，不包含prefix）
     */
    public static Component parse(String message) {
        if (message == null || message.isEmpty()) return Component.empty();
        return miniMessage.deserialize(message);
    }

    /**
     * 解析消息为Component（带文本placeholder）
     */
    public static Component parse(String message, Map<String, String> placeholders) {
        if (message == null || message.isEmpty()) return Component.empty();
        Component component = miniMessage.deserialize(message);
        return replaceTextPlaceholders(component, placeholders);
    }

    /**
     * 解析消息为Component（带组件placeholder）
     */
    public static Component parseWithComponents(String message, Map<String, Component> placeholders) {
        if (message == null || message.isEmpty()) return Component.empty();
        Component component = miniMessage.deserialize(message);
        return replaceComponentPlaceholders(component, placeholders);
    }

    /**
     * 从配置解析消息为Component
     */
    public static Component parseConfig(SimpleTaskPlugin plugin, String key) {
        String message = plugin.getConfigManager().getRawMessage(key);
        return parse(message);
    }

    /**
     * 从配置解析消息为Component（带placeholder）
     */
    public static Component parseConfig(SimpleTaskPlugin plugin, String key,
                                        Map<String, String> placeholders) {
        String message = plugin.getConfigManager().getRawMessage(key);
        return parse(message, placeholders);
    }

    /**
     * 获取状态消息Component
     */
    public static Component parseStatus(SimpleTaskPlugin plugin, String key) {
        String message = plugin.getConfigManager().getStatusMessage(key);
        return parse(message);
    }

    // ==================== Placeholder处理核心方法 ====================

    /**
     * 替换文本placeholder（纯文本，特殊字符会被转义）
     */
    private static Component replaceTextPlaceholders(Component component, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return component;

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            component = component.replaceText(builder ->
                builder.matchLiteral(placeholder).replacement(value)
            );
        }
        return component;
    }

    /**
     * 替换组件placeholder（支持嵌套样式）
     */
    private static Component replaceComponentPlaceholders(Component component, Map<String, Component> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return component;

        for (Map.Entry<String, Component> entry : placeholders.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            Component value = entry.getValue() != null ? entry.getValue() : Component.empty();
            component = component.replaceText(builder ->
                builder.matchLiteral(placeholder).replacement(value)
            );
        }
        return component;
    }

    // ==================== 辅助方法 ====================

    /**
     * 发送Component到接收者
     */
    private static void sendComponent(CommandSender sender, Component component) {
        if (sender instanceof Player player) {
            player.sendMessage(component);
        } else {
            // 控制台直接序列化为纯文本
            sender.sendMessage(miniMessage.serialize(component));
        }
    }

    // ==================== GUI专用快捷方法（自动处理斜体） ====================

    /**
     * 创建物品显示名称Component（自动禁用斜体）
     */
    public static Component guiName(String message) {
        if (message == null || message.isEmpty()) return Component.empty().decoration(TextDecoration.ITALIC, false);
        return miniMessage.deserialize(message).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 创建物品显示名称Component（带文本placeholder，自动禁用斜体）
     */
    public static Component guiName(String message, Map<String, String> placeholders) {
        if (message == null || message.isEmpty()) return Component.empty().decoration(TextDecoration.ITALIC, false);
        Component component = miniMessage.deserialize(message);
        component = replaceTextPlaceholders(component, placeholders);
        return component.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 创建GUI lore行（自动禁用斜体）
     */
    public static Component guiLore(String message) {
        if (message == null || message.isEmpty()) return Component.empty().decoration(TextDecoration.ITALIC, false);
        return miniMessage.deserialize(message).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 创建GUI lore行（带文本placeholder，自动禁用斜体）
     */
    public static Component guiLore(String message, Map<String, String> placeholders) {
        if (message == null || message.isEmpty()) return Component.empty().decoration(TextDecoration.ITALIC, false);
        Component component = miniMessage.deserialize(message);
        component = replaceTextPlaceholders(component, placeholders);
        return component.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 创建GUI lore列表（每行自动禁用斜体）
     */
    public static List<Component> guiLore(List<String> messageLines) {
        if (messageLines == null || messageLines.isEmpty()) return List.of();
        return messageLines.stream()
            .map(line -> miniMessage.deserialize(line).decoration(TextDecoration.ITALIC, false))
            .toList();
    }

    // ==================== 便捷Builder方法 ====================

    /**
     * 创建文本placeholder Map
     */
    public static Map<String, String> textPlaceholders(Object... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i + 1 < pairs.length) {
                map.put(String.valueOf(pairs[i]), String.valueOf(pairs[i + 1]));
            }
        }
        return map;
    }

    /**
     * 创建组件placeholder Map
     */
    public static Map<String, Component> componentPlaceholders(Object... pairs) {
        Map<String, Component> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i + 1 < pairs.length && pairs[i + 1] instanceof Component) {
                map.put(String.valueOf(pairs[i]), (Component) pairs[i + 1]);
            }
        }
        return map;
    }
}
