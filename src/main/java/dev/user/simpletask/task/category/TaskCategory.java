package dev.user.simpletask.task.category;

import dev.user.simpletask.task.ExpirePolicy;
import dev.user.simpletask.task.ExpirePolicyConfig;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

/**
 * 任务类别配置
 */
public class TaskCategory {

    private String id;
    private boolean enabled = true;

    // 显示配置
    private String displayName;
    private List<String> lore;
    private String item;
    private int slot = -1;

    // 分配配置
    private int maxConcurrent = 3;
    private boolean autoAssign = true;
    private boolean repeatable = true;
    private boolean autoClaim = false;  // 自动领取奖励
    private Duration resetAfterComplete;

    // 任务过期策略配置
    private ExpirePolicyConfig expirePolicyConfig = new ExpirePolicyConfig(ExpirePolicy.DAILY);
    private Duration warningBefore = Duration.ofHours(24);  // 过期警告提前时间

    // Reroll 配置
    private boolean rerollEnabled = true;
    private double rerollCost = 0.0;
    private int rerollMaxCount = 3;
    private boolean rerollKeepCompleted = true;  // 刷新时保留已完成的任务
    private ExpirePolicyConfig rerollResetConfig = new ExpirePolicyConfig(ExpirePolicy.DAILY);

    public TaskCategory() {}

    public TaskCategory(String id) {
        this.id = id;
    }

    // ==================== 快捷方法 ====================

    public ExpirePolicy getExpirePolicy() {
        return expirePolicyConfig.getPolicy();
    }

    public void setExpirePolicy(ExpirePolicy policy) {
        expirePolicyConfig.setPolicy(policy);
    }

    public LocalTime getResetTime() {
        return expirePolicyConfig.getResetTime();
    }

    public void setResetTime(LocalTime resetTime) {
        expirePolicyConfig.setResetTime(resetTime);
    }

    public DayOfWeek getResetDayOfWeek() {
        return expirePolicyConfig.getResetDayOfWeek();
    }

    public void setResetDayOfWeek(DayOfWeek resetDayOfWeek) {
        expirePolicyConfig.setResetDayOfWeek(resetDayOfWeek);
    }

    public int getResetDayOfMonth() {
        return expirePolicyConfig.getResetDayOfMonth();
    }

    public void setResetDayOfMonth(int resetDayOfMonth) {
        expirePolicyConfig.setResetDayOfMonth(resetDayOfMonth);
    }

    public Duration getDefaultDuration() {
        return expirePolicyConfig.getDuration();
    }

    public void setDefaultDuration(Duration duration) {
        expirePolicyConfig.setDuration(duration);
    }

    public String getFixedStart() {
        return expirePolicyConfig.getFixedStart();
    }

    public void setFixedStart(String fixedStart) {
        expirePolicyConfig.setFixedStart(fixedStart);
    }

    public String getFixedEnd() {
        return expirePolicyConfig.getFixedEnd();
    }

    public void setFixedEnd(String fixedEnd) {
        expirePolicyConfig.setFixedEnd(fixedEnd);
    }

    // ==================== Reroll 快捷方法 ====================

    public ExpirePolicy getRerollResetPolicy() {
        return rerollResetConfig.getPolicy();
    }

    public void setRerollResetPolicy(ExpirePolicy policy) {
        rerollResetConfig.setPolicy(policy);
    }

    public LocalTime getRerollResetTime() {
        return rerollResetConfig.getResetTime();
    }

    public void setRerollResetTime(LocalTime resetTime) {
        rerollResetConfig.setResetTime(resetTime);
    }

    public DayOfWeek getRerollResetDayOfWeek() {
        return rerollResetConfig.getResetDayOfWeek();
    }

    public void setRerollResetDayOfWeek(DayOfWeek dayOfWeek) {
        rerollResetConfig.setResetDayOfWeek(dayOfWeek);
    }

    public int getRerollResetDayOfMonth() {
        return rerollResetConfig.getResetDayOfMonth();
    }

    public void setRerollResetDayOfMonth(int dayOfMonth) {
        rerollResetConfig.setResetDayOfMonth(dayOfMonth);
    }

    public Duration getRerollResetDuration() {
        return rerollResetConfig.getDuration();
    }

    public void setRerollResetDuration(Duration duration) {
        rerollResetConfig.setDuration(duration);
    }

    // ==================== Getters and Setters ====================

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDisplayName() {
        return displayName != null ? displayName : id;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public List<String> getLore() {
        return lore;
    }

    public void setLore(List<String> lore) {
        this.lore = lore;
    }

    public String getItem() {
        return item != null ? item : "minecraft:paper";
    }

    public void setItem(String item) {
        this.item = item;
    }

    public int getSlot() {
        return slot;
    }

    public void setSlot(int slot) {
        this.slot = slot;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public boolean isAutoAssign() {
        return autoAssign;
    }

    public void setAutoAssign(boolean autoAssign) {
        this.autoAssign = autoAssign;
    }

    public boolean isRepeatable() {
        return repeatable;
    }

    public void setRepeatable(boolean repeatable) {
        this.repeatable = repeatable;
    }

    public boolean isAutoClaim() {
        return autoClaim;
    }

    public void setAutoClaim(boolean autoClaim) {
        this.autoClaim = autoClaim;
    }

    public Duration getResetAfterComplete() {
        return resetAfterComplete;
    }

    public void setResetAfterComplete(Duration resetAfterComplete) {
        this.resetAfterComplete = resetAfterComplete;
    }

    public Duration getWarningBefore() {
        return warningBefore;
    }

    public void setWarningBefore(Duration warningBefore) {
        this.warningBefore = warningBefore;
    }

    public ExpirePolicyConfig getExpirePolicyConfig() {
        return expirePolicyConfig;
    }

    public void setExpirePolicyConfig(ExpirePolicyConfig config) {
        this.expirePolicyConfig = config;
    }

    public boolean isRerollEnabled() {
        return rerollEnabled;
    }

    public void setRerollEnabled(boolean rerollEnabled) {
        this.rerollEnabled = rerollEnabled;
    }

    public double getRerollCost() {
        return rerollCost;
    }

    public void setRerollCost(double rerollCost) {
        this.rerollCost = rerollCost;
    }

    public int getRerollMaxCount() {
        return rerollMaxCount;
    }

    public void setRerollMaxCount(int rerollMaxCount) {
        this.rerollMaxCount = rerollMaxCount;
    }

    public ExpirePolicyConfig getRerollResetConfig() {
        return rerollResetConfig;
    }

    public void setRerollResetConfig(ExpirePolicyConfig config) {
        this.rerollResetConfig = config;
    }

    public boolean isRerollKeepCompleted() {
        return rerollKeepCompleted;
    }

    public void setRerollKeepCompleted(boolean rerollKeepCompleted) {
        this.rerollKeepCompleted = rerollKeepCompleted;
    }
}
