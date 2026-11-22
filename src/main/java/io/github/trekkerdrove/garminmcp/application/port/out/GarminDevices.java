package io.github.trekkerdrove.garminmcp.application.port.out;

import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;

public interface GarminDevices {
    enum Content { WORKOUT, COURSE }

    ToolResult list(long accountActivityId, Content accountType);

    ToolResult send(long accountActivityId, Content content, long contentId, String deviceId, boolean confirm);
}
