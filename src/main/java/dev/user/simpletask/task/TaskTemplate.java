package dev.user.simpletask.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import dev.user.simpletask.util.ItemUtil;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TaskTemplate {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .create();

    private final String taskKey;
    private final String name; // 任务显示名称
    private final TaskType type;
    private final List<String> targetItems;
    private final int targetAmount;
    private final List<String> description; // 支持多行描述
    private final String icon;
    private final int weight;
    private final Reward reward;
    private boolean enabled = true;

    // Task category (default: daily)
    private String category = "daily";

    // Database ID (set after loading from database)
    private int id = -1;

    // Version for template synchronization
    private int version = 1;

    // Extension data for future use (task chains, requirements, etc.)
    private final Map<String, Object> extensions = new HashMap<>();

    // NBT matching conditions (optional)
    private List<String> nbtMatchConditions = new ArrayList<>();

    public TaskTemplate(String taskKey, String name, TaskType type, String targetItem, int targetAmount,
                        String description, String icon, int weight, Reward reward) {
        this(taskKey, name, type, targetItem != null ? Arrays.asList(targetItem) : new ArrayList<>(),
                targetAmount, description != null ? Collections.singletonList(description) : new ArrayList<>(),
                icon, weight, reward);
    }

    public TaskTemplate(String taskKey, String name, TaskType type, List<String> targetItems, int targetAmount,
                        String description, String icon, int weight, Reward reward) {
        this(taskKey, name, type, targetItems, targetAmount,
                description != null ? Collections.singletonList(description) : new ArrayList<>(),
                icon, weight, reward);
    }

    public TaskTemplate(String taskKey, String name, TaskType type, List<String> targetItems, int targetAmount,
                        List<String> description, String icon, int weight, Reward reward) {
        this.taskKey = taskKey;
        this.name = name != null && !name.isEmpty() ? name : taskKey;
        this.type = type;
        this.targetItems = targetItems != null ? new ArrayList<>(targetItems) : new ArrayList<>();
        this.targetAmount = targetAmount;
        this.description = description != null ? new ArrayList<>(description) : new ArrayList<>();
        this.icon = icon;
        this.weight = weight;
        this.reward = reward;
    }

    public String getTaskKey() {
        return taskKey;
    }

    public String getName() {
        return name;
    }

    public TaskType getType() {
        return type;
    }

    /**
     * 获取目标物品列表（支持多ID匹配）
     */
    public List<String> getTargetItems() {
        return new ArrayList<>(targetItems);
    }

    /**
     * 获取第一个目标物品（向后兼容）
     */
    public String getTargetItem() {
        return targetItems.isEmpty() ? null : targetItems.get(0);
    }

    public int getTargetAmount() {
        return targetAmount;
    }

    /**
     * 获取描述列表（支持多行）
     */
    public List<String> getDescription() {
        return new ArrayList<>(description);
    }

    /**
     * 获取第一行描述（向后兼容）
     */
    public String getFirstDescription() {
        return description.isEmpty() ? "" : description.get(0);
    }

    public String getIcon() {
        return icon;
    }

    public int getWeight() {
        return weight;
    }

    public Reward getReward() {
        return reward;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category != null && !category.isEmpty() ? category : "daily";
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getDisplayName() {
        return name != null && !name.isEmpty() ? name : type.getDisplayName() + "任务";
    }

    public boolean matchesTarget(String itemKey) {
        return matchesTarget(itemKey, null);
    }

    /**
     * 检查目标是否匹配（支持 NBT 条件）
     * @param itemKey 物品 key
     * @param item 物品实例（用于 NBT 匹配，可为 null）
     * @return 是否匹配
     */
    public boolean matchesTarget(String itemKey, ItemStack item) {
        if (targetItems == null || targetItems.isEmpty()) {
            return true; // No specific target required
        }
        // 遍历所有目标ID，任一匹配即可
        for (String targetItem : targetItems) {
            if (matchesSingleTarget(targetItem, itemKey, item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查单个目标是否匹配（带物品信息）
     */
    private boolean matchesSingleTarget(String targetItem, String itemKey, ItemStack item) {
        // 基础 ID 匹配
        boolean baseMatch = false;

        if (targetItem == null || targetItem.isEmpty()) {
            baseMatch = true;
        } else if (targetItem.equalsIgnoreCase(itemKey)) {
            baseMatch = true;
        } else if (type == TaskType.HARVEST || type == TaskType.BREAK) {
            baseMatch = matchesBlockOrItem(targetItem, itemKey);
        } else if (type == TaskType.CHAT) {
            baseMatch = matchesChatMessage(targetItem, itemKey);
        } else if (type == TaskType.COMMAND) {
            // 前缀匹配：检查命令是否以 targetItem 开头
            baseMatch = itemKey.startsWith(targetItem.toLowerCase());
        }

        if (!baseMatch) {
            return false;
        }

        // NBT 条件匹配
        if (hasNbtMatchConditions() && item != null) {
            if (!matchesNbtConditions(item)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 检查物品是否满足 NBT 匹配条件
     */
    private boolean matchesNbtConditions(ItemStack item) {
        if (nbtMatchConditions == null || nbtMatchConditions.isEmpty()) {
            return true;
        }
        // 使用 ItemUtil 检查 NBT
        for (String nbtCondition : nbtMatchConditions) {
            if (!ItemUtil.matchesNbtCondition(item, nbtCondition)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查发言消息是否匹配目标关键词
     * 支持包含匹配（不区分大小写）
     */
    private boolean matchesChatMessage(String targetKeyword, String message) {
        if (message == null) return false;
        // 不区分大小写的包含匹配
        return message.toLowerCase().contains(targetKeyword.toLowerCase());
    }

    /**
     * 检查方块或物品的目标是否匹配
     * 处理方块ID和物品ID的差异（如 carrot <-> carrots）
     * 支持 CE 物品的兼容性检查
     */
    private boolean matchesBlockOrItem(String targetItem, String itemKey) {
        String normalizedTarget = normalizeBlockOrItemId(targetItem);
        String normalizedInput = normalizeBlockOrItemId(itemKey);
        return normalizedTarget.equals(normalizedInput);
    }

    /**
     * 标准化方块/物品ID，用于灵活匹配
     * - 移除 minecraft: 前缀
     * - 处理常见的单复数差异
     * - 统一特殊命名（如 cocoa_beans <-> cocoa）
     */
    private String normalizeBlockOrItemId(String itemKey) {
        if (itemKey == null) return "";
        String normalized = itemKey.toLowerCase();

        // 提取命名空间和ID
        String namespace = "minecraft";
        String id = normalized;
        if (normalized.contains(":")) {
            String[] parts = normalized.split(":", 2);
            namespace = parts[0];
            id = parts[1];
        }

        // 特殊映射表：处理差异大的命名
        id = switch (id) {
            case "cocoa" -> "cocoa_bean";
            case "cocoa_beans" -> "cocoa_bean";
            default -> id;
        };

        // 统一单复数形式（移除末尾的 's'，但保留例外）
        if (id.endsWith("s") && !isIrregularPlural(id)) {
            id = id.substring(0, id.length() - 1);
        }

        return namespace + ":" + id;
    }

    /**
     * 检查是否是不规则复数形式（不应该去 's' 的）
     */
    private boolean isIrregularPlural(String id) {
        return switch (id) {
            case "cactus", "chorus", "bamboo", "sugar_cane", "chorus_plant",
                 "chorus_flower", "kelp", "seagrass", "tall_seagrass",
                 "vines", "cave_vines", "weeping_vines", "twisting_vines" -> true;
            default -> false;
        };
    }

    /**
     * 解析目标物品配置（支持字符串或列表）
     * @param configValue 配置值（String 或 List<String>）
     * @return 目标物品列表
     */
    public static List<String> parseTargetItems(Object configValue) {
        List<String> items = new ArrayList<>();
        if (configValue == null) {
            return items;
        }
        if (configValue instanceof String str) {
            // 支持逗号分隔
            if (str.contains(",")) {
                for (String part : str.split(",")) {
                    items.add(part.trim());
                }
            } else {
                items.add(str);
            }
        } else if (configValue instanceof List<?> list) {
            for (Object obj : list) {
                if (obj instanceof String str) {
                    items.add(str);
                }
            }
        }
        return items;
    }

    @Override
    public String toString() {
        return "TaskTemplate{" +
                "taskKey='" + taskKey + '\'' +
                ", type=" + type +
                ", targetItems=" + targetItems +
                ", targetAmount=" + targetAmount +
                ", weight=" + weight +
                ", enabled=" + enabled +
                '}';
    }

    // ========== Version Control ==========

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    // ========== Extension Data (for future features) ==========

    /**
     * 获取扩展数据，用于未来功能（任务链、前置条件等）
     */
    public Map<String, Object> getExtensions() {
        return extensions;
    }

    public void setExtension(String key, Object value) {
        extensions.put(key, value);
    }

    public Object getExtension(String key) {
        return extensions.get(key);
    }

    // ========== NBT Conditions ==========

    /**
     * 获取NBT匹配条件列表
     */
    public List<String> getNbtMatchConditions() {
        return new ArrayList<>(nbtMatchConditions);
    }

    /**
     * 设置NBT匹配条件列表
     */
    public void setNbtMatchConditions(List<String> nbtMatchConditions) {
        this.nbtMatchConditions = nbtMatchConditions != null ? new ArrayList<>(nbtMatchConditions) : new ArrayList<>();
    }

    /**
     * 检查是否有NBT匹配条件
     */
    public boolean hasNbtMatchConditions() {
        return nbtMatchConditions != null && !nbtMatchConditions.isEmpty();
    }

    // ========== JSON Serialization ==========

    /**
     * 将模板序列化为 JSON 字符串
     */
    public String toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("taskKey", taskKey);
        json.addProperty("name", name);
        json.addProperty("type", type.name());
        json.addProperty("category", category);
        json.add("targetItems", GSON.toJsonTree(targetItems));
        json.addProperty("targetAmount", targetAmount);
        json.add("description", GSON.toJsonTree(description));
        json.addProperty("icon", icon);
        json.addProperty("weight", weight);
        json.addProperty("version", version);
        json.add("reward", GSON.toJsonTree(reward));
        if (!extensions.isEmpty()) {
            json.add("extensions", GSON.toJsonTree(extensions));
        }
        if (!nbtMatchConditions.isEmpty()) {
            json.add("nbtMatchConditions", GSON.toJsonTree(nbtMatchConditions));
        }
        return GSON.toJson(json);
    }

    /**
     * 从 JSON 字符串解析模板
     */
    public static TaskTemplate fromJson(String jsonString) {
        JsonObject json = JsonParser.parseString(jsonString).getAsJsonObject();

        String taskKey = json.get("taskKey").getAsString();
        // 支持新旧格式：读取 name，如果不存在则使用 taskKey
        String name = json.has("name") && !json.get("name").isJsonNull() ? json.get("name").getAsString() : taskKey;
        TaskType type = TaskType.valueOf(json.get("type").getAsString());

        // 支持新旧格式：优先读取 targetItems，否则回退到 targetItem
        List<String> targetItems = new ArrayList<>();
        if (json.has("targetItems") && !json.get("targetItems").isJsonNull()) {
            targetItems = GSON.fromJson(json.get("targetItems"), new TypeToken<List<String>>() {}.getType());
        } else if (json.has("targetItem") && !json.get("targetItem").isJsonNull()) {
            String targetItem = json.get("targetItem").getAsString();
            if (targetItem != null && !targetItem.isEmpty()) {
                targetItems.add(targetItem);
            }
        }

        int targetAmount = json.get("targetAmount").getAsInt();

        // 支持新旧格式：description 可以是字符串或数组
        List<String> description = new ArrayList<>();
        if (json.has("description") && !json.get("description").isJsonNull()) {
            var descElement = json.get("description");
            if (descElement.isJsonArray()) {
                description = GSON.fromJson(descElement, new TypeToken<List<String>>() {}.getType());
            } else {
                description.add(descElement.getAsString());
            }
        }

        String icon = json.get("icon").getAsString();
        int weight = json.get("weight").getAsInt();

        Reward reward = GSON.fromJson(json.get("reward"), Reward.class);

        TaskTemplate template = new TaskTemplate(taskKey, name, type, targetItems, targetAmount,
                description, icon, weight, reward);

        // Load category if present
        if (json.has("category") && !json.get("category").isJsonNull()) {
            template.setCategory(json.get("category").getAsString());
        }

        if (json.has("version")) {
            template.setVersion(json.get("version").getAsInt());
        }

        // Load extensions if present
        if (json.has("extensions")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ext = GSON.fromJson(json.get("extensions"), Map.class);
            template.extensions.putAll(ext);
        }

        // Load NBT match conditions if present
        if (json.has("nbtMatchConditions") && !json.get("nbtMatchConditions").isJsonNull()) {
            List<String> nbtConditions = GSON.fromJson(json.get("nbtMatchConditions"), new TypeToken<List<String>>() {}.getType());
            template.setNbtMatchConditions(nbtConditions);
        }

        return template;
    }
}
