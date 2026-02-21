package dev.user.simpletask.util;

import dev.user.simpletask.SimpleTaskPlugin;

import java.time.*;

/**
 * 统一时区管理类
 * 所有时间相关操作都通过此类获取时区，确保全局时区一致性
 */
public class TimeZoneConfig {

    private static volatile ZoneId zoneId = ZoneId.systemDefault();
    private static volatile boolean initialized = false;

    /**
     * 初始化时区配置
     * 应该在插件启动时调用一次
     *
     * @param plugin SimpleTaskPlugin实例
     */
    public static synchronized void initialize(SimpleTaskPlugin plugin) {
        if (initialized) {
            plugin.getLogger().warning("TimeZoneConfig already initialized, skipping...");
            return;
        }

        String timezone = plugin.getConfig().getString("timezone", "system");

        if ("system".equalsIgnoreCase(timezone)) {
            zoneId = ZoneId.systemDefault();
            plugin.getLogger().info("Using system default timezone: " + zoneId);
        } else if ("utc".equalsIgnoreCase(timezone)) {
            zoneId = ZoneOffset.UTC;
            plugin.getLogger().info("Using UTC timezone");
        } else {
            try {
                zoneId = ZoneId.of(timezone);
                plugin.getLogger().info("Using configured timezone: " + zoneId);
            } catch (Exception e) {
                plugin.getLogger().warning("Invalid timezone: " + timezone + ", using system default. Error: " + e.getMessage());
                zoneId = ZoneId.systemDefault();
            }
        }

        initialized = true;
    }

    /**
     * 获取配置的时区
     *
     * @return 当前配置的ZoneId
     */
    public static ZoneId getZoneId() {
        return zoneId;
    }

    /**
     * 获取当前日期（使用配置时区）
     *
     * @return 当前日期
     */
    public static LocalDate today() {
        return LocalDate.now(zoneId);
    }

    /**
     * 获取当前日期时间（使用配置时区）
     *
     * @return 当前日期时间
     */
    public static LocalDateTime now() {
        return LocalDateTime.now(zoneId);
    }

    /**
     * 获取当前Instant（使用配置时区）
     *
     * @return 当前Instant
     */
    public static Instant instantNow() {
        return Instant.now();
    }

    /**
     * 将Instant转换为配置时区的LocalDateTime
     *
     * @param instant Instant时间
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, zoneId);
    }

    /**
     * 将LocalDateTime转换为Instant（使用配置时区）
     *
     * @param dateTime LocalDateTime时间
     * @return Instant
     */
    public static Instant toInstant(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atZone(zoneId).toInstant();
    }

    /**
     * 检查是否已初始化
     *
     * @return 是否已初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }
}
