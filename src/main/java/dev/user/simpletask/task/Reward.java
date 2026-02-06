package dev.user.simpletask.task;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class Reward {

    private final double money;
    private final List<RewardItem> items;
    private final List<String> commands;

    public Reward(double money, List<RewardItem> items, List<String> commands) {
        this.money = money;
        this.items = items != null ? items : new ArrayList<>();
        this.commands = commands != null ? commands : new ArrayList<>();
    }

    public double getMoney() {
        return money;
    }

    public List<RewardItem> getItems() {
        return items;
    }

    public List<String> getCommands() {
        return commands;
    }

    public boolean hasMoney() {
        return money > 0;
    }

    public boolean hasItems() {
        return !items.isEmpty();
    }

    public boolean hasCommands() {
        return !commands.isEmpty();
    }

    public void grant(Player player, SimpleTaskPlugin plugin) {
        // Grant money (可以在异步线程执行)
        if (money > 0 && plugin.getEconomyManager().isEnabled()) {
            plugin.getEconomyManager().deposit(player, money);
        }

        // Grant items - 使用玩家实体调度器确保线程安全
        // 预先生成所有ItemStack，避免在调度器内访问外部状态
        List<ItemStack> itemStacks = new ArrayList<>();
        for (RewardItem rewardItem : items) {
            ItemStack stack = ItemUtil.createItem(plugin, rewardItem.getItemKey(), rewardItem.getAmount());
            if (stack != null) {
                itemStacks.add(stack);
            }
        }

        if (!itemStacks.isEmpty()) {
            player.getScheduler().execute(plugin, () -> {
                List<ItemStack> itemsToDrop = new ArrayList<>();
                for (ItemStack stack : itemStacks) {
                    var leftover = player.getInventory().addItem(stack);
                    if (!leftover.isEmpty()) {
                        itemsToDrop.addAll(leftover.values());
                    }
                }

                // 满的物品drop到脚下（仍在实体调度器中执行）
                for (ItemStack dropStack : itemsToDrop) {
                    player.getWorld().dropItem(player.getLocation(), dropStack);
                }
            }, () -> {
                // 玩家离线时的取消回调 - 物品将不会被给予
                plugin.getLogger().warning("Player " + player.getName() + " went offline before reward items could be granted");
            }, 0L); // 立即执行，无延迟
        }

        // Execute commands - 使用GlobalRegionScheduler
        if (!commands.isEmpty()) {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                for (String command : commands) {
                    String formatted = command.replace("{player}", player.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formatted);
                }
            });
        }
    }

    public String getDisplayString(SimpleTaskPlugin plugin) {
        StringBuilder sb = new StringBuilder();

        if (money > 0) {
            sb.append(money).append(" 金币");
        }

        for (RewardItem item : items) {
            if (sb.length() > 0) sb.append(", ");
            ItemStack stack = ItemUtil.createItem(plugin, item.getItemKey(), 1);
            String name = stack != null ? ItemUtil.getDisplayName(stack) : item.getItemKey();
            sb.append(item.getAmount()).append("x ").append(name);
        }

        return sb.toString();
    }

    // JSON 序列化支持
    public String toJson() {
        return "{\"money\":" + money +
               ",\"items\":" + itemsToJson() +
               ",\"commands\":" + commandsToJson() + "}";
    }

    private String itemsToJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            RewardItem item = items.get(i);
            sb.append("{\"itemKey\":\"").append(item.getItemKey())
              .append("\",\"amount\":").append(item.getAmount()).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String commandsToJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < commands.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(commands.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    public static class RewardItem {
        private String itemKey;
        private int amount;

        // 默认构造函数（用于JSON反序列化）
        public RewardItem() {}

        public RewardItem(String itemKey, int amount) {
            this.itemKey = itemKey;
            this.amount = amount;
        }

        public String getItemKey() {
            return itemKey;
        }

        public int getAmount() {
            return amount;
        }
    }
}
