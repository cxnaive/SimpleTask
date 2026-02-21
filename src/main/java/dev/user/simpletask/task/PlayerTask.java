package dev.user.simpletask.task;

import dev.user.simpletask.task.category.TaskCategory;
import dev.user.simpletask.util.ExpireUtil;
import dev.user.simpletask.util.TimeUtil;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PlayerTask {

    // 使用复合唯一键 (playerUuid, taskKey, assignedAt) 而不是自增id，确保跨服一致性
    private final UUID playerUuid;
    private final String taskKey;
    private final TaskTemplate template;
    private final AtomicInteger currentProgress;
    private final AtomicBoolean completed;
    private final AtomicBoolean claimed;

    // 任务分配时间（带时分秒，用于精确过期判断）
    private final LocalDateTime assignedAt;

    // 兼容字段：任务日期（用于数据库查询和显示）
    private final LocalDate taskDate;

    // 任务分类（过期策略从 category 配置获取）
    private final String category;

    // ==================== 构造方法 ====================

    /**
     * 创建新任务（使用当前时间作为分配时间，截断到秒级确保跨数据库一致性）
     */
    public PlayerTask(UUID playerUuid, String taskKey, TaskTemplate template, String category) {
        this(playerUuid, taskKey, template, 0, false, false,
             TimeUtil.nowTruncated(), category);
    }

    /**
     * 完整构造方法（时间戳自动截断到秒级确保跨数据库一致性）
     */
    public PlayerTask(UUID playerUuid, String taskKey, TaskTemplate template,
                      int currentProgress, boolean completed, boolean claimed,
                      LocalDateTime assignedAt, String category) {
        this.playerUuid = playerUuid;
        this.taskKey = taskKey;
        this.template = template;
        this.currentProgress = new AtomicInteger(currentProgress);
        this.completed = new AtomicBoolean(completed);
        this.claimed = new AtomicBoolean(claimed);
        // 截断到秒级确保跨数据库 TIMESTAMP 一致性
        this.assignedAt = assignedAt != null ? TimeUtil.truncateToSeconds(assignedAt) : TimeUtil.nowTruncated();
        this.taskDate = this.assignedAt.toLocalDate();
        this.category = category != null ? category : "daily";
    }

    /**
     * 从数据库加载的兼容构造方法（旧数据使用 task_date）
     * @deprecated 使用带 assignedAt 的构造方法
     */
    @Deprecated
    public PlayerTask(UUID playerUuid, String taskKey, TaskTemplate template,
                      int currentProgress, boolean completed, boolean claimed,
                      LocalDate taskDate, String category) {
        this(playerUuid, taskKey, template, currentProgress, completed, claimed,
             taskDate.atTime(4, 0), category);
    }

    // ==================== Getter 方法 ====================

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getTaskKey() {
        return taskKey;
    }

    public TaskTemplate getTemplate() {
        return template;
    }

    public int getCurrentProgress() {
        return currentProgress.get();
    }

    public void setCurrentProgress(int currentProgress) {
        this.currentProgress.set(Math.min(currentProgress, template.getTargetAmount()));
        checkCompletion();
    }

    public void addProgress(int amount) {
        int target = template.getTargetAmount();
        this.currentProgress.updateAndGet(current -> Math.min(current + amount, target));
        checkCompletion();
    }

    private void checkCompletion() {
        // 使用 CAS 避免 check-then-act 竞态条件
        completed.compareAndSet(false, currentProgress.get() >= template.getTargetAmount());
    }

    public boolean isCompleted() {
        return completed.get();
    }

    public void setCompleted(boolean completed) {
        this.completed.set(completed);
    }

    public boolean isClaimed() {
        return claimed.get();
    }

    public void setClaimed(boolean claimed) {
        this.claimed.set(claimed);
    }

    /**
     * 获取任务分配时间（带时分秒）
     */
    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }

    /**
     * 获取任务日期（兼容旧代码）
     */
    public LocalDate getTaskDate() {
        return taskDate;
    }

    public String getCategory() {
        return category;
    }

    public int getTargetProgress() {
        return template.getTargetAmount();
    }

    public double getProgressPercentage() {
        return (double) currentProgress.get() / template.getTargetAmount() * 100;
    }

    public String getProgressBar(int length) {
        int filled = (int) Math.round(getProgressPercentage() / 100.0 * length);
        StringBuilder bar = new StringBuilder();
        bar.append("<yellow>");
        for (int i = 0; i < length; i++) {
            if (i < filled) {
                bar.append("■");
            } else {
                bar.append("□");
            }
        }
        bar.append("<reset>");
        return bar.toString();
    }

    // ==================== 过期判断 ====================

    /**
     * 判断任务是否已过期（基于 assignedAt 时间戳）
     * @param category 任务类别配置
     * @return 是否已过期
     */
    public boolean isExpired(TaskCategory category) {
        if (category == null) {
            return false; // 没有类别信息无法判断，默认不过期
        }
        return ExpireUtil.isExpired(assignedAt, category);
    }

    /**
     * 获取过期时间（用于显示）
     */
    public Instant getExpireTime(TaskCategory category) {
        return ExpireUtil.getExpireTime(assignedAt, category);
    }

    /**
     * 检查是否是即将过期（用于警告提示）
     * @param category 任务类别配置
     * @param threshold 提前多久警告
     * @return 是否即将过期
     */
    public boolean isNearExpire(TaskCategory category, Duration threshold) {
        return ExpireUtil.isNearExpire(assignedAt, category, threshold);
    }

    /**
     * 获取过期时间的描述文本
     */
    public String getExpireTimeDescription(TaskCategory category) {
        return ExpireUtil.getExpireTimeDescription(assignedAt, category);
    }

    @Override
    public String toString() {
        return "PlayerTask{" +
                "playerUuid=" + playerUuid +
                ", taskKey='" + taskKey + '\'' +
                ", category='" + category + '\'' +
                ", assignedAt=" + assignedAt +
                ", currentProgress=" + currentProgress.get() +
                ", completed=" + completed.get() +
                ", claimed=" + claimed.get() +
                '}';
    }
}
