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

        // Grant items - 满了直接drop到脚下
        // 注意：背包操作在主线程执行
        List<ItemStack> itemsToDrop = new ArrayList<>();
        for (RewardItem rewardItem : items) {
            ItemStack stack = ItemUtil.createItem(plugin, rewardItem.getItemKey(), rewardItem.getAmount());
            if (stack != null) {
                var leftover = player.getInventory().addItem(stack);
                if (!leftover.isEmpty()) {
                    itemsToDrop.addAll(leftover.values());
                }
            }
        }

        // drop物品必须在玩家所在区域的调度器中执行
        if (!itemsToDrop.isEmpty()) {
            // 获取玩家位置用于调度
            org.bukkit.Location location = player.getLocation();
            plugin.getServer().getRegionScheduler().execute(plugin, location, () -> {
                for (ItemStack dropStack : itemsToDrop) {
                    player.getWorld().dropItem(player.getLocation(), dropStack);
                }
            });
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

    private int calculateRequiredSlots(SimpleTaskPlugin plugin) {
        int slots = 0;
        for (RewardItem rewardItem : items) {
            ItemStack stack = ItemUtil.createItem(plugin, rewardItem.getItemKey(), 1);
            if (stack != null) {
                int maxStackSize = stack.getMaxStackSize();
                slots += (int) Math.ceil((double) rewardItem.getAmount() / maxStackSize);
            }
        }
        return slots;
    }

    private int getEmptySlots(Player player) {
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getItem(i) == null) {
                empty++;
            }
        }
        return empty;
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
