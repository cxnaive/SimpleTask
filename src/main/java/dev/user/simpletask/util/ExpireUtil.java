package dev.user.simpletask.util;

import dev.user.simpletask.task.ExpirePolicy;
import dev.user.simpletask.task.ExpirePolicyConfig;
import dev.user.simpletask.task.category.TaskCategory;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

/**
 * 任务过期判断工具类
 * 基于 assigned_at 时间戳进行精确的过期判断
 *
 * 核心思想：将时间点映射到周期编号，编号不同即表示过期
 *
 * 注意：时区统一通过 TimeZoneConfig 管理
 */
public class ExpireUtil {

    // 获取时区统一从 TimeZoneConfig
    private static ZoneId getZoneId() {
        return TimeZoneConfig.getZoneId();
    }

    /**
     * 判断任务是否已过期
     *
     * @param assignedAt 任务分配时间（带时分秒）
     * @param category 任务类别配置
     * @return 是否已过期
     */
    public static boolean isExpired(LocalDateTime assignedAt, TaskCategory category) {
        if (assignedAt == null || category == null) {
            return true;
        }
        return isExpired(assignedAt, category.getExpirePolicyConfig());
    }

    /**
     * 判断时间是否已过期（基于策略配置）
     * 用于刷新次数重置等场景，可以与任务使用不同的策略配置
     *
     * @param time 需要判断的时间点
     * @param config 过期策略配置
     * @return 是否已过期
     */
    public static boolean isExpired(LocalDateTime time, ExpirePolicyConfig config) {
        if (time == null || config == null) {
            return true;
        }

        ExpirePolicy policy = config.getPolicy();
        LocalDateTime now = TimeZoneConfig.now(); // 使用统一时区

        return switch (policy) {
            case DAILY -> isDailyExpired(time, now, config.getResetTime());
            case WEEKLY -> isWeeklyExpired(time, now, config.getResetDayOfWeek(), config.getResetTime());
            case MONTHLY -> isMonthlyExpired(time, now, config.getResetDayOfMonth(), config.getResetTime());
            case RELATIVE -> isRelativeExpired(time, now, config.getDuration());
            case PERMANENT -> false;
            case FIXED -> isFixedExpired(now, config.getFixedStart(), config.getFixedEnd());
        };
    }

    /**
     * 每日任务过期判断
     *
     * 逻辑：将时间映射到"日周期编号"，编号不同即过期
     * 周期切换点在 resetTime
     *
     * 示例（resetTime=04:00）：
     * - 周一 03:00 分配 → 属于周日周期（编号：周日）
     * - 周一 05:00 分配 → 属于周一周期（编号：周一）
     * - 周一 03:00 的任务，在周一 05:00 检查 → 已过期（周日 ≠ 周一）
     */
    public static boolean isDailyExpired(LocalDateTime assignedAt, LocalDateTime now, LocalTime resetTime) {
        long assignedPeriod = getDailyPeriodIndex(assignedAt, resetTime);
        long currentPeriod = getDailyPeriodIndex(now, resetTime);
        return assignedPeriod < currentPeriod;
    }

    /**
     * 周常任务过期判断
     *
     * 逻辑：将时间映射到"周周期编号"，编号不同即过期
     * 周期切换点在 resetDay + resetTime
     */
    public static boolean isWeeklyExpired(LocalDateTime assignedAt, LocalDateTime now,
                                          DayOfWeek resetDay, LocalTime resetTime) {
        long assignedPeriod = getWeeklyPeriodIndex(assignedAt, resetDay, resetTime);
        long currentPeriod = getWeeklyPeriodIndex(now, resetDay, resetTime);
        return assignedPeriod < currentPeriod;
    }

    /**
     * 月常任务过期判断
     *
     * 逻辑：将时间映射到"月周期编号"，编号不同即过期
     * 周期切换点在 resetDayOfMonth + resetTime
     */
    public static boolean isMonthlyExpired(LocalDateTime assignedAt, LocalDateTime now,
                                           int resetDayOfMonth, LocalTime resetTime) {
        long assignedPeriod = getMonthlyPeriodIndex(assignedAt, resetDayOfMonth, resetTime);
        long currentPeriod = getMonthlyPeriodIndex(now, resetDayOfMonth, resetTime);
        return assignedPeriod < currentPeriod;
    }

    /**
     * 相对时间任务过期判断
     *
     * 逻辑：assignedAt + duration < now 即过期
     */
    public static boolean isRelativeExpired(LocalDateTime assignedAt, LocalDateTime now, Duration duration) {
        if (duration == null || duration.isZero()) {
            return true;
        }
        LocalDateTime expireTime = assignedAt.plus(duration);
        return now.isAfter(expireTime);
    }

