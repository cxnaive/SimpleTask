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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            MessageUtil.sendConfigMessage(plugin, sender, "no-permission");
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player player) {
                // Open admin GUI
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
                MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getAdminMessage("config-reloaded"));
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
                    MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getCommandMessage("player-only"));
                    return true;
                }
                deleteTemplate(sender, args[1]);
                return true;
            }
            case "reroll" -> {
                if (args.length < 2) {
                    MessageUtil.sendMiniMessage(sender, "\u003cred\u003e用法: /taskadmin reroll \u003c玩家名/all\u003e");
                    return true;
                }
                rerollTasks(sender, args[1]);
                return true;
            }
            case "rerollall" -> {
                if (args.length < 2) {
                    MessageUtil.sendMiniMessage(sender, "<red>用法: /taskadmin rerollall <玩家名/all>");
                    return true;
                }
                rerollAllTasks(sender, args[1]);
                return true;
            }
            case "assign" -> {
                if (args.length < 3) {
                    MessageUtil.sendMiniMessage(sender, "\u003cred\u003e用法: /taskadmin assign \u003c任务key\u003e \u003c玩家名/all\u003e");
                    return true;
                }
                assignTask(sender, args[1], args[2]);
                return true;
            }
            case "help" -> {
                sendHelp(sender);
                return true;
            }
            default -> {
                MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getCommandMessage("unknown-command"));
                return true;
            }
        }
    }

    private void importTemplates(CommandSender sender, String[] args) {
        String taskKey = args.length >= 2 ? args[1] : "all";

        List<TaskTemplate> templates = plugin.getConfigManager().loadTemplatesFromConfig(taskKey);
        if (templates.isEmpty()) {
            if (!taskKey.equalsIgnoreCase("all")) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("task_key", taskKey);
                MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getAdminMessage("import-fail-not-found", placeholders));
            } else {
                MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getAdminMessage("import-fail"));
            }
            return;
        }

        plugin.getTaskManager().importTemplates(templates);

        if (taskKey.equalsIgnoreCase("all")) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("count", String.valueOf(templates.size()));
            MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getAdminMessage("import-success-all", placeholders));
        } else {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("task_key", taskKey);
            MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getAdminMessage("import-success-single", placeholders));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!sender.hasPermission("simpletask.admin")) {
            return completions;
        }

        if (args.length == 1) {
            // 第一级子命令
            String[] subCommands = {"reloadconfig", "reloadfromdb", "import", "list", "delete", "reroll", "rerollall", "assign", "help"};
            for (String sub : subCommands) {
                if (sub.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("assign")) {
            // assign 命令的补全 - 数据库中的模板
            for (TaskTemplate template : plugin.getTaskManager().getAllTemplates()) {
                if (template.getTaskKey().toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(template.getTaskKey());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("assign")) {
            // assign 命令的第三个参数 - 在线玩家名 + all
            completions.add("all");
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[2].toLowerCase())) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("reroll") || args[0].equalsIgnoreCase("rerollall"))) {
            // reroll 和 rerollall 命令的补全 - 在线玩家名 + all
            completions.add("all");
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
            // import 命令的补全 - 所有任务key
            Set<String> keys = plugin.getConfigManager().getTaskKeysFromConfig();
            for (String key : keys) {
                if (key.toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(key);
                }
            }
            completions.add("all");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
            // delete 命令的补全 - 数据库中的模板
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

        MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getAdminMessage("list-title-config"));
        for (String key : configKeys) {
            boolean inDb = dbTemplates.stream().anyMatch(t -> t.getTaskKey().equals(key));
            String status = inDb ? plugin.getConfigManager().getAdminMessage("list-status-imported")
                                  : plugin.getConfigManager().getAdminMessage("list-status-not-imported");
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("key", key);
            placeholders.put("status", status);
            MessageUtil.sendMiniMessage(sender, "<yellow>{key} <gray>{status}", placeholders);
        }

        MessageUtil.sendMiniMessage(sender, "");
        MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getAdminMessage("list-title-database"));
        for (TaskTemplate template : dbTemplates) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("key", template.getTaskKey());
            placeholders.put("type", plugin.getConfigManager().getTaskTypeName(template.getType()));
            placeholders.put("weight", String.valueOf(template.getWeight()));
            MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getAdminMessage("list-template-info", placeholders));
        }
    }

    private void deleteTemplate(CommandSender sender, String taskKey) {
        TaskTemplate template = plugin.getTaskManager().getTemplateByKey(taskKey);
        if (template == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("task_key", taskKey);
            MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getAdminMessage("delete-fail-not-found", placeholders));
            return;
        }

        // 使用回调获取实际执行结果
        plugin.getTaskManager().deleteTemplate(taskKey, success -> {
            if (success) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("task_key", taskKey);
                MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getAdminMessage("delete-success", placeholders));
            } else {
                MessageUtil.sendMiniMessage(sender, "<red>删除任务失败，请检查数据库连接");
            }
        });
    }

    private void rerollAllTasks(CommandSender sender, String target) {
        if (target.equalsIgnoreCase("all")) {
            // 强制刷新所有在线玩家的所有任务
            int count = plugin.getServer().getOnlinePlayers().size();
            if (count == 0) {
                MessageUtil.sendMiniMessage(sender, "<red>当前没有在线玩家");
                return;
            }

            plugin.getTaskManager().forceRerollAllPlayerTasks(true, success -> {
                if (success) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("count", String.valueOf(count));
                    MessageUtil.sendMiniMessage(sender, "<green>已为所有在线玩家强制刷新所有任务 (<yellow>{count}<green>人)", placeholders);
                } else {
                    MessageUtil.sendMiniMessage(sender, "<red>强制刷新任务失败，请检查数据库连接");
                }
            });
        } else {
            // 强制刷新指定玩家的所有任务
            Player targetPlayer = plugin.getServer().getPlayerExact(target);
            if (targetPlayer == null || !targetPlayer.isOnline()) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", target);
                MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getAdminMessage("reroll-fail-player-not-found", placeholders));
                return;
            }

            plugin.getTaskManager().forceRerollPlayerTasks(targetPlayer, true, success -> {
                if (success) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player", targetPlayer.getName());
                    MessageUtil.sendMiniMessage(sender, "<green>已为玩家 <yellow>{player} <green>强制刷新所有任务", placeholders);
                } else {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player", targetPlayer.getName());
                    MessageUtil.sendMiniMessage(sender, "<red>强制刷新 {player} 的任务失败", placeholders);
                }
            });
        }
    }

    private void rerollTasks(CommandSender sender, String target) {
        if (target.equalsIgnoreCase("all")) {
            // 重新抽取所有在线玩家
            int count = plugin.getServer().getOnlinePlayers().size();
            if (count == 0) {
                MessageUtil.sendMiniMessage(sender, "\u003cred\u003e当前没有在线玩家");
                return;
            }

            // 使用回调获取实际执行结果
            plugin.getTaskManager().rerollAllPlayerTasks(true, success -> {
                if (success) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("count", String.valueOf(count));
                    MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getAdminMessage("reroll-success-all", placeholders));
                } else {
                    MessageUtil.sendMiniMessage(sender, "<red>重新抽取任务失败，请检查数据库连接");
                }
            });
        } else {
            // 重新抽取指定玩家
            Player targetPlayer = plugin.getServer().getPlayerExact(target);
            if (targetPlayer == null || !targetPlayer.isOnline()) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", target);
                MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getAdminMessage("reroll-fail-player-not-found", placeholders));
                return;
            }

            // 使用回调获取实际执行结果
            plugin.getTaskManager().rerollPlayerTasks(targetPlayer, true, success -> {
                if (success) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player", targetPlayer.getName());
                    MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getAdminMessage("reroll-success-player", placeholders));
                } else {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player", targetPlayer.getName());
                    MessageUtil.sendMiniMessage(sender, "<red>重新抽取 {player} 的任务失败", placeholders);
                }
            });
        }
    }

    private void assignTask(CommandSender sender, String taskKey, String target) {
        TaskTemplate template = plugin.getTaskManager().getTemplateByKey(taskKey);
        if (template == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("task_key", taskKey);
            MessageUtil.sendMiniMessage(sender, "\u003cred\u003e未找到任务模板: \u003cyellow\u003e{task_key}", placeholders);
            return;
        }

        if (target.equalsIgnoreCase("all")) {
            // 给所有在线玩家添加任务
            List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
            if (players.isEmpty()) {
                MessageUtil.sendMiniMessage(sender, "\u003cred\u003e当前没有在线玩家");
                return;
            }

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger completedCount = new AtomicInteger(0);
            int total = players.size();

            for (Player player : players) {
                plugin.getTaskManager().assignTaskToPlayer(player, template, success -> {
                    if (success) {
                        successCount.incrementAndGet();
                    }
                    completedCount.incrementAndGet();

                    if (completedCount.get() == total) {
                        Map<String, String> placeholders = new HashMap<>();
                        placeholders.put("task_key", taskKey);
                        placeholders.put("count", String.valueOf(successCount.get()));
                        MessageUtil.sendMiniMessage(sender, "\u003cgreen\u003e成功为 \u003cyellow\u003e{count} \u003cgreen\u003e名玩家添加任务: \u003cyellow\u003e{task_key}", placeholders);
                    }
                });
            }
        } else {
            // 给指定玩家添加任务
            Player targetPlayer = plugin.getServer().getPlayerExact(target);
            if (targetPlayer == null || !targetPlayer.isOnline()) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", target);
                MessageUtil.sendMiniMessage(sender, "\u003cred\u003e找不到玩家: \u003cyellow\u003e{player}", placeholders);
                return;
            }

            plugin.getTaskManager().assignTaskToPlayer(targetPlayer, template, success -> {
                if (success) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player", targetPlayer.getName());
                    placeholders.put("task_key", taskKey);
                    MessageUtil.sendMiniMessage(sender, "\u003cgreen\u003e已为玩家 \u003cyellow\u003e{player} \u003cgreen\u003e添加任务: \u003cyellow\u003e{task_key}", placeholders);
                } else {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player", targetPlayer.getName());
                    MessageUtil.sendMiniMessage(sender, "\u003cred\u003e为 {player} 添加任务失败", placeholders);
                }
            });
        }
    }

    private void reloadFromDatabase(CommandSender sender) {
        MessageUtil.sendMiniMessage(sender, "\u003cyellow\u003e正在从数据库重新加载模板...");
        plugin.getTaskManager().getTemplateSyncManager().reloadFromDatabase(() -> {
            MessageUtil.sendMiniMessage(sender, "\u003cgreen\u003e模板已从数据库重新加载完成！");
        });
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.sendMiniMessage(sender, plugin.getConfigManager().getCommandMessage("help-title"));

        List<String> helpMessages;
        if (sender instanceof Player) {
            // 玩家显示玩家和管理员命令
            helpMessages = plugin.getConfigManager().getCommandHelpMessages("player");
            for (String msg : helpMessages) {
                MessageUtil.sendMiniMessage(sender, msg);
            }
            MessageUtil.sendMiniMessage(sender, "");
            helpMessages = plugin.getConfigManager().getCommandHelpMessages("admin");
            for (String msg : helpMessages) {
                MessageUtil.sendMiniMessage(sender, msg);
            }
            // 额外命令帮助
            MessageUtil.sendMiniMessage(sender, "\u003cyellow\u003e/taskadmin reloadconfig \u003cgray\u003e- 重新加载配置文件");
            MessageUtil.sendMiniMessage(sender, "\u003cyellow\u003e/taskadmin reloadfromdb \u003cgray\u003e- 从数据库重新加载模板");
            MessageUtil.sendMiniMessage(sender, "\u003cyellow\u003e/taskadmin reroll \u003c玩家名/all\u003e \u003cgray\u003e- 重新抽取每日任务");
            MessageUtil.sendMiniMessage(sender, "\u003cyellow\u003e/taskadmin rerollall \u003c玩家名/all\u003e \u003cgray\u003e- 强制刷新所有任务(无视完成状态)");
            MessageUtil.sendMiniMessage(sender, "\u003cyellow\u003e/taskadmin assign \u003c任务key\u003e \u003c玩家名/all\u003e \u003cgray\u003e- 给玩家添加指定任务");
        } else {
            // 控制台只显示管理员命令
            helpMessages = plugin.getConfigManager().getCommandHelpMessages("admin");
            for (String msg : helpMessages) {
                MessageUtil.sendMiniMessage(sender, msg);
            }
            // 额外命令帮助
            MessageUtil.sendMiniMessage(sender, "\u003cyellow\u003e/taskadmin reloadconfig \u003cgray\u003e- 重新加载配置文件");
            MessageUtil.sendMiniMessage(sender, "\u003cyellow\u003e/taskadmin reloadfromdb \u003cgray\u003e- 从数据库重新加载模板");
            MessageUtil.sendMiniMessage(sender, "\u003cyellow\u003e/taskadmin reroll \u003c玩家名/all\u003e \u003cgray\u003e- 重新抽取每日任务");
            MessageUtil.sendMiniMessage(sender, "\u003cyellow\u003e/taskadmin rerollall \u003c玩家名/all\u003e \u003cgray\u003e- 强制刷新所有任务(无视完成状态)");
            MessageUtil.sendMiniMessage(sender, "\u003cyellow\u003e/taskadmin assign \u003c任务key\u003e \u003c玩家名/all\u003e \u003cgray\u003e- 给玩家添加指定任务");
        }
    }
}
