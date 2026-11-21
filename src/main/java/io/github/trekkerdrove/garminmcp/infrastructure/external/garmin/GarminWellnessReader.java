package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.port.out.WellnessReader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import static io.github.trekkerdrove.garminmcp.infrastructure.external.garmin.GarminRoute.*;

@Component
public final class GarminWellnessReader implements WellnessReader {
    private final GarminHttpClient http;

    public GarminWellnessReader(GarminHttpClient http) {
        this.http = http;
    }

    @Override
    public List<Observation> read(View view, LocalDate start, LocalDate end, Options options) {
        boolean single = start.equals(end);
        Map<String, Object> range = Map.of("startDate", start, "endDate", end);
        JsonNode body = switch (view) {
            case SUMMARY -> http.get(H1, Map.of("calendarDate", start));
            case TRENDS -> http.get(H2, range);
            case RESPIRATION, STRESS_DETAILS -> single ? http.get(H1, Map.of("calendarDate", start)) : http.get(H2, range);
            case SLEEP -> single ? http.get(H6, Map.of("date", start)) : http.get(H7, range);
            case HEART_RATE -> http.get(H8, Map.of("date", start));
            case HRV -> single ? http.get(H11, Map.of(), start) : http.get(H12, Map.of(), start, end);
            case STEPS -> http.get(H3, Map.of(), start, end);
            case STRESS -> http.get(H4, Map.of(), start, end);
            case HYDRATION -> http.get(H5, Map.of(), start, end);
            case BATTERY -> {
                Map<String, Object> params = new LinkedHashMap<>(range);
                if (options.chunks() > 0) params.put("chunks", options.chunks());
                yield http.get(H9, params);
            }
            case SPO2 -> http.get(H10, Map.of(), start);
            case ZONES -> http.get(H13, Map.of("generateDefaults", options.defaults()));
            case FITNESS_AGE -> http.get(H14, Map.of("fullHistoryScan", true), start);
            case VO2 -> http.get(H15, options.sport() == null ? Map.of() : Map.of("sport", options.sport()), start, end);
        };
        if (body.isNull()) return List.of();
        if (view == View.VO2) return vo2(body, start, end);
        boolean nestedDaily = view == View.TRENDS || (view == View.RESPIRATION || view == View.STRESS_DETAILS) && !single;
        JsonNode items = nestedDaily ? body.path("data")
                : view == View.HRV && !single ? body.path("hrvSummaries") : body;
        boolean expectArray = nestedDaily || !single || switch (view) {
            case STEPS, STRESS, HYDRATION, BATTERY, FITNESS_AGE, ZONES -> true;
            default -> false;
        };
        if (expectArray ? !items.isArray() : !items.isObject()) throw malformed();
        List<Observation> rows = new ArrayList<>();
        for (JsonNode item : expectArray ? items : List.of(items)) {
            if (!item.isObject()) throw malformed();
            var values = project(view, item, options, single);
            if (values.isEmpty()) continue;
            JsonNode date = switch (view) {
                case HRV -> (single ? item.path("hrvSummary") : item).path("calendarDate");
                case BATTERY -> item.path("date");
                case HEART_RATE -> item.path("calendarDate").isObject()
                        ? item.path("calendarDate").path("localDate") : item.path("calendarDate");
                default -> item.path("calendarDate");
            };
            boolean requiresMeasurementDate = view == View.SLEEP || view == View.HEART_RATE || view == View.HRV || view == View.TRENDS;
            LocalDate local = !requiresMeasurementDate && date.isMissingNode() && single ? start : parseDate(date);
            if (local.isBefore(start) || local.isAfter(end)) throw malformed();
            rows.add(new Observation(local, values));
        }
        return rows;
    }

    private static GarminFailure malformed() {
        return new GarminFailure("garmin_unavailable");
    }

    private static LocalDate parseDate(JsonNode node) {
        try {
            return LocalDate.parse(node.asString(""));
        } catch (java.time.DateTimeException error) {
            throw malformed();
        }
    }

