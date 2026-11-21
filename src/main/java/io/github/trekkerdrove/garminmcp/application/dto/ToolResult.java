package io.github.trekkerdrove.garminmcp.application.dto;

import java.time.DateTimeException;
import java.util.function.Supplier;

public record ToolResult(String status, Object data) {
    public static ToolResult ok(Object data) {
        return new ToolResult("ok", data);
    }

    public static ToolResult empty(Object data) {
        return new ToolResult("no_data", data);
    }

    public static ToolResult truncated(Object data) {
        return new ToolResult("truncated", data);
    }

    public static ToolResult run(Supplier<ToolResult> action) {
        try {
            return action.get();
        } catch (GarminFailure failure) {
            return new ToolResult(failure.status(), null);
        } catch (IllegalArgumentException | DateTimeException invalid) {
            return new ToolResult("invalid_input", null);
        }
    }
}
