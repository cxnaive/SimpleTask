package dev.user.simpletask.listener;

import dev.user.simpletask.gui.AbstractGUI;
import dev.user.simpletask.gui.GUIManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

public class GUIListener implements Listener {

    private final GUIManager guiManager;

    public GUIListener(GUIManager guiManager) {
        this.guiManager = guiManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        AbstractGUI gui = GUIManager.getOpenGUI(player.getUniqueId());
        if (gui == null) {
            return;
        }

        // Check if clicking our GUI
        if (event.getInventory() != gui.getInventory()) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= gui.getInventory().getSize()) {
            return;
        }

        AbstractGUI.GUIAction action = gui.getAction(slot);
        if (action != null) {
            action.execute(player, event);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        GUIManager.unregisterGUI(player.getUniqueId());
    }
}
