package dev.user.simpletask.util;

import dev.user.simpletask.SimpleTaskPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public class MessageUtil {

    private static final MiniMessage miniMessage = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacySection();

    /**
     * 发送MiniMessage格式的消息
     */
    public static void sendMessage(CommandSender sender, String message) {
        if (message == null || message.isEmpty()) return;

        Component component = miniMessage.deserialize(message);
        if (sender instanceof Player player) {
            player.sendMessage(component);
        } else {
            sender.sendMessage(legacySerializer.serialize(component));
        }
    }

    /**
     * 发送带占位符的消息
     */
    public static void sendMessage(CommandSender sender, String message, Map<String, String> placeholders) {
        if (message == null) return;

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        sendMessage(sender, message);
    }

    /**
     * 从配置发送消息
     */
    public static void sendConfigMessage(SimpleTaskPlugin plugin, CommandSender sender, String key) {
        String message = plugin.getConfigManager().getRawMessage(key);
        if (message != null && !message.isEmpty()) {
            sendMessage(sender, plugin.getConfigManager().getPrefix() + message);
        }
    }

    /**
     * 从配置发送带占位符的消息
     */
    public static void sendConfigMessage(SimpleTaskPlugin plugin, CommandSender sender, String key,
                                         Map<String, String> placeholders) {
        String message = plugin.getConfigManager().getRawMessage(key);
        if (message != null && !message.isEmpty()) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                message = message.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            sendMessage(sender, plugin.getConfigManager().getPrefix() + message);
        }
    }

    /**
     * 将MiniMessage字符串转换为Bukkit的字符串 (带§颜色代码)
     */
    public static String toLegacyString(String miniMessageString) {
        Component component = miniMessage.deserialize(miniMessageString);
        return legacySerializer.serialize(component);
    }

    /**
     * 将MiniMessage字符串转换为Component
     */
    public static Component toComponent(String miniMessageString) {
        return miniMessage.deserialize(miniMessageString);
    }

    /**
     * 发送MiniMessage格式的消息（便捷方法）
     */
    public static void sendMiniMessage(CommandSender sender, String message) {
        sendMessage(sender, message);
    }

    /**
     * 发送MiniMessage格式的消息（带占位符）
     */
    public static void sendMiniMessage(CommandSender sender, String message, Map<String, String> placeholders) {
        sendMessage(sender, message, placeholders);
    }
}
