package io.github.trekkerdrove.garminmcp.application.port.out;

import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import java.util.Map;

public interface WorkoutPublisher {
    enum Operation { create, update, delete, search, get, duplicate }
    ToolResult publish(Operation operation, Map<String, Object> arguments, long expectedActivityId);
}
