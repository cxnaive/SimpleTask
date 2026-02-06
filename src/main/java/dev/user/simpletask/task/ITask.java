package dev.user.simpletask.task;

import org.bukkit.entity.Player;

/**
 * 任务基础接口，预留任务链扩展
 */
public interface ITask {

    /**
     * 获取任务ID
     */
    String getTaskId();

    /**
     * 获取任务类型
     */
    TaskType getType();

    /**
     * 检查任务是否完成
     */
    boolean isCompleted();

    /**
     * 发放任务奖励
     *
     * @param player 玩家
     * @param plugin 插件实例
     * @return 是否成功发放
     */
    boolean grantReward(Player player, dev.user.simpletask.SimpleTaskPlugin plugin);
}
