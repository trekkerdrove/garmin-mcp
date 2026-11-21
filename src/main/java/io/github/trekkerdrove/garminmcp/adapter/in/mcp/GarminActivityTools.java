package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import io.github.trekkerdrove.garminmcp.application.dto.ActivityFilters;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.service.ActivityService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class GarminActivityTools {
    private final ActivityService service;

    GarminActivityTools(ActivityService service) { this.service = service; }

    @McpTool(name = "running_distance",
            description = "Count runs and kilometres. Defaults to the previous Monday–Sunday in Europe/Paris. Explicit date ranges accept up to 366 days. Reads up to 20 pages of 50 activities.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult runningDistance(
            @McpToolParam(required = false, description = "First inclusive local date, YYYY-MM-DD.") String startDate,
            @McpToolParam(required = false, description = "Last inclusive local date, YYYY-MM-DD.") String endDate) {
        return ToolResult.run(() -> service.running(startDate, endDate));
    }

    @McpTool(name = "compare_sleep_by_training_day",
            description = "Compare main-sleep seconds after training and rest days. Defaults to the last 28 finished days. An explicit range accepts up to 93 days. Reads up to 20 pages of 50 activities.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult compareSleep(
            @McpToolParam(required = false, description = "First inclusive local date, YYYY-MM-DD.") String startDate,
            @McpToolParam(required = false, description = "Last inclusive local date, YYYY-MM-DD.") String endDate) {
        return ToolResult.run(() -> service.compareSleep(startDate, endDate));
    }

    @McpTool(name = "garmin_search_activities",
            description = "Search activities with the Garmin filters. Distances and elevation are meters, durations seconds, dive depth centimeters. A full page includes the next offset.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult search(
            @McpToolParam(required = false, description = "First inclusive local date, YYYY-MM-DD.") String startDate,
            @McpToolParam(required = false, description = "Last inclusive local date, YYYY-MM-DD.") String endDate,
            @McpToolParam(required = false, description = "Zero-based offset, default 0.") Integer offset,
            @McpToolParam(required = false, description = "Page size 1–100, default 25.") Integer limit,
            @McpToolParam(required = false, description = "Optional search criteria. Use all four coordinates for a bounding box. favorite accepts -1, 0 or 1. sortBy accepts startLocal, name, distance or duration. sortOrder accepts asc or desc.") ActivityFilters filters) {
        return ToolResult.run(() -> service.search(startDate, endDate, offset, limit, filters));
    }

    @McpTool(name = "garmin_get_activity",
            description = "Read the activity summary, local start, sport and available measurements, including FTP and lactate threshold.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult activity(
            @McpToolParam(required = true, description = "Positive Garmin activity ID.") long activityId) {
        return ToolResult.run(() -> service.activity(activityId, "summary"));
    }

    @McpTool(name = "garmin_get_activity_types",
            description = "List sport keys and their parent key for activity search and analysis.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult activityTypes() {
        return ToolResult.run(() -> service.types());
    }

    @McpTool(name = "garmin_get_activity_workouts",
            description = "Get workouts performed in this activity, including targets and repeat instructions. Up to 64 steps per workout.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult workouts(
            @McpToolParam(required = true, description = "Positive Garmin activity ID.") long activityId,
            @McpToolParam(required = false, description = "Workouts returned, 1–20; default 5.") Integer limit) {
        return ToolResult.run(() -> service.workouts(activityId, limit));
    }

    @McpTool(name = "garmin_get_wellness_sessions",
            description = "Read wellness sessions, measurements and zone durations for up to 366 inclusive days.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult wellness(
            @McpToolParam(required = true, description = "First inclusive local date, YYYY-MM-DD.") String startDate,
            @McpToolParam(required = true, description = "Last inclusive local date, YYYY-MM-DD.") String endDate,
            @McpToolParam(required = false, description = "Sessions returned, 1–100; default 20.") Integer limit) {
        return ToolResult.run(() -> service.wellness(startDate, endDate, limit));
    }

    @McpTool(name = "garmin_get_activity_splits",
            description = "Get up to 100 split summaries and measurements by segment type.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult splits(
            @McpToolParam(required = true, description = "Positive Garmin activity ID.") long activityId) {
        return ToolResult.run(() -> service.activity(activityId, "splits"));
    }

    @McpTool(name = "garmin_get_activity_zones",
            description = "Get seconds in each heart-rate or power zone for an activity. Reads up to 20 pages of 50 activities.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult zones(
            @McpToolParam(required = true, description = "Positive Garmin activity ID.") long activityId,
            @McpToolParam(required = false, description = "heart_rate, power or both (default).") String sensor) {
        return ToolResult.run(() -> service.zones(activityId, sensor));
    }

    @McpTool(name = "garmin_get_activity_strength_summary",
            description = "Get sets, repetitions, exercise volume and maximum weight. Up to 100 exercise groups. Reads up to 20 pages of 50 activities.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult strength(
            @McpToolParam(required = true, description = "Positive Garmin activity ID.") long activityId) {
        return ToolResult.run(() -> service.searchDetails(activityId, "strength"));
    }

    @McpTool(name = "garmin_get_training_effect",
            description = "Get the aerobic and anaerobic training effect, label and observed training load of an activity.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult effect(
            @McpToolParam(required = true, description = "Positive Garmin activity ID.") long activityId) {
        return ToolResult.run(() -> service.activity(activityId, "effect"));
    }

    @McpTool(name = "garmin_summarize_activities",
            description = "Summarize activity counts and measured totals by sport for up to 366 days. Reads up to 20 pages of 50 activities.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult summarize(
            @McpToolParam(required = true, description = "First inclusive local date, YYYY-MM-DD.") String startDate,
            @McpToolParam(required = true, description = "Last inclusive local date, YYYY-MM-DD.") String endDate,
            @McpToolParam(required = false, description = "Optional Garmin sport key or parent family; see garmin_get_activity_types.") String sport,
            @McpToolParam(required = false, description = "Select distanceMeters, durationSeconds, ascentMeters or caloriesKcal. Defaults to all four.") List<String> metrics) {
        return ToolResult.run(() -> service.performance(startDate, endDate, sport, "totals", metrics));
    }

    @McpTool(name = "garmin_get_power_curve",
            description = "Best recorded average watts per Garmin duration, including source activity, within 366 days. Reads up to 20 pages of 50 activities.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult power(
            @McpToolParam(required = true, description = "First inclusive local date, YYYY-MM-DD.") String startDate,
            @McpToolParam(required = true, description = "Last inclusive local date, YYYY-MM-DD.") String endDate,
            @McpToolParam(required = false, description = "Optional Garmin sport key or parent family; see garmin_get_activity_types.") String sport) {
        return ToolResult.run(() -> service.performance(startDate, endDate, sport, "power", null));
    }

    @McpTool(name = "garmin_get_best_efforts",
            description = "Best recorded splits, furthest distance and greatest ascent with source activity, within 366 days. Reads up to 20 pages of 50 activities.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult efforts(
            @McpToolParam(required = true, description = "First inclusive local date, YYYY-MM-DD.") String startDate,
            @McpToolParam(required = true, description = "Last inclusive local date, YYYY-MM-DD.") String endDate,
            @McpToolParam(required = false, description = "Optional Garmin sport key or parent family; see garmin_get_activity_types.") String sport) {
        return ToolResult.run(() -> service.performance(startDate, endDate, sport, "best", null));
    }
}
