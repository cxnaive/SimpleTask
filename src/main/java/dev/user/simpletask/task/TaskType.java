package dev.user.simpletask.task;

public enum TaskType {
    CHAT(true),         // 发言，可选目标内容（指定关键词）
    CRAFT(true),        // 合成，需要目标物品
    FISH(true),         // 钓鱼，可选目标物品（指定钓获物品）
    CONSUME(true),      // 消耗，可选目标物品
    BREAK(true),        // 挖掘，需要目标方块
    HARVEST(true),      // 收获，需要目标作物
    SUBMIT(true),       // 提交，需要提交特定物品（点击GUI时扫描背包）
    KILL(true),         // 击杀，需要目标实体类型
    BREED(true),        // 繁殖，需要目标实体类型
    COMMAND(true);      // 命令，需要执行指定命令

    private final boolean requiresTarget;

    TaskType(boolean requiresTarget) {
        this.requiresTarget = requiresTarget;
    }

    public boolean requiresTarget() {
        return requiresTarget;
    }

    public String getDisplayName() {
        return switch (this) {
            case CHAT -> "发言";
            case CRAFT -> "合成";
            case FISH -> "钓鱼";
            case CONSUME -> "消耗";
            case BREAK -> "挖掘";
            case HARVEST -> "收获";
            case SUBMIT -> "提交";
            case KILL -> "击杀";
            case BREED -> "繁殖";
            case COMMAND -> "命令";
        };
    }
}
