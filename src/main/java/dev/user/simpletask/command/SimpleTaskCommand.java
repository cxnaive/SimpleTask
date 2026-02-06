package dev.user.simpletask.command;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.gui.DailyTaskGUI;
import dev.user.simpletask.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SimpleTaskCommand implements CommandExecutor {

    private final SimpleTaskPlugin plugin;

    public SimpleTaskCommand(SimpleTaskPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getCommandMessage("player-only"));
            return true;
        }

        if (!player.hasPermission("simpletask.use")) {
            MessageUtil.sendConfigMessage(plugin, player, "no-permission");
            return true;
        }

        // Open daily task GUI - 异步查询后打开
        DailyTaskGUI.open(plugin, player);

        return true;
    }
}
