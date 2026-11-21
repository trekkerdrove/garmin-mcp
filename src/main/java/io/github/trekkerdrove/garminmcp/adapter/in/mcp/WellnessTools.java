package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import java.util.List;

import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.WellnessReader.Metric;
import io.github.trekkerdrove.garminmcp.application.port.out.WellnessReader.Options;
import io.github.trekkerdrove.garminmcp.application.port.out.WellnessReader.View;
import io.github.trekkerdrove.garminmcp.application.service.WellnessService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
class WellnessTools {
    private static final String DATE = "Local calendar date (YYYY-MM-DD), default today in Europe/Paris.";
    private static final String FROM = "Inclusive first date (YYYY-MM-DD), default today in Europe/Paris.";
    private static final String THROUGH = "Inclusive last date (YYYY-MM-DD); omit for one day. Maximum 366 days.";
    private static final String WEEKLY = "Include Monday-based weekly figures over measured days.";
    private static final String SAMPLES = "Maximum projected samples, 0–200 (default 0). Only for one date.";

    public enum ZoneSport { DEFAULT, RUNNING, CYCLING, SWIMMING, ROWING }
    public enum Vo2Sport { RUNNING, CYCLING, ROWING, WALKING }

    private final WellnessService wellness;

    WellnessTools(WellnessService wellness) {
        this.wellness = wellness;
    }

    @McpTool(name = "garmin_get_daily_summary", description = "Daily calories, activity, goals and available wellness measures.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult dailySummary(
            @McpToolParam(required = false, description = DATE) String date) {
        return wellness.report(View.SUMMARY, date, null, Options.BASIC, false, false);
    }

    @McpTool(name = "garmin_get_wellness_trends", description = "Daily wellness metric groups. Weekly intensity uses moderate minutes plus twice vigorous minutes.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult wellnessTrends(
            @McpToolParam(required = false, description = FROM) String startDate,
            @McpToolParam(required = false, description = THROUGH) String endDate,
            @McpToolParam(required = false, description = "Selected metric groups. Omitting the list returns every documented group.") List<Metric> metrics,
            @McpToolParam(required = false, description = WEEKLY) Boolean weekly) {
        var options = new Options(metrics, 0, 0,
                false, false, false, null, false);
        return wellness.report(View.TRENDS, startDate, endDate, options, Boolean.TRUE.equals(weekly), false);
    }

    @McpTool(name = "garmin_get_sleep", description = "Main sleep duration, stages, scores, need and window by wake date. Detail adds naps and overnight respiration.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult sleep(
            @McpToolParam(required = false, description = FROM) String startDate,
            @McpToolParam(required = false, description = THROUGH) String endDate,
            @McpToolParam(required = false, description = "Include the available detailed fields.") Boolean detail,
            @McpToolParam(required = false, description = "Also request same-date resting heart rate and HRV.") Boolean enrich) {
        var options = new Options(List.of(), 0, 0,
                Boolean.TRUE.equals(detail), false, false, null, false);
        return wellness.report(View.SLEEP, startDate, endDate, options, false, Boolean.TRUE.equals(enrich));
    }

    @McpTool(name = "garmin_get_heart_rate", description = "Daily resting, minimum and maximum heart rate, with optional bounded samples and mean.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult heartRate(
            @McpToolParam(required = false, description = DATE) String date,
            @McpToolParam(required = false, description = SAMPLES) Integer samples,
            @McpToolParam(required = false, description = "Compute the arithmetic mean of the valid heart-rate readings.") Boolean mean) {
        var options = new Options(List.of(), samples == null ? 0 : samples, 0,
                Boolean.TRUE.equals(mean), false, false, null, false);
        return wellness.report(View.HEART_RATE, date, null, options, false, false);
    }

    @McpTool(name = "garmin_get_hrv", description = "Nightly and weekly HRV, measured-period mean, and optional readings on a single date.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult hrv(
            @McpToolParam(required = false, description = FROM) String startDate,
            @McpToolParam(required = false, description = THROUGH) String endDate,
            @McpToolParam(required = false, description = SAMPLES) Integer samples) {
        var options = new Options(List.of(), samples == null ? 0 : samples, 0,
                false, false, false, null, false);
        return wellness.report(View.HRV, startDate, endDate, options, false, false);
    }

    @McpTool(name = "garmin_get_steps", description = "Daily steps, distance and goals. Weekly means divide by days with measured steps.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult steps(
            @McpToolParam(required = false, description = FROM) String startDate,
            @McpToolParam(required = false, description = THROUGH) String endDate,
            @McpToolParam(required = false, description = WEEKLY) Boolean weekly) {
        return wellness.report(View.STEPS, startDate, endDate, Options.BASIC, Boolean.TRUE.equals(weekly), false);
    }

