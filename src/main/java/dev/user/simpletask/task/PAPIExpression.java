package dev.user.simpletask.task;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PAPI 表达式解析器
 * 解析格式：%placeholder% operator value
 * 支持操作符：>=, <=, >, <, =
 * 示例：%player_level% >= 100
 */
public record PAPIExpression(String placeholder, String operator, String value) {

    // 解析表达式：提取占位符、操作符、比较值
    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("^(%[^%]+%)\\s*(>=|<=|>|<|=)\\s*(.+)$");

    /**
     * 从 target-item 字符串解析 PAPI 表达式
     *
     * @param expressionStr 表达式字符串，如 "%player_level% >= 100"
     * @return 解析后的 PAPIExpression，格式错误返回 null
     */
    public static PAPIExpression parse(String expressionStr) {
        if (expressionStr == null || expressionStr.isBlank()) {
            return null;
        }
        Matcher matcher = EXPRESSION_PATTERN.matcher(expressionStr.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new PAPIExpression(matcher.group(1), matcher.group(2), matcher.group(3).trim());
    }

    /**
     * 评估表达式是否满足条件
     *
     * @param player 玩家
     * @return 表达式是否为 true；PAPI 未安装时返回 false
     */
    public boolean evaluate(Player player) {
        if (!isPAPIAvailable()) {
            return false;
        }

        String resolved = PlaceholderAPI.setPlaceholders(player, placeholder);
        if (resolved == null || resolved.equals(placeholder)) {
            // 占位符未被替换，无法比较
            return false;
        }

        return compare(resolved, operator, value);
    }

    /**
     * 获取占位符的数值（用于进度显示）
     *
     * @param player 玩家
     * @return 解析后的数值；PAPI 未安装或无法解析时返回 -1
     */
    public double getNumericValue(Player player) {
        if (!isPAPIAvailable()) {
            return -1;
        }

        String resolved = PlaceholderAPI.setPlaceholders(player, placeholder);
        if (resolved == null || resolved.equals(placeholder)) {
            return -1;
        }

        try {
            return Double.parseDouble(resolved.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 获取目标比较值（数值形式）
     * 用于 GUI 进度显示
     *
     * @return 解析后的目标数值；无法解析时返回 -1
     */
    public double getTargetNumericValue() {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 比较解析后的值与目标值
     */
    private boolean compare(String resolved, String operator, String targetValue) {
        // 尝试数值比较
        try {
            double resolvedNum = Double.parseDouble(resolved.trim());
            double targetNum = Double.parseDouble(targetValue.trim());

            return switch (operator) {
                case ">=" -> resolvedNum >= targetNum;
                case "<=" -> resolvedNum <= targetNum;
                case ">" -> resolvedNum > targetNum;
                case "<" -> resolvedNum < targetNum;
                case "=" -> resolvedNum == targetNum;
                default -> false;
            };
        } catch (NumberFormatException e) {
            // 无法解析为数值，进行字符串比较（仅 = 操作符有意义）
            if ("=".equals(operator)) {
                return resolved.trim().equalsIgnoreCase(targetValue.trim());
            }
            // 其他操作符无法对非数值进行比较
            return false;
        }
    }

    /**
     * 检查 PlaceholderAPI 是否可用
     */
    public static boolean isPAPIAvailable() {
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    /**
     * 获取表达式的显示文本（用于 GUI）
     * 示例：%player_level% >= 100 → "player_level >= 100"
     */
    public String getDisplayText() {
        // 去掉占位符的 % 符号，更易读
        String placeholderName = placeholder.replace("%", "");
        return placeholderName + " " + operator + " " + value;
    }
}