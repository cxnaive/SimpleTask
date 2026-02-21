package dev.user.simpletask.gui;

import dev.user.simpletask.SimpleTaskPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import dev.user.simpletask.util.MessageUtil;
import java.util.HashMap;
import java.util.Map;

public abstract class AbstractGUI implements InventoryHolder {

    protected final SimpleTaskPlugin plugin;
    protected final Player player;
    protected final String title;
    protected final int size;
    protected Inventory inventory;
    protected final Map<Integer, GUIAction> actions;

    @FunctionalInterface
    public interface GUIAction {
        void execute(Player player, InventoryClickEvent event);

        /**
         * 便捷方法：创建一个只接收player的action（向后兼容）
         */
        static GUIAction of(java.util.function.Consumer<Player> consumer) {
            return (player, event) -> consumer.accept(player);
        }
    }

    public AbstractGUI(SimpleTaskPlugin plugin, Player player, String title, int size) {
        this.plugin = plugin;
        this.player = player;
        this.title = title;
        this.size = size;
        this.actions = new HashMap<>();
    }

    /**
     * 初始化GUI内容，子类必须实现
     */
    public abstract void initialize();

    /**
     * 打开GUI
     */
    public void open() {
        // Parse MiniMessage to Component for title
        Component titleComponent = MessageUtil.parse(title);
        inventory = Bukkit.createInventory(this, size, titleComponent);
        initialize();
        player.openInventory(inventory);
        GUIManager.registerGUI(player.getUniqueId(), this);
    }

    /**
     * 设置物品到指定槽位
     */
    protected void setItem(int slot, ItemStack item) {
        inventory.setItem(slot, item);
    }

    /**
     * 设置物品并绑定点击动作
     */
    protected void setItem(int slot, ItemStack item, GUIAction action) {
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    /**
     * 填充边框
     */
    protected void fillBorder(ItemStack item) {
        int rows = size / 9;
        for (int i = 0; i < size; i++) {
            int row = i / 9;
            int col = i % 9;
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                if (inventory.getItem(i) == null) {
                    inventory.setItem(i, item);
                }
            }
        }
    }

    /**
     * 填充所有空槽位
     */
    protected void fillEmpty(ItemStack item) {
        for (int i = 0; i < size; i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, item);
            }
        }
    }

    /**
     * 获取槽位的点击动作
     */
    public GUIAction getAction(int slot) {
        return actions.get(slot);
    }

    /**
     * 关闭GUI
     */
    public void close() {
        player.closeInventory();
        GUIManager.unregisterGUI(player.getUniqueId());
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player getPlayer() {
        return player;
    }
}
