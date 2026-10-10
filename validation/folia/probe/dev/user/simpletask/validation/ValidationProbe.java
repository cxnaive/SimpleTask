package dev.user.simpletask.validation;

import com.google.gson.Gson;
import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.PlayerTask;
import dev.user.simpletask.task.TaskTemplate;
import dev.user.simpletask.task.TaskType;
import dev.user.simpletask.task.Reward;
import dev.user.simpletask.util.ItemUtil;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.*;
import org.bukkit.damage.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Isolated validation only. Event fixtures do not pretend to be real client actions. */
public class ValidationProbe extends JavaPlugin implements CommandExecutor {
    private final Gson gson = new Gson();
    private SimpleTaskPlugin task;
    private PlaceholderExpansion expansion;
    private final Map<String, Integer> commandRewards = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Location> contentBlocks = new HashMap<>();

    @Override public void onEnable() {
        task = (SimpleTaskPlugin) Bukkit.getPluginManager().getPlugin("SimpleTask");
        getCommand("stprobe").setExecutor(this);
        getCommand("stprobe_reward").setExecutor((sender, command, label, args) -> {
            if (sender instanceof ConsoleCommandSender && args.length == 1) commandRewards.merge(args[0], 1, Integer::sum);
            return true;
        });
        getCommand("stprobe_noop").setExecutor((sender, command, label, args) -> true);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            expansion = new PlaceholderExpansion() {
                @Override public String getIdentifier() { return "stqa"; }
                @Override public String getAuthor() { return "SimpleTask validation"; }
                @Override public String getVersion() { return "1"; }
                @Override public boolean persist() { return true; }
                @Override public String onPlaceholderRequest(Player player, String params) {
                    if (!Bukkit.isOwnedByCurrentRegion(player)) {
                        throw new IllegalStateException("PAPI read outside player region");
                    }
                    return String.valueOf(player.getLevel());
                }
            };
            expansion.register();
        }
    }

    @Override public void onDisable() {
        if (expansion != null) expansion.unregister();
    }

    private void report(String id, Object value) {
        try {
            Path dir = getDataFolder().toPath().resolve("evidence");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(id + ".json"), gson.toJson(value));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length < 3) return false;
        String id = args[0], action = args[1];
        if (!id.matches("[a-zA-Z0-9_-]+")) return false;
        Player player = Bukkit.getPlayerExact(args[2]);
        if (player == null) { report(id, Map.of("error", "player offline")); return true; }
        player.getScheduler().execute(this, () -> {
            try {
                player.setInvulnerable(true);
                if (action.equals("content-task")) {
                    player.closeInventory();
                    task.getConfigManager().getTaskCategory("content").setEnabled(false);
                    TaskTemplate template = new TaskTemplate("qa_content", "qa_content", TaskType.valueOf(args[3]),
                            Arrays.asList(args[4].split(",")), args[3].equals("SUBMIT") ? 1 : 10000,
                            List.of(), "minecraft:paper", 10, new Reward(0, List.of(), List.of()));
                    template.setCategory("content");
                    if (args.length > 5) template.setNbtMatchConditions(List.of(args[5]));
                    task.getDatabaseQueue().submit("validationContentReset", conn -> {
                        try (var ps = conn.prepareStatement("DELETE FROM player_daily_tasks WHERE player_uuid=? AND category='content'")) {
                            ps.setString(1, player.getUniqueId().toString()); return ps.executeUpdate();
                        }
                    }, count -> {
                        task.getTaskManager().updateCategoryTaskCache(player.getUniqueId(), "content", List.of());
                        task.getTaskManager().importTemplates(List.of(template));
                        task.getTaskManager().getTemplateSyncManager().reloadFromDatabase(() ->
                                player.getScheduler().execute(this, () -> {
                                    task.getConfigManager().getTaskCategory("content").setEnabled(true);
                                    task.getTaskManager().assignTaskToCategory(player, "content", template,
                                            success -> report(id, success ? Map.of("assigned", template.getType().name()) : Map.of("error", "assignment failed")));
                                }, () -> report(id, Map.of("error", "player retired")), 1L));
                    }, error -> report(id, Map.of("error", error.toString())));
                    return;
                }
                if (action.equals("age")) {
                    var category = task.getConfigManager().getTaskCategory(args[3]);
                    var policy = dev.user.simpletask.task.ExpirePolicy.valueOf(args[4].toUpperCase());
                    category.setExpirePolicy(policy);
                    category.setDefaultDuration(java.time.Duration.ofHours(1));
                    if (policy == dev.user.simpletask.task.ExpirePolicy.FIXED) {
                        category.setFixedStart("2000-01-01T00:00:00"); category.setFixedEnd("2001-01-01T00:00:00");
                    }
                    var old = dev.user.simpletask.util.TimeZoneConfig.now().minusYears(2).withNano(0);
                    task.getDatabaseQueue().submit("validationAge", conn -> {
                        try (var ps = conn.prepareStatement("UPDATE player_daily_tasks SET assigned_at=?,current_progress=1,completed=TRUE WHERE player_uuid=? AND category=?")) {
                            ps.setTimestamp(1, Timestamp.from(dev.user.simpletask.util.TimeZoneConfig.toInstant(old)), dev.user.simpletask.util.TimeZoneConfig.UTC_CALENDAR);
                            ps.setString(2, player.getUniqueId().toString()); ps.setString(3, args[3]); return ps.executeUpdate();
                        }
                    }, count -> {
                        var aged = task.getTaskManager().getPlayerTasksByCategory(player.getUniqueId(), args[3]).stream()
                                .map(t -> new PlayerTask(player.getUniqueId(), t.getTaskKey(), t.getTemplate(), 1, true, false, old, args[3])).toList();
                        task.getTaskManager().updateCategoryTaskCache(player.getUniqueId(), args[3], aged);
                        report(id, Map.of("aged", count, "policy", policy.name()));
                    }, error -> report(id, Map.of("error", error.toString())));
                    return;
                }
                Object result = switch (action) {
                    case "status" -> status(player);
                    case "level" -> { player.setLevel(Integer.parseInt(args[3])); yield Map.of("level", player.getLevel()); }
                    case "give" -> {
                        var stack = ItemUtil.createItem(task, args[3], Integer.parseInt(args[4]));
                        if (args.length > 5) ItemUtil.applyComponents(stack, Map.of("minecraft:custom_data.qa", args[5]));
                        player.getInventory().addItem(stack);
                        yield Map.of("given", ItemUtil.getItemKey(stack));
                    }
                    case "clear" -> { player.getInventory().clear(); yield Map.of("cleared", true); }
                    case "nbt" -> nbt();
                    case "fill" -> {
                        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
                        yield Map.of("full", true);
                    }
                    case "event" -> event(player, args[3]);
                    case "registry" -> registry(args[3], args[4]);
                    case "prepare-block" -> prepareBlock(player, args[3], args.length > 4 ? args[4] : "natural");
                    case "block-state" -> {
                        Block block = contentBlocks.get(player.getUniqueId()).getBlock();
                        yield Map.of("type", block.getType().name(), "data", block.getBlockData().getAsString());
                    }
                    case "prepare-food" -> {
                        player.closeInventory(); player.getInventory().clear();
                        player.setGameMode(GameMode.SURVIVAL); player.setFoodLevel(Integer.parseInt(args[4]));
                        ItemStack item = ItemUtil.createItem(task, args[3], 1);
                        if (args.length > 5 && args[5].equals("component")) {
                            item.setData(DataComponentTypes.FOOD, FoodProperties.food().nutrition(2).saturation(0.1f).canAlwaysEat(true).build());
                            item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable().consumeSeconds(0.1f).build());
                        }
                        player.getInventory().setItemInMainHand(item);
                        yield Map.of("key", ItemUtil.getItemKey(item), "food", player.getFoodLevel());
                    }
                    case "content-event" -> contentEvent(player, args[3], args.length > 4 ? args[4] : "minecraft:air");
                    case "ce" -> ce(player, args[3]);
                    default -> throw new IllegalArgumentException("Unknown probe action: " + action);
                };
                if (action.equals("status")) {
                    @SuppressWarnings("unchecked") Map<String, Object> snapshot = (Map<String, Object>) result;
                    task.getDatabaseQueue().submit("validationSnapshot", conn -> {
                        List<Map<String, Object>> rows = new ArrayList<>();
                        try (var ps = conn.prepareStatement("SELECT task_key,current_progress,completed,claimed,assigned_at FROM player_daily_tasks WHERE player_uuid=? ORDER BY task_key")) {
                            ps.setString(1, player.getUniqueId().toString());
                            try (var rs = ps.executeQuery()) {
                                while (rs.next()) rows.add(Map.of("key", rs.getString(1), "progress", rs.getInt(2),
                                        "completed", rs.getBoolean(3), "claimed", rs.getBoolean(4), "assigned", rs.getTimestamp(5).toString()));
                            }
                        }
                        List<Map<String, Object>> rerolls = new ArrayList<>();
                        try (var ps = conn.prepareStatement("SELECT category_id,reroll_count FROM player_category_reroll WHERE player_uuid=?")) {
                            ps.setString(1, player.getUniqueId().toString());
                            try (var rs = ps.executeQuery()) {
                                while (rs.next()) rerolls.add(Map.of("category", rs.getString(1), "count", rs.getInt(2)));
                            }
                        }
                        return Map.of("database", rows, "rerolls", rerolls);
                    }, rows -> { snapshot.putAll(rows); report(id, snapshot); },
                            error -> report(id, Map.of("error", error.toString())));
                } else report(id, result);
            } catch (Throwable e) {
                report(id, Map.of("error", e.toString()));
                getLogger().log(java.util.logging.Level.SEVERE, "Probe " + action + " failed", e);
            }
        }, () -> report(id, Map.of("error", "player retired")), 1L);
        return true;
    }

    private Map<String, Object> status(Player player) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ownerThread", Bukkit.isOwnedByCurrentRegion(player));
        result.put("balance", task.getEconomyManager().getBalance(player));
        result.put("level", player.getLevel());
        result.put("food", player.getFoodLevel());
        result.put("invulnerable", player.isInvulnerable());
        result.put("cakeSlices", player.getStatistic(Statistic.CAKE_SLICES_EATEN));
        InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
        result.put("gui", holder == null ? "none" : holder.getClass().getSimpleName());
        result.put("commandRewards", commandRewards.getOrDefault(player.getName(), 0));
        result.put("expiryPolicy", task.getConfigManager().getTaskCategory("expiry").getExpirePolicy().name());
        result.put("apiInitialized", dev.user.simpletask.api.TaskAPI.isInitialized());
        result.put("apiTaskCount", dev.user.simpletask.api.TaskAPI.getPlayerTasks(player.getUniqueId()).size());
        result.put("templates", task.getTaskManager().getAllTemplates().stream().map(t -> t.getTaskKey()).sorted().toList());
        result.put("tasks", task.getTaskManager().getPlayerTasks(player.getUniqueId()).stream().map(t ->
                Map.of("key", t.getTaskKey(), "progress", t.getCurrentProgress(), "completed", t.isCompleted(),
                        "claimed", t.isClaimed(), "category", t.getCategory())).toList());
        Map<String, Integer> inventory = new TreeMap<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir()) inventory.merge(ItemUtil.getItemKey(item), item.getAmount(), Integer::sum);
        }
        result.put("inventory", inventory);
        result.put("droppedEmeralds", player.getNearbyEntities(4, 4, 4).stream().filter(Item.class::isInstance)
                .map(Item.class::cast).filter(item -> item.getItemStack().getType() == Material.EMERALD)
                .mapToInt(item -> item.getItemStack().getAmount()).sum());
        return result;
    }

    private Map<String, Object> nbt() {
        ItemStack item = new ItemStack(Material.DIAMOND);
        ItemUtil.applyComponents(item, Map.of("minecraft:custom_data.qa", "7"));
        return Map.of("exists", ItemUtil.matchesNbtCondition(item, "minecraft:custom_data.qa+exists"),
                "numeric", ItemUtil.matchesNbtCondition(item, "minecraft:custom_data.qa>=7"),
                "exact", ItemUtil.matchesNbtCondition(item, "minecraft:custom_data.qa+7"),
                "wrong", ItemUtil.matchesNbtCondition(item, "minecraft:custom_data.qa+8"),
                "key", ItemUtil.getItemKey(item), "name", ItemUtil.getDisplayNameComponent(item).toString());
    }

    private Object ce(Player player, String key) {
        ItemStack item = ItemUtil.createItem(task, key, 2);
        Map<String, Object> result = new LinkedHashMap<>(Map.of("available", ItemUtil.isCEAvailable(), "key", ItemUtil.getItemKey(item),
                "material", item.getType().name(), "amount", item.getAmount()));
        if (ItemUtil.isCEAvailable()) {
            Block block = player.getLocation().add(2, 0, 0).getBlock();
            boolean placed = net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.place(block.getLocation(),
                    net.momirealms.craftengine.core.util.Key.of("default:topaz_ore"), false);
            result.put("blockPlaced", placed); result.put("blockKey", ItemUtil.getBlockKey(block));
            net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.remove(block);
        }
        return result;
    }

    private Object event(Player player, String type) {
        boolean cancelled = type.endsWith("-cancelled");
        String base = type.replace("-cancelled", "");
        Location location = player.getLocation().add(2, 0, 0);
        Block block = location.getBlock();
        switch (base) {
            case "break", "placed" -> {
                block.setType(Material.STONE);
                if (base.equals("placed")) Bukkit.getPluginManager().callEvent(new BlockPlaceEvent(block, block.getState(),
                        block.getRelative(0, -1, 0), new ItemStack(Material.STONE), player, true, EquipmentSlot.HAND));
                var event = new BlockBreakEvent(block, player);
                event.setCancelled(cancelled);
                Bukkit.getPluginManager().callEvent(event);
                block.setType(Material.AIR);
            }
            case "harvest" -> {
                block.getRelative(0, -1, 0).setType(Material.FARMLAND);
                block.setType(Material.POTATOES);
                Ageable data = (Ageable) block.getBlockData();
                data.setAge(data.getMaximumAge()); block.setBlockData(data);
                var state = block.getState();
                Bukkit.getPluginManager().callEvent(new BlockBreakEvent(block, player));
                List<Item> drops = List.of(player.getWorld().dropItem(location, new ItemStack(Material.POTATO, 2)),
                        player.getWorld().dropItem(location, new ItemStack(Material.POTATO, 3)));
                Bukkit.getPluginManager().callEvent(new BlockDropItemEvent(block, state, player, new ArrayList<>(drops)));
                drops.forEach(Entity::remove); block.setType(Material.AIR);
            }
            case "fish" -> {
                Item caught = player.getWorld().dropItem(location, new ItemStack(Material.COD, 2));
                var event = new PlayerFishEvent(player, caught, null, PlayerFishEvent.State.CAUGHT_FISH);
                event.setCancelled(cancelled); Bukkit.getPluginManager().callEvent(event); caught.remove();
            }
            case "consume" -> {
                var event = new PlayerItemConsumeEvent(player, new ItemStack(Material.APPLE), EquipmentSlot.HAND);
                event.setCancelled(cancelled); Bukkit.getPluginManager().callEvent(event);
            }
            case "craft", "shiftcraft" -> {
                InventoryView view = player.openWorkbench(null, true);
                CraftingInventory inventory = (CraftingInventory) view.getTopInventory();
                ItemStack[] matrix = new ItemStack[9]; matrix[0] = new ItemStack(Material.OAK_LOG, 2);
                inventory.setMatrix(matrix);
                var recipe = new ShapelessRecipe(new NamespacedKey(this, "fixture"), new ItemStack(Material.OAK_PLANKS, 4));
                recipe.addIngredient(Material.OAK_LOG);
                inventory.setResult(recipe.getResult());
                var event = new CraftItemEvent(recipe, view, InventoryType.SlotType.RESULT, 0,
                        base.equals("craft") ? ClickType.LEFT : ClickType.SHIFT_LEFT,
                        base.equals("craft") ? InventoryAction.PICKUP_ALL : InventoryAction.MOVE_TO_OTHER_INVENTORY);
                event.setCancelled(cancelled); Bukkit.getPluginManager().callEvent(event);
                inventory.clear(); player.closeInventory();
            }
            case "kill" -> {
                Zombie zombie = player.getWorld().spawn(location, Zombie.class);
                zombie.setKiller(player);
                Bukkit.getPluginManager().callEvent(new EntityDeathEvent(zombie,
                        DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<>()));
                zombie.remove();
            }
            case "breed" -> {
                Cow mother = player.getWorld().spawn(location, Cow.class);
                Cow father = player.getWorld().spawn(location, Cow.class);
                Cow child = player.getWorld().spawn(location, Cow.class);
                var event = new EntityBreedEvent(child, mother, father, player, new ItemStack(Material.WHEAT), 0);
                event.setCancelled(cancelled); Bukkit.getPluginManager().callEvent(event);
                mother.remove(); father.remove(); child.remove();
            }
            default -> throw new IllegalArgumentException("Unknown fixture event: " + base);
        }
        return Map.of("eventFixture", type, "ownerThread", Bukkit.isOwnedByCurrentRegion(player));
    }

    private Object registry(String itemIds, String entityIds) {
        List<String> foods = Arrays.stream(Material.values()).filter(m -> !m.isLegacy() && m.isItem() && m.isEdible())
                .map(m -> m.getKey().toString()).sorted().toList();
        Map<String, String> items = new TreeMap<>();
        for (String id : itemIds.split(",")) {
            String key = "minecraft:" + id;
            items.put(key, ItemUtil.getItemKey(ItemUtil.createItem(task, key, 1)));
        }
        Map<String, String> entities = new TreeMap<>();
        for (String id : entityIds.split(",")) {
            EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(id));
            entities.put(id, type.getKey().toString());
        }
        return Map.of("foods", foods, "items", items, "entities", entities,
                "newEntity", EntityType.SULFUR_CUBE.getKey().toString(),
                "oldChain", ItemUtil.getItemKey(ItemUtil.createItem(task, "minecraft:chain", 1)));
    }

    private Object prepareBlock(Player player, String data, String placement) {
        player.closeInventory(); player.getInventory().clear(); player.setGameMode(GameMode.SURVIVAL);
        Location location = player.getLocation().getBlock().getLocation().add(2, 0, 0);
        Block block = location.getBlock();
        for (int y = 0; y < 4; y++) {
            Block part = block.getRelative(0, y, 0);
            task.getAntiCheatManager().removeBlockRecord(part.getLocation()); part.setType(Material.AIR, false);
        }
        block.getRelative(0, -1, 0).setType(Material.FARMLAND, false);
        if (data.equals("kelp-column")) {
            block.getRelative(0, -1, 0).setType(Material.DIRT, false);
            block.setType(Material.KELP_PLANT, false);
            block.getRelative(0, 1, 0).setType(Material.KELP_PLANT, false);
            block.getRelative(0, 2, 0).setBlockData(Bukkit.createBlockData("minecraft:kelp[age=0]"), false);
        } else {
            block.setBlockData(Bukkit.createBlockData(data), false);
            if (block.getType() == Material.CAKE || block.getType() == Material.SWEET_BERRY_BUSH) {
                block.getRelative(0, -1, 0).setType(Material.DIRT, false);
            }
            if (block.getType() == Material.CACTUS_FLOWER) {
                block.getRelative(0, -2, 0).setType(Material.DIRT, false);
                block.getRelative(0, -1, 0).setType(Material.CACTUS, false);
            }
            if (block.getType() == Material.CAVE_VINES || block.getType() == Material.CAVE_VINES_PLANT) {
                block.getRelative(0, 1, 0).setType(Material.STONE, false);
            }
            if (block.getType() == Material.PITCHER_CROP && ((Ageable) block.getBlockData()).getAge() >= 3) {
                block.getRelative(0, 1, 0).setBlockData(Bukkit.createBlockData(data.replace("half=lower", "half=upper")), false);
                if (placement.equals("upper")) block = block.getRelative(0, 1, 0);
            }
        }
        if (placement.equals("placed")) task.getAntiCheatManager().recordBlockPlace(block.getLocation());
        if (placement.equals("grown")) {
            var grown = block.getState();
            block.setBlockData(Bukkit.createBlockData("minecraft:torchflower_crop[age=1]"), false);
            task.getAntiCheatManager().recordBlockPlace(block.getLocation());
            Bukkit.getPluginManager().callEvent(new BlockGrowEvent(block, grown));
            grown.update(true, false);
        }
        if (block.getType().name().contains("SULFUR") || block.getType().name().contains("CINNABAR")) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_PICKAXE));
        }
        contentBlocks.put(player.getUniqueId(), block.getLocation());
        return Map.of("x", block.getX(), "y", block.getY(), "z", block.getZ(), "data", block.getBlockData().getAsString());
    }

    private Object contentEvent(Player player, String type, String key) {
        boolean cancelled = type.endsWith("-cancelled");
        String base = type.replace("-cancelled", "");
        ItemStack result = ItemUtil.createItem(task, key, 1);
        switch (base) {
            case "consume" -> {
                var event = new PlayerItemConsumeEvent(player, result, EquipmentSlot.OFF_HAND);
                event.setCancelled(cancelled); Bukkit.getPluginManager().callEvent(event);
            }
            case "craft" -> {
                InventoryView view = player.openWorkbench(null, true);
                CraftingInventory inventory = (CraftingInventory) view.getTopInventory();
                var recipe = new ShapelessRecipe(new NamespacedKey(this, "content_fixture"), result.clone());
                recipe.addIngredient(Material.SULFUR);
                ItemUtil.applyComponents(result, Map.of("minecraft:custom_data.qa", "7"));
                inventory.setMatrix(new ItemStack[]{new ItemStack(Material.SULFUR), null, null, null, null, null, null, null, null});
                inventory.setResult(result);
                var event = new CraftItemEvent(recipe, view, InventoryType.SlotType.RESULT, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
                event.setCancelled(cancelled); Bukkit.getPluginManager().callEvent(event);
                inventory.clear(); player.closeInventory();
            }
            case "smith" -> {
                InventoryView view = MenuType.SMITHING.create(player);
                SmithingInventory inventory = (SmithingInventory) view.getTopInventory();
                ItemUtil.applyComponents(result, Map.of("minecraft:custom_data.qa", "7"));
                inventory.setResult(result);
                player.openInventory(view);
                var event = new SmithItemEvent(view, InventoryType.SlotType.RESULT, 3, ClickType.LEFT, InventoryAction.PICKUP_ALL);
                event.setCancelled(cancelled); Bukkit.getPluginManager().callEvent(event);
                inventory.clear(); player.closeInventory();
            }
            case "kill" -> {
                EntityType entityType = Registry.ENTITY_TYPE.get(NamespacedKey.fromString(key.replace("_spawn_egg", "")));
                LivingEntity entity = (LivingEntity) player.getWorld().spawnEntity(player.getLocation().add(2, 0, 0), entityType);
                if (entity instanceof SulfurCube cube) cube.setSize(1);
                entity.damage(1000, player);
                if (!entity.isDead()) throw new IllegalStateException("Entity did not die: " + entityType);
            }
            case "fish" -> {
                Item caught = player.getWorld().dropItem(player.getLocation(), result);
                var event = new PlayerFishEvent(player, caught, null, PlayerFishEvent.State.CAUGHT_FISH);
                event.setCancelled(cancelled); Bukkit.getPluginManager().callEvent(event); caught.remove();
            }
            case "breed" -> {
                Location location = player.getLocation().add(2, 0, 0);
                Nautilus mother = player.getWorld().spawn(location, Nautilus.class);
                Nautilus father = player.getWorld().spawn(location, Nautilus.class);
                Nautilus child = player.getWorld().spawn(location, Nautilus.class);
                child.setBaby();
                var event = new EntityBreedEvent(child, mother, father, player, new ItemStack(Material.PUFFERFISH), 0);
                event.setCancelled(cancelled); Bukkit.getPluginManager().callEvent(event);
                mother.remove(); father.remove(); child.remove();
            }
            default -> throw new IllegalArgumentException("Unknown content event: " + type);
        }
        return Map.of("eventFixture", type, "key", ItemUtil.getItemKey(result));
    }
}
