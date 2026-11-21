package io.github.trekkerdrove.garminmcp.application.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.WellnessReader;
import io.github.trekkerdrove.garminmcp.application.port.out.WellnessReader.Metric;
import io.github.trekkerdrove.garminmcp.application.port.out.WellnessReader.Observation;
import io.github.trekkerdrove.garminmcp.application.port.out.WellnessReader.Options;
import io.github.trekkerdrove.garminmcp.application.port.out.WellnessReader.View;

public final class WellnessService {
    private static final Set<String> GOALS = Set.of("netCalorieGoal", "dailyStepGoal", "dailyPushesGoal",
            "intensityMinutesGoal", "userFloorsAscendedGoal", "hydrationGoalInML", "goal", "consumedGoal",
            "goalInMl", "goalInFractionalMl");
    private record Gap(View source, LocalDate from, LocalDate through, String status) {
        boolean includes(LocalDate date) {
            return !date.isBefore(from) && !date.isAfter(through);
        }
    }

    private final WellnessReader reader;

    public WellnessService(WellnessReader reader) {
        this.reader = reader;
    }

    public ToolResult report(View view, String start, String end, Options options, boolean weekly, boolean enrichSeries) {
        return ToolResult.run(() -> {
            LocalDate today = LocalDate.now(ZoneId.of("Europe/Paris"));
            LocalDate first = start == null ? today : parse(start);
            LocalDate last = end == null ? first : parse(end);
            long days = ChronoUnit.DAYS.between(first, last) + 1;
            if (days < 1 || days > 366 || view.windowDays == 1 && days != 1
                    || (options.chunks() > 0 || options.events()) && days > 31
                    || options.samples() > 0 && days != 1 || options.chunks() < 0 || options.chunks() > 24
                    || options.samples() < 0 || options.samples() > 200) {
                throw new IllegalArgumentException("invalid interval or sampling limit");
            }
            if (options.metrics() != null && (options.metrics().size() > Metric.values().length
                    || options.metrics().stream().anyMatch(java.util.Objects::isNull) || options.metrics().stream().distinct().count() != options.metrics().size())) {
                throw new IllegalArgumentException("invalid metrics");
            }
            if (weekly && view == View.TRENDS && options.metrics() != null && !options.metrics().isEmpty()
                    && !options.metrics().contains(Metric.intensityMinutes)) {
                throw new IllegalArgumentException("weekly intensity requires intensityMinutes");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("startDate", first);
            data.put("endDate", last);
            data.put("includesCurrentDay", !today.isBefore(first) && !today.isAfter(last));
            data.put("source", "Garmin Connect Personal API");
            data.put("units", units(view));
            List<Observation> rows = new ArrayList<>();
            List<Gap> gaps = new ArrayList<>();
            for (LocalDate from = first; !from.isAfter(last);) {
                LocalDate to = min(last, from.plusDays(view.windowDays - 1));
                try {
                    rows.addAll(readWindow(view, from, to, options));
                } catch (GarminFailure error) {
                    data.put("failedFrom", from);
                    data.put("failedThrough", to);
                    data.put("error", error.status());
                    gaps.add(new Gap(view, from, last, error.status()));
                    extractGoals(view, rows, data);
                    coverage(data, first, last, rows, view, gaps);
                    return new ToolResult(error.status(), data);
                }
                from = to.plusDays(1);
            }
            extractGoals(view, rows, data);
            rows.sort(java.util.Comparator.comparing(Observation::date));
            if (view == View.SLEEP && enrichSeries && !rows.isEmpty()) {
                enrichSleep(rows, first, last, gaps);
            }
            if (view == View.STRESS && options.detail() || view == View.RESPIRATION) {
                addDailyDetails(view, rows, first, last, gaps);
            }
            if (!gaps.isEmpty()) data.put("enrichmentUnavailable", gaps);
            if (view == View.VO2 && enrichSeries) data.put("dailySeries", continuousVo2(rows, first, last));
            if (weekly) {
                data.put("weeks", weekly(rows, view));
                data.put("weeklyMethod", view == View.TRENDS ? "Sum of moderate + 2 × vigorous minutes on measured days"
                        : "Arithmetic mean over days with a measured value, each day weighted equally");
            }
            if (view == View.HRV) mean(rows, "lastNightAvg", "meanOfMeasuredNightlyAveragesMs", data);
            boolean optionalCanSupplyMeasurement = view == View.STRESS || view == View.RESPIRATION;
            int measuredDays = coverage(data, first, last, rows, view, optionalCanSupplyMeasurement ? gaps : List.of());
            if (measuredDays == 0 && optionalCanSupplyMeasurement && !gaps.isEmpty()) {
                return new ToolResult(gaps.getFirst().status(), data);
            }
            if (rows.stream().anyMatch(row -> Boolean.TRUE.equals(row.values().get("truncated")))) {
                return ToolResult.truncated(data);
            }
            return measuredDays == 0 ? ToolResult.empty(data) : ToolResult.ok(data);
        });
    }

    private List<Observation> readWindow(View view, LocalDate from, LocalDate to, Options options) {
        record Key(LocalDate date, Object sport) {}
        Map<Key, Observation> unique = new LinkedHashMap<>();
        for (var row : reader.read(view, from, to, options)) {
            if (row.date().isBefore(from) || row.date().isAfter(to)) throw new GarminFailure("garmin_unavailable");
            if (row.values().isEmpty()) continue;
            Object sport = view == View.VO2 || view == View.ZONES ? row.values().get("sport") : null;
            var key = new Key(row.date(), sport);
            var existing = unique.putIfAbsent(key, new Observation(row.date(), new LinkedHashMap<>(row.values())));
            if (existing != null && !existing.values().equals(row.values()) || view == View.ZONES && unique.size() > 32) {
                throw new GarminFailure("garmin_unavailable");
            }
        }
        return List.copyOf(unique.values());
    }

    private static void extractGoals(View view, List<Observation> rows, Map<String, Object> result) {
        if (view != View.SUMMARY && view != View.TRENDS) return;
        List<Observation> goals = new ArrayList<>();
        for (var row : rows) {
            var dayGoals = new LinkedHashMap<String, Object>();
            for (var entry : List.copyOf(row.values().entrySet())) {
                Object measured = separateGoals(entry.getKey(), entry.getValue(), dayGoals);
                if (measured == null) row.values().remove(entry.getKey());
                else row.values().put(entry.getKey(), measured);
            }
            if (!dayGoals.isEmpty()) goals.add(new Observation(row.date(), dayGoals));
        }
        rows.removeIf(row -> row.values().isEmpty());
        if (!goals.isEmpty()) result.put("goals", goals);
    }

    private static Object separateGoals(String field, Object value, Map<String, Object> goals) {
        if (GOALS.contains(field)) {
            goals.put(field, value);
            return null;
        }
        if (!(value instanceof Map<?, ?> group)) return value;
        Map<String, Object> measured = new LinkedHashMap<>();
        Map<String, Object> nestedGoals = new LinkedHashMap<>();
        for (var entry : group.entrySet()) {
            String key = (String) entry.getKey();
            Object observation = separateGoals(key, entry.getValue(), nestedGoals);
            if (observation != null) measured.put(key, observation);
        }
        if (!nestedGoals.isEmpty()) goals.put(field, nestedGoals);
        return measured.isEmpty() ? null : measured;
    }

    private void enrichSleep(List<Observation> rows, LocalDate first, LocalDate last, List<Gap> gaps) {
        Map<LocalDate, Observation> nights = new TreeMap<>();
        rows.forEach(night -> nights.put(night.date(), night));
        var options = new Options(List.of(Metric.heartRate), 0, 0, false, false, false, null, false);
        boolean allowReads = true;
        for (View extra : List.of(View.HRV, first.equals(last) ? View.HEART_RATE : View.TRENDS)) {
            if (!allowReads) {
                gaps.add(new Gap(extra, first, last, "not_requested"));
                break;
            }
            allowReads = collect(extra, first, last, options, gaps, item -> {
                var night = nights.get(item.date());
                if (night == null) return;
                Object value = switch (extra) {
                    case HRV -> item.values().get("lastNightAvg");
                    case HEART_RATE -> item.values().get("restingHeartRate");
                    default -> item.values().get("heartRate") instanceof Map<?, ?> hr ? hr.get("restingValue") : null;
                };
                if (value != null) night.values().put(extra == View.HRV ? "nightHrvMs" : "restingHeartRateBpm",
                        Map.of("date", item.date(), "value", value));
            });
        }
    }

    private void addDailyDetails(View view, List<Observation> rows, LocalDate first, LocalDate last, List<Gap> gaps) {
        Map<LocalDate, Observation> byDate = new TreeMap<>();
        rows.forEach(r -> byDate.put(r.date(), r));
        boolean respiration = view == View.RESPIRATION;
        var options = new Options(List.of(), 0, 0, respiration, false, false, null, false);
        collect(respiration ? View.SLEEP : View.STRESS_DETAILS, first, last, options, gaps, item -> {
            var fields = new LinkedHashMap<>(item.values());
            if (respiration) fields.keySet().retainAll(List.of("averageRespirationValue", "lowestRespirationValue",
                    "highestRespirationValue"));
            if (!fields.isEmpty()) byDate.computeIfAbsent(item.date(), d -> new Observation(d, new LinkedHashMap<>()))
                    .values().put(respiration ? "sleep" : "dailySummary", fields);
        });
        rows.clear();
        rows.addAll(byDate.values());
    }

    private boolean collect(View view, LocalDate first, LocalDate last, Options options, List<Gap> gaps,
            Consumer<Observation> merge) {
        for (LocalDate from = first; !from.isAfter(last);) {
            LocalDate to = min(last, from.plusDays(view.windowDays - 1));
            try {
                readWindow(view, from, to, options).forEach(merge);
            } catch (GarminFailure error) {
                gaps.add(new Gap(view, from, to, error.status()));
                if (Set.of("garmin_rate_limited", "garmin_access_denied", "missing_garmin_key", "invalid_garmin_key")
                        .contains(error.status())) {
                    if (to.isBefore(last)) gaps.add(new Gap(view, to.plusDays(1), last, "not_requested"));
                    return false;
                }
            }
            from = to.plusDays(1);
        }
        return true;
    }

    private static List<Map<String, Object>> continuousVo2(List<Observation> observations, LocalDate first, LocalDate last) {
        List<Map<String, Object>> series = new ArrayList<>();
        Map<String, Observation> latest = new LinkedHashMap<>();
        int index = 0;
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            while (index < observations.size() && observations.get(index).date().equals(date)) {
                Observation measurement = observations.get(index++);
                latest.put((String) measurement.values().get("sport"), measurement);
            }
            for (var item : latest.values()) {
                series.add(Map.of("date", date, "measuredDate", item.date(), "carriedForward", !date.equals(item.date()),
                        "measurement", item.values()));
            }
        }
        return series;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate parse(String text) {
        if (text == null || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("invalid date");
        try {
            return LocalDate.parse(text);
        } catch (java.time.DateTimeException error) {
            throw new IllegalArgumentException("invalid date");
        }
    }

    private static int coverage(Map<String, Object> result, LocalDate first, LocalDate through,
            List<Observation> rows, View view, List<Gap> gaps) {
        var measured = new HashSet<LocalDate>();
        for (var row : rows) {
            boolean hasMeasurement = switch (view) {
                case FITNESS_AGE -> row.values().get("fitnessAge") instanceof Number;
                case HRV -> row.values().get("lastNightAvg") instanceof Number
                        || row.values().get("lastNight5MinHigh") instanceof Number || row.values().get("weeklyAvg") instanceof Number
                        || row.values().get("samples") instanceof List<?> samples
                        && samples.stream().anyMatch(sample -> sample instanceof Map<?, ?> point && point.get("hrvValue") instanceof Number);
                case STEPS -> row.values().get("totalSteps") instanceof Number || row.values().get("totalDistance") instanceof Number;
                case HYDRATION -> row.values().get("valueInML") instanceof Number || row.values().get("sweatLossInML") instanceof Number
                        || row.values().get("activityIntakeInML") instanceof Number;
                default -> !row.values().isEmpty();
            };
            if (hasMeasurement) measured.add(row.date());
        }
        result.put("observations", rows);
        result.put("measuredDays", measured.size());
        List<LocalDate> missing = new ArrayList<>();
        List<LocalDate> unknown = new ArrayList<>();
        for (LocalDate date = first; !date.isAfter(through); date = date.plusDays(1)) {
            if (measured.contains(date)) continue;
            if (isUnknown(gaps, date)) unknown.add(date);
            else missing.add(date);
        }
        result.put("missingDates", missing);
        if (!unknown.isEmpty()) result.put("unknownDates", unknown);
        return measured.size();
    }

    private static boolean isUnknown(List<Gap> gaps, LocalDate date) {
        return gaps.stream().anyMatch(gap -> gap.includes(date));
    }

    private static void mean(List<Observation> rows, String field, String name, Map<String, Object> result) {
        rows.stream().map(row -> row.values().get(field)).filter(Number.class::isInstance)
                .mapToDouble(n -> ((Number) n).doubleValue()).average().ifPresent(v -> result.put(name, v));
    }

    private static List<Map<String, Object>> weekly(List<Observation> rows, View view) {
        Map<LocalDate, List<Observation>> weeks = new TreeMap<>();
        for (var row : rows) {
            LocalDate monday = row.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            weeks.computeIfAbsent(monday, d -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (var entry : weeks.entrySet()) {
            List<Number> values = new ArrayList<>();
            for (var row : entry.getValue()) {
                Object metric = switch (view) {
                    case TRENDS -> row.values().get("intensityMinutes") instanceof Map<?, ?> im ? im.get("weighted") : null;
                    case STEPS -> row.values().get("totalSteps");
                    case STRESS -> row.values().get("overallStressLevel");
                    default -> null;
                };
                if (metric instanceof Number n) values.add(n);
            }
            Map<String, Object> week = new LinkedHashMap<>();
            week.put("weekStartingMonday", entry.getKey());
            week.put("measuredDays", values.size());
            if (!values.isEmpty()) {
                double total = values.stream().mapToDouble(Number::doubleValue).sum();
                if (view == View.TRENDS) week.put("weightedIntensityMinutes", total);
                if (view == View.STEPS) week.put("totalSteps", total);
                if (view != View.TRENDS) week.put("meanPerMeasuredDay", total / values.size());
            }
            results.add(week);
        }
        return results;
    }

    private static String units(View view) {
        return switch (view) {
            case FITNESS_AGE -> "years";
            case ZONES, HEART_RATE -> "beats/min";
            case HRV -> "ms";
            case BATTERY -> "Body Battery points (0–100)";
            case STEPS -> "steps, distance in m";
            case STRESS, STRESS_DETAILS -> "stress score (0–100), durations in s unless named InMillis";
            case HYDRATION -> "mL";
            case SPO2 -> "%";
            case VO2 -> "mL/kg/min";
            case RESPIRATION -> "breaths/min";
            case SLEEP -> "sleep stages in s, alignment in minutes, SpO2 in %, heart rate in beats/min, respiration in breaths/min";
            case SUMMARY, TRENDS -> "energy in kcal, distance in m, heart rate in beats/min, intensity in minutes, hydration in mL, respiration in breaths/min, SpO2 in %";
        };
    }
}
