package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminPersonalData;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminWeight;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class PersonalTrackingGateway implements GarminPersonalData, GarminWeight {
    private static final int FOOD_LIMIT = 100;
    private final GarminHttpClient client;

    public PersonalTrackingGateway(GarminHttpClient client) {
        this.client = client;
    }

    @Override
    public List<Map<String, Object>> weights(LocalDate start, LocalDate end, WeightView view) {
        var rows = new ArrayList<Map<String, Object>>();
        if (view == WeightView.summary) {
            var body = client.get(GarminRoute.H17, Map.of("includeAll", false), start, end);
            for (var day : body.path("dailyWeightSummaries")) {
                var latest = day.path("latestWeight");
                var row = fields(day, "numOfWeightEntries");
                measurementKg(latest).ifPresent(kg -> row.put("latestWeightKg", kg));
                if (!row.isEmpty() && inRange(day.path("summaryDate"), start, end)) {
                    row.put("date", day.path("summaryDate").asString());
                    rows.add(row);
                }
            }
        } else {
            for (var entry : client.get(GarminRoute.H16, Map.of(), start, end)) {
                if (!inRange(entry.path("calendarDate"), start, end)) {
                    continue;
                }
                var row = view == WeightView.composition
                        ? fields(entry, "bmi", "bodyFat", "bodyWater", "boneMass", "muscleMass", "physiqueRating", "visceralFat", "metabolicAge")
                        : new LinkedHashMap<String, Object>();
                measurementKg(entry).ifPresent(kg -> row.put("weightKg", kg));
                if (!row.isEmpty()) {
                    row.putAll(fields(entry, "calendarDate", "timestampGMT"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    @Override
    public OptionalDouble kilogramsOnOrBefore(LocalDate date) {
        // H17 includes the measurement preceding the range; its next measurement may be in the future.
        var body = client.get(GarminRoute.H17, Map.of("includeAll", false), date, date);
        var latest = body.path("previousDateWeight");
        for (var day : body.path("dailyWeightSummaries")) {
            var sameDay = day.path("latestWeight");
            if (measurementKg(sameDay).isPresent() && inRange(sameDay.path("calendarDate"), date, date)) {
                latest = sameDay;
            }
        }
        return inRange(latest.path("calendarDate"), LocalDate.of(1, 1, 1), date)
                ? measurementKg(latest) : OptionalDouble.empty();
    }

    private static OptionalDouble measurementKg(JsonNode measurement) {
        var weight = measurement.path("weight");
        if (!weight.isNumber() || !Double.isFinite(weight.asDouble()) || weight.asDouble() <= 0) {
            return OptionalDouble.empty();
        }
        double value = weight.asDouble();
        // Personal API documents kg; weight-service also returns grams for the same measurement.
        return OptionalDouble.of(value >= 1_000 ? value / 1_000 : value);
    }

    @Override
    public List<Map<String, Object>> goals(LocalDate start, LocalDate end) {
        var body = client.get(GarminRoute.H18, Map.of(), start, end);
        var goals = new ArrayList<Map<String, Object>>();
        for (String group : List.of("userGoals", "userGoalRanges")) {
            for (var entry : body.path(group)) {
                var goal = fields(entry, "goalValue", "lowWeightGoalInGrams", "highWeightGoalInGrams", "weekNum",
                        "trackingPeriodType", "userGoalType", "userGoalCategory");
                if (!goal.isEmpty()) {
                    addDates(goal, entry, "startDate", "endDate");
                    goals.add(goal);
                }
            }
        }
        return goals;
    }

    @Override
    public NutritionDay nutrition(LocalDate date, Long mealId, boolean journal) {
        var query = new LinkedHashMap<String, Object>();
        query.put("includeGoals", journal);
        query.put("includeNutrition", true);
        query.put("includeMedia", false);
        if (mealId != null) {
            query.put("mealId", mealId);
        }
        var body = client.get(GarminRoute.H19, query, date);
        if (!date.toString().equals(body.path("mealDate").asString(""))) {
            throw new GarminFailure("garmin_unavailable");
        }
        var macros = new LinkedHashMap<String, Number>();
        var extra = new LinkedHashMap<String, Object>();
        var meals = new ArrayList<Map<String, Object>>();
        int foodCount = 0;
        int foodDetails = 0;
        boolean truncated = false;
        for (var entry : body.path("mealDetails")) {
            var meal = entry.path("meal");
            if (mealId != null && meal.path("mealId").asLong(-1) != mealId) {
                continue;
            }
            var logs = entry.path("loggedFoods");
            int count = logs.isArray() ? logs.size() : 0;
            foodCount += count;
            var nutrients = macros(entry.path("mealNutritionContent"));
            // An enabled but empty meal can have placeholder zero totals.
            if (mealId != null && (count > 0 || hasIntake(nutrients))) {
                macros.putAll(nutrients);
            }
            if (journal && meals.size() < FOOD_LIMIT) {
                var mealInfo = fields(meal, "mealId", "mealName", "defaultMealName");
                mealInfo.put("nutrients", nutrients);
                mealInfo.put("goals", fields(entry.path("mealNutritionGoals"), "calories", "carbs", "fat", "protein"));
                var foods = new ArrayList<Map<String, Object>>();
                for (var log : logs) {
                    if (foodDetails >= FOOD_LIMIT) {
                        truncated = true;
                        break;
                    }
                    var item = fields(log, "servingQty", "mealTime");
                    item.putAll(fields(log.path("foodMetaData"), "foodName"));
                    foods.add(item);
                    foodDetails++;
                }
                mealInfo.put("foods", foods);
                meals.add(mealInfo);
            } else if (journal) {
                truncated = true;
            }
        }
        if (mealId == null) {
            var daily = macros(body.path("dailyNutritionContent"));
            if (foodCount > 0 || hasIntake(daily)) {
                macros.putAll(daily);
            }
            if (journal) {
                extra.put("goals", fields(body.path("dailyNutritionGoals"),
                        "calories", "carbs", "protein", "fat", "adjustedCalories", "adjustedCarbs", "adjustedProtein", "adjustedFat"));
            }
        }
        if (journal) {
            extra.put("meals", meals);
        }
        return new NutritionDay(foodCount, macros, extra, truncated);
    }

    private static boolean hasIntake(Map<String, Number> nutrients) {
        return nutrients.values().stream().anyMatch(value -> value.doubleValue() > 0);
    }

    private static Map<String, Number> macros(JsonNode source) {
        var result = new LinkedHashMap<String, Number>();
        for (String key : List.of("calories", "carbs", "protein", "fat")) {
            var value = source.path(key);
            if (value.isNumber() && value.asDouble() >= 0) {
                result.put(key, value.numberValue());
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> cycle(LocalDate date, CycleView view, boolean details, boolean analysis) {
        boolean daily = view == CycleView.day;
        var response = daily
                ? client.get(GarminRoute.H20, Map.of("reducedResponse", !details, "includeAnalysis", analysis, "skipbm", !details), date)
                : client.get(GarminRoute.H21, Map.of(), date);
        var summary = daily ? response.path("daySummary") : response;
        var result = fields(summary, "cycleType", "currentPhase", "dayInCycle", "daysUntilNextPhase", "periodLength",
                "lengthOfCurrentPhase", "predictedCycle", "predictedCycleLength");
        addDates(result, summary, "startDate", "endDate");
        if (analysis) {
            var analysisNode = summary.path("analysis");
            var selected = fields(analysisNode.path("cycleTypeAnalysis"), "cycleType", "medianCycleLength",
                    "medianCycleLengthClassifier", "cycleVariability", "cycleVariabilityClassifier",
                    "cycleLengthDeviationDays", "periodLengthDeviationDays");
            selected.putAll(fields(analysisNode, "perimenopauseDetected"));
            if (!selected.isEmpty()) {
                result.put("analysis", selected);
            }
        }
        if (details) {
            var log = response.path("dayLog");
            var selected = fields(log, "flow", "ovulationDay", "sexDrive", "sexualActivity", "hasBabyMovement", "hasGlucoseLog");
            for (String key : List.of("discharge", "moods", "symptoms")) {
                var entries = new ArrayList<String>();
                if (log.path(key).size() > 32) {
                    result.put("truncated", true);
                }
                for (var value : log.path(key)) {
                    if (value.isString() && entries.size() < 32 && value.stringValue().length() <= 128) {
                        entries.add(value.asString());
                    }
                }
                if (!entries.isEmpty()) {
                    selected.put(key, entries);
                }
            }
            if (!selected.isEmpty()) {
                result.put("dayLog", selected);
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> profile() {
        var settings = client.get(GarminRoute.P1, Map.of());
        var result = fields(settings, "preferredLocale", "measurementSystem", "numberFormat", "timeZone",
                "hydrationMeasurementUnit", "glucoseMeasurementUnit", "golfDistanceUnit", "golfElevationUnit", "golfSpeedUnit");
        var day = settings.path("firstDayOfWeek").path("dayName");
        if (day.isString()) {
            result.put("firstDayOfWeek", day.stringValue());
        }
        for (String key : List.of("dateFormat", "timeFormat", "powerFormat", "heartRateFormat")) {
            var format = settings.path(key).path("formatKey");
            if (format.isString()) {
                result.put(key, format.stringValue());
            }
        }
        return result;
    }

    private static Map<String, Object> fields(JsonNode source, String... keys) {
        var result = new LinkedHashMap<String, Object>();
        for (String key : keys) {
            var value = source.path(key);
            if (value.isString() && value.stringValue().length() <= 512) {
                result.put(key, value.stringValue());
            } else if (value.isNumber()) {
                result.put(key, value.numberValue());
            } else if (value.isBoolean()) {
                result.put(key, value.asBoolean());
            }
        }
        return result;
    }

    private static void addDates(Map<String, Object> target, JsonNode source, String... names) {
        for (String name : names) {
            var value = source.path(name);
            if (value.isObject()) {
                value = value.path("localDate").isString() ? value.path("localDate") : value.path("date");
            }
            if (value.isString()) {
                target.put(name, value.stringValue());
            }
        }
    }

    private static boolean inRange(JsonNode date, LocalDate start, LocalDate end) {
        if (!date.isString()) {
            return false;
        }
        String key = date.stringValue();
        return key.compareTo(start.toString()) >= 0 && key.compareTo(end.toString()) <= 0;
    }
}
