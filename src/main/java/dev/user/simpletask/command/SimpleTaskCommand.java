package dev.user.simpletask.command;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.gui.CategoryTaskGUI;
import dev.user.simpletask.gui.TaskCategoryGUI;
import dev.user.simpletask.task.category.TaskCategory;
import dev.user.simpletask.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class SimpleTaskCommand implements CommandExecutor, TabCompleter {

    private final SimpleTaskPlugin plugin;

    public SimpleTaskCommand(SimpleTaskPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendCommand(plugin, sender, "player-only");
            return true;
        }

        if (!player.hasPermission("simpletask.use")) {
            MessageUtil.sendConfig(plugin, player, "no-permission");
            return true;
        }

        // 如果有参数，直接打开指定分类
        if (args.length >= 1) {
            String categoryId = args[0].toLowerCase();
            TaskCategory category = plugin.getConfigManager().getTaskCategory(categoryId);

            if (category == null) {
                MessageUtil.send(plugin, player, "<red>分类不存在: <yellow>{category}",
                    MessageUtil.textPlaceholders("category", categoryId));
                return true;
            }

            if (!category.isEnabled()) {
                MessageUtil.send(plugin, player, "<red>该分类当前已禁用");
                return true;
            }

            // 直接打开分类任务界面（会触发检测刷新）
            CategoryTaskGUI.open(plugin, player, category);
            return true;
        }

        // 无参数：打开主界面
        TaskCategoryGUI.open(plugin, player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!sender.hasPermission("simpletask.use")) {
            return completions;
        }

        if (args.length == 1) {
            // 补全分类名
            String input = args[0].toLowerCase();
            for (String categoryId : plugin.getConfigManager().getTaskCategories().keySet()) {
                if (categoryId.toLowerCase().startsWith(input)) {
                    completions.add(categoryId);
                }
            }
        }

        return completions;
    }
}
