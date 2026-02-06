package dev.user.simpletask.api;

import dev.user.simpletask.SimpleTaskPlugin;
import dev.user.simpletask.task.PlayerTask;
import dev.user.simpletask.task.TaskTemplate;

import java.util.List;
import java.util.UUID;

/**
 * SimpleTask API
 * Provides public access to task system functionality
 */
public class TaskAPI {

    private static SimpleTaskPlugin plugin;

    /**
     * Internal use only - called by plugin on enable
     */
    public static void initialize(SimpleTaskPlugin pluginInstance) {
        plugin = pluginInstance;
    }

    /**
     * Get all task templates
     */
    public static List<TaskTemplate> getAllTemplates() {
        checkInitialized();
        return List.copyOf(plugin.getTaskManager().getAllTemplates());
    }

    /**
     * Get a template by its key
     */
    public static TaskTemplate getTemplate(String key) {
        checkInitialized();
        return plugin.getTaskManager().getTemplateByKey(key);
    }

    /**
     * Get player's current daily tasks
     */
    public static List<PlayerTask> getPlayerTasks(UUID playerUuid) {
        checkInitialized();
        return plugin.getTaskManager().getPlayerTasks(playerUuid);
    }

    /**
     * Check if plugin is fully initialized
     */
    public static boolean isInitialized() {
        return plugin != null && plugin.isEnabled();
    }

    private static void checkInitialized() {
        if (plugin == null) {
            throw new IllegalStateException("SimpleTask API not initialized");
        }
    }
}
