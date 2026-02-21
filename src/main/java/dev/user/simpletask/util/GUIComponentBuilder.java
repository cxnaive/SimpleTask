package dev.user.simpletask.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * GUI Lore构建器
 * 简化GUI lore的构建，自动处理斜体样式
 */
public class GUIComponentBuilder {

    private final List<Component> lines = new ArrayList<>();

    /**
     * 添加MiniMessage格式的行
     */
    public GUIComponentBuilder add(String miniMessage) {
        lines.add(MessageUtil.guiLore(miniMessage));
        return this;
    }

    /**
     * 添加带placeholder的MiniMessage行
     */
    public GUIComponentBuilder add(String miniMessage, Map<String, String> placeholders) {
        lines.add(MessageUtil.guiLore(miniMessage, placeholders));
        return this;
    }

    /**
     * 添加已构建的Component
     */
    public GUIComponentBuilder add(Component component) {
        if (component != null) {
            lines.add(component.decoration(TextDecoration.ITALIC, false));
        }
        return this;
    }

    /**
     * 添加空行
     */
    public GUIComponentBuilder empty() {
        lines.add(Component.empty());
        return this;
    }

    /**
     * 条件添加行
     */
    public GUIComponentBuilder addIf(boolean condition, String miniMessage) {
        if (condition) {
            add(miniMessage);
        }
        return this;
    }

    /**
     * 条件添加行（带placeholder）
     */
    public GUIComponentBuilder addIf(boolean condition, String miniMessage, Map<String, String> placeholders) {
        if (condition) {
            add(miniMessage, placeholders);
        }
        return this;
    }

    /**
     * 条件添加Component
     */
    public GUIComponentBuilder addComponentIf(boolean condition, Component component) {
        if (condition) {
            add(component);
        }
        return this;
    }

    /**
     * 条件添加行（懒加载）
     */
    public GUIComponentBuilder addIf(boolean condition, Supplier<String> messageSupplier) {
        if (condition) {
            add(messageSupplier.get());
        }
        return this;
    }

    /**
     * 添加多行（每条都是MiniMessage格式）
     */
    public GUIComponentBuilder addAll(List<String> miniMessages) {
        if (miniMessages != null) {
            for (String msg : miniMessages) {
                add(msg);
            }
        }
        return this;
    }

    /**
     * 添加带前缀的列表（如目标物品列表）
     * @param prefix 前缀（如 "  <dark_gray>• "）
     * @param items 列表项（MiniMessage格式）
     */
    public GUIComponentBuilder addList(String prefix, List<String> items) {
        if (items != null) {
            for (String item : items) {
                add(prefix + item);
            }
        }
        return this;
    }

    /**
     * 添加Component列表
     */
    public GUIComponentBuilder addComponents(List<Component> components) {
        if (components != null) {
            for (Component comp : components) {
                add(comp);
            }
        }
        return this;
    }

    /**
     * 添加分隔线
     */
    public GUIComponentBuilder separator(String style) {
        add(style + "-------------------");
        return this;
    }

    /**
     * 添加默认风格的分隔线
     */
    public GUIComponentBuilder separator() {
        return separator("<dark_gray>");
    }

    /**
     * 构建并返回Component列表
     */
    public List<Component> build() {
        return new ArrayList<>(lines);
    }

    /**
     * 构建并返回不可变列表
     */
    public List<Component> buildImmutable() {
        return List.copyOf(lines);
    }

    /**
     * 获取当前行数
     */
    public int size() {
        return lines.size();
    }

    /**
     * 检查是否为空
     */
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /**
     * 清空builder
     */
    public GUIComponentBuilder clear() {
        lines.clear();
        return this;
    }

    /**
     * 创建新的空builder
     */
    public static GUIComponentBuilder create() {
        return new GUIComponentBuilder();
    }
}
