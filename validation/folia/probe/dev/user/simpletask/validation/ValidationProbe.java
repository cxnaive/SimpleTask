package dev.user.simpletask.validation;

import com.google.gson.Gson;
import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.PlayerTask;
import dev.user.simpletask.util.ItemUtil;
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
}
