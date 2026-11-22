package io.github.trekkerdrove.garminmcp.application.port.out;

import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import java.util.Map;

public interface GarminConnectPort {
    enum Operation { create, create_strength, update, delete, search, get, duplicate, set_weight, coach_workouts }
    ToolResult publish(Operation operation, Map<String, Object> arguments, long expectedActivityId);
}
