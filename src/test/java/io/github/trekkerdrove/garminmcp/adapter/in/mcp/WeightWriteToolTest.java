package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.service.GarminConnectService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.provider.tool.SyncStatelessMcpToolProvider;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeightWriteToolTest {
    @Test
    void publishesNumericWeightAndPassesSelectionAsAString() {
        var service = mock(GarminConnectService.class);
        var tool = new SyncStatelessMcpToolProvider(List.of(new WeightWriteTool(service))).getToolSpecifications().getFirst();
        assertThat(tool.tool().name()).isEqualTo("garmin_set_weight");
        assertThat(tool.tool().annotations().destructiveHint()).isTrue();
        var schema = JsonMapper.builder().build().valueToTree(tool.tool().inputSchema());
        assertThat(schema.at("/properties/weightKg/type").asString()).isEqualTo("number");
        assertThat(schema.at("/properties/selection/type").asString()).isEqualTo("string");
        assertThat(schema.path("required").valueStream().map(node -> node.asString()).toList()).containsExactly("weightKg");

        String selection = "9007199254740993:1774769400000:1774762200000:72.4";
        var kg = new BigDecimal("72.5");
        when(service.setWeight("2026-03-29", null, kg, selection, true))
                .thenReturn(new ToolResult("updated", Map.of()));
        tool.callHandler().apply(McpTransportContext.EMPTY, new CallToolRequest("garmin_set_weight",
                Map.of("weightKg", 72.5, "date", "2026-03-29", "selection", selection, "confirm", true)));
        verify(service).setWeight("2026-03-29", null, kg, selection, true);
    }
}
