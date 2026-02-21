package dev.user.simpletask.util;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间工具类
 * 所有时区相关操作统一通过 TimeZoneConfig 管理
 */
public class TimeUtil {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    // 默认刷新时间
    public static final LocalTime DEFAULT_RESET_TIME = LocalTime.of(4, 0);


    /**
     * 获取当前日期（使用时区）
     */
    public static LocalDate today() {
        return TimeZoneConfig.today();
    }

    /**
     * 获取当前日期时间（使用时区）
     */
    public static LocalDateTime now() {
        return TimeZoneConfig.now();
    }

    /**
     * 解析时间字符串 (HH:mm)
     */
    public static LocalTime parseTime(String timeStr) {
        try {
            return LocalTime.parse(timeStr, TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            return LocalTime.of(4, 0); // 默认4:00
        }
    }

    /**
     * 格式化日期为字符串
     */
    public static String formatDate(LocalDate date) {
        return date.format(DATE_FORMATTER);
    }

    /**
     * 解析日期字符串
     */
    public static LocalDate parseDate(String dateStr) {
        return LocalDate.parse(dateStr, DATE_FORMATTER);
    }

    /**
     * 获取今天的日期字符串
     */
    public static String getTodayString() {
        return formatDate(today());
    }

    /**
     * 获取当前日期（使用时区）
     */
    public static LocalDate getToday() {
        return today();
    }

    // ==================== 下次刷新时间计算 ====================
    // 注意：所有过期/刷新相关方法已移至 ExpireUtil
    // 统一使用 ExpireUtil.getNextDailyReset() 等方法

    // ==================== 持续时间解析 ====================

    /**
     * 解析持续时间字符串
     * 支持: 7d, 24h, 30m, 60s
     */
    public static Duration parseDuration(String input) {
        if (input == null || input.isEmpty()) {
            return Duration.ZERO;
        }

        input = input.trim().toLowerCase();
        try {
            if (input.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(input.substring(0, input.length() - 1)));
            } else if (input.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(input.substring(0, input.length() - 1)));
            } else if (input.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(input.substring(0, input.length() - 1)));
            } else if (input.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(input.substring(0, input.length() - 1)));
            } else {
                // 纯数字，默认为天
                return Duration.ofDays(Long.parseLong(input));
            }
        } catch (NumberFormatException e) {
            return Duration.ZERO;
        }
    }

    /**
     * 格式化持续时间为可读字符串
     */
    public static String formatDuration(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return "已过期";
        }

        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();

        if (days > 0) {
            return days + "天" + (hours > 0 ? hours + "小时" : "");
        } else if (hours > 0) {
            return hours + "小时" + (minutes > 0 ? minutes + "分钟" : "");
        } else {
            return minutes + "分钟";
        }
    }

    /**
     * 计算从分配日期开始的过期时间（相对时间策略）
     */
    public static Instant calculateRelativeExpire(LocalDate assignedDate, Duration duration) {
        return TimeZoneConfig.toInstant(assignedDate.atStartOfDay().plus(duration));
    }

    /**
     * 截断 LocalDateTime 到秒级，确保跨数据库时间戳一致性
     * MySQL 和 H2 对 TIMESTAMP 的精度支持不同（微秒 vs 毫秒），
     * 统一截断到秒级避免主键匹配问题
     */
    public static LocalDateTime truncateToSeconds(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.withNano(0);
    }

    /**
     * 获取当前时间（截断到秒级，使用时区）
     */
    public static LocalDateTime nowTruncated() {
        return truncateToSeconds(now());
    }
}
