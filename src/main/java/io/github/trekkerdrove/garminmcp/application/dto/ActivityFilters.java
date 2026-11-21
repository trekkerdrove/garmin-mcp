package io.github.trekkerdrove.garminmcp.application.dto;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Optional documented activity-search filters, using Garmin's measurement units. */
public record ActivityFilters(@Nullable Integer favorite, @Nullable String sortOrder, @Nullable Boolean excludeChildren, @Nullable String sortBy, @Nullable String search,
        @Nullable String activityType, @Nullable String activitySubType, @Nullable String eventType, @Nullable Long minDistance, @Nullable Long maxDistance,
        @Nullable Long minDuration, @Nullable Long maxDuration, @Nullable Long minElevation, @Nullable Long maxElevation, @Nullable Double upperLatitude,
        @Nullable Double rightLongitude, @Nullable Double lowerLatitude, @Nullable Double leftLongitude, @Nullable Long lowerMaxDepth, @Nullable Long upperMaxDepth,
        @Nullable Boolean decoDive, @Nullable Long minElapsedDuration, @Nullable Long maxElapsedDuration, @Nullable Long minBottomTime, @Nullable Long maxBottomTime,
        @Nullable Long minAvgPower, @Nullable List<String> excludedActivitySubType, @Nullable List<String> requiredField) {

    public Map<String, Object> parameters() {
        var out = new LinkedHashMap<String, Object>();
        if (favorite != null && (favorite < -1 || favorite > 1)) throw invalid();
        String direction = sortOrder == null ? null : sortOrder.toLowerCase(Locale.ROOT);
        if (direction != null && !Set.of("asc", "desc").contains(direction)) throw invalid();
        if (activityType != null && activityType.equals(activitySubType)) throw invalid();
        if (sortBy != null && !Set.of("name","duration","distance","startLocal").contains(sortBy)) throw invalid();
        if (search != null && (search.isBlank() || search.length() > 160 || search.chars().anyMatch(Character::isISOControl))) throw invalid();
        put(out,"favorite", favorite);
        put(out, "sortOrder", direction);
        put(out,"sortBy", sortBy);
        put(out,"excludeChildren", excludeChildren);
        put(out,"search", search);
        put(out,"activityType", key(activityType));
        put(out,"activitySubType", key(activitySubType));
        put(out,"eventType", key(eventType));
        range(out, "minDistance", minDistance, "maxDistance", maxDistance);
        range(out, "minDuration", minDuration, "maxDuration", maxDuration);
        range(out, "minElevation", minElevation, "maxElevation", maxElevation);
        range(out, "lowerMaxDepth", lowerMaxDepth, "upperMaxDepth", upperMaxDepth);
        range(out, "minElapsedDuration", minElapsedDuration, "maxElapsedDuration", maxElapsedDuration);
        range(out, "minBottomTime", minBottomTime, "maxBottomTime", maxBottomTime);
        if (minAvgPower != null && minAvgPower < 0) throw invalid();
        put(out,"minAvgPower", minAvgPower);
        put(out,"decoDive", decoDive);
        if (upperLatitude != null || lowerLatitude != null || rightLongitude != null || leftLongitude != null) {
            if (!coordinate(upperLatitude, 90) || !coordinate(lowerLatitude, 90) || lowerLatitude > upperLatitude
                    || !coordinate(leftLongitude, 180) || !coordinate(rightLongitude, 180)) throw invalid();
            put(out,"upperLatitude", upperLatitude);
            put(out,"lowerLatitude", lowerLatitude);
            put(out,"leftLongitude", leftLongitude);
            put(out,"rightLongitude", rightLongitude);
        }
        keys(out,"excludedActivitySubType", excludedActivitySubType);
        keys(out,"requiredField", requiredField);
        return out;
    }
    public static String key(String key) {
        if (key != null && !key.matches("[a-zA-Z][a-zA-Z0-9_]{0,79}")) throw invalid();
        return key;
    }
    private static void keys(Map<String, Object> out, String name, List<String> keys) {
        if (keys == null) return;
        if (keys.isEmpty() || keys.size() > 16) throw invalid();
        for (String item : keys) {
            if (item == null) throw invalid();
            key(item);
        }
        out.put(name, List.copyOf(keys));
    }
    private static boolean coordinate(Double value, int limit) {
        return value != null && Double.isFinite(value) && Math.abs(value) <= limit;
    }
    private static void range(Map<String, Object> out, String a, Long min, String b, Long max) {
        if ((min != null && min < 0) || (max != null && max < 0) || (min != null && max != null && min > max)) throw invalid();
        put(out, a, min);
        put(out, b, max);
    }
    private static void put(Map<String, Object> out, String name, Object value) {
        if (value != null) out.put(name, value);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid activity filter"); }
}
