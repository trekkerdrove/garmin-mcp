package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import java.math.BigDecimal;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.service.GarminConnectService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "garmin.workouts.enabled", havingValue = "true")
class WeightWriteTool {
    private final GarminConnectService garmin;

    WeightWriteTool(GarminConnectService garmin) {
        this.garmin = garmin;
    }

    @McpTool(name = "garmin_set_weight", description = "Record or correct a Garmin Connect weigh-in for a local date in Europe/Paris. "
            + "Call without confirm first. Show the date, old and new weights, and time. If selection_required, ask which measurement to correct. "
            + "After explicit approval, repeat with confirm=true and copy the selection string returned for corrections. "
            + "Corrections replace a manual weight-only record at its original time. If weight_cleanup_pending, retry with the same selection. "
            + "If weight_write_unconfirmed or weight_measurement_changed, preview the day again before any further confirmation.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false))
    public ToolResult set(
            @McpToolParam(description = "Weight in kg as a number, for example 72.4.") BigDecimal weightKg,
            @McpToolParam(required = false, description = "YYYY-MM-DD, defaults to today in Europe/Paris. Use the date from the preview on confirmation.") String date,
            @McpToolParam(required = false, description = "Optional HH:mm for a new entry, defaults to 00:00 local. Corrections keep the original time.") String time,
            @McpToolParam(required = false, description = "Copy the selection string from the chosen measurement exactly. Required when confirming a correction.") String selection,
            @McpToolParam(required = false, description = "Default false. Set true only after approval of the preview.") Boolean confirm) {
        return ToolResult.run(() -> garmin.setWeight(date, time, weightKg, selection, Boolean.TRUE.equals(confirm)));
    }
}
