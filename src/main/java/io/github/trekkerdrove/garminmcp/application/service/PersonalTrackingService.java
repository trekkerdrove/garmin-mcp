package io.github.trekkerdrove.garminmcp.application.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminPersonalData;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminPersonalData.CycleView;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminPersonalData.WeightView;

public final class PersonalTrackingService {
    private static final int ROW_LIMIT = 256;
    private final GarminPersonalData garmin;
    private final Clock clock;

    public PersonalTrackingService(GarminPersonalData garmin, Clock clock) {
        this.garmin = garmin;
        this.clock = clock;
    }

    public ToolResult weight(String start, String end, WeightView view) {
        var period = period(start, end, 730);
        var selection = view == null ? WeightView.measurements : view;
        var data = period.describe(selection == WeightView.summary ? "H17" : "H16");
        data.put("weightUnit", "kg");
        var rows = new ArrayList<Map<String, Object>>();
        for (var from = period.start(); !from.isAfter(period.end());) {
            LocalDate to = from.plusDays(Math.min(363, ChronoUnit.DAYS.between(from, period.end())));
            for (var row : garmin.weights(from, to, selection)) {
                if (rows.size() == ROW_LIMIT) {
                    data.put("rows", rows);
                    return ToolResult.truncated(data);
                }
                rows.add(row);
            }
            from = to.plusDays(1);
        }
        return rows(data, rows);
    }

    public ToolResult goals(String start, String end) {
        var period = period(start, end, 364);
        var data = period.describe("H18");
        data.put("weightUnit", "g");
        return rows(data, garmin.goals(period.start(), period.end()));
    }

    public ToolResult nutrition(String start, String end, Long mealId) {
        if (mealId != null && mealId < 1) {
            throw new IllegalArgumentException("mealId must be positive");
        }
        var period = period(start, end, 31);
        boolean journal = period.start().equals(period.end());
        var data = period.describe("H19");
        var days = new ArrayList<Map<String, Object>>();
        var totals = new LinkedHashMap<String, Double>();
        var coverage = new LinkedHashMap<String, Integer>();
        var missingDates = new ArrayList<LocalDate>();
        int foodCount = 0;
        boolean truncated = false;
        for (LocalDate date : period.start().datesUntil(period.end().plusDays(1)).toList()) {
            var item = garmin.nutrition(date, mealId, journal);
            var day = new LinkedHashMap<String, Object>();
            day.put("date", date);
            day.put("foodCount", item.foodCount());
            day.put("nutrients", item.nutrients());
            day.putAll(item.journal());
            days.add(day);
            if (item.foodCount() > 0 || !item.nutrients().isEmpty()) {
                foodCount += item.foodCount();
                item.nutrients().forEach((key, value) -> {
                    totals.merge(key, value.doubleValue(), Double::sum);
                    coverage.merge(key, 1, Integer::sum);
                });
            } else {
                missingDates.add(date);
            }
            truncated |= item.truncated();
        }
        data.put("days", days);
        data.put("missingDates", missingDates);
        data.put("foodCount", foodCount);
        data.put("totals", totals);
        data.put("measuredDaysByNutrient", coverage);
        data.put("units", Map.of("calories", "kcal", "carbs", "g", "protein", "g", "fat", "g"));
        if (truncated) {
            return ToolResult.truncated(data);
        }
        return foodCount == 0 && totals.isEmpty() ? ToolResult.empty(data) : ToolResult.ok(data);
    }

    public ToolResult cycle(String start, String end, CycleView view, boolean details, boolean analysis) {
        var period = period(start, end, 31);
        CycleView selection = view == null ? CycleView.summary : view;
        if (details && selection != CycleView.day) {
            throw new IllegalArgumentException("Details require day view");
        }
        var data = period.describe(selection == CycleView.day ? "H20" : "H21");
        var measurements = new ArrayList<Map<String, Object>>();
        var missing = new ArrayList<LocalDate>();
        boolean truncated = false;
        for (LocalDate date : period.start().datesUntil(period.end().plusDays(1)).toList()) {
            var projected = garmin.cycle(date, selection, details, analysis);
            if (projected.isEmpty()) {
                missing.add(date);
            } else {
                measurements.add(Map.of("date", date, "cycle", projected));
                truncated |= Boolean.TRUE.equals(projected.get("truncated"));
            }
        }
        data.put("missingDates", missing);
        data.put("rows", measurements);
        if (truncated) return ToolResult.truncated(data);
        return measurements.isEmpty() ? ToolResult.empty(data) : ToolResult.ok(data);
    }

    public ToolResult profile() {
        var settings = garmin.profile();
        var data = Map.of("source", "P1", "settings", settings);
        return settings.isEmpty() ? ToolResult.empty(data) : ToolResult.ok(data);
    }

    private static ToolResult rows(Map<String, Object> data, List<Map<String, Object>> rows) {
        data.put("rows", rows.stream().limit(ROW_LIMIT).toList());
        if (rows.size() > ROW_LIMIT) {
            return ToolResult.truncated(data);
        }
        return rows.isEmpty() ? ToolResult.empty(data) : ToolResult.ok(data);
    }

    private Period period(String start, String end, int maxDays) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = start == null ? (end == null ? today : LocalDate.parse(end)) : LocalDate.parse(start);
        LocalDate to = end == null ? from : LocalDate.parse(end);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days < 1 || days > maxDays || from.getYear() < 1 || to.getYear() > 9999) {
            throw new IllegalArgumentException("Invalid calendar range");
        }
        return new Period(from, to, !today.isBefore(from) && !today.isAfter(to));
    }

    private record Period(LocalDate start, LocalDate end, boolean includesToday) {
        Map<String, Object> describe(String source) {
            var data = new LinkedHashMap<String, Object>();
            data.put("startDate", start);
            data.put("endDate", end);
            data.put("includesCurrentDay", includesToday);
            data.put("source", source);
            return data;
        }
    }
}
