package dev.user.simpletask;

import dev.user.simpletask.task.*;
import dev.user.simpletask.task.manager.TaskGenerator;
import dev.user.simpletask.util.ExpireUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompatibilityTest {
    private TaskTemplate template(TaskType type, List<String> targets) {
        return new TaskTemplate("fixture", "Fixture", type, targets, 10,
                List.of("line one", "line two"), "minecraft:paper", 10,
                new Reward(5, List.of(new Reward.RewardItem("minecraft:diamond", 2)), List.of("say {player}")));
    }

    @ParameterizedTest
    @EnumSource(TaskType.class)
    void everyTaskTypeSurvivesDatabaseJson(TaskType type) {
        TaskTemplate original = template(type, List.of("minecraft:stone", "minecraft:dirt"));
        original.setCategory("weekly");
        original.setVersion(7);
        TaskTemplate restored = TaskTemplate.fromJson(original.toJson());
        assertEquals(type, restored.getType());
        assertEquals(original.getTargetItems(), restored.getTargetItems());
        assertEquals(original.getDescription(), restored.getDescription());
        assertEquals("weekly", restored.getCategory());
        assertEquals(7, restored.getVersion());
        assertEquals(5, restored.getReward().getMoney());
        assertEquals(2, restored.getReward().getItems().getFirst().getAmount());
    }

    @Test
    void targetMatchingPreservesChatCommandAndMultipleItems() {
        assertTrue(template(TaskType.CHAT, List.of("hello")).matchesTarget("say hello world", null));
        assertTrue(template(TaskType.COMMAND, List.of("home bed")).matchesTarget("home bed extra", null));
        assertFalse(template(TaskType.COMMAND, List.of("home bed")).matchesTarget("warp bed", null));
        assertTrue(template(TaskType.BREAK, List.of("minecraft:stone", "minecraft:dirt")).matchesTarget("minecraft:dirt", null));
        assertFalse(template(TaskType.BREAK, List.of("minecraft:stone")).matchesTarget("minecraft:dirt", null));
        assertTrue(template(TaskType.CHAT, List.of()).matchesTarget("anything", null));
    }

    @Test
    void dailyAndWeeklyBoundariesUseConfiguredResetTime() {
        LocalDateTime before = LocalDateTime.of(2026, 10, 12, 3, 59);
        LocalDateTime after = before.plusMinutes(1);
        LocalTime reset = LocalTime.of(4, 0);
        assertFalse(ExpireUtil.isDailyExpired(before, before, reset));
        assertTrue(ExpireUtil.isDailyExpired(before, after, reset));
        assertFalse(ExpireUtil.isWeeklyExpired(before, before, DayOfWeek.MONDAY, reset));
        assertTrue(ExpireUtil.isWeeklyExpired(before, after, DayOfWeek.MONDAY, reset));
    }

    @Test
    void monthlyAndRelativeBoundariesPreserveProgressUntilExpiration() {
        LocalDateTime before = LocalDateTime.of(2026, 11, 1, 3, 59);
        assertFalse(ExpireUtil.isMonthlyExpired(before, before, 1, LocalTime.of(4, 0)));
        assertTrue(ExpireUtil.isMonthlyExpired(before, before.plusMinutes(1), 1, LocalTime.of(4, 0)));
        assertFalse(ExpireUtil.isRelativeExpired(before, before.plusHours(1), Duration.ofDays(7)));
        assertTrue(ExpireUtil.isRelativeExpired(before, before.plusDays(8), Duration.ofDays(7)));
    }

    @Test
    void fixedAndPermanentPoliciesAndPapiParsing() {
        var now = LocalDateTime.of(2026, 10, 10, 12, 0);
        assertFalse(ExpireUtil.isFixedExpired(now, "2026-10-01T00:00:00", "2026-11-01T00:00:00"));
        assertTrue(ExpireUtil.isFixedExpired(now, "2026-10-01T00:00:00", "2026-10-02T00:00:00"));
        assertFalse(ExpireUtil.isExpired(now.minusYears(10), ExpirePolicyConfig.permanent()));
        var expression = PAPIExpression.parse(" %player_level% >= 100 ");
        assertNotNull(expression);
        assertEquals(100, expression.getTargetNumericValue());
        assertNull(PAPIExpression.parse("missing placeholder"));
    }

    @Test
    void weightedSelectionNeverDuplicatesTemplates() {
        TaskTemplate first = template(TaskType.CHAT, List.of());
        TaskTemplate second = template(TaskType.BREAK, List.of());
        var selected = TaskGenerator.selectRandomTasksFromList(List.of(first, second), 20);
        assertEquals(2, selected.size());
        assertEquals(2, selected.stream().distinct().count());
        assertTrue(TaskGenerator.selectRandomTasksFromList(List.of(first), 0).isEmpty());
    }
}
