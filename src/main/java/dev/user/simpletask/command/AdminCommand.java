package dev.user.simpletask.command;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.gui.AdminTaskGUI;
import dev.user.simpletask.task.TaskTemplate;
import dev.user.simpletask.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class AdminCommand implements CommandExecutor, TabCompleter {

    private final SimpleTaskPlugin plugin;

    public AdminCommand(SimpleTaskPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("simpletask.admin")) {
            MessageUtil.sendConfig(plugin, sender, "no-permission");
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player player) {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                    AdminTaskGUI gui = new AdminTaskGUI(plugin, player);
                    gui.open();
                });
            } else {
                sendHelp(sender);
            }
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reloadconfig" -> {
                plugin.getConfigManager().reload();
                MessageUtil.sendAdmin(plugin, sender, "config-reloaded");
                return true;
            }
            case "reloadfromdb" -> {
                reloadFromDatabase(sender);
                return true;
            }
            case "import" -> {
                importTemplates(sender, args);
                return true;
            }
            case "list" -> {
                listTemplatesFromConfig(sender);
                return true;
            }
            case "delete" -> {
                if (args.length < 2) {
                    MessageUtil.sendCommand(plugin, sender, "player-only");
                    return true;
                }
                deleteTemplate(sender, args[1]);
                return true;
            }
            case "reroll" -> {
                if (args.length < 3) {
                    MessageUtil.send(plugin, sender, "<red>用法: /taskadmin reroll <分类> <玩家名/all>");
                    return true;
                }
                rerollTasks(sender, args[1], args[2]);
                return true;
            }
            case "rerollall" -> {
                if (args.length < 3) {
                    MessageUtil.send(plugin, sender, "<red>用法: /taskadmin rerollall <分类> <玩家名/all>");
                    return true;
                }
                rerollAllTasks(sender, args[1], args[2]);
                return true;
            }
            case "assign" -> {
                if (args.length < 4) {
                    MessageUtil.send(plugin, sender, "<red>用法: /taskadmin assign <分类> <任务key> <玩家名/all>");
                    return true;
                }
                assignTask(sender, args[1], args[2], args[3]);
                return true;
            }
            case "remove" -> {
                if (args.length < 4) {
                    MessageUtil.send(plugin, sender, "<red>用法: /taskadmin remove <分类> <任务key> <玩家名/all>");
                    return true;
                }
                removeTask(sender, args[1], args[2], args[3]);
                return true;
            }
            case "resetreroll" -> {
                if (args.length < 3) {
                    MessageUtil.send(plugin, sender, "<red>用法: /taskadmin resetreroll <分类> <玩家名/all>");
                    return true;
                }
                resetRerollCount(sender, args[1], args[2]);
                return true;
            }
            case "help" -> {
                sendHelp(sender);
                return true;
            }
            default -> {
                MessageUtil.sendCommand(plugin, sender, "unknown-command");
                return true;
            }
        }
    }

    private void importTemplates(CommandSender sender, String[] args) {
        String taskKey = args.length >= 2 ? args[1] : "all";

        List<TaskTemplate> templates = plugin.getConfigManager().loadTemplatesFromConfig(taskKey);
        if (templates.isEmpty()) {
            if (!taskKey.equalsIgnoreCase("all")) {
                MessageUtil.sendAdmin(plugin, sender, "import-fail-not-found",
                    MessageUtil.textPlaceholders("task_key", taskKey));
            } else {
                MessageUtil.sendAdmin(plugin, sender, "import-fail");
            }
            return;
        }

        plugin.getTaskManager().importTemplates(templates);

        if (taskKey.equalsIgnoreCase("all")) {
            MessageUtil.sendAdmin(plugin, sender, "import-success-all",
                MessageUtil.textPlaceholders("count", String.valueOf(templates.size())));
        } else {
            MessageUtil.sendAdmin(plugin, sender, "import-success-single",
                MessageUtil.textPlaceholders("task_key", taskKey));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!sender.hasPermission("simpletask.admin")) {
            return completions;
        }

        if (args.length == 1) {
            String[] subCommands = {"reloadconfig", "reloadfromdb", "import", "list", "delete", "reroll", "rerollall", "assign", "remove", "resetreroll", "help"};
            for (String sub : subCommands) {
                if (sub.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("reroll") || args[0].equalsIgnoreCase("rerollall") || args[0].equalsIgnoreCase("assign") || args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("resetreroll"))) {
            // 分类补全
            completions.addAll(plugin.getConfigManager().getTaskCategories().keySet());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("assign")) {
            // assign 命令的任务key补全
            for (TaskTemplate template : plugin.getTaskManager().getAllTemplates()) {
                if (template.getTaskKey().toLowerCase().startsWith(args[2].toLowerCase())) {
                    completions.add(template.getTaskKey());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("remove")) {
            // remove 命令的任务key补全
            for (TaskTemplate template : plugin.getTaskManager().getAllTemplates()) {
                if (template.getTaskKey().toLowerCase().startsWith(args[2].toLowerCase())) {
                    completions.add(template.getTaskKey());
                }
            }
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("reroll") || args[0].equalsIgnoreCase("rerollall") || args[0].equalsIgnoreCase("resetreroll"))) {
            // 玩家名/all 补全
            completions.add("all");
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[2].toLowerCase())) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("assign")) {
            // assign 命令的玩家名补全
            completions.add("all");
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[3].toLowerCase())) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("remove")) {
            // remove 命令的玩家名补全
            completions.add("all");
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[3].toLowerCase())) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
            Set<String> keys = plugin.getConfigManager().getTaskKeysFromConfig();
            for (String key : keys) {
                if (key.toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(key);
                }
            }
            completions.add("all");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
            for (TaskTemplate template : plugin.getTaskManager().getAllTemplates()) {
                if (template.getTaskKey().toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(template.getTaskKey());
                }
            }
        }

        return completions;
    }

    private void listTemplatesFromConfig(CommandSender sender) {
        Set<String> configKeys = plugin.getConfigManager().getTaskKeysFromConfig();
        List<TaskTemplate> dbTemplates = List.copyOf(plugin.getTaskManager().getAllTemplates());

        MessageUtil.sendAdmin(plugin, sender, "list-title-config");
        for (String key : configKeys) {
            boolean inDb = dbTemplates.stream().anyMatch(t -> t.getTaskKey().equals(key));
            String status = inDb ? plugin.getConfigManager().getAdminMessage("list-status-imported")
                                  : plugin.getConfigManager().getAdminMessage("list-status-not-imported");
            MessageUtil.send(plugin, sender, "<yellow>{key} <gray>{status}",
                MessageUtil.textPlaceholders("key", key, "status", status));
        }

        MessageUtil.send(plugin, sender, "");
        MessageUtil.sendAdmin(plugin, sender, "list-title-database");
        for (TaskTemplate template : dbTemplates) {
            MessageUtil.send(plugin, sender,
                "<yellow>{key} <gray>({type}) 权重:{weight}",
                MessageUtil.textPlaceholders(
                    "key", template.getTaskKey(),
                    "type", plugin.getConfigManager().getTaskTypeName(template.getType()),
                    "weight", String.valueOf(template.getWeight())
                ));
        }
    }

    private void deleteTemplate(CommandSender sender, String taskKey) {
        TaskTemplate template = plugin.getTaskManager().getTemplateByKey(taskKey);
        if (template == null) {
            MessageUtil.sendAdmin(plugin, sender, "delete-fail-not-found",
                MessageUtil.textPlaceholders("task_key", taskKey));
            return;
        }

        plugin.getTaskManager().deleteTemplate(taskKey, success -> {
            if (success) {
                MessageUtil.sendAdmin(plugin, sender, "delete-success",
                    MessageUtil.textPlaceholders("task_key", taskKey));
            } else {
                MessageUtil.send(plugin, sender, "<red>删除任务失败，请检查数据库连接");
            }
        });
    }

    private void rerollAllTasks(CommandSender sender, String categoryId, String target) {
        if (target.equalsIgnoreCase("all")) {
            int count = plugin.getServer().getOnlinePlayers().size();
            if (count == 0) {
                MessageUtil.send(plugin, sender, "<red>当前没有在线玩家");
                return;
            }

            plugin.getTaskManager().forceRerollAllPlayerCategoryTasks(categoryId, true, success -> {
                if (success) {
                    MessageUtil.send(plugin, sender, "<green>已为所有在线玩家强制刷新 <yellow>{category}<green> 分类的任务 (<yellow>{count}<green>人)",
                        MessageUtil.textPlaceholders("category", categoryId, "count", String.valueOf(count)));
                } else {
                    MessageUtil.send(plugin, sender, "<red>强制刷新任务失败，请检查数据库连接");
                }
            });
        } else {
            Player targetPlayer = plugin.getServer().getPlayerExact(target);
            if (targetPlayer == null || !targetPlayer.isOnline()) {
                MessageUtil.send(plugin, sender, "<red>找不到玩家: <yellow>{player}",
                    MessageUtil.textPlaceholders("player", target));
                return;
            }

            plugin.getTaskManager().forceRerollPlayerCategoryTasks(targetPlayer, categoryId, true, success -> {
                if (success) {
                    MessageUtil.send(plugin, sender, "<green>已为玩家 <yellow>{player} <green>强制刷新 <yellow>{category}<green> 分类的任务",
                        MessageUtil.textPlaceholders("player", targetPlayer.getName(), "category", categoryId));
                } else {
                    MessageUtil.send(plugin, sender, "<red>强制刷新 {player} 的任务失败",
                        MessageUtil.textPlaceholders("player", targetPlayer.getName()));
                }
            });
        }
    }

    private void rerollTasks(CommandSender sender, String categoryId, String target) {
        if (target.equalsIgnoreCase("all")) {
            int count = plugin.getServer().getOnlinePlayers().size();
            if (count == 0) {
                MessageUtil.send(plugin, sender, "<red>当前没有在线玩家");
                return;
            }

            plugin.getTaskManager().rerollAllPlayerCategoryTasks(categoryId, true, success -> {
                if (success) {
                    MessageUtil.send(plugin, sender, "<green>已为所有在线玩家重新抽取 <yellow>{category}<green> 分类的任务 (<yellow>{count}<green>人)",
                        MessageUtil.textPlaceholders("category", categoryId, "count", String.valueOf(count)));
                } else {
                    MessageUtil.send(plugin, sender, "<red>重新抽取任务失败，请检查数据库连接");
                }
            });
        } else {
            Player targetPlayer = plugin.getServer().getPlayerExact(target);
            if (targetPlayer == null || !targetPlayer.isOnline()) {
                MessageUtil.send(plugin, sender, "<red>找不到玩家: <yellow>{player}",
                    MessageUtil.textPlaceholders("player", target));
                return;
            }

            plugin.getTaskManager().rerollPlayerCategoryTasks(targetPlayer, categoryId, true, success -> {
                if (success) {
                    MessageUtil.send(plugin, sender, "<green>已为玩家 <yellow>{player} <green>重新抽取 <yellow>{category}<green> 分类的任务",
                        MessageUtil.textPlaceholders("player", targetPlayer.getName(), "category", categoryId));
                } else {
                    MessageUtil.send(plugin, sender, "<red>重新抽取 {player} 的任务失败",
                        MessageUtil.textPlaceholders("player", targetPlayer.getName()));
                }
            });
        }
    }

    private void assignTask(CommandSender sender, String categoryId, String taskKey, String target) {
        TaskTemplate template = plugin.getTaskManager().getTemplateByKey(taskKey);
        if (template == null) {
            MessageUtil.send(plugin, sender, "<red>未找到任务模板: <yellow>{task_key}",
                MessageUtil.textPlaceholders("task_key", taskKey));
            return;
        }

        // 检查分类是否存在
        var category = plugin.getConfigManager().getTaskCategory(categoryId);
        if (category == null) {
            MessageUtil.send(plugin, sender, "<red>分类不存在: <yellow>{category}",
                MessageUtil.textPlaceholders("category", categoryId));
            return;
        }

        // 警告：模板类别与目标类别不一致
        if (!categoryId.equals(template.getCategory())) {
            plugin.getLogger().warning("[SimpleTask] Admin assigning template '" + taskKey +
                "' (template category: " + template.getCategory() + ") to category: " + categoryId +
                " (requested by: " + sender.getName() + ")");
            MessageUtil.send(plugin, sender, "<yellow>警告: 任务模板 <gold>{task_key} <yellow>的类别是 <gold>{template_category}<yellow>，但将分配到 <gold>{target_category}",
                MessageUtil.textPlaceholders("task_key", taskKey,
                    "template_category", template.getCategory(),
                    "target_category", categoryId));
        }

        if (target.equalsIgnoreCase("all")) {
            List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
            if (players.isEmpty()) {
                MessageUtil.send(plugin, sender, "<red>当前没有在线玩家");
                return;
            }

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger completedCount = new AtomicInteger(0);
            int total = players.size();

            for (Player player : players) {
                plugin.getTaskManager().assignTaskToCategory(player, categoryId, template, success -> {
                    if (success) {
                        successCount.incrementAndGet();
                    }
                    completedCount.incrementAndGet();

                    if (completedCount.get() == total) {
                        MessageUtil.send(plugin, sender, "<green>成功为 <yellow>{count} <green>名玩家添加任务: <yellow>{task_key}",
                            MessageUtil.textPlaceholders("count", String.valueOf(successCount.get()), "task_key", taskKey));
                    }
                });
            }
        } else {
            Player targetPlayer = plugin.getServer().getPlayerExact(target);
            if (targetPlayer == null || !targetPlayer.isOnline()) {
                MessageUtil.send(plugin, sender, "<red>找不到玩家: <yellow>{player}",
                    MessageUtil.textPlaceholders("player", target));
                return;
            }

            plugin.getTaskManager().assignTaskToCategory(targetPlayer, categoryId, template, success -> {
                if (success) {
                    MessageUtil.send(plugin, sender, "<green>已为玩家 <yellow>{player} <green>添加任务: <yellow>{task_key}",
                        MessageUtil.textPlaceholders("player", targetPlayer.getName(), "task_key", taskKey));
                } else {
                    MessageUtil.send(plugin, sender, "<red>为 {player} 添加任务失败",
                        MessageUtil.textPlaceholders("player", targetPlayer.getName()));
                }
            });
        }
    }

    private void resetRerollCount(CommandSender sender, String categoryId, String target) {
        if (target.equalsIgnoreCase("all")) {
            int count = plugin.getServer().getOnlinePlayers().size();
            if (count == 0) {
                MessageUtil.send(plugin, sender, "<red>当前没有在线玩家");
                return;
            }

            plugin.getTaskManager().resetAllPlayerCategoryRerollCount(categoryId, success -> {
                if (success) {
                    MessageUtil.send(plugin, sender, "<green>已重置所有在线玩家 <yellow>{category}<green> 分类的刷新次数 (<yellow>{count}<green>人)",
                        MessageUtil.textPlaceholders("category", categoryId, "count", String.valueOf(count)));
                } else {
                    MessageUtil.send(plugin, sender, "<red>重置刷新次数失败，请检查数据库连接");
                }
            });
        } else {
            Player targetPlayer = plugin.getServer().getPlayerExact(target);
            if (targetPlayer == null || !targetPlayer.isOnline()) {
                MessageUtil.send(plugin, sender, "<red>找不到玩家: <yellow>{player}",
                    MessageUtil.textPlaceholders("player", target));
                return;
            }

            plugin.getTaskManager().resetPlayerCategoryRerollCount(targetPlayer, categoryId, success -> {
                if (success) {
                    MessageUtil.send(plugin, sender, "<green>已重置玩家 <yellow>{player} <green>在 <yellow>{category}<green> 分类的刷新次数",
                        MessageUtil.textPlaceholders("player", targetPlayer.getName(), "category", categoryId));
                } else {
                    MessageUtil.send(plugin, sender, "<red>重置 {player} 的刷新次数失败",
                        MessageUtil.textPlaceholders("player", targetPlayer.getName()));
                }
            });
        }
    }

    private void removeTask(CommandSender sender, String categoryId, String taskKey, String target) {
        // 检查分类是否存在
        var category = plugin.getConfigManager().getTaskCategory(categoryId);
        if (category == null) {
            MessageUtil.send(plugin, sender, "<red>分类不存在: <yellow>{category}",
                MessageUtil.textPlaceholders("category", categoryId));
            return;
        }

        // 检查任务模板是否存在
        TaskTemplate template = plugin.getTaskManager().getTemplateByKey(taskKey);
        if (template == null) {
            MessageUtil.send(plugin, sender, "<red>未找到任务模板: <yellow>{task_key}",
                MessageUtil.textPlaceholders("task_key", taskKey));
            return;
        }

        if (target.equalsIgnoreCase("all")) {
            List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
            if (players.isEmpty()) {
                MessageUtil.send(plugin, sender, "<red>当前没有在线玩家");
                return;
            }

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger completedCount = new AtomicInteger(0);
            int total = players.size();

            for (Player player : players) {
                plugin.getTaskManager().removePlayerTask(player.getUniqueId(), categoryId, taskKey, success -> {
                    if (success) {
                        successCount.incrementAndGet();
                    }
                    completedCount.incrementAndGet();

                    if (completedCount.get() == total) {
                        MessageUtil.send(plugin, sender, "<green>已为 <yellow>{count} <green>名玩家删除任务: <yellow>{task_key}",
                            MessageUtil.textPlaceholders("count", String.valueOf(successCount.get()), "task_key", taskKey));
                    }
                });
            }
        } else {
            Player targetPlayer = plugin.getServer().getPlayerExact(target);
            if (targetPlayer == null || !targetPlayer.isOnline()) {
                MessageUtil.send(plugin, sender, "<red>找不到玩家: <yellow>{player}",
                    MessageUtil.textPlaceholders("player", target));
                return;
            }

            plugin.getTaskManager().removePlayerTask(targetPlayer.getUniqueId(), categoryId, taskKey, success -> {
                if (success) {
                    MessageUtil.send(plugin, sender, "<green>已为玩家 <yellow>{player} <green>删除任务: <yellow>{task_key}",
                        MessageUtil.textPlaceholders("player", targetPlayer.getName(), "task_key", taskKey));
                } else {
                    MessageUtil.send(plugin, sender, "<yellow>玩家 <yellow>{player} <yellow>没有任务 <gold>{task_key}",
                        MessageUtil.textPlaceholders("player", targetPlayer.getName(), "task_key", taskKey));
                }
            });
        }
    }

    private void reloadFromDatabase(CommandSender sender) {
        MessageUtil.send(plugin, sender, "<yellow>正在从数据库重新加载模板...");
        plugin.getTaskManager().getTemplateSyncManager().reloadFromDatabase(() -> {
            MessageUtil.send(plugin, sender, "<green>模板已从数据库重新加载完成！");
        });
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.send(plugin, sender, plugin.getConfigManager().getCommandMessage("help-title"));

        List<String> helpMessages;
        if (sender instanceof Player) {
            helpMessages = plugin.getConfigManager().getCommandHelpMessages("player");
            for (String msg : helpMessages) {
                MessageUtil.send(plugin, sender, msg);
            }
            MessageUtil.send(plugin, sender, "");
            helpMessages = plugin.getConfigManager().getCommandHelpMessages("admin");
            for (String msg : helpMessages) {
                MessageUtil.send(plugin, sender, msg);
            }
            MessageUtil.send(plugin, sender, "<yellow>/taskadmin reloadconfig <gray>- 重新加载配置文件");
            MessageUtil.send(plugin, sender, "<yellow>/taskadmin reloadfromdb <gray>- 从数据库重新加载模板");
            MessageUtil.send(plugin, sender, "<yellow>/taskadmin reroll <分类> <玩家名/all> <gray>- 重新抽取任务(保留已完成)");
            MessageUtil.send(plugin, sender, "<yellow>/taskadmin rerollall <分类> <玩家名/all> <gray>- 强制刷新所有任务");
            MessageUtil.send(plugin, sender, "<yellow>/taskadmin assign <分类> <任务key> <玩家名/all> <gray>- 给玩家添加指定任务");
            MessageUtil.send(plugin, sender, "<yellow>/taskadmin remove <分类> <任务key> <玩家名/all> <gray>- 删除玩家的指定任务");
            MessageUtil.send(plugin, sender, "<yellow>/taskadmin resetreroll <分类> <玩家名/all> <gray>- 重置玩家刷新次数");
        } else {
            helpMessages = plugin.getConfigManager().getCommandHelpMessages("admin");
            for (String msg : helpMessages) {
                MessageUtil.send(plugin, sender, msg);
            }
            MessageUtil.send(plugin, sender, "<yellow>/taskadmin reloadconfig <gray>- 重新加载配置文件");
            MessageUtil.send(plugin, sender, "<yellow>/taskadmin reloadfromdb <gray>- 从数据库重新加载模板");
            MessageUtil.send(plugin, sender, "<yellow>/taskadmin reroll <分类> <玩家名/all> <gray>- 重新抽取任务");
            MessageUtil.send(plugin, sender, "<yellow>/taskadmin rerollall <分类> <玩家名/all> <gray>- 强制刷新所有任务");
            MessageUtil.send(plugin, sender, "<yellow>/taskadmin assign <分类> <任务key> <玩家名/all> <gray>- 给玩家添加指定任务");
            MessageUtil.send(plugin, sender, "<yellow>/taskadmin remove <分类> <任务key> <玩家名/all> <gray>- 删除玩家的指定任务");
            MessageUtil.send(plugin, sender, "<yellow>/taskadmin resetreroll <分类> <玩家名/all> <gray>- 重置玩家刷新次数");
        }
    }
}