    private static List<Observation> vo2(JsonNode body, LocalDate start, LocalDate end) {
        if (!body.isArray() || body.size() > 3 * 366) throw malformed();
        List<Observation> rows = new ArrayList<>();
        for (var day : body) {
            if (!day.isObject()) throw malformed();
            for (String sport : List.of("generic", "cycling", "rowing")) {
                var item = day.path(sport);
                var data = fields(item, "vo2MaxValue vo2MaxPreciseValue");
                if (data.isEmpty()) continue;
                LocalDate date = parseDate(item.path("calendarDate"));
                if (date.isBefore(start) || date.isAfter(end)) continue;
                data.put("sport", sport);
                rows.add(new Observation(date, data));
            }
        }
        return rows;
    }

    private static Map<String, Object> project(View view, JsonNode item, Options o, boolean single) {
        return switch (view) {
            case SUMMARY -> fields(item, "totalKilocalories activeKilocalories bmrKilocalories consumedKilocalories remainingKilocalories netCalorieGoal totalSteps dailyStepGoal totalDistanceMeters dailyPushesGoal totalPushes activeSeconds highlyActiveSeconds sedentarySeconds sleepingSeconds moderateIntensityMinutes vigorousIntensityMinutes intensityMinutesGoal floorsAscended floorsDescended userFloorsAscendedGoal minHeartRate maxHeartRate restingHeartRate lastSevenDaysAvgRestingHeartRate averageStressLevel maxStressLevel bodyBatteryChargedValue bodyBatteryDrainedValue bodyBatteryHighestValue bodyBatteryLowestValue averageSpo2 lowestSpo2 hydrationValueInML hydrationGoalInML avgWakingRespirationValue");
            case TRENDS -> trends(item, o);
            case SLEEP -> sleep(item, o.detail());
            case HEART_RATE -> heartRate(item, o);
            case HRV -> hrv(item, o, single);
            case STEPS -> fields(item, "totalSteps totalDistance stepGoal");
            case STRESS -> fields(item.path("values"), "overallStressLevel highStressDuration mediumStressDuration lowStressDuration restStressDuration");
            case STRESS_DETAILS -> single ? fields(item, "averageStressLevel maxStressLevel stressDuration restStressDuration activityStressDuration uncategorizedStressDuration lowStressDuration mediumStressDuration highStressDuration totalStressDuration")
                    : fields(item.path("stress"), "avgLevel maxLevel totalDurationInMillis restDurationInMillis activityDurationInMillis uncategorizedDurationInMillis lowStressDurationInMillis mediumStressDurationInMillis highStressDurationInMillis");
            case HYDRATION -> fields(item, "valueInML goalInML lastEntryTimestampLocal sweatLossInML activityIntakeInML");
            case BATTERY -> battery(item, o);
            case RESPIRATION -> {
                Map<String, Object> result = new LinkedHashMap<>();
                if (single) {
                    add(result, "awake", fields(item, "avgWakingRespirationValue"));
                    add(result, "daily", fields(item, "lowestRespirationValue highestRespirationValue latestRespirationValue"));
                } else add(result, "daily", fields(item.path("respiration"), "avgValue maxValue minValue latestValue"));
                yield result;
            }
            case SPO2 -> spo2(item, o);
            case FITNESS_AGE -> {
                var result = fields(item, "chronologicalAge fitnessAge achievableFitnessAge previousFitnessAge lastUpdated invalidReason");
                if (o.detail()) add(result, "components", namedFields(item.path("components"),
                        "value targetValue improvementValue potentialAge priority stale lastMeasurementDate", List.of()));
                yield result;
            }
            case ZONES -> {
                if (o.sport() != null && !o.sport().equals(item.path("sport").asString(""))) yield Map.of();
                yield fields(item, "sport trainingMethod restingHeartRateUsed lactateThresholdHeartRateUsed zone1Floor zone2Floor zone3Floor zone4Floor zone5Floor maxHeartRateUsed");
            }
            case VO2 -> throw new IllegalStateException("handled at response level");
        };
    }