    /**
     * 固定时间任务过期判断
     *
     * 逻辑：当前时间超过 fixedEnd 即过期
     */
    public static boolean isFixedExpired(LocalDateTime now, String fixedStart, String fixedEnd) {
        // FIXED策略：检查当前时间是否超过 fixedEnd
        // 时间字符串应该是ISO-8601格式（如 2024-01-01T00:00:00Z）
        // 统一使用配置时区进行比较
        if (fixedEnd != null && !fixedEnd.isEmpty()) {
            try {
                Instant end = Instant.parse(fixedEnd);
                Instant nowInstant = TimeZoneConfig.toInstant(now);
                return nowInstant.isAfter(end);
            } catch (Exception e) {
                // 解析失败，默认不过期
                return false;
            }
        }
        return false;
    }

    // ==================== 周期索引计算方法 ====================

    /**
     * 获取每日周期编号
     *
     * 周期切换点在 resetTime
     * 如果时间在 resetTime 之前，属于前一天的周期
     */
    public static long getDailyPeriodIndex(LocalDateTime dateTime, LocalTime resetTime) {
        LocalDate date = dateTime.toLocalDate();
        LocalTime time = dateTime.toLocalTime();

        // 如果时间在重置时间之前，属于前一天的周期
        if (time.isBefore(resetTime)) {
            date = date.minusDays(1);
        }

        return date.toEpochDay();
    }

    /**
     * 获取每周周期编号
     *
     * 周期切换点在 resetDay + resetTime
     */
    public static long getWeeklyPeriodIndex(LocalDateTime dateTime, DayOfWeek resetDay, LocalTime resetTime) {
        LocalDate date = dateTime.toLocalDate();
        LocalTime time = dateTime.toLocalTime();

        // 找到本周的resetDay日期
        LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(resetDay));

        // 如果当前日期就是resetDay，但时间在resetTime之前，属于上一周
        if (date.getDayOfWeek() == resetDay && time.isBefore(resetTime)) {
            weekStart = weekStart.minusWeeks(1);
        }
        // 注意：previousOrSame已经处理了所有其他情况
        // - 如果date在resetDay之后，previousOrSame返回本周的resetDay（正确）
        // - 如果date在resetDay之前，previousOrSame返回上周的resetDay（正确）

