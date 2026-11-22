package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.service.StrengthWorkouts;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
class StrengthExerciseTools {
    private final StrengthWorkouts strength;

    StrengthExerciseTools(StrengthWorkouts strength) {
        this.strength = strength;
    }

    @McpTool(name = "garmin_search_strength_exercises",
            description = "Find official Garmin strength exercise references by name, key or common French alias. "
                    + "Use category and exerciseName from the results to construct a workout. "
                    + "Ask which exercise the user wants if several variants match.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public ToolResult search(
            @McpToolParam(description = "Exercise name, key or category/key, for example squat à la barre or SQUAT/BARBELL_BACK_SQUAT.") String query,
            @McpToolParam(required = false, description = "Maximum results, 1–20. Defaults to 10.") Integer limit) {
        return ToolResult.run(() -> strength.search(query, limit == null ? 10 : limit));
    }
}
