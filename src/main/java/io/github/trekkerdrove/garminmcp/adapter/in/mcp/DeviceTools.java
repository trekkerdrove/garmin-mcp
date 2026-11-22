package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.service.DeviceSendService;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class DeviceTools {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> SEND_ARGUMENTS = Set.of("workoutId", "courseId", "deviceId", "confirm");

    @Bean
    List<SyncToolSpecification> deviceToolSpecifications(ObjectProvider<DeviceSendService> deviceService) {
        var devices = deviceService.getIfAvailable();
        if (devices == null) return List.of();
        var list = Tool.builder("garmin_list_devices", Map.of(
                        "type", "object", "properties", Map.of(), "additionalProperties", false))
                .description("List Garmin Connect devices by name and deviceId. Ask which device to use, even if only one is listed.")
                .annotations(ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false).idempotentHint(true).build())
                .build();
        var send = Tool.builder("garmin_send_to_device", Map.of(
                        "type", "object", "additionalProperties", false, "required", List.of("deviceId"),
                        "properties", Map.of(
                                "workoutId", Map.of("type", "integer", "minimum", 1, "maximum", Long.MAX_VALUE,
                                        "description", "Saved workout ID from the Garmin Connect library or workout save result."),
                                "courseId", Map.of("type", "integer", "minimum", 1, "maximum", Long.MAX_VALUE,
                                        "description", "Saved course ID from the Garmin Connect library or course save result."),
                                "deviceId", Map.of("type", "string", "pattern", "^[0-9]{1,19}$",
                                        "description", "Copy the decimal deviceId from garmin_list_devices exactly."),
                                "confirm", Map.of("type", "boolean", "default", false))))
                .description("Preview sending exactly one saved workout or course to a chosen Garmin device. "
                        + "Show both names and obtain approval before repeating the same IDs with confirm=true. "
                        + "Queued means Garmin Connect accepted the send for the next sync. "
                        + "If device_send_unconfirmed, tell the user to sync and check the device before confirming another send.")
                .annotations(ToolAnnotations.builder().readOnlyHint(false).destructiveHint(false).idempotentHint(false).build())
                .build();
        // Read raw arguments so JSON coercion cannot turn a malformed ID or confirm value into a write.
        return List.of(new SyncToolSpecification(list, (context, request) -> result(ToolResult.run(devices::list))),
                new SyncToolSpecification(send, (context, request) -> result(ToolResult.run(() -> send(devices, request.arguments())))));
    }

    private static ToolResult send(DeviceSendService devices, Map<String, Object> arguments) {
        if (arguments == null || !(arguments.get("deviceId") instanceof String deviceId)
                || !SEND_ARGUMENTS.containsAll(arguments.keySet())
                || (arguments.containsKey("confirm") && !(arguments.get("confirm") instanceof Boolean))) {
            throw new IllegalArgumentException("Expected a decimal deviceId and optional boolean confirm");
        }
        return devices.send(contentId(arguments, "workoutId"), contentId(arguments, "courseId"), deviceId,
                Boolean.TRUE.equals(arguments.get("confirm")));
    }

    private static Long contentId(Map<String, Object> arguments, String key) {
        if (!arguments.containsKey(key)) return null;
        return switch (arguments.get(key)) {
            case Integer id -> id.longValue();
            case Long id -> id;
            case BigInteger id when id.bitLength() <= 63 -> id.longValue();
            case null, default -> throw new IllegalArgumentException("Expected an integer content ID");
        };
    }

    private static CallToolResult result(ToolResult result) {
        return CallToolResult.builder().structuredContent(result).addTextContent(JSON.writeValueAsString(result)).build();
    }
}
