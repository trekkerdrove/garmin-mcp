package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminConnectPort;
import io.github.trekkerdrove.garminmcp.application.service.GarminConnectService;
import io.github.trekkerdrove.garminmcp.application.service.StrengthWorkouts;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.util.ToolInputValidator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.provider.tool.SyncStatelessMcpToolProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkoutToolsTest {
    @Test
    void publishesCoachAsAReadOnlyToolAndReturnsPartialCoverage() {
        var service = mock(GarminConnectService.class);
        var tool = new SyncStatelessMcpToolProvider(List.of(new WorkoutTools(service, mock(StrengthWorkouts.class)))).getToolSpecifications().stream()
                .filter(spec -> spec.tool().name().equals("garmin_get_coach_workouts"))
                .findFirst().orElseThrow();
        assertThat(tool.tool().annotations().readOnlyHint()).isTrue();
        assertThat(tool.tool().annotations().destructiveHint()).isFalse();
        assertThat(tool.tool().inputSchema()).containsEntry("properties", Map.of());
        when(service.getCoachWorkouts()).thenReturn(new ToolResult("partial", Map.of(
                "weeks", List.of(Map.of("status", "published"), Map.of("status", "error")),
                "entries", List.of(Map.of("date", "2026-11-22", "name", "Tempo")))));

        var result = tool.callHandler().apply(McpTransportContext.EMPTY, new CallToolRequest(tool.tool().name(), Map.of()));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).singleElement().isInstanceOfSatisfying(TextContent.class,
                content -> assertThat(content.text()).contains("partial", "2026-11-22", "Tempo", "published", "error"));
        verify(service).getCoachWorkouts();
    }

    @Test
    void publishesDetailOnlyWhenEnabledWithOneRequiredUuidAndReadOnlyHints() {
        var service = mock(GarminConnectService.class);
        var runner = new ApplicationContextRunner().withUserConfiguration(WorkoutTools.class)
                .withBean(GarminConnectService.class, () -> service)
                .withBean(StrengthWorkouts.class, () -> mock(StrengthWorkouts.class));
        runner.withPropertyValues("garmin.workouts.enabled=false").run(context -> assertThat(context).doesNotHaveBean(WorkoutTools.class));
        runner.withPropertyValues("garmin.workouts.enabled=true").run(context -> {
            var tool = new SyncStatelessMcpToolProvider(List.of(context.getBean(WorkoutTools.class))).getToolSpecifications().stream()
                    .filter(spec -> spec.tool().name().equals("garmin_get_coach_workout_details"))
                    .findFirst().orElseThrow();
            assertThat(tool.tool().annotations().readOnlyHint()).isTrue();
            assertThat(tool.tool().annotations().destructiveHint()).isFalse();
            assertThat(tool.tool().annotations().idempotentHint()).isTrue();
            JsonNode schema = JsonMapper.builder().build().valueToTree(tool.tool().inputSchema());
            assertThat(schema.path("properties").size()).isEqualTo(1);
            assertThat(schema.path("properties").path("workoutUuid").path("description").asString()).contains("Text UUID");
            assertThat(schema.path("required").path(0).asString()).isEqualTo("workoutUuid");

            var uuid = "123e4567-e89b-42d3-a456-426614174000";
            when(service.getCoachWorkoutDetails(uuid)).thenReturn(new ToolResult("no_data",
                    Map.of("workoutUuid", uuid, "workout", Map.of("workoutSegments", List.of()))));
            var result = tool.callHandler().apply(McpTransportContext.EMPTY,
                    new CallToolRequest(tool.tool().name(), Map.of("workoutUuid", uuid)));
            assertThat(result.isError()).isFalse();
            assertThat(result.content()).singleElement().isInstanceOfSatisfying(TextContent.class,
                    content -> assertThat(content.text()).contains("no_data", uuid, "workoutSegments"));
            verify(service).getCoachWorkoutDetails(uuid);
        });
    }

    @Test
    void numericUuidReturnsInvalidInputThroughMcp() {
        var activities = mock(ActivityReader.class);
        var port = mock(GarminConnectPort.class);
        var service = new GarminConnectService(activities, port, Clock.systemUTC());
        var tool = new SyncStatelessMcpToolProvider(List.of(new WorkoutTools(service, mock(StrengthWorkouts.class))))
                .getToolSpecifications().stream().filter(spec -> spec.tool().name().equals("garmin_get_coach_workout_details"))
                .findFirst().orElseThrow();
        var args = Map.<String, Object>of("workoutUuid", 123);
        assertThat(ToolInputValidator.validate(tool.tool(), args, true, new DefaultJsonSchemaValidator())).isNull();
        var result = tool.callHandler().apply(McpTransportContext.EMPTY, new CallToolRequest(tool.tool().name(), args));
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).singleElement().isInstanceOfSatisfying(TextContent.class,
                content -> assertThat(content.text()).contains("invalid_input"));
        verifyNoInteractions(activities, port);
    }
}
