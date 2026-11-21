package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import java.util.List;
import io.github.trekkerdrove.garminmcp.application.dto.FitRequest;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.service.ReadFitActivity;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
class FitTools {
    private final ReadFitActivity activity;

    FitTools(ReadFitActivity activity) {
        this.activity = activity;
    }

    @McpTool(name = "garmin_analyze_activity_file",
            description = "Analyze recorded FIT laps, multisport legs, power, climbs, cardiac drift, cycling dynamics or strength sets. "
                    + "One archive at a time, up to 4 MiB compressed, 8 MiB expanded and 200 rows.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult analyze(@McpToolParam(description = "Positive Garmin activity ID") long activityId,
            @McpToolParam(required = false, description = "laps (default), multisport, power, climbs, cardiac_drift, cycling_dynamics or strength") String view,
            @McpToolParam(required = false, description = "1 to 200 rows, default 50") Integer limit,
            @McpToolParam(required = false, description = "Power view only. Look up measured weight on or before the activity's local start date.") Boolean includeWattsPerKg) {
        return ToolResult.run(() -> {
            var selected = view == null ? FitRequest.View.laps : FitRequest.View.valueOf(view);
            if (selected == FitRequest.View.samples) {
                throw new IllegalArgumentException();
            }
            return activity.read(new FitRequest(activityId, selected, List.of(), limit == null ? 50 : limit,
                    Boolean.TRUE.equals(includeWattsPerKg)));
        });
    }

    @McpTool(name = "garmin_get_fit_samples",
            description = "Read timestamped FIT record measurements in recording order. Includes coverage and truncation. "
                    + "Up to 4 MiB compressed, 8 MiB expanded and 200 matching records.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ToolResult samples(@McpToolParam(description = "Positive Garmin activity ID") long activityId,
            @McpToolParam(required = false, description = "Distinct metrics, default heartRateBpm. Units appear in each metric name.") List<FitRequest.Metric> metrics,
            @McpToolParam(required = false, description = "1 to 200 rows, default 50") Integer limit) {
        return ToolResult.run(() -> activity.read(new FitRequest(activityId, FitRequest.View.samples,
                metrics == null ? List.of(FitRequest.Metric.heartRateBpm) : metrics, limit == null ? 50 : limit, false)));
    }
}
