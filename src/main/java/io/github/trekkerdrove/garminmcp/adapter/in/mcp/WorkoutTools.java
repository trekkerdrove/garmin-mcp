package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import java.util.List;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.dto.WorkoutDraft;
import io.github.trekkerdrove.garminmcp.application.service.GarminConnectService;
import io.github.trekkerdrove.garminmcp.application.service.StrengthWorkouts;
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
    private final GarminConnectService workouts;

    private final StrengthWorkouts strength;

    WorkoutTools(GarminConnectService workouts, StrengthWorkouts strength) {
        this.workouts = workouts;
        this.strength = strength;
    }

    @McpTool(name = "garmin_create_strength_workout",
            description = "Preview or save a structured strength workout. Use keys from garmin_search_strength_exercises. "
                    + "Show the effective sets and rests and obtain approval before setting confirm=true. "
                    + "An identical workout with the same name is reused. A different workout with that name is left intact.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false))
    public ToolResult createStrength(
            @McpToolParam(description = "Workout name, up to 120 characters.") String name,
            @McpToolParam(description = "Ordered blocks, at most 50 effective steps including rests. Each block needs category, "
                    + "exerciseName, sets and exactly one of reps or durationSeconds. Optional weightKg is the external load "
                    + "in kg, at most 453.5 with up to 3 decimal places. Clarify total versus per-dumbbell if ambiguous. "
                    + "Optional restBetweenSetsSeconds only runs between sets of this block. Optional restAfterBlockSeconds "
                    + "runs before the next block and must be omitted or zero in the last block. Both default to zero. "
                    + "Repeat the exercise in another block to vary the load, reps or duration.") List<StrengthWorkouts.Block> exercises,
            @McpToolParam(required = false, description = "Default false. Set true only after approval.") Boolean confirm) {
        return ToolResult.run(() -> strength.create(name, exercises, Boolean.TRUE.equals(confirm), workouts));
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

    @McpTool(name = "garmin_search_workouts",
            description = "Find saved workouts by name in your Garmin Connect library, most recently updated first.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public ToolResult search(
            @McpToolParam(required = false, description = "Case- and accent-insensitive substring in the workout name. Omit for recent workouts.") String query,
            @McpToolParam(required = false, description = "Maximum results, 1–20. Defaults to 10.") Integer limit) {
        return ToolResult.run(() -> workouts.search(query, limit == null ? 10 : limit));
    }

    @McpTool(name = "garmin_get_workout",
            description = "Read a saved library workout by ID, including each step, target, and repeat group. "
                    + "Garmin expresses pace targets in meters per second.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public ToolResult get(@McpToolParam(description = "Garmin library workout ID from garmin_search_workouts.") long workoutId) {
        return ToolResult.run(() -> workouts.get(workoutId));
    }

    @McpTool(name = "garmin_duplicate_workout",
            description = "Copy a saved library workout under a new name. Optionally adjust the lengths of distance-based steps. "
                    + "Show the proposed copy and obtain approval before confirming. Replaying a verified identical copy reuses its ID.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false))
    public ToolResult duplicate(
            @McpToolParam(description = "Source workout ID from garmin_search_workouts.") long workoutId,
            @McpToolParam(description = "Name of the copy, up to 120 characters.") String name,
            @McpToolParam(required = false, description = "List of stepOrder and distanceMeters pairs. Use orders from garmin_get_workout. "
                    + "Each order must uniquely identify a distance-based step.") List<WorkoutDraft.DistanceChange> distanceChanges,
            @McpToolParam(required = false, description = "Default false. Set true only after the user approves the copy.") Boolean confirm) {
        return ToolResult.run(() -> workouts.duplicate(workoutId, name, distanceChanges, Boolean.TRUE.equals(confirm)));
    }
}
