package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import java.util.List;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.dto.WorkoutDraft;
import io.github.trekkerdrove.garminmcp.application.service.WorkoutService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "garmin.workouts.enabled", havingValue = "true")
class WorkoutTools {
    private static final String STEP_TARGETS = "Optional target: heartRateZone (1–5) or, for running/walking, "
            + "paceSecondsPerKm with fast and slow bounds in whole seconds per km. "
            + "4:25–4:35/km is {fast:265,slow:275}. For a single pace use equal bounds.";
    private final WorkoutService workouts;

    WorkoutTools(WorkoutService workouts) {
        this.workouts = workouts;
    }

    @McpTool(name = "garmin_create_workout",
            description = "Preview or save a structured workout in the Garmin Connect library. "
                    + "Show the preview and obtain approval before calling again with confirm=true. "
                    + "An identical workout with the same name is reused. A different workout with that name is left intact.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false))
    public ToolResult create(
            @McpToolParam(description = "Workout name, up to 120 characters.") String name,
            @McpToolParam(description = "Garmin sport: running, walking or cycling.") WorkoutDraft.Sport sport,
            @McpToolParam(description = "1 to 50 ordered steps. Each has a kind (warmup, interval, recovery, cooldown), "
                    + "and exactly one of durationSeconds or distanceMeters. " + STEP_TARGETS) List<WorkoutDraft.Step> steps,
            @McpToolParam(required = false, description = "Default false. Set true only after the user approves the workout.") Boolean confirm) {
        return ToolResult.run(() -> workouts.create(new WorkoutDraft(name, sport, steps), Boolean.TRUE.equals(confirm)));
    }

    @McpTool(name = "garmin_update_workout", description = "Edit a library workout in place by ID. Supply a new name, "
            + "a complete replacement step list, or both. Show the preview before confirming.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false))
    public ToolResult update(
            @McpToolParam(description = "Garmin library workout ID.") long workoutId,
            @McpToolParam(required = false, description = "New workout name.") String name,
            @McpToolParam(required = false, description = "Replace all steps of a single-segment running, walking or cycling workout. "
                    + STEP_TARGETS) List<WorkoutDraft.Step> steps,
            @McpToolParam(required = false, description = "Default false. Set true after approval.") Boolean confirm) {
        return ToolResult.run(() -> workouts.update(workoutId, name, steps, Boolean.TRUE.equals(confirm)));
    }

    @McpTool(name = "garmin_delete_workout", description = "Permanently delete one library workout by ID. "
            + "Show the current name and ID from the preview and obtain approval before confirming.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true))
    public ToolResult delete(
            @McpToolParam(description = "Garmin library workout ID.") long workoutId,
            @McpToolParam(required = false, description = "Default false. Set true after approval to permanently delete.") Boolean confirm) {
        return ToolResult.run(() -> workouts.delete(workoutId, Boolean.TRUE.equals(confirm)));
    }
}