    @McpTool(name = "garmin_get_stress", description = "Daily stress scores and durations. The weekly mean weights each measured day equally.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult stress(
            @McpToolParam(required = false, description = FROM) String startDate,
            @McpToolParam(required = false, description = THROUGH) String endDate,
            @McpToolParam(required = false, description = WEEKLY) Boolean weekly,
            @McpToolParam(required = false, description = "Include daily-summary stress levels and duration breakdowns.") Boolean breakdown) {
        var options = new Options(List.of(), 0, 0,
                Boolean.TRUE.equals(breakdown), false, false, null, false);
        return wellness.report(View.STRESS, startDate, endDate, options, Boolean.TRUE.equals(weekly), false);
    }

    @McpTool(name = "garmin_get_hydration", description = "Daily hydration in mL, including goal, estimated sweat loss and latest available entry.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult hydration(
            @McpToolParam(required = false, description = FROM) String startDate,
            @McpToolParam(required = false, description = THROUGH) String endDate) {
        return wellness.report(View.HYDRATION, startDate, endDate, Options.BASIC, false, false);
    }

    @McpTool(name = "garmin_get_body_battery", description = "Daily Body Battery charge and drain, optional interval readings and classified events.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult bodyBattery(
            @McpToolParam(required = false, description = FROM) String startDate,
            @McpToolParam(required = false, description = THROUGH) String endDate,
            @McpToolParam(required = false, description = "Requested intervals per day, 1–24; omit or use 0 for daily totals. At most 31 days with intervals.") Integer chunks,
            @McpToolParam(required = false, description = "Include up to 16 classified activity events per day. At most 31 days in this view.") Boolean events) {
        var options = new Options(List.of(), 0, chunks == null ? 0 : chunks,
                false, Boolean.TRUE.equals(events), false, null, false);
        return wellness.report(View.BATTERY, startDate, endDate, options, false, false);
    }

    @McpTool(name = "garmin_get_respiration", description = "Daily and sleep respiratory aggregates. Single dates also expose waking averages when available.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult respiration(
            @McpToolParam(required = false, description = FROM) String startDate,
            @McpToolParam(required = false, description = THROUGH) String endDate) {
        return wellness.report(View.RESPIRATION, startDate, endDate, Options.BASIC, false, false);
    }

    @McpTool(name = "garmin_get_spo2", description = "Daily and overnight blood oxygen percentage with optional timestamped single or hourly readings.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult spo2(
            @McpToolParam(required = false, description = DATE) String date,
            @McpToolParam(required = false, description = SAMPLES) Integer samples,
            @McpToolParam(required = false, description = "Use hourly averages instead of individual readings when samples are requested.") Boolean hourly) {
        var options = new Options(List.of(), samples == null ? 0 : samples, 0,
                false, false, false, null, Boolean.TRUE.equals(hourly));
        return wellness.report(View.SPO2, date, null, options, false, false);
    }

    @McpTool(name = "garmin_get_vo2max", description = "Dated VO2 max in mL/kg/min by Garmin sport group.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult vo2max(
            @McpToolParam(required = false, description = FROM) String startDate,
            @McpToolParam(required = false, description = THROUGH) String endDate,
            @McpToolParam(required = false, description = "Restrict to one FIT sport.") Vo2Sport sport,
            @McpToolParam(required = false, description = "Include a daily series carrying the latest measured value within the requested period, labelled with its measurement date.") Boolean continuous) {
        var options = new Options(List.of(), 0, 0,
                false, false, false, sport == null ? null : sport.name(), false);
        return wellness.report(View.VO2, startDate, endDate, options, false, Boolean.TRUE.equals(continuous));
    }

    @McpTool(name = "garmin_get_fitness_age", description = "Latest fitness age as of the date, components on request, and Garmin's reason when missing.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult fitnessAge(
            @McpToolParam(required = false, description = DATE) String date,
            @McpToolParam(required = false, description = "Include the available contributing components.") Boolean components) {
        var options = new Options(List.of(), 0, 0,
                Boolean.TRUE.equals(components), false, false, null, false);
        return wellness.report(View.FITNESS_AGE, date, null, options, false, false);
    }

    @McpTool(name = "garmin_get_heart_rate_zones", description = "Current configured heart rate zone floors by sport in bpm. Default generation is opt-in.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult heartRateZones(
            @McpToolParam(required = false, description = "Ask Garmin to generate default zones where user-defined zones are missing.") Boolean defaults,
            @McpToolParam(required = false, description = "Restrict zones to one activity profile.") ZoneSport sport) {
        var options = new Options(List.of(), 0, 0,
                false, false, Boolean.TRUE.equals(defaults), sport == null ? null : sport.name(), false);
        return wellness.report(View.ZONES, null, null, options, false, false);
    }

}
