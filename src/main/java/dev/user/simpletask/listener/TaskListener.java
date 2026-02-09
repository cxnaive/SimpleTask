package dev.user.simpletask.listener;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.TaskManager;
import dev.user.simpletask.task.TaskType;
import dev.user.simpletask.util.ItemUtil;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.inventory.ItemStack;

public class TaskListener implements Listener {

    private final SimpleTaskPlugin plugin;
    private final TaskManager taskManager;

    public TaskListener(SimpleTaskPlugin plugin) {
        this.plugin = plugin;
        this.taskManager = plugin.getTaskManager();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Load player tasks (internally handled asynchronously)
        taskManager.loadPlayerTasks(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        taskManager.clearPlayerCache(event.getPlayer().getUniqueId());
    }

    // 使用 HIGH 优先级，在 PlayerChat (HIGHEST) 之前捕获
    // 不忽略已取消的事件，因为我们要在 PlayerChat 取消它之前处理
    @EventHandler(priority = EventPriority.HIGH)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        plugin.getLogger().fine("[TaskListener] AsyncPlayerChatEvent triggered for " + player.getName() + ": " + message);
        taskManager.updateProgress(player, TaskType.CHAT, message, 1);
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack result = event.getRecipe().getResult();
        if (result == null || result.getType() == Material.AIR) {
            return;
        }

        String itemKey = ItemUtil.getItemKey(result);
        if (itemKey == null) {
            itemKey = "minecraft:" + result.getType().name().toLowerCase();
        }

        // Calculate actual amount crafted (considering shift-click)
        int amount = result.getAmount();
        if (event.isShiftClick()) {
            // Estimate the maximum amount that can be crafted
            amount = Math.min(amount * 64, result.getMaxStackSize());
        }

        // 注意：合成结果物品的NBT在CraftItemEvent时还未应用到玩家背包
        // 这里使用配方结果物品的NBT（如果有）
        final int finalAmount = amount;
        final String finalItemKey = itemKey;
        final ItemStack finalResult = result.clone();

        // updateProgress handles its own async database operations
        taskManager.updateProgress(player, TaskType.CRAFT, finalItemKey, finalResult, finalAmount);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }

        Player player = event.getPlayer();
        Entity caught = event.getCaught();

        if (!(caught instanceof Item caughtItem)) {
            return;
        }

        // 获取钓获物品的 key 和 ItemStack
        ItemStack caughtStack = caughtItem.getItemStack();
        String itemKey = ItemUtil.getItemKey(caughtStack);

        // updateProgress handles its own async database operations
        taskManager.updateProgress(player, TaskType.FISH, itemKey, caughtStack, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        String itemKey = ItemUtil.getItemKey(item);
        if (itemKey == null) {
            itemKey = "minecraft:" + item.getType().name().toLowerCase();
        }

        final String finalItemKey = itemKey;
        final ItemStack finalItem = item.clone();

        // updateProgress handles its own async database operations
        taskManager.updateProgress(player, TaskType.CONSUME, finalItemKey, finalItem, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        // Get item key for the block (support CE custom blocks)
        String itemKey = ItemUtil.getBlockKey(block);

        // 检查是否是可收获的成熟作物
        boolean isHarvestable = isHarvestableCrop(block) && isFullyGrown(block);

        // 如果是成熟作物，同时触发 HARVEST 和 BREAK
        // 例如：破坏成熟的土豆 -> 触发"收获土豆"(HARVEST) + "挖掘泥土"(BREAK)
        if (isHarvestable) {
            taskManager.updateProgress(player, TaskType.HARVEST, itemKey, 1);
        }

        // BREAK 类型：先检查是否是玩家自己放置的（防刷检测），再清除记录
        boolean isPlayerPlaced = plugin.getAntiCheatManager().isPlayerPlacedBlock(block.getLocation());

        // 清除防刷记录（无论谁破坏的，都清除该位置的记录）
        plugin.getAntiCheatManager().removeBlockRecord(block.getLocation());

        // 如果不是玩家放置的，才计入 BREAK 任务进度
        if (!isPlayerPlaced) {
            taskManager.updateProgress(player, TaskType.BREAK, itemKey, 1);
        } else {
            plugin.getLogger().fine("[AntiCheat] Block break at " + block.getLocation() + " ignored for BREAK task (player placed)");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        // 记录玩家放置的方块，用于防刷检测
        plugin.getAntiCheatManager().recordBlockPlace(event.getBlock().getLocation());
    }

    private boolean isHarvestableCrop(Block block) {
        Material type = block.getType();
        return switch (type) {
            // 带 Ageable 的作物
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA -> true;
            // 成熟果实方块（无 Ageable，出现时即为成熟）
            case PUMPKIN, MELON -> true;
            // 其他可收获植物
            case SUGAR_CANE, CACTUS -> true;  // 竹子和甘蔗类
            case BAMBOO -> true;  // 竹子（成熟后可以被收获）
            case SWEET_BERRY_BUSH -> true;  // 甜浆果丛
            case CAVE_VINES -> true;  // 发光浆果
            default -> false;
        };
    }

    private boolean isFullyGrown(Block block) {
        Material type = block.getType();

        // 无 Ageable 的成熟果实方块，出现时即为成熟
        if (type == Material.PUMPKIN || type == Material.MELON ||
            type == Material.SUGAR_CANE || type == Material.CACTUS ||
            type == Material.BAMBOO) {
            return true;
        }

        // 发光浆果 - 判断是否有浆果
        if (type == Material.CAVE_VINES) {
            if (block.getBlockData() instanceof org.bukkit.block.data.type.CaveVines caveVines) {
                return caveVines.isBerries();
            }
            return false;
        }

        // 甜浆果丛 - Ageable，最大年龄为 3
        if (type == Material.SWEET_BERRY_BUSH) {
            if (block.getBlockData() instanceof Ageable ageable) {
                return ageable.getAge() >= 2; // 2 或 3 都有浆果
            }
            return false;
        }

        // 标准 Ageable 作物
        if (block.getBlockData() instanceof Ageable ageable) {
            return ageable.getAge() == ageable.getMaximumAge();
        }

        // 默认为不成熟（安全起见）
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        // 获取击杀者
        if (!(event.getEntity().getKiller() instanceof Player player)) {
            return;
        }

        // 获取实体类型 key (格式: minecraft:zombie)
        String entityKey = event.getEntity().getType().getKey().toString();

        // 更新击杀任务进度
        taskManager.updateProgress(player, TaskType.KILL, entityKey, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityBreed(EntityBreedEvent event) {
        // 获取繁殖者（喂食物的玩家）
        if (!(event.getBreeder() instanceof Player player)) {
            return;
        }

        // 获取繁殖出的实体类型 key
        String entityKey = event.getEntity().getType().getKey().toString();

        // 更新繁殖任务进度
        taskManager.updateProgress(player, TaskType.BREED, entityKey, 1);
    }
}