        return weekStart.toEpochDay();
    }

    /**
     * 获取每月周期编号
     *
     * 周期切换点在 resetDayOfMonth + resetTime
     */
    public static long getMonthlyPeriodIndex(LocalDateTime dateTime, int resetDayOfMonth, LocalTime resetTime) {
        LocalDate date = dateTime.toLocalDate();
        LocalTime time = dateTime.toLocalTime();

        int currentDay = date.getDayOfMonth();

        LocalDate thisMonthResetDate;

        if (currentDay < resetDayOfMonth) {
            // 还没到本月的重置日，属于上个月开始的周期
            thisMonthResetDate = date.minusMonths(1).withDayOfMonth(
                    Math.min(resetDayOfMonth, date.minusMonths(1).lengthOfMonth()));
        } else if (currentDay == resetDayOfMonth) {
            // 今天就是重置日
            if (time.isBefore(resetTime)) {
                // 在重置时间之前，属于上个月开始的周期
                thisMonthResetDate = date.minusMonths(1).withDayOfMonth(
                        Math.min(resetDayOfMonth, date.minusMonths(1).lengthOfMonth()));
            } else {
                // 在重置时间之后，属于本月开始的周期
                thisMonthResetDate = date;
            }
        } else {
            // 已经过了本月的重置日
            thisMonthResetDate = date.withDayOfMonth(resetDayOfMonth);
        }

        // 使用年月作为周期编号（YYYYMM）
        return (long) thisMonthResetDate.getYear() * 100 + thisMonthResetDate.getMonthValue();
    }

    // ==================== 获取过期时间（用于显示）====================

    /**
     * 获取任务的过期时间（用于 GUI 显示）
     */
    public static Instant getExpireTime(LocalDateTime assignedAt, TaskCategory category) {
        if (assignedAt == null || category == null) {
            return null;
        }

        ExpirePolicy policy = category.getExpirePolicy();

        return switch (policy) {
            case DAILY -> getNextDailyReset(category.getResetTime());
            case WEEKLY -> getNextWeeklyReset(category.getResetDayOfWeek(), category.getResetTime());
            case MONTHLY -> getNextMonthlyReset(category.getResetDayOfMonth(), category.getResetTime());
            case RELATIVE -> {
                Duration duration = category.getDefaultDuration();
                if (duration == null) {
                    duration = Duration.ofDays(7);
                }
                yield TimeZoneConfig.toInstant(assignedAt.plus(duration));
            }
            case FIXED -> {
                if (category.getFixedEnd() != null) {
                    try {
                        yield Instant.parse(category.getFixedEnd());
                    } catch (Exception e) {
                        yield null;
                    }
                }
                yield null;
            }
            case PERMANENT -> null;
        };
    }

    /**
     * 获取下次每日重置时间
     */
    public static Instant getNextDailyReset(LocalTime resetTime) {
        LocalDateTime now = TimeZoneConfig.now(); // 使用统一时区
        LocalDateTime reset = now.toLocalDate().atTime(resetTime);

        if (now.isAfter(reset) || now.equals(reset)) {
            reset = reset.plusDays(1);
        }
        return TimeZoneConfig.toInstant(reset);
    }

    /**
     * 获取下次周常重置时间
     *
     * 修复：使用 TemporalAdjusters.next() 确保总是返回未来的日期
     * 逻辑：
     * 1. 如果今天就是刷新日且未过刷新时间 → 今天
     * 2. 如果今天就是刷新日但已过刷新时间 → 下周一
     * 3. 如果今天不是刷新日 → 下一个刷新日（一定是未来）
     */
    public static Instant getNextWeeklyReset(DayOfWeek resetDay, LocalTime resetTime) {
        LocalDate today = TimeZoneConfig.today();
        LocalTime nowTime = TimeZoneConfig.now().toLocalTime();

        LocalDate nextReset;
        if (today.getDayOfWeek() == resetDay && nowTime.isBefore(resetTime)) {
            // 今天就是刷新日，且还未到刷新时间
            nextReset = today;
        } else if (today.getDayOfWeek() == resetDay) {
            // 今天就是刷新日，但已过刷新时间
            nextReset = today.plusWeeks(1);
        } else {
            // 今天不是刷新日，获取下一个刷新日（一定是未来）
            nextReset = today.with(TemporalAdjusters.next(resetDay));
        }

        return TimeZoneConfig.toInstant(nextReset.atTime(resetTime));
    }

    /**
     * 获取下次月常重置时间
     */
    public static Instant getNextMonthlyReset(int resetDayOfMonth, LocalTime resetTime) {
        LocalDate today = TimeZoneConfig.today(); // 使用统一时区
        LocalDate nextReset;

        if (today.getDayOfMonth() < resetDayOfMonth) {
            nextReset = today.withDayOfMonth(resetDayOfMonth);
        } else if (today.getDayOfMonth() == resetDayOfMonth) {
            LocalDateTime now = TimeZoneConfig.now();
            LocalDateTime todayReset = today.atTime(resetTime);
            if (now.isAfter(todayReset) || now.equals(todayReset)) {
                nextReset = today.plusMonths(1).withDayOfMonth(
                        Math.min(resetDayOfMonth, today.plusMonths(1).lengthOfMonth()));
            } else {
                nextReset = today;
            }
        } else {
            nextReset = today.plusMonths(1).withDayOfMonth(
                    Math.min(resetDayOfMonth, today.plusMonths(1).lengthOfMonth()));
        }

        return TimeZoneConfig.toInstant(nextReset.atTime(resetTime));
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查任务是否即将过期
     *
     * @param assignedAt 分配时间
     * @param category 类别配置
     * @param threshold 提前警告的时间阈值
     * @return 是否即将过期
     */
    public static boolean isNearExpire(LocalDateTime assignedAt, TaskCategory category, Duration threshold) {
        Instant expireTime = getExpireTime(assignedAt, category);
        if (expireTime == null) {
            return false;
        }
        return TimeZoneConfig.toInstant(TimeZoneConfig.now()).plus(threshold).isAfter(expireTime);
    }

    /**
     * 获取过期时间的描述文本
     */
    public static String getExpireTimeDescription(LocalDateTime assignedAt, TaskCategory category) {
        Instant expireTime = getExpireTime(assignedAt, category);
        if (expireTime == null) {
            return "永不过期";
        }

        Instant now = TimeZoneConfig.toInstant(TimeZoneConfig.now());
        if (expireTime.isBefore(now)) {
            return "已过期";
        }

        Duration remaining = Duration.between(now, expireTime);
        return TimeUtil.formatDuration(remaining);
    }

    /**
     * 检查 FIXED 策略是否在有效期内
     * 统一使用配置时区进行判断
     */
    public static boolean isInFixedPeriod(ExpirePolicyConfig config) {
        String fixedStart = config.getFixedStart();
        String fixedEnd = config.getFixedEnd();

        if (fixedStart == null || fixedEnd == null) {
            return true; // 没有设置时间限制，视为一直有效
        }

        try {
            // 使用配置时区获取当前时间
            Instant now = TimeZoneConfig.toInstant(TimeZoneConfig.now());
            Instant start = Instant.parse(fixedStart);
            Instant end = Instant.parse(fixedEnd);

            return !now.isBefore(start) && !now.isAfter(end);
        } catch (Exception e) {
            return true; // 解析失败，默认有效
        }
    }
}