    private static Map<String, Object> trends(JsonNode item, Options o) {
        Map<String, Object> result = new LinkedHashMap<>();
        var selection = o.metrics() == null || o.metrics().isEmpty() ? List.of(Metric.values()) : o.metrics();
        for (var metric : selection) {
            var group = item.path(metric.name());
            var values = switch (metric) {
                case movement -> {
                    Map<String, Object> movement = new LinkedHashMap<>();
                    for (String type : List.of("steps", "pushes", "floorsAscended", "floorsDescended")) {
                        add(movement, type, fields(group.path(type), "goal value distanceInMeters"));
                    }
                    yield movement;
                }
                case calories -> fields(group, "burnedResting burnedActive burnedTotal consumedGoal consumedValue consumedRemaining");
                case heartRate -> fields(group, "minValue maxValue restingValue alertCount");
                case intensityMinutes -> {
                    var intensity = fields(group, "goal moderate vigorous");
                    if (intensity.get("moderate") instanceof Number m && intensity.get("vigorous") instanceof Number v)
                        intensity.put("weighted", m.longValue() + 2L * v.longValue());
                    yield intensity;
                }
                case stress -> fields(group, "avgLevel maxLevel qualifier totalDurationInMillis restDurationInMillis lowStressDurationInMillis mediumStressDurationInMillis highStressDurationInMillis");
                case bodyBattery -> fields(group, "minValue maxValue chargedValue drainedValue latestValue endOfDayValue");
                case hydration -> fields(group, "goalInMl goalInFractionalMl consumedInFractionalMl");
                case respiration -> fields(group, "avgValue minValue maxValue latestValue");
                case pulseOx -> fields(group, "avgValue minValue latestValue");
            };
            add(result, metric.name(), values);
        }
        return result;
    }

    private static Map<String, Object> sleep(JsonNode item, boolean detail) {
        var result = fields(item, "sleepTimeSeconds napTimeSeconds deepSleepSeconds lightSleepSeconds remSleepSeconds awakeSleepSeconds unmeasurableSleepSeconds awakeCount avgSleepStress averageSpO2Value lowestSpO2Value highestSpO2Value sleepStartTimestampLocal sleepEndTimestampLocal");
        add(result, "scores", namedFields(item.path("sleepScores"), "value qualifierKey", List.of("overall","duration","quality","recovery","deep","light","rem","awakeTime","interruptions","awakenings","restlessness","stress")));
        add(result, "need", fields(item.path("sleepNeed"), "calendarDate baseline actual recommendedBedtimeStartMins recommendedBedtimeEndMins"));
        add(result, "alignment", fields(item.path("sleepAlignment"), "sleepNeedBaselineMins optimalSleepWindowStartMins optimalSleepWindowEndMins status"));
        if (detail) {
            result.putAll(fields(item, "averageRespirationValue lowestRespirationValue highestRespirationValue avgHeartRate"));
            projectList(result, "naps", item.path("dailyNapDTOS"), "napTimeSec napStartTimestampGMT napEndTimestampGMT", 16);
        }
        return result;
    }

    private static Map<String, Object> heartRate(JsonNode item, Options o) {
        var result = fields(item, "minHeartRate maxHeartRate restingHeartRate lastSevenDaysAvgRestingHeartRate");
        if (o.samples() > 0 || o.detail()) {
            series(result, item.path("heartRateValues"), item.path("heartRateValueDescriptors"), "index", "key",
                    "heartrate", "heartRateBpm", o.samples(), 1, 300, o.detail());
        }
        return result;
    }

    private static Map<String, Object> hrv(JsonNode item, Options o, boolean single) {
        var summary = single ? item.path("hrvSummary") : item;
        var result = fields(summary, "weeklyAvg lastNightAvg lastNight5MinHigh status");
        if (o.samples() > 0) {
            projectList(result, "samples", item.path("hrvReadings"), "hrvValue readingTimeGMT readingTimeLocal", o.samples());
        }
        return result;
    }

