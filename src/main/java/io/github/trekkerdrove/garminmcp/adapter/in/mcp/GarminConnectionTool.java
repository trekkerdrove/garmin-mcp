package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import io.github.trekkerdrove.garminmcp.application.dto.ConnectionResult;
import io.github.trekkerdrove.garminmcp.application.port.in.CheckGarminConnection;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

@Component
class GarminConnectionTool {

    private final CheckGarminConnection checkGarminConnection;

    GarminConnectionTool(CheckGarminConnection checkGarminConnection) {
        this.checkGarminConnection = checkGarminConnection;
    }

    @McpTool(name = "garmin_check_connection",
            description = "Checks the owner's Garmin connection using at most one activity. Returns a status and "
                    + "whether Garmin returned an activity. Does not return activity details.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ConnectionResult checkConnection() {
        return checkGarminConnection.checkConnection();
    }
}
