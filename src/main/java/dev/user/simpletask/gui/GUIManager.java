package dev.user.simpletask.gui;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GUIManager {

    private static final Map<UUID, AbstractGUI> openGUIs = new ConcurrentHashMap<>();

    /**
     * 注册打开的GUI
     */
    public static void registerGUI(UUID uuid, AbstractGUI gui) {
        openGUIs.put(uuid, gui);
    }

    /**
     * 取消注册GUI
     */
    public static void unregisterGUI(UUID uuid) {
        openGUIs.remove(uuid);
    }

    /**
     * 获取玩家当前打开的GUI
     */
    public static AbstractGUI getOpenGUI(UUID uuid) {
        return openGUIs.get(uuid);
    }

    /**
     * 检查玩家是否有打开的GUI
     */
    public static boolean hasOpenGUI(UUID uuid) {
        return openGUIs.containsKey(uuid);
    }

    /**
     * 关闭所有打开的GUI
     */
    public static void closeAll() {
        for (AbstractGUI gui : openGUIs.values()) {
            if (gui.getPlayer().isOnline()) {
                gui.getPlayer().closeInventory();
            }
        }
        openGUIs.clear();
    }

    /**
     * 刷新所有GUI
     */
    public static void refreshAll() {
        for (AbstractGUI gui : openGUIs.values()) {
            if (gui.getPlayer().isOnline()) {
                gui.getPlayer().getScheduler().execute(gui.getPlayer().getServer().getPluginManager().getPlugin("SimpleTask"), () -> {
                    gui.initialize();
                }, null, 0);
            }
        }
    }
}