    private static Map<String, Object> battery(JsonNode item, Options o) {
        var result = fields(item, "charged drained");
        if (o.events()) {
            add(result, "feedback", fields(item.path("bodyBatteryDynamicFeedbackEvent"), "eventTimestampGmt bodyBatteryLevel feedbackShortType"));
            add(result, "endOfDayFeedback", fields(item.path("endOfDayBodyBatteryDynamicFeedbackEvent"), "eventTimestampGmt bodyBatteryLevel feedbackShortType"));
            projectList(result, "activityEvents", item.path("bodyBatteryActivityEvent"), "eventType eventStartTimeGmt durationInMilliseconds bodyBatteryImpact", 16);
        }
        if (o.chunks() > 0) {
            result.put("requestedIntervalsPerDay", o.chunks());
            series(result, item.path("bodyBatteryValuesArray"), item.path("bodyBatteryValueDescriptorDTOList"),
                    "bodyBatteryValueDescriptorIndex", "bodyBatteryValueDescriptorKey", "bodyBatteryLevel",
                    "bodyBatteryLevel", 2 * o.chunks(), 0, 100, false);
            if (result.size() == 1) result.clear();
        }
        return result;
    }

    private static Map<String, Object> spo2(JsonNode item, Options o) {
        var result = fields(item, "averageSpO2 lowestSpO2 lastSevenDaysAvgSpO2 latestSpO2 latestSpO2TimestampLocal avgSleepSpO2");
        if (o.samples() > 0) {
            String descriptor = o.hourly() ? "spo2ValueDesc" : "spo2ValueDescriptor";
            series(result, item.path(o.hourly() ? "spO2HourlyAverages" : "spO2SingleValues"),
                    item.path(o.hourly() ? "spO2HourlyAveragesDescriptorList" : "spO2SingleValuesDescriptorList"),
                    descriptor + "Index", descriptor + "Key", "spo2Level", "spo2Percent", o.samples(), 1, 100, false);
        }
        return result;
    }

    private static void series(Map<String, Object> output, JsonNode rows, JsonNode descriptors, String indexField,
            String keyField, String metric, String label, int limit, int min, int max, boolean includeMean) {
        int time = -1, value = -1;
        for (var descriptor : descriptors) {
            String key = descriptor.path(keyField).asString("");
            int index = descriptor.path(indexField).asInt(-1);
            if (key.equalsIgnoreCase("timestamp")) time = index;
            if (key.equalsIgnoreCase(metric)) value = index;
        }
        if (time < 0 || value < 0 || !rows.isArray()) return;
        List<Map<String, Object>> samples = new ArrayList<>();
        int count = 0;
        double total = 0;
        for (var row : rows) {
            var point = row.path(value);
            Object timestamp = scalar(row.path(time));
            if (timestamp == null || !point.isNumber() || point.asDouble() < min || point.asDouble() > max) continue;
            if (count < limit) samples.add(Map.of("timestamp", timestamp, label, point.numberValue()));
            count++;
            total += point.asDouble();
        }
        if (count == 0) return;
        output.put("validReadingCount", count);
        if (limit > 0) {
            output.put("samples", samples);
            if (count > limit) output.put("truncated", true);
        }
        if (includeMean) output.put("meanOfValidReadingsBpm", total / count);
    }

    private static void projectList(Map<String, Object> dest, String key, JsonNode nodes, String names, int limit) {
        List<Map<String, Object>> selected = new ArrayList<>();
        for (var node : nodes) {
            var entry = fields(node, names);
            if (entry.isEmpty()) continue;
            if (selected.size() == limit) {
                dest.put("truncated", true);
                break;
            }
            selected.add(entry);
        }
        if (!selected.isEmpty()) dest.put(key, selected);
    }

    private static Map<String, Object> namedFields(JsonNode node, String names, List<String> allowed) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : node.properties()) {
            var key = entry.getKey();
            if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,48}") || !allowed.isEmpty() && !allowed.contains(key)) continue;
            add(result, key, fields(entry.getValue(), names));
            if (result.size() == 24) break;
        }
        return result;
    }

    private static void add(Map<String, Object> target, String key, Map<String, Object> values) {
        if (!values.isEmpty()) target.put(key, values);
    }

    private static Map<String, Object> fields(JsonNode node, String keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys.split(" ")) {
            Object value = scalar(node.path(key));
            if (value != null) result.put(key, value);
        }
        return result;
    }

    private static Object scalar(JsonNode value) {
        if (value.isNumber()) return value.numberValue();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isString() && value.asString().length() <= 128) return value.asString();
        return null;
    }
}
