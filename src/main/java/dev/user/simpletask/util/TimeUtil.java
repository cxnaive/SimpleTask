package dev.user.simpletask.util;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class TimeUtil {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

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
     * 检查是否过了重置时间
     */
    public static boolean isAfterResetTime(LocalDate lastResetDate, String resetTimeStr, LocalDate currentDate) {
        if (lastResetDate == null) {
            return true;
        }

        LocalTime resetTime = parseTime(resetTimeStr);
        LocalTime currentTime = LocalTime.now();

        // 如果上次重置不是今天，检查是否过了重置时间
        if (!lastResetDate.equals(currentDate)) {
            return currentTime.isAfter(resetTime) || currentTime.equals(resetTime);
        }

        return false;
    }

    /**
     * 获取今天的日期字符串
     */
    public static String getTodayString() {
        return formatDate(LocalDate.now());
    }

    /**
     * 获取当前日期
     */
    public static LocalDate getToday() {
        return LocalDate.now();
    }
}
