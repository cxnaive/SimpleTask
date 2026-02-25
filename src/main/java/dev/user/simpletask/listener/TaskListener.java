package dev.user.simpletask.listener;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.TaskManager;
import dev.user.simpletask.task.TaskType;
import dev.user.simpletask.util.ItemUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.inventory.ItemStack;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class TaskListener implements Listener {

    private final SimpleTaskPlugin plugin;
    private final TaskManager taskManager;

    // 临时存储玩家破坏的方块信息（用于 BlockDropItemEvent）
    // Key: playerUUID + location, Value: BlockBreakInfo
    // 5秒自动过期，防止内存泄漏
    private final Cache<String, BlockBreakInfo> brokenBlockInfo = CacheBuilder.newBuilder()
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .build();

    private record BlockBreakInfo(BlockState state, boolean isPlayerPlaced, long tick) {}

    // ===== 堆叠作物处理去重缓存 =====
    // Key: playerUUID + location + tick分组, Value: 已处理标记
    private final Cache<String, Boolean> stackableCropProcessed = CacheBuilder.newBuilder()
            .expireAfterWrite(100, TimeUnit.MILLISECONDS) // 100ms = 约2 ticks
            .build();

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

        int singleCraftAmount = result.getAmount();
        int totalAcquired;

        if (event.isShiftClick()) {
            // Shift+点击：计算实际合成数量

            // 1. 计算合成矩阵中的材料瓶颈（按槽位取最小）
            int maxCraftableByIngredients = Integer.MAX_VALUE;
            for (ItemStack item : event.getInventory().getMatrix()) {
                if (item != null && item.getType() != Material.AIR) {
                    maxCraftableByIngredients = Math.min(maxCraftableByIngredients, item.getAmount());
                }
            }
            if (maxCraftableByIngredients == Integer.MAX_VALUE) {
                maxCraftableByIngredients = 1;
            }

            // 2. 计算玩家背包还能装下多少个该物品
            int availableSpace = 0;
            for (ItemStack item : player.getInventory().getStorageContents()) {
                if (item == null || item.getType() == Material.AIR) {
                    // 空槽位可以放满一整组
                    availableSpace += result.getMaxStackSize();
                } else if (item.isSimilar(result)) {
                    // 相似物品可以堆叠，计算剩余空间
                    availableSpace += (result.getMaxStackSize() - item.getAmount());
                }
            }

            // 3. 计算实际合成的次数
            // 将可用空间除以单次产出量，得出背包能承受的合成次数
            int maxCraftableBySpace = availableSpace / singleCraftAmount;
            int actualCrafts = Math.min(maxCraftableByIngredients, maxCraftableBySpace);

            // 4. 最终实际获得的物品总数
            totalAcquired = actualCrafts * singleCraftAmount;
        } else {
            // 普通点击合成
            totalAcquired = singleCraftAmount;
        }

        if (totalAcquired <= 0) return;

        final int finalAmount = totalAcquired;
        final String finalItemKey = itemKey;
        final ItemStack finalResult = result.clone();

        // updateProgress handles its own async database operations
        taskManager.updateProgress(player, TaskType.CRAFT, finalItemKey, finalResult, finalAmount);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmithItem(SmithItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // 获取锻造结果
        ItemStack result = event.getInventory().getResult();
        if (result == null || result.getType() == Material.AIR) {
            return;
        }

        String itemKey = ItemUtil.getItemKey(result);
        if (itemKey == null) {
            itemKey = "minecraft:" + result.getType().name().toLowerCase();
        }

        // 锻造台一次只能合成1个物品
        final String finalItemKey = itemKey;
        final ItemStack finalResult = result.clone();

        // updateProgress handles its own async database operations
        taskManager.updateProgress(player, TaskType.CRAFT, finalItemKey, finalResult, 1);
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
    public void onPlayerInteract(PlayerInteractEvent event) {
        // 只处理右键点击方块
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;

        Block block = event.getClickedBlock();
        Material type = block.getType();

        // 检查是否是蛋糕
        if (!isCake(type)) return;

        Player player = event.getPlayer();

        // 检查玩家是否可以食用蛋糕
        if (!canEatCake(player)) return;

        // 获取蛋糕的 item key
        String itemKey = ItemUtil.getBlockKey(block);
        if (itemKey == null) {
            itemKey = "minecraft:" + type.name().toLowerCase();
        }

        // 更新 CONSUME 任务进度
        taskManager.updateProgress(player, TaskType.CONSUME, itemKey, null, 1);
    }

    /**
     * 检查是否是蛋糕（支持所有蛋糕类型）
     */
    private boolean isCake(Material type) {
        return type == Material.CAKE ||
               type.name().endsWith("_CAKE");  // 覆盖蜡烛蛋糕等变种
    }

    /**
     * 检查玩家是否可以食用蛋糕
     */
    private boolean canEatCake(Player player) {
        // 创造模式总是可以"吃"（不会减少饥饿值，但会触发动作）
        if (player.getGameMode() == GameMode.CREATIVE) return true;

        // 检查饥饿值是否已满（20点满）
        if (player.getFoodLevel() >= 20) return false;

        // 检查是否潜行并手持方块（此时会尝试放置方块而不是吃蛋糕）
        if (player.isSneaking() && player.getInventory().getItemInMainHand().getType().isBlock()) {
            return false;
        }

        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material type = block.getType();

        // Get item key for the block (support CE custom blocks)
        String itemKey = ItemUtil.getBlockKey(block);

        // 检查是否是玩家自己放置的（防刷检测）
        boolean isPlayerPlaced = plugin.getAntiCheatManager().isPlayerPlacedBlock(block.getLocation());

        // 获取当前 tick 用于去重
        long currentTick = player.getWorld().getFullTime();

        // 保存方块信息供 BlockDropItemEvent 使用（在清除防刷记录之前）
        saveBlockBreakInfo(player, block, isPlayerPlaced, currentTick);
        if (!isPlayerPlaced) {
            taskManager.updateProgress(player, TaskType.BREAK, itemKey, 1);
        }

        // 处理堆叠作物（竹子、甘蔗、仙人掌）的 HARVEST 任务
        // 这些作物破坏底部时上方会连锁掉落，需要特殊处理
        if (isStackableCrop(type)) {
            // 检查是否已在同一 tick 处理过这个位置（防止重复计算）
            String dedupKey = getStackableCropDedupKey(player.getUniqueId(), block.getLocation(), currentTick);
            if (stackableCropProcessed.getIfPresent(dedupKey) == null) {
                stackableCropProcessed.put(dedupKey, true);
                handleStackableCropHarvest(player, block, type, currentTick);
            }
        }

        // 清除防刷记录（所有检测完成后）
        plugin.getAntiCheatManager().removeBlockRecord(block.getLocation());
    }

    /**
     * 检查是否是堆叠生长的作物
     */
    private boolean isStackableCrop(Material type) {
        return type == Material.BAMBOO || type == Material.SUGAR_CANE || type == Material.CACTUS;
    }

    /**
     * 处理堆叠作物的 HARVEST 任务（带 tick 去重）
     * 向上扫描相同类型的方块，计算总数量（排除玩家放置的）
     */
    private void handleStackableCropHarvest(Player player, Block block, Material type, long currentTick) {
        int totalCount = 0;
        Block current = block;

        // 向上扫描相同类型的方块
        while (current.getType() == type) {
            Location loc = current.getLocation();

            // 检查是否在此 tick 已处理过这个方块（防止快速连击重复计算）
            String blockDedupKey = player.getUniqueId().toString() + "@" + loc.toString() + "#" + (currentTick / 2);
            if (stackableCropProcessed.getIfPresent(blockDedupKey) != null) {
                current = current.getRelative(0, 1, 0);
                continue;
            }
            stackableCropProcessed.put(blockDedupKey, true);

            boolean isPlayerPlaced = plugin.getAntiCheatManager().isPlayerPlacedBlock(loc);

            if (!isPlayerPlaced) {
                totalCount++;
            }

            // 清除防刷记录
            plugin.getAntiCheatManager().removeBlockRecord(loc);

            // 向上检查
            current = current.getRelative(0, 1, 0);
        }

        if (totalCount > 0) {
            // 堆叠作物掉落物与方块类型相同
            String itemKey = "minecraft:" + type.name().toLowerCase();
            taskManager.updateProgress(player, TaskType.HARVEST, itemKey, null, totalCount);
        }
    }

    /**
     * 保存方块信息供后续事件使用
     */
    private void saveBlockBreakInfo(Player player, Block block, boolean isPlayerPlaced, long tick) {
        BlockState state = block.getState();
        String key = getBlockKey(player.getUniqueId(), block.getLocation());
        brokenBlockInfo.put(key, new BlockBreakInfo(state, isPlayerPlaced, tick));
    }

    /**
     * 生成堆叠作物去重键
     */
    private String getStackableCropDedupKey(UUID playerUuid, Location location, long tick) {
        return playerUuid.toString() + "@" + location.getWorld().getName() + ":"
            + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ()
            + "#" + tick; // 使用单 tick，精确去重
    }

    private String getBlockKey(UUID playerUuid, Location location) {
        return playerUuid.toString() + "@" + location.getWorld().getName() + ":" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    private BlockBreakInfo getBlockBreakInfo(Player player, Location location) {
        String key = getBlockKey(player.getUniqueId(), location);
        BlockBreakInfo info = brokenBlockInfo.getIfPresent(key);
        if (info != null) {
            brokenBlockInfo.invalidate(key); // 获取后移除
        }
        return info;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        Block block = event.getBlock();
        Location loc = block.getLocation();

        // 获取保存的方块信息（BlockBreakEvent中保存的）
        BlockBreakInfo info = getBlockBreakInfo(player, loc);
        if (info == null) return;

        // 检查tick一致性，防止竞态条件（允许当前tick或上一tick）
        long currentTick = player.getWorld().getFullTime();
        if (info.tick() != currentTick && info.tick() != currentTick - 1) {
            // 过旧的缓存数据，忽略
            return;
        }

        BlockState state = info.state();
        boolean wasPlayerPlaced = info.isPlayerPlaced();
        Material type = state.getType();

        // 1. 检查是否是 HARVEST 目标作物
        if (!isHarvestableCrop(type)) return;

        // 堆叠作物（竹子、甘蔗、仙人掌）已在 BlockBreakEvent 中处理
        if (isStackableCrop(type)) return;

        // 2. 检查是否成熟（使用BlockState中的数据）
        if (!isFullyGrown(state)) return;

        // 3. 南瓜、西瓜需要防刷检测（可被精准采集放置）
        if ((type == Material.PUMPKIN || type == Material.MELON) && wasPlayerPlaced) {
            plugin.getLogger().fine("[AntiCheat] Harvest at " + loc + " ignored (player placed pumpkin/melon)");
            return;
        }

        // 4. 处理掉落物，按物品类型累加后批量更新（避免多次调用导致重复发放奖励）
        Map<String, Integer> itemCounts = new HashMap<>();
        Map<String, ItemStack> itemSamples = new HashMap<>();

        for (org.bukkit.entity.Item drop : event.getItems()) {
            ItemStack itemStack = drop.getItemStack();
            String itemKey = ItemUtil.getItemKey(itemStack);
            if (itemKey == null) {
                itemKey = "minecraft:" + itemStack.getType().name().toLowerCase();
            }
            itemCounts.merge(itemKey, itemStack.getAmount(), Integer::sum);
            itemSamples.putIfAbsent(itemKey, itemStack);
        }

        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            String itemKey = entry.getKey();
            int totalAmount = entry.getValue();
            ItemStack sampleItem = itemSamples.get(itemKey);

            taskManager.updateProgress(player, TaskType.HARVEST, itemKey, sampleItem, totalAmount);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHarvestBlock(PlayerHarvestBlockEvent event) {
        Player player = event.getPlayer();
        Block block = event.getHarvestedBlock();
        Material type = block.getType();

        // 只处理浆果类作物
        if (type != Material.SWEET_BERRY_BUSH && type != Material.CAVE_VINES) return;

        // 处理掉落物，按物品类型累加后批量更新（避免多次调用导致重复发放奖励）
        Map<String, Integer> itemCounts = new HashMap<>();
        Map<String, ItemStack> itemSamples = new HashMap<>();

        for (ItemStack itemStack : event.getItemsHarvested()) {
            String itemKey = ItemUtil.getItemKey(itemStack);
            if (itemKey == null) {
                itemKey = "minecraft:" + itemStack.getType().name().toLowerCase();
            }
            itemCounts.merge(itemKey, itemStack.getAmount(), Integer::sum);
            itemSamples.putIfAbsent(itemKey, itemStack);
        }

        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            String itemKey = entry.getKey();
            int totalAmount = entry.getValue();
            ItemStack sampleItem = itemSamples.get(itemKey);

            taskManager.updateProgress(player, TaskType.HARVEST, itemKey, sampleItem, totalAmount);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        // 记录玩家放置的方块，用于防刷检测
        Location loc = event.getBlock().getLocation();
        plugin.getAntiCheatManager().recordBlockPlace(loc);
    }

    private boolean isHarvestableCrop(Material type) {
        return switch (type) {
            // 带 Ageable 的作物
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA -> true;
            // 成熟果实方块（无 Ageable，出现时即为成熟）
            case PUMPKIN, MELON -> true;
            // 其他可收获植物（堆叠作物在BlockBreakEvent中处理）
            case SUGAR_CANE, CACTUS -> true;  // 甘蔗和仙人掌
            case BAMBOO, BAMBOO_SAPLING -> true;  // 竹子（包括竹笋阶段）
            // 注意：甜浆果丛(SWEET_BERRY_BUSH)和发光浆果(CAVE_VINES)由PlayerHarvestBlockEvent处理
            default -> false;
        };
    }

    private boolean isFullyGrown(BlockState state) {
        Material type = state.getType();

        // 无 Ageable 的成熟果实方块，出现时即为成熟
        if (type == Material.PUMPKIN || type == Material.MELON ||
            type == Material.SUGAR_CANE || type == Material.CACTUS ||
            type == Material.BAMBOO) {
            return true;
        }

        // 竹笋（BAMBOO_SAPLING）未成熟，不能计入 HARVEST
        if (type == Material.BAMBOO_SAPLING) {
            return false;
        }

        // 注意：甜浆果丛(SWEET_BERRY_BUSH)和发光浆果(CAVE_VINES)由PlayerHarvestBlockEvent处理
        // 不需要在这里检查成熟度

        // 标准 Ageable 作物（小麦、胡萝卜、土豆等）
        if (state.getBlockData() instanceof Ageable ageable) {
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String fullCommand = event.getMessage();
        // 提取完整命令（去除 / 前缀，保留参数用于前缀匹配）
        String fullCommandName = fullCommand.substring(1).toLowerCase();

        plugin.getLogger().info("[CommandTask] " + player.getName() + " executed: " + fullCommandName);

        // 更新命令任务进度
        taskManager.updateProgress(player, TaskType.COMMAND, fullCommandName, 1);
    }
}
