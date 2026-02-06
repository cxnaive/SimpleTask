package dev.user.simpletask.task;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PlayerTask {

    // 使用复合唯一键 (playerUuid, taskKey, taskDate) 而不是自增id，确保跨服一致性
    private final UUID playerUuid;
    private final String taskKey;
    private final TaskTemplate template;
    private final AtomicInteger currentProgress;
    private final AtomicBoolean completed;
    private final AtomicBoolean claimed;
    private final LocalDate taskDate;

    public PlayerTask(UUID playerUuid, String taskKey, TaskTemplate template, LocalDate taskDate) {
        this.playerUuid = playerUuid;
        this.taskKey = taskKey;
        this.template = template;
        this.currentProgress = new AtomicInteger(0);
        this.completed = new AtomicBoolean(false);
        this.claimed = new AtomicBoolean(false);
        this.taskDate = taskDate;
    }

    public PlayerTask(UUID playerUuid, String taskKey, TaskTemplate template,
                      int currentProgress, boolean completed, boolean claimed, LocalDate taskDate) {
        this.playerUuid = playerUuid;
        this.taskKey = taskKey;
        this.template = template;
        this.currentProgress = new AtomicInteger(currentProgress);
        this.completed = new AtomicBoolean(completed);
        this.claimed = new AtomicBoolean(claimed);
        this.taskDate = taskDate;
    }

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

    public LocalDate getTaskDate() {
        return taskDate;
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

    @Override
    public String toString() {
        return "PlayerTask{" +
                "playerUuid=" + playerUuid +
                ", taskKey='" + taskKey + '\'' +
                ", currentProgress=" + currentProgress.get() +
                ", completed=" + completed.get() +
                ", claimed=" + claimed.get() +
                ", taskDate=" + taskDate +
                '}';
    }
}
