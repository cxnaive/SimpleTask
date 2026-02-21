package dev.user.simpletask.task;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import dev.user.simpletask.util.ExpireUtil;
import dev.user.simpletask.util.TimeUtil;

/**
 * 过期策略配置 - 用于任务过期和 reroll 重置等场景
 */
public class ExpirePolicyConfig {

    private ExpirePolicy policy = ExpirePolicy.DAILY;

    // 时间配置
    private LocalTime resetTime = LocalTime.of(4, 0);
    private DayOfWeek resetDayOfWeek = DayOfWeek.MONDAY;
    private int resetDayOfMonth = 1;

    // 相对时间配置
    private Duration duration = Duration.ofDays(7);

    // 固定时间配置
    private String fixedStart;
    private String fixedEnd;

    public ExpirePolicyConfig() {}

    public ExpirePolicyConfig(ExpirePolicy policy) {
        this.policy = policy;
    }

    // ==================== 快捷创建方法 ====================

    public static ExpirePolicyConfig daily(LocalTime resetTime) {
        ExpirePolicyConfig config = new ExpirePolicyConfig(ExpirePolicy.DAILY);
        config.setResetTime(resetTime);
        return config;
    }

    public static ExpirePolicyConfig weekly(DayOfWeek dayOfWeek, LocalTime resetTime) {
        ExpirePolicyConfig config = new ExpirePolicyConfig(ExpirePolicy.WEEKLY);
        config.setResetDayOfWeek(dayOfWeek);
        config.setResetTime(resetTime);
        return config;
    }

    public static ExpirePolicyConfig monthly(int dayOfMonth, LocalTime resetTime) {
        ExpirePolicyConfig config = new ExpirePolicyConfig(ExpirePolicy.MONTHLY);
        config.setResetDayOfMonth(dayOfMonth);
        config.setResetTime(resetTime);
        return config;
    }

    public static ExpirePolicyConfig relative(Duration duration) {
        ExpirePolicyConfig config = new ExpirePolicyConfig(ExpirePolicy.RELATIVE);
        config.setDuration(duration);
        return config;
    }

    public static ExpirePolicyConfig permanent() {
        return new ExpirePolicyConfig(ExpirePolicy.PERMANENT);
    }

    // ==================== 核心判断方法 ====================

    // ==================== 核心判断方法 ====================
    // 所有过期判断统一委托给 ExpireUtil

    /**
     * 检查从指定日期时间开始是否已经过期
     * @param startTime 开始日期时间
     * @return 是否已经过期
     */
    public boolean isExpired(LocalDateTime startTime) {
        if (startTime == null) {
            return true;
        }
        return ExpireUtil.isExpired(startTime, this);
    }

    /**
     * 检查 FIXED 策略是否在有效期内
     */
    public boolean isInFixedPeriod() {
        return ExpireUtil.isInFixedPeriod(this);
    }

    /**
     * 获取下次过期/重置时间的描述
     */
    public String getNextResetDescription() {
        return switch (policy) {
            case DAILY -> "明天";
            case WEEKLY -> "下周一";
            case MONTHLY -> "下月" + resetDayOfMonth + "号";
            case RELATIVE -> duration != null ? TimeUtil.formatDuration(duration) + "后" : null;
            case PERMANENT -> null;
            case FIXED -> null;
        };
    }

    // ==================== Getters and Setters ====================

    public ExpirePolicy getPolicy() {
        return policy;
    }

    public void setPolicy(ExpirePolicy policy) {
        this.policy = policy;
    }

    public LocalTime getResetTime() {
        return resetTime;
    }

    public void setResetTime(LocalTime resetTime) {
        this.resetTime = resetTime;
    }

    public DayOfWeek getResetDayOfWeek() {
        return resetDayOfWeek;
    }

    public void setResetDayOfWeek(DayOfWeek resetDayOfWeek) {
        this.resetDayOfWeek = resetDayOfWeek;
    }

    public int getResetDayOfMonth() {
        return resetDayOfMonth;
    }

    public void setResetDayOfMonth(int resetDayOfMonth) {
        this.resetDayOfMonth = resetDayOfMonth;
    }

    public Duration getDuration() {
        return duration;
    }

    public void setDuration(Duration duration) {
        this.duration = duration;
    }

    public String getFixedStart() {
        return fixedStart;
    }

    public void setFixedStart(String fixedStart) {
        this.fixedStart = fixedStart;
    }

    public String getFixedEnd() {
        return fixedEnd;
    }

    public void setFixedEnd(String fixedEnd) {
        this.fixedEnd = fixedEnd;
    }
}
