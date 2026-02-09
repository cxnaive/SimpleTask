package dev.user.simpletask.util;

import de.tr7zw.nbtapi.NBT;
import de.tr7zw.nbtapi.NBTType;
import de.tr7zw.nbtapi.iface.ReadableNBT;
import de.tr7zw.nbtapi.iface.ReadWriteNBT;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility class for NBT path parsing and manipulation.
 */
public final class NBTPathUtils {

    // ==================== Path Parsing Patterns ====================
    private static final Pattern BYTE_PATTERN = Pattern.compile("^(-?\\d+)[bB]$");
    private static final Pattern SHORT_PATTERN = Pattern.compile("^(-?\\d+)[sS]$");
    private static final Pattern LONG_PATTERN = Pattern.compile("^(-?\\d+)[lL]$");
    private static final Pattern FLOAT_PATTERN = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)[fF]$");
    private static final Pattern DOUBLE_PATTERN = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)[dD]$");
    private static final Pattern QUOTED_STRING = Pattern.compile("^\"(.*)\"$");
    private static final Pattern PATH_SEGMENT_PATTERN = Pattern.compile("^([^\\[\\]{]+)(?:\\[([^\\]]+)\\])?$");
    private static final Pattern COMPOUND_FILTER_PATTERN = Pattern.compile("\\{(.+)\\}");

    // ==================== Path Segment ====================

    /**
     * Represents a parsed NBT path segment with optional index or filter.
     */
    public static class PathSegment {
        private final String key;
        private final Integer index;
        private final ReadableNBT filter;

        public PathSegment(String key, Integer index, ReadableNBT filter) {
            this.key = key;
            this.index = index;
            this.filter = filter;
        }

        public String getKey() {
            return key;
        }

        public Integer getIndex() {
            return index;
        }

        public ReadableNBT getFilter() {
            return filter;
        }

        public boolean hasIndex() {
            return index != null;
        }

        public boolean hasFilter() {
            return filter != null;
        }

        @Override
        public String toString() {
            if (hasIndex()) return key + "[" + index + "]";
            if (hasFilter()) return key + "[{" + filter + "}]";
            return key;
        }
    }

    // ==================== Path Parsing ====================

    /**
     * Parses an NBT path into segments.
     * Supports: key, key[0], key[{Slot:0b}], key[{Slot:0b,id:"minecraft:diamond"}]
     * Also supports quoted keys: key."sub key" or key."key.with:dot"
     */
    public static List<PathSegment> parsePath(String path) {
        List<PathSegment> segments = new ArrayList<>();

        // Split by dots, respecting quotes and escapes
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        boolean inQuotes = false;

        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\' && !escaped) {
                escaped = true;
            } else if (c == '"' && !escaped) {
                inQuotes = !inQuotes;
                current.append(c);
                escaped = false;
            } else if (c == '.' && !escaped && !inQuotes) {
                parts.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
                escaped = false;
            }
        }
        parts.add(current.toString());

        // Parse each part as a path segment
        for (String part : parts) {
            // Remove surrounding quotes from quoted keys
            String cleanPart = part;
            if (part.startsWith("\"") && part.endsWith("\"") && part.length() > 1) {
                cleanPart = part.substring(1, part.length() - 1);
            } else {
                // Remove escape sequences for dots
                cleanPart = part.replace("\\.", ".");
            }

            // Check for index or filter syntax
            Matcher matcher = PATH_SEGMENT_PATTERN.matcher(cleanPart);
            if (matcher.matches()) {
                String key = matcher.group(1);
                String indexOrFilter = matcher.group(2);

                if (indexOrFilter == null) {
                    segments.add(new PathSegment(key, null, null));
                } else {
                    // Check if it's a compound filter
                    Matcher filterMatcher = COMPOUND_FILTER_PATTERN.matcher(indexOrFilter);
                    if (filterMatcher.matches()) {
                        try {
                            ReadableNBT filter = NBT.parseNBT("{" + filterMatcher.group(1) + "}");
                            segments.add(new PathSegment(key, null, filter));
                        } catch (Exception e) {
                            // If parsing fails, try as integer
                            try {
                                segments.add(new PathSegment(key, Integer.parseInt(indexOrFilter), null));
                            } catch (NumberFormatException ex) {
                                segments.add(new PathSegment(key, null, null));
                            }
                        }
                    } else {
                        // Try to parse as integer index
                        try {
                            segments.add(new PathSegment(key, Integer.parseInt(indexOrFilter), null));
                        } catch (NumberFormatException e) {
                            segments.add(new PathSegment(key, null, null));
                        }
                    }
                }
            } else {
                // No bracket syntax, use the whole part as key
                segments.add(new PathSegment(cleanPart, null, null));
            }
        }

        return segments;
    }

    /**
     * Represents a parsed NBT path with parent and key components.
     */
    public static class PathComponents {
        private final String parentPath;
        private final String key;

        public PathComponents(String parentPath, String key) {
            this.parentPath = parentPath;
            this.key = key;
        }

        public String getParentPath() {
            return parentPath;
        }

        public String getKey() {
            return key;
        }
    }

    /**
     * Parses a path into parent and key components.
     */
    public static PathComponents parsePathComponents(String path) {
        int lastDot = path.lastIndexOf('.');
        if (lastDot != -1) {
            return new PathComponents(path.substring(0, lastDot), path.substring(lastDot + 1));
        }
        return new PathComponents("", path);
    }

    // ==================== Value Parsing ====================

    /**
     * Parses a string value into the appropriate NBT type.
     * Supports type suffixes: 10b (byte), 10s (short), 10L (long), 10.0f (float), 10.0d (double)
     */
    public static Object parseValue(String input) {
        if (input.startsWith("{")) {
            return NBT.parseNBT(input);
        }
        if (input.startsWith("[")) {
            // NBT-API 要求根节点必须是复合标签，所以将列表包装到临时复合标签中
            ReadableNBT temp = NBT.parseNBT("{_temp: " + input + "}");
            return getTypedValue(temp, "_temp");
        }
        if (input.equalsIgnoreCase("true")) return true;
        if (input.equalsIgnoreCase("false")) return false;

        Matcher m = QUOTED_STRING.matcher(input);
        if (m.matches()) return m.group(1);

        m = BYTE_PATTERN.matcher(input);
        if (m.matches()) return Byte.parseByte(m.group(1));

        m = SHORT_PATTERN.matcher(input);
        if (m.matches()) return Short.parseShort(m.group(1));

        m = LONG_PATTERN.matcher(input);
        if (m.matches()) return Long.parseLong(m.group(1));

        m = FLOAT_PATTERN.matcher(input);
        if (m.matches()) return Float.parseFloat(m.group(1));

        m = DOUBLE_PATTERN.matcher(input);
        if (m.matches()) return Double.parseDouble(m.group(1));

        try {
            if (input.contains(".")) return Double.parseDouble(input);
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return input; // Plain string
        }
    }

    // ==================== General Utilities ====================

    /**
     * Joins array elements from start index with spaces.
     */
    public static String joinArgs(String[] args, int start) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length));
    }

    /**
     * Filters a list of options by a prefix (case-insensitive).
     */
    public static List<String> filter(List<String> options, String input) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(input.toLowerCase()))
                .collect(Collectors.toList());
    }

    // ==================== Server/Bukkit Utilities ====================

    /**
     * Gets the world for a command sender.
     */
    public static World getWorld(org.bukkit.command.CommandSender sender) {
        return (sender instanceof Entity) ? ((Entity) sender).getWorld() : Bukkit.getWorlds().get(0);
    }

    /**
     * Parses a location from x, y, z strings.
     */
    public static Location parseLocation(World world, String x, String y, String z) {
        return new Location(world, Integer.parseInt(x), Integer.parseInt(y), Integer.parseInt(z));
    }

    // ==================== NBT Resolution ====================

    /**
     * Resolves a list element by index.
     */
    public static Object resolveListIndex(ReadableNBT nbt, String key, int index) {
        NBTType type = nbt.getType(key);
        if (type != NBTType.NBTTagList) {
            return null;
        }

        NBTType listType = nbt.getListType(key);
        switch (listType) {
            case NBTTagCompound:
                var compoundList = nbt.getCompoundList(key);
                if (index >= 0 && index < compoundList.size()) {
                    return compoundList.get(index);
                }
                return null;
            case NBTTagString:
                var stringList = nbt.getStringList(key);
                if (index >= 0 && index < stringList.size()) {
                    return stringList.get(index);
                }
                return null;
            case NBTTagInt:
                var intList = nbt.getIntegerList(key);
                if (index >= 0 && index < intList.size()) {
                    return intList.get(index);
                }
                return null;
            case NBTTagLong:
                var longList = nbt.getLongList(key);
                if (index >= 0 && index < longList.size()) {
                    return longList.get(index);
                }
                return null;
            case NBTTagDouble:
                var doubleList = nbt.getDoubleList(key);
                if (index >= 0 && index < doubleList.size()) {
                    return doubleList.get(index);
                }
                return null;
            case NBTTagFloat:
                var floatList = nbt.getFloatList(key);
                if (index >= 0 && index < floatList.size()) {
                    return floatList.get(index);
                }
                return null;
            default:
                return null;
        }
    }

    /**
     * Resolves a list element by compound filter.
     * Example: Inventory[{Slot:0b}] finds the item with Slot: 0b
     */
    public static ReadableNBT resolveListFilter(ReadableNBT nbt, String key, ReadableNBT filter) {
        NBTType type = nbt.getType(key);
        if (type != NBTType.NBTTagList) {
            return null;
        }

        NBTType listType = nbt.getListType(key);
        if (listType != NBTType.NBTTagCompound) {
            return null;
        }

        var compoundList = nbt.getCompoundList(key);
        for (ReadableNBT element : compoundList) {
            if (matchesFilter(element, filter)) {
                return element;
            }
        }

        return null;
    }

    /**
     * Checks if an NBT compound matches a filter.
     * All key-value pairs in the filter must match.
     */
    public static boolean matchesFilter(ReadableNBT element, ReadableNBT filter) {
        for (String filterKey : filter.getKeys()) {
            if (!element.hasTag(filterKey)) {
                return false;
            }

            NBTType filterType = filter.getType(filterKey);
            NBTType elementType = element.getType(filterKey);

            if (filterType != elementType) {
                return false;
            }

            switch (filterType) {
                case NBTTagString:
                    if (!filter.getString(filterKey).equals(element.getString(filterKey))) {
                        return false;
                    }
                    break;
                case NBTTagInt:
                    if (!filter.getInteger(filterKey).equals(element.getInteger(filterKey))) {
                        return false;
                    }
                    break;
                case NBTTagByte:
                    if (!filter.getByte(filterKey).equals(element.getByte(filterKey))) {
                        return false;
                    }
                    break;
                case NBTTagShort:
                    if (!filter.getShort(filterKey).equals(element.getShort(filterKey))) {
                        return false;
                    }
                    break;
                case NBTTagLong:
                    if (!filter.getLong(filterKey).equals(element.getLong(filterKey))) {
                        return false;
                    }
                    break;
                case NBTTagFloat:
                    if (!filter.getFloat(filterKey).equals(element.getFloat(filterKey))) {
                        return false;
                    }
                    break;
                case NBTTagDouble:
                    if (!filter.getDouble(filterKey).equals(element.getDouble(filterKey))) {
                        return false;
                    }
                    break;
                default:
                    // For complex types, try string comparison
                    if (!filter.getOrDefault(filterKey, "").equals(element.getOrDefault(filterKey, ""))) {
                        return false;
                    }
                    break;
            }
        }
        return true;
    }

    /**
     * Finds the index of an element in a list that matches the filter.
     * Returns null if no matching element is found.
     */
    public static Integer resolveListFilterIndex(ReadableNBT nbt, String key, ReadableNBT filter) {
        NBTType type = nbt.getType(key);
        if (type != NBTType.NBTTagList) {
            return null;
        }

        NBTType listType = nbt.getListType(key);
        if (listType != NBTType.NBTTagCompound) {
            return null;
        }

        var compoundList = nbt.getCompoundList(key);
        for (int i = 0; i < compoundList.size(); i++) {
            if (matchesFilter(compoundList.get(i), filter)) {
                return i;
            }
        }

        return null;
    }

    /**
     * Removes an element from a list by index.
     * Handles compound lists by rebuilding the list without the element.
     */
    public static void removeListElement(ReadWriteNBT nbt, String key, int index) {
        NBTType listContentType = nbt.getListType(key);
        if (listContentType == null) {
            return;
        }

        // Compound list requires special handling
        if (listContentType == NBTType.NBTTagCompound) {
            var list = nbt.getCompoundList(key);
            if (index < 0 || index >= list.size()) {
                return;
            }
            // Rebuild list without the element
            List<ReadWriteNBT> temp = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                if (i != index) {
                    temp.add(NBT.parseNBT(list.get(i).toString()));
                }
            }
            list.clear();
            for (ReadWriteNBT item : temp) {
                list.addCompound(item);
            }
        } else {
            // For primitive lists, use the direct remove method if available
            switch (listContentType) {
                case NBTTagString:
                    nbt.getStringList(key).remove(index);
                    break;
                case NBTTagInt:
                    nbt.getIntegerList(key).remove(index);
                    break;
                case NBTTagDouble:
                    nbt.getDoubleList(key).remove(index);
                    break;
                case NBTTagFloat:
                    nbt.getFloatList(key).remove(index);
                    break;
                case NBTTagLong:
                    nbt.getLongList(key).remove(index);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Result of path navigation.
     */
    public static class PathNavigationResult {
        private final de.tr7zw.nbtapi.iface.ReadWriteNBT parent;
        private final String lastKey;
        private final boolean success;

        public PathNavigationResult(de.tr7zw.nbtapi.iface.ReadWriteNBT parent, String lastKey, boolean success) {
            this.parent = parent;
            this.lastKey = lastKey;
            this.success = success;
        }

        public de.tr7zw.nbtapi.iface.ReadWriteNBT getParent() {
            return parent;
        }

        public String getLastKey() {
            return lastKey;
        }

        public boolean isSuccess() {
            return success;
        }
    }

    /**
     * Navigates a path and returns the final value.
     * For read operations - resolves to the end of the path.
     */
    public static Object navigatePath(ReadableNBT nbt, String path) {
        if (path == null || path.isEmpty()) return nbt;

        List<PathSegment> segments = parsePath(path);
        Object current = nbt;

        for (PathSegment segment : segments) {
            if (!(current instanceof ReadableNBT)) {
                return null;
            }

            ReadableNBT parent = (ReadableNBT) current;

            if (segment.hasFilter()) {
                current = resolveListFilter(parent, segment.getKey(), segment.getFilter());
            } else if (segment.hasIndex()) {
                current = resolveListIndex(parent, segment.getKey(), segment.getIndex());
            } else {
                // Simple key - check if key exists first
                if (!parent.hasTag(segment.getKey())) {
                    return null;
                }
                current = getTypedValue(parent, segment.getKey());
            }

            if (current == null) {
                return null;
            }
        }

        return current;
    }

    /**
     * Navigates to the parent of the final key.
     * For write operations - returns the parent container and the last key.
     */
    public static PathNavigationResult navigateToParent(ReadWriteNBT nbt, String path) {
        if (path == null || path.isEmpty()) {
            return new PathNavigationResult(null, "", false);
        }

        List<PathSegment> segments = parsePath(path);
        if (segments.isEmpty()) {
            return new PathNavigationResult(null, "", false);
        }

        // Navigate to all but the last segment
        Object current = nbt;
        for (int i = 0; i < segments.size() - 1; i++) {
            PathSegment segment = segments.get(i);
            if (!(current instanceof ReadWriteNBT)) {
                return new PathNavigationResult(null, "", false);
            }

            ReadWriteNBT parent = (ReadWriteNBT) current;

            if (segment.hasFilter()) {
                current = resolveListFilter(parent, segment.getKey(), segment.getFilter());
            } else if (segment.hasIndex()) {
                current = resolveListIndex(parent, segment.getKey(), segment.getIndex());
            } else {
                // Simple key - get or create the compound
                current = parent.getOrCreateCompound(segment.getKey());
            }

            if (current == null) {
                return new PathNavigationResult(null, "", false);
            }
        }

        if (!(current instanceof ReadWriteNBT)) {
            return new PathNavigationResult(null, "", false);
        }

        PathSegment lastSegment = segments.get(segments.size() - 1);
        return new PathNavigationResult((ReadWriteNBT) current, lastSegment.getKey(), true);
    }

    /**
     * Gets a typed value from NBT.
     */
    public static Object getTypedValue(ReadableNBT nbt, String key) {
        NBTType type = nbt.getType(key);
        if (type == null) return null;

        switch (type) {
            case NBTTagCompound: return nbt.getCompound(key);
            case NBTTagString: return nbt.getString(key);
            case NBTTagInt: return nbt.getInteger(key);
            case NBTTagDouble: return nbt.getDouble(key);
            case NBTTagFloat: return nbt.getFloat(key);
            case NBTTagLong: return nbt.getLong(key);
            case NBTTagShort: return nbt.getShort(key);
            case NBTTagByte: return nbt.getByte(key);
            case NBTTagByteArray: return nbt.getByteArray(key);
            case NBTTagIntArray: return nbt.getIntArray(key);
            case NBTTagLongArray: return nbt.getLongArray(key);
            case NBTTagList:
                NBTType listType = nbt.getListType(key);
                switch (listType) {
                    case NBTTagCompound: return nbt.getCompoundList(key);
                    case NBTTagString: return nbt.getStringList(key);
                    case NBTTagInt: return nbt.getIntegerList(key);
                    case NBTTagFloat: return nbt.getFloatList(key);
                    case NBTTagDouble: return nbt.getDoubleList(key);
                    case NBTTagLong: return nbt.getLongList(key);
                    default: return "List<" + listType + ">";
                }
            default: return null;
        }
    }

    /**
     * Sets a typed value to NBT (write operation).
     */
    public static void setTypedValue(ReadWriteNBT nbt, String key, Object value) {
        if (nbt.hasTag(key)) {
            nbt.removeKey(key);
        }

        if (value instanceof ReadableNBT) {
            nbt.getOrCreateCompound(key).mergeCompound((ReadableNBT) value);
        } else if (value instanceof List) {
            List<?> sourceList = (List<?>) value;
            for (Object item : sourceList) {
                if (item instanceof ReadableNBT) {
                    String serialized = item.toString();
                    ReadableNBT copied = NBT.parseNBT(serialized);
                    var newElement = nbt.getCompoundList(key).addCompound();
                    newElement.mergeCompound(copied);
                } else if (item instanceof String) {
                    nbt.getStringList(key).add((String) item);
                } else if (item instanceof Integer) {
                    nbt.getIntegerList(key).add((Integer) item);
                } else if (item instanceof Long) {
                    nbt.getLongList(key).add((Long) item);
                } else if (item instanceof Double) {
                    nbt.getDoubleList(key).add((Double) item);
                } else if (item instanceof Float) {
                    nbt.getFloatList(key).add((Float) item);
                } else if (item instanceof Byte) {
                    nbt.getIntegerList(key).add(((Byte) item).intValue());
                } else if (item instanceof Short) {
                    nbt.getIntegerList(key).add(((Short) item).intValue());
                } else if (item instanceof Boolean) {
                    nbt.getIntegerList(key).add(((Boolean) item) ? 1 : 0);
                } else {
                    nbt.getStringList(key).add(String.valueOf(item));
                }
            }
        } else if (value instanceof Integer) {
            nbt.setInteger(key, (Integer) value);
        } else if (value instanceof Long) {
            nbt.setLong(key, (Long) value);
        } else if (value instanceof Double) {
            nbt.setDouble(key, (Double) value);
        } else if (value instanceof Float) {
            nbt.setFloat(key, (Float) value);
        } else if (value instanceof Short) {
            nbt.setShort(key, (Short) value);
        } else if (value instanceof Byte) {
            nbt.setByte(key, (Byte) value);
        } else if (value instanceof Boolean) {
            nbt.setBoolean(key, (Boolean) value);
        } else if (value instanceof String) {
            nbt.setString(key, (String) value);
        } else {
            nbt.setString(key, value.toString());
        }
    }

    /**
     * Replaces an element in a compound list by index.
     */
    public static void replaceInCompoundList(ReadWriteNBT nbt, String key, int index, ReadableNBT value) {
        var list = nbt.getCompoundList(key);
        if (index < 0 || index >= list.size()) {
            return;
        }

        // 1. 创建临时列表，按照正确的顺序收集数据
        List<ReadableNBT> tempList = new ArrayList<>();
        
        for (int i = 0; i < list.size(); i++) {
            if (i == index) {
                // 如果是目标索引，加入传入的新值
                tempList.add(value);
            } else {
                // 如果是其他索引，保留旧值
                // 必须使用 parseNBT(toString()) 进行克隆，因为稍后 list.clear() 会清除引用
                tempList.add(NBT.parseNBT(list.get(i).toString()));
            }
        }

        // 2. 清空原 NBT 列表
        list.clear();

        // 3. 按顺序将数据写回
        for (ReadableNBT item : tempList) {
            list.addCompound(item);
        }
    }

    // Private constructor to prevent instantiation
    private NBTPathUtils() {}

    // ==================== NBT Formatting ====================

    /**
     * Formats an NBT value for display with colors.
     * Keys: blue, Strings: green, Numbers: gold/orange, Booleans: red, Compounds: yellow
     */
    public static String formatNBTValue(Object value) {
        if (value == null) {
            return "null";
        }

        // Use simple string representation without colors for now
        // Adventure Component formatting would need to be done in the command handler
        return value.toString();
    }

    /**
     * Formats a key-value pair for display.
     */
    public static String formatNBTEntry(String key, Object value) {
        return key + ": " + formatNBTValue(value);
    }
}
