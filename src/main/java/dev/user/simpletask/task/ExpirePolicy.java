package dev.user.simpletask.task;

/**
 * 任务过期策略枚举
 */
public enum ExpirePolicy {
    DAILY("daily", "每日刷新"),
    WEEKLY("weekly", "每周刷新"),
    MONTHLY("monthly", "每月刷新"),
    RELATIVE("relative", "相对时间"),
    PERMANENT("permanent", "永不过期"),
    FIXED("fixed", "固定时间段");

    private final String id;
    private final String displayName;

    ExpirePolicy(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static ExpirePolicy fromString(String s) {
        if (s == null || s.isEmpty()) {
            return DAILY;
        }
        for (ExpirePolicy p : values()) {
            if (p.id.equalsIgnoreCase(s) || p.name().equalsIgnoreCase(s)) {
                return p;
            }
        }
        // 尝试解析相对时间前缀
        if (s.endsWith("d") || s.endsWith("h")) {
            return RELATIVE;
        }
        return DAILY;
    }

    /**
     * 是否是周期刷新策略（基于日历）
     */
    public boolean isCalendarBased() {
        return this == DAILY || this == WEEKLY || this == MONTHLY;
    }

    /**
     * 是否是相对时间策略
     */
    public boolean isRelative() {
        return this == RELATIVE;
    }

    /**
     * 是否永不过期
     */
    public boolean isPermanent() {
        return this == PERMANENT;
    }
}
