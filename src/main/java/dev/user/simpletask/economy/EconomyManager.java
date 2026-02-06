package dev.user.simpletask.economy;

import dev.user.simpletask.SimpleTaskPlugin;
import me.yic.xconomy.api.XConomyAPI;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

public class EconomyManager {

    private final SimpleTaskPlugin plugin;
    private XConomyAPI xconomyAPI;
    private boolean enabled = false;

    public EconomyManager(SimpleTaskPlugin plugin) {
        this.plugin = plugin;
        setupEconomy();
    }

    private void setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("XConomy") == null) {
            plugin.getLogger().warning("XConomy not found, economy features disabled");
            return;
        }

        try {
            xconomyAPI = new XConomyAPI();
            enabled = true;
            plugin.getLogger().info("XConomy integration enabled");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize XConomy: " + e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取玩家余额
     */
    public double getBalance(Player player) {
        if (!enabled || xconomyAPI == null) return 0;

        try {
            BigDecimal bal = xconomyAPI.getPlayerData(player.getUniqueId()).getBalance();
            return bal.doubleValue();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get balance: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 给予玩家金币
     */
    public void deposit(Player player, double amount) {
        if (!enabled || xconomyAPI == null || amount <= 0) return;

        try {
            xconomyAPI.changePlayerBalance(
                player.getUniqueId(),
                player.getName(),
                BigDecimal.valueOf(amount),
                true
            );
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to deposit: " + e.getMessage());
        }
    }

    /**
     * 扣除玩家金币
     */
    public boolean withdraw(Player player, double amount) {
        if (!enabled || xconomyAPI == null || amount <= 0) return false;
        if (getBalance(player) < amount) return false;

        try {
            int result = xconomyAPI.changePlayerBalance(
                player.getUniqueId(),
                player.getName(),
                BigDecimal.valueOf(amount),
                false
            );
            return result == 0;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to withdraw: " + e.getMessage());
            return false;
        }
    }

    /**
     * 异步获取余额
     */
    public CompletableFuture<Double> getBalanceAsync(Player player) {
        return CompletableFuture.supplyAsync(() -> getBalance(player));
    }

    /**
     * 异步给予金币
     */
    public CompletableFuture<Void> depositAsync(Player player, double amount) {
        return CompletableFuture.runAsync(() -> deposit(player, amount));
    }
}
