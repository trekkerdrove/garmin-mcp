package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader;
import io.github.trekkerdrove.garminmcp.application.service.ActivityService;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static io.github.trekkerdrove.garminmcp.infrastructure.external.garmin.GarminRoute.*;

@Component
public class GarminActivities implements ActivityReader {
    private static final int[] POWER_SECONDS = {1, 2, 5, 10, 20, 30, 60, 120, 300, 600, 1200, 1800, 3600, 7200, 18000};
    private static final int[] SPLIT_DISTANCES = {91, 100, 400, 457, 750, 914, 1000, 1500, 1508, 1609, 5000, 10000, 21098, 40000, 42195};
    private static final String[] MEASURES = {"distance:distanceMeters", "duration:durationSeconds",
            "elapsedDuration:elapsedSeconds", "movingDuration:movingSeconds", "elevationGain:ascentMeters",
            "elevationLoss:descentMeters", "averageHR:averageHeartRateBpm", "maxHR:maxHeartRateBpm",
            "averageSpeed:averageSpeedMps", "maxSpeed:maxSpeedMps", "calories:caloriesKcal"};
    private final GarminHttpClient http;

    public GarminActivities(GarminHttpClient http) { this.http = http; }

    @Bean
    ActivityService activityService() { return new ActivityService(this, Clock.systemUTC()); }

    @Override
    public List<Activity> search(Map<String, Object> filters, int offset, int limit, SearchView view) {
        var query = new LinkedHashMap<>(filters);
        query.put("start", offset);
        query.put("limit", limit);
        JsonNode rows = array(http.get(A1, query));
        if (rows.size() > limit) throw malformed();
        var activities = new ArrayList<Activity>();
        for (JsonNode row : rows) {
            long id = id(row);
            String start = row.path("startTimeLocal").asString("");
            LocalDate date = localDate(start);
            String sport = row.path("activityType").path("typeKey").asString("");
            if (sport.isBlank()) throw malformed();
            var summary = fields(row, MEASURES);
            summary.putAll(fields(row, "activityName:name", "avgPower:averagePowerWatts", "maxPower:maxPowerWatts",
                    "excludeFromPowerCurveReports", "totalSets", "totalReps", "activeSets"));
            summary.put("activityId", id);
            summary.put("startLocal", start);
            summary.put("sport", sport);
            var parent = row.path("parentId");
            Long parentId = parent.isIntegralNumber() && parent.asLong() > 0 ? parent.asLong() : null;
            if (parentId != null) summary.put("parentId", parentId);
            var exercises = view == SearchView.STRENGTH ? select(row.path("summarizedExerciseSets"), 100,
                    set -> fields(set, "category", "subCategory", "reps", "sets", "volume:gramRepetitions",
                            "duration:durationMillis", "maxWeight:maxWeightGrams", "peakPower:peakPowerWatts"))
                    : new Selection(List.of(), false);
            activities.add(new Activity(id, parentId, date, sport, summary,
                    view == SearchView.ZONES ? zones(row, "hrTimeInZone_", 5) : Map.of(),
                    view == SearchView.ZONES ? zones(row, "powerTimeInZone_", 7) : Map.of(), exercises,
                    view == SearchView.POWER ? curve(row, "maxAvgPower_", POWER_SECONDS, true) : Map.of(),
                    view == SearchView.SPLITS ? curve(row, "fastestSplit_", SPLIT_DISTANCES, false) : Map.of()));
        }
        return activities;
    }

    @Override
    public List<Sport> types() {
        var types = new ArrayList<Sport>();
        for (JsonNode row : array(http.get(A4, Map.of()))) {
            var key = row.path("key");
            var parent = row.path("parent");
            if (!key.isString() || key.stringValue().isBlank()) throw malformed();
            if (!parent.isNull() && !parent.isMissingNode() && (!parent.isString() || parent.stringValue().isBlank())) {
                throw malformed();
            }
            types.add(new Sport(key.stringValue(), parent.asString(null)));
            if (types.size() > 1024) throw malformed();
        }
        return types;
    }

    @Override
    public Detail detail(long id) {
        JsonNode row = http.get(A2, Map.of(), id);
        if (row == null || row.isNull()) throw malformed();
        if (id(row) != id) throw malformed();
        JsonNode stats = row.path("summaryDTO");
        var summary = fields(stats, MEASURES);
        summary.putAll(fields(row, "activityId", "activityName:name", "parentId", "isMultiSportParent"));
        summary.putAll(fields(row.path("activityTypeDTO"), "typeKey:sport"));
        summary.putAll(fields(stats, "startTimeLocal:startLocal", "averagePower:averagePowerWatts", "maxPower:maxPowerWatts",
                "functionalThresholdPower:ftpWatts", "directFunctionalThresholdPower:directFtpWatts",
                "lactateThresholdHeartRate:lactateThresholdBpm", "lactateThresholdSpeed:lactateThresholdMps"));
        var effect = fields(stats, "trainingEffect:aerobic", "anaerobicTrainingEffect:anaerobic", "trainingEffectLabel:label",
                "activityTrainingLoad:trainingLoad", "aerobicTrainingEffectMessage", "anaerobicTrainingEffectMessage");
        var splits = select(row.path("splitSummaries"), 100, split -> {
            var fields = fields(split, MEASURES);
            fields.putAll(fields(split, "splitType", "noOfSplits:count", "averagePower:averagePowerWatts"));
            return fields;
        });
        String start = stats.path("startTimeLocal").asString("");
        return new Detail(id, start.isEmpty() ? null : localDate(start), summary, effect, splits);
    }

