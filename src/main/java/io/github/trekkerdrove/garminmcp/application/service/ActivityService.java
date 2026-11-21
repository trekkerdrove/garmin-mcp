package io.github.trekkerdrove.garminmcp.application.service;

import io.github.trekkerdrove.garminmcp.application.dto.ActivityFilters;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader.Activity;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader.Selection;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader.SearchView;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ActivityService {
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 20;
    private static final List<String> TOTALS = List.of("distanceMeters", "durationSeconds", "ascentMeters", "caloriesKcal");
    private final ActivityReader reader;
    private final Clock clock;

    public ActivityService(ActivityReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock.withZone(ZoneId.of("Europe/Paris"));
    }

    public ToolResult search(String start, String end, Integer offset, Integer limit, ActivityFilters filters) {
        int count = bounded(limit, 25, 100);
        int from = offset == null ? 0 : offset;
        if (from < 0 || from > Integer.MAX_VALUE - count) throw invalid();
        var query = filters == null ? new LinkedHashMap<String, Object>() : filters.parameters();
        LocalDate first = parse(start), last = parse(end);
        if (first != null && last != null && first.isAfter(last)) throw invalid();
        if (first != null) query.put("startDate", first);
        if (last != null) query.put("endDate", last);
        var page = reader.search(query, from, count, SearchView.SUMMARY);
        var data = context("Garmin activity search", first, last);
        data.put("offset", from);
        data.put("activities", page.stream().map(Activity::summary).toList());
        data.put("endConfirmed", page.size() < count);
        if (page.size() == count) {
            data.put("nextOffset", from + count);
            return ToolResult.truncated(data);
        }
        return page.isEmpty() ? ToolResult.empty(data) : ToolResult.ok(data);
    }

    public ToolResult types() {
        var types = reader.types();
        var data = Map.of("source","Garmin activity types", "types", types);
        return types.isEmpty() ? ToolResult.empty(data) : ToolResult.ok(data);
    }

    public ToolResult activity(long id, String view) {
        positive(id);
        var detail = reader.detail(id);
        var data = context("Garmin activity detail", detail.date(), detail.date());
        data.put("activityId", id);
        return switch (view) {
            case "summary" -> { data.put("activity", detail.summary()); yield ToolResult.ok(data); }
            case "effect" -> {
                data.put("trainingEffect", detail.effect());
                yield detail.effect().isEmpty() ? ToolResult.empty(data) : ToolResult.ok(data);
            }
            case "splits" -> selection(data,"splitSummaries", detail.splits());
            default -> throw invalid();
        };
    }

    public ToolResult searchDetails(long id, String kind) {
        positive(id);
        var detail = reader.detail(id);
        if (detail.date() == null) throw unavailable();
        var range = new Window(detail.date(), detail.date());
        var data = context("Garmin activity detail and search", range.start, range.end);
        data.put("activityId", id);
        var view = kind.equals("strength") ? SearchView.STRENGTH : SearchView.ZONES;
        for (int i = 0; i < MAX_PAGES; i++) {
            var page = reader.search(range.query(), i * PAGE_SIZE, PAGE_SIZE, view);
            for (var row : page) {
                if (row.id() != id) continue;
                if (kind.equals("strength")) {
                    for (String key : List.of("totalSets","totalReps","activeSets")) {
                        if (row.summary().containsKey(key)) data.put(key, row.summary().get(key));
                    }
                    var sets = row.exercises();
                    data.put("exercises", sets.rows());
                    if (sets.truncated()) return ToolResult.truncated(data);
                    return !sets.rows().isEmpty() || data.containsKey("totalSets") || data.containsKey("totalReps") || data.containsKey("activeSets")
                            ? ToolResult.ok(data) : ToolResult.empty(data);
                }
                boolean heart = !kind.equals("power") && !row.heartZones().isEmpty();
                boolean power = !kind.equals("heart_rate") && !row.powerZones().isEmpty();
                if (heart) data.put("heartRateSecondsPerZone", row.heartZones());
                if (power) data.put("powerSecondsPerZone", row.powerZones());
                return heart || power ? ToolResult.ok(data) : ToolResult.empty(data);
            }
            if (page.size() < PAGE_SIZE) return ToolResult.empty(data);
        }
        return ToolResult.truncated(data);
    }

    public ToolResult zones(long id, String sensor) {
        if (sensor != null && !Set.of("heart_rate","power","both").contains(sensor)) throw invalid();
        return searchDetails(id, sensor == null ? "both" : sensor);
    }

    public ToolResult workouts(long id, Integer limit) {
        positive(id);
        var data = context("Garmin workouts performed in activity", null, null);
        data.put("activityId", id);
        return selection(data,"workouts", reader.workouts(id, bounded(limit, 5, 20)));
    }

    public ToolResult wellness(String start, String end, Integer limit) {
        var range = requiredWindow(start, end, 366);
        return selection(context("Garmin wellness activities", range.start, range.end),"sessions",
                reader.wellness(range.start, range.end, bounded(limit, 20, 100)));
    }

    public ToolResult running(String start, String end) {
        Window range;
        if (start == null && end == null) {
            LocalDate monday = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
            range = new Window(monday, monday.plusDays(6));
        } else range = requiredWindow(start, end, 366);
        var data = context("Garmin activity search and type hierarchy", range.start, range.end);
        var parents = hierarchy();
        var rows = collect(range, SearchView.SUMMARY);
        if (rows.incomplete) return ToolResult.truncated(data);
        if (!rows.items.isEmpty() && !parents.containsKey("running")) throw unavailable();
        var running = uniqueSports(rows.items, "running", parents);
        double meters = 0;
        for (var activity : running) {
            Double distance = activity.measurement("distanceMeters");
            if (distance == null || distance < 0) throw unavailable();
            meters += distance;
        }
        data.put("runCount", running.size());
        data.put("distanceKm", meters / 1000.0);
        return ToolResult.ok(data);
    }

    public ToolResult compareSleep(String start, String end) {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        Window range = start == null && end == null ? new Window(yesterday.minusDays(27), yesterday) : requiredWindow(start, end, 93);
        var data = context("Garmin activity search and main sleep by wake date", range.start, range.end);
        var activities = collect(range, SearchView.SUMMARY);
        if (activities.incomplete) return ToolResult.truncated(data);
        var training = new HashSet<LocalDate>();
        for (Activity row : activities.items) training.add(row.date());
        var nights = new HashMap<LocalDate, Long>();
        LocalDate lastWake = range.end.plusDays(1);
        for (LocalDate first = range.start.plusDays(1); !first.isAfter(lastWake); first = first.plusDays(31)) {
            LocalDate last = first.plusDays(30).isBefore(lastWake) ? first.plusDays(30) : lastWake;
            for (var night : reader.sleep(first, last)) {
                if (night.wakeDate().isBefore(first) || night.wakeDate().isAfter(last) || night.seconds() == null) continue;
                var existing = nights.putIfAbsent(night.wakeDate().minusDays(1), night.seconds());
                if (existing != null && !existing.equals(night.seconds())) throw unavailable();
            }
        }
        var trained = new Group();
        var rest = new Group();
        for (var date = range.start; !date.isAfter(range.end); date = date.plusDays(1)) {
            (training.contains(date) ? trained : rest).accept(nights.get(date));
        }
        data.put("training", trained.summary());
        data.put("rest", rest.summary());
        if (trained.measured > 0 && rest.measured > 0) data.put("trainingMinusRestSeconds", trained.mean() - rest.mean());
        return trained.measured + rest.measured == 0 ? ToolResult.empty(data) : ToolResult.ok(data);
    }

    public ToolResult performance(String start, String end, String sport, String view, List<String> metrics) {
        var range = requiredWindow(start, end, 366);
        ActivityFilters.key(sport);
        List<String> selected = metrics == null ? TOTALS : metrics;
        if (selected.isEmpty() || selected.size() > TOTALS.size() || selected.stream().anyMatch(k -> k == null || !TOTALS.contains(k))) throw invalid();
        var data = context("Garmin activity search and type hierarchy", range.start, range.end);
        Map<String, String> parents = sport == null ? Map.of() : hierarchy();
        if (sport != null && !parents.containsKey(sport)) throw invalid();
        var projection = switch (view) {
            case "power" -> SearchView.POWER;
            case "best" -> SearchView.SPLITS;
            case "totals" -> SearchView.SUMMARY;
            default -> throw invalid();
        };
        var collection = collect(range, projection);
        if (collection.incomplete) return ToolResult.truncated(data);
        var activities = view.equals("totals") ? uniqueSports(collection.items, sport, parents)
                : collection.items.stream().filter(a -> sport == null || belongs(a.sport(), sport, parents)).toList();
        data.put("activityCount", activities.size());
        if (view.equals("totals")) {
            var bySport = new LinkedHashMap<String, Totals>();
            for (var a : activities) {
                var totals = bySport.computeIfAbsent(a.sport(), unused -> new Totals());
                totals.add(a, selected);
            }
            var groups = new LinkedHashMap<String, Object>();
            bySport.forEach((key, totals) -> groups.put(key, totals.result()));
            data.put("bySport", groups);
            return groups.isEmpty() ? ToolResult.empty(data) : ToolResult.ok(data);
        }
        var best = new LinkedHashMap<String, Effort>();
        for (var a : activities) {
            if (view.equals("power")) {
                if (Boolean.TRUE.equals(a.summary().get("excludeFromPowerCurveReports"))) continue;
                a.powerCurve().forEach((seconds, watts) -> improve(best, seconds + "s", watts, "W", a, false));
            } else {
                a.splits().forEach((distance, seconds) -> improve(best, "fastestSplit_" + distance, seconds,"s", a, true));
                for (String field : List.of("distanceMeters","ascentMeters")) {
                    var value = a.measurement(field);
                    if (value != null && value >= 0) improve(best, field, value,"m", a, false);
                }
            }
        }
        data.put("efforts", best);
        data.put("method", view.equals("power") ? "maximum reported maxAvgPower per interval, excluded activities omitted"
                : "minimum positive Garmin fastestSplit time per distance, maximum reported distance and ascent");
        return best.isEmpty() ? ToolResult.empty(data) : ToolResult.ok(data);
    }

    private static void improve(Map<String, Effort> best, String key, double value, String unit, Activity a, boolean minimize) {
        var existing = best.get(key);
        if (existing == null || (minimize ? value < existing.value : value > existing.value)) {
            best.put(key, new Effort(value, unit, a.id(), a.date(), a.sport()));
        }
    }

    private record Effort(double value, String unit, long activityId, LocalDate date, String sport) {}

    private static class Totals {
        int count;
        final Map<String, Double> totals = new LinkedHashMap<>();
        final Map<String, Integer> measured = new LinkedHashMap<>();
        void add(Activity a, List<String> fields) {
            count++;
            for (String field : fields.stream().distinct().toList()) {
                var value = a.measurement(field);
                if (value != null && value >= 0) {
                    totals.merge(field, value, Double::sum);
                    measured.merge(field, 1, Integer::sum);
                }
            }
        }
        Map<String, Object> result() {
            return Map.of("activityCount", count,"totalsOfMeasuredActivities", totals,"measuredActivityCounts", measured);
        }
    }

    private static class Group {
        int measured, missing;
        long seconds;
        void accept(Long duration) {
            if (duration == null) missing++;
            else { measured++; seconds += duration; }
        }
        double mean() { return seconds / (double) measured; }
        Map<String, Object> summary() {
            var values = new LinkedHashMap<String, Object>();
            values.put("measuredNights", measured);
            values.put("missingNights", missing);
            if (measured > 0) values.put("meanSleepSeconds", mean());
            return values;
        }
    }

    private record Collection(List<Activity> items, boolean incomplete) {}

    private Collection collect(Window range, SearchView view) {
        var unique = new LinkedHashMap<Long, Activity>();
        var query = range.query();
        for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
            var page = reader.search(query, pageNumber * PAGE_SIZE, PAGE_SIZE, view);
            for (var activity : page) {
                if (!activity.date().isBefore(range.start) && !activity.date().isAfter(range.end))
                    unique.putIfAbsent(activity.id(), activity);
            }
            if (page.size() < PAGE_SIZE) return new Collection(new ArrayList<>(unique.values()), false);
        }
        return new Collection(List.of(), true);
    }

    private Map<String, String> hierarchy() {
        var result = new HashMap<String, String>();
        for (var sport : reader.types()) {
            if (result.containsKey(sport.key()) && !Objects.equals(result.get(sport.key()), sport.parent())) {
                throw unavailable();
            }
            result.put(sport.key(), sport.parent());
        }
        return result;
    }

    private static List<Activity> uniqueSports(List<Activity> rows, String sport, Map<String, String> parents) {
        var selected = rows.stream().filter(a -> sport == null || belongs(a.sport(), sport, parents)).toList();
        var ids = new HashSet<Long>();
        for (var a : selected) ids.add(a.id());
        // A selected parent already accounts for its legs. If the parent sport is excluded, keep the matching legs.
        return selected.stream().filter(a -> a.parentId() == null || !ids.contains(a.parentId())).toList();
    }

    private static boolean belongs(String key, String family, Map<String, String> hierarchy) {
        var visited = new HashSet<String>();
        boolean matched = false;
        while (key != null) {
            if (!visited.add(key) || !hierarchy.containsKey(key)) throw unavailable();
            if (key.equals(family)) matched = true;
            key = hierarchy.get(key);
        }
        return matched;
    }

    private static ToolResult selection(Map<String, Object> data, String name, Selection selection) {
        data.put(name, selection.rows());
        if (selection.truncated()) return ToolResult.truncated(data);
        return selection.rows().isEmpty() ? ToolResult.empty(data) : ToolResult.ok(data);
    }

    private static Window requiredWindow(String start, String end, int maxDays) {
        LocalDate first = parse(start), last = parse(end);
        if (first == null || last == null || first.isAfter(last) || ChronoUnit.DAYS.between(first, last) >= maxDays) throw invalid();
        return new Window(first, last);
    }

    private static LocalDate parse(String value) {
        if (value == null) return null;
        try {
            var date = LocalDate.parse(value);
            if (date.getYear() < 1 || date.getYear() > 9998) throw invalid();
            return date;
        } catch (DateTimeParseException e) { throw invalid(); }
    }

    private record Window(LocalDate start, LocalDate end) {
        Map<String, Object> query() { return Map.of("startDate", start, "endDate", end, "excludeChildren", false); }
    }

    private Map<String, Object> context(String source, LocalDate start, LocalDate end) {
        var data = new LinkedHashMap<String, Object>();
        data.put("source", source);
        if (start != null) data.put("startDate", start);
        if (end != null) data.put("endDate", end);
        var today = LocalDate.now(clock);
        if (start != null && end != null && !today.isBefore(start) && !today.isAfter(end)) data.put("todayInProgress", true);
        return data;
    }

    private static int bounded(Integer value, int defaultValue, int max) {
        if (value == null) return defaultValue;
        if (value < 1 || value > max) throw invalid();
        return value;
    }
    private static void positive(long id) { if (id <= 0) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid activity request"); }
    private static GarminFailure unavailable() { return new GarminFailure("garmin_unavailable"); }
}
