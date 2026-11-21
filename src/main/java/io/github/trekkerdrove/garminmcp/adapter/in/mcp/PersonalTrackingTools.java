package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminPersonalData.CycleView;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminPersonalData.WeightView;
import io.github.trekkerdrove.garminmcp.application.service.PersonalTrackingService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
class PersonalTrackingTools {
    private final PersonalTrackingService service;

    PersonalTrackingTools(PersonalTrackingService service) {
        this.service = service;
    }

    @McpTool(name = "garmin_get_weight",
            description = "Read individual weigh-ins in kg, body composition or daily summaries. Up to 730 days and 256 rows.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult weight(
            @McpToolParam(required = false, description = "Inclusive ISO date YYYY-MM-DD. Defaults to endDate or today in Europe/Paris.") String startDate,
            @McpToolParam(required = false, description = "Inclusive ISO date YYYY-MM-DD. Defaults to startDate.") String endDate,
            @McpToolParam(required = false, description = "measurements (default), composition or summary.") WeightView view) {
        return ToolResult.run(() -> service.weight(startDate, endDate, view));
    }

    @McpTool(name = "garmin_get_weight_goals",
            description = "Read effective weight targets and ranges in grams. Up to 364 days and 256 goals.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult goals(
            @McpToolParam(required = false, description = "Inclusive ISO date YYYY-MM-DD. Defaults to endDate or today in Europe/Paris.") String startDate,
            @McpToolParam(required = false, description = "Inclusive ISO date YYYY-MM-DD. Defaults to startDate.") String endDate) {
        return ToolResult.run(() -> service.goals(startDate, endDate));
    }

    @McpTool(name = "garmin_get_nutrition",
            description = "Read a daily food journal, goals or a multi-day nutrient total (kcal and grams). Up to 31 days, 100 foods per journal.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult nutrition(
            @McpToolParam(required = false, description = "Inclusive ISO date YYYY-MM-DD. Defaults to endDate or today in Europe/Paris.") String startDate,
            @McpToolParam(required = false, description = "Inclusive ISO date YYYY-MM-DD. Defaults to startDate.") String endDate,
            @McpToolParam(required = false, description = "Optional positive meal ID. Limits all returned nutrition to that meal.") Long mealId) {
        return ToolResult.run(() -> service.nutrition(startDate, endDate, mealId));
    }

    @McpTool(name = "garmin_get_cycle",
            description = "Read cycle summaries or day views, up to 31 days. Detailed symptom information and analyses require opt-in.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult cycle(
            @McpToolParam(required = false, description = "Inclusive ISO date YYYY-MM-DD. Defaults to endDate or today in Europe/Paris.") String startDate,
            @McpToolParam(required = false, description = "Inclusive ISO date YYYY-MM-DD. Defaults to startDate.") String endDate,
            @McpToolParam(required = false, description = "summary (default) or day.") CycleView view,
            @McpToolParam(required = false, description = "Include sensitive day logs, up to 32 items per symptom, mood and discharge list. Default false.") Boolean details,
            @McpToolParam(required = false, description = "Include available cycle analysis. Default false.") Boolean analysis) {
        return ToolResult.run(() -> service.cycle(startDate, endDate, view, Boolean.TRUE.equals(details), Boolean.TRUE.equals(analysis)));
    }

    @McpTool(name = "garmin_get_profile_settings",
            description = "Read account units, locale, timezone and week-start preference.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult profile() {
        return ToolResult.run(service::profile);
    }
}
