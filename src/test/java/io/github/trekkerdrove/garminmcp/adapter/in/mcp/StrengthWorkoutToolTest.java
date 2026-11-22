package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader.SearchView;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminConnectPort;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminConnectPort.Operation;
import io.github.trekkerdrove.garminmcp.application.port.out.StrengthExerciseCatalog.Exercise;
import io.github.trekkerdrove.garminmcp.application.service.GarminConnectService;
import io.github.trekkerdrove.garminmcp.application.service.StrengthWorkouts;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.provider.tool.SyncStatelessMcpToolProvider;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StrengthWorkoutToolTest {
    @Test
    void exposesTypedBlocksAndUsesTheVerifiedWriterOnlyOnConfirmation() {
        var activities = mock(ActivityReader.class);
        var publisher = mock(GarminConnectPort.class);
        var strength = new StrengthWorkouts(() -> List.of(new Exercise("PLANK", "PLANK")));
        var workouts = new GarminConnectService(activities, publisher, Clock.systemUTC());
        var tool = new SyncStatelessMcpToolProvider(List.of(new WorkoutTools(workouts, strength)))
                .getToolSpecifications().stream().filter(spec -> spec.tool().name().equals("garmin_create_strength_workout"))
                .findFirst().orElseThrow();
        var json = JsonMapper.builder().build();
        var schema = json.valueToTree(tool.tool().inputSchema());
        assertThat(schema.at("/properties/exercises/items/properties/sets/type").asString()).isEqualTo("integer");
        assertThat(schema.at("/properties/exercises/items/properties/weightKg/type").asString()).isEqualTo("number");
        assertThat(tool.tool().annotations().readOnlyHint()).isFalse();
        var blocks = List.of(Map.of("category", "PLANK", "exerciseName", "PLANK", "sets", 1, "durationSeconds", 30));
        var preview = tool.callHandler().apply(McpTransportContext.EMPTY, new CallToolRequest(tool.tool().name(),
                Map.of("name", "Strength", "exercises", blocks)));
        var plan = json.readTree(((TextContent) preview.content().getFirst()).text());
        assertThat(plan.path("status").asString()).isEqualTo("preview");
        assertThat(plan.at("/data/steps/0/weightKg").isNull()).isTrue();
        verifyNoInteractions(activities, publisher);

        var activity = new ActivityReader.Activity(42, null, LocalDate.of(2026, 11, 20), "running",
                Map.of(), Map.of(), Map.of(), new ActivityReader.Selection(List.of(), false), Map.of(), Map.of());
        when(activities.search(Map.of(), 0, 1, SearchView.SUMMARY)).thenReturn(List.of(activity));
        when(publisher.publish(eq(Operation.create_strength), anyMap(), eq(42L)))
                .thenReturn(new ToolResult("created", Map.of("workoutId", 17L)));
        var confirmed = tool.callHandler().apply(McpTransportContext.EMPTY, new CallToolRequest(tool.tool().name(),
                Map.of("name", "Strength", "exercises", blocks, "confirm", true)));
        assertThat(json.readTree(((TextContent) confirmed.content().getFirst()).text())
                .at("/data/workoutId").asLong()).isEqualTo(17);
    }
}
