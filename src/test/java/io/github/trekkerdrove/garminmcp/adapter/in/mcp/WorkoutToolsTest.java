package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import java.util.List;
import java.util.Map;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.service.GarminConnectService;
import io.github.trekkerdrove.garminmcp.application.service.StrengthWorkouts;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.provider.tool.SyncStatelessMcpToolProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
}