    @Override
    public Selection workouts(long id, int limit) {
        boolean[] shortened = {false};
        var result = select(array(http.get(A5, Map.of(), id)), limit, row -> {
            var workout = fields(row, "workoutName:name", "sport", "index", "multisportSet");
            var steps = select(row.path("steps"), 64, step -> {
                var values = fields(step, "stepIndex", "name", "intensity", "durationType", "durationValue",
                        "targetType", "targetValue", "targetValueLow", "targetValueHigh", "exerciseCategory",
                        "exerciseName", "exerciseWeightValue", "weightDisplayUnit");
                String duration = step.path("durationType").asString("");
                if (duration.startsWith("REPEAT_")) {
                    values.putAll(fields(step, "durationValue:repeatFromStepIndex"));
                    if (duration.equals("REPEAT_UNTIL_STEPS_CMPLT")) values.putAll(fields(step, "targetValue:repeatCount"));
                }
                return values;
            });
            if (!steps.rows().isEmpty()) workout.put("steps", steps.rows());
            shortened[0] |= steps.truncated();
            return workout;
        });
        return new Selection(result.rows(), result.truncated() || shortened[0]);
    }

    @Override
    public Selection wellness(LocalDate start, LocalDate end, int limit) {
        boolean[] shortened = {false};
        var result = select(array(http.get(A6, Map.of(), start, end)), limit, row -> {
            var session = fields(row, "activityName:name", "wellnessActivityType:type");
            session.putAll(fields(row.path("calendarDate"), "localDate:date"));
            session.putAll(fields(row.path("startTimestampGMT"), "date:startUtc"));
            session.putAll(fields(row.path("endTimestampGMT"), "date:endUtc"));
            var metrics = select(row.path("summaryTypeDataList"), 32, item -> fields(item, "summaryType", "minValue", "maxValue", "avgValue"));
            var zones = select(row.path("timeInZoneList"), 32, item -> fields(item, "zoneNumber", "millisInZone", "zoneLowBoundary"));
            if (!metrics.rows().isEmpty()) session.put("metrics", metrics.rows());
            if (!zones.rows().isEmpty()) session.put("zones", zones.rows());
            shortened[0] |= metrics.truncated() || zones.truncated();
            return session;
        });
        return new Selection(result.rows(), result.truncated() || shortened[0]);
    }

    @Override
    public List<Night> sleep(LocalDate first, LocalDate last) {
        var nights = new ArrayList<Night>();
        for (JsonNode row : array(http.get(H7, Map.of("startDate", first, "endDate", last)))) {
            if (!row.isObject()) throw malformed();
            var duration = row.path("sleepTimeSeconds");
            // Garmin can return an undated placeholder when no main-sleep duration was measured.
            if (duration.isNull() || duration.isMissingNode()) continue;
            if (!duration.isIntegralNumber() || !duration.canConvertToLong() || duration.asLong() < 0) throw malformed();
            LocalDate date = localDate(row.path("calendarDate").asString(""));
            nights.add(new Night(date, duration.asLong()));
        }
        return nights;
    }

    private static JsonNode array(JsonNode value) {
        if (value == null || !value.isArray()) throw malformed();
        return value;
    }

    private static long id(JsonNode row) {
        JsonNode id = row.path("activityId");
        if (!id.isIntegralNumber() || id.asLong() <= 0) throw malformed();
        return id.asLong();
    }

    private static LocalDate localDate(String value) {
        try {
            return LocalDate.parse(value.length() >= 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException ignored) {
            throw malformed();
        }
    }

    private static GarminFailure malformed() { return new GarminFailure("garmin_unavailable"); }

    private static Map<Integer, Double> curve(JsonNode node, String prefix, int[] intervals, boolean includeZero) {
        var values = new LinkedHashMap<Integer, Double>();
        for (int interval : intervals) {
            var v = node.path(prefix + interval);
            if (v.isNumber() && (v.doubleValue() > 0 || (includeZero && v.doubleValue() == 0))) values.put(interval, v.doubleValue());
        }
        return values;
    }

    private static Map<String, Object> zones(JsonNode node, String prefix, int count) {
        var values = new LinkedHashMap<String, Object>();
        for (int i = 1; i <= count; i++) {
            var v = node.path(prefix + i);
            if (v.isNumber() && v.doubleValue() >= 0) values.put(String.valueOf(i), v.doubleValue());
        }
        return values;
    }

    private static Selection select(JsonNode array, int max, Function<JsonNode, Map<String, Object>> mapper) {
        if (array.isMissingNode() || array.isNull()) return new Selection(List.of(), false);
        if (!array.isArray()) throw malformed();
        var items = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < Math.min(max, array.size()); i++) {
            if (!array.path(i).isObject()) throw malformed();
            var item = mapper.apply(array.path(i));
            if (!item.isEmpty()) items.add(item);
        }
        return new Selection(items, array.size() > max);
    }

    private static Map<String, Object> fields(JsonNode node, String... names) {
        var out = new LinkedHashMap<String, Object>();
        for (String namespec : names) {
            int separator = namespec.indexOf(':');
            String name = separator < 0 ? namespec : namespec.substring(0, separator);
            String alias = separator < 0 ? name : namespec.substring(separator + 1);
            var v = node.path(name);
            if (v.isNumber()) out.put(alias, v.numberValue());
            else if (v.isBoolean()) out.put(alias, v.booleanValue());
            else if (v.isString()) out.put(alias, v.stringValue().substring(0, Math.min(256, v.stringValue().length())));
        }
        return out;
    }
}
